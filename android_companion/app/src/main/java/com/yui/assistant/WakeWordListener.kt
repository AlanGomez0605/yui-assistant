package com.yui.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class WakeWordListener(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit
) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val TAG = "WakeWordListener"
        private val WAKE_KEYWORDS = listOf("yui", "oye yui", "hey yui", "hola yui", "yui despierta")
    }

    init {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Yui:WakeWordListenerWakeLock"
        )
    }

    fun startListening() {
        if (isListening) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "SpeechRecognizer no disponible en el sistema.")
            return
        }

        try {
            wakeLock?.acquire(3600000L) // 1 hora máximo
        } catch (e: Exception) {
            // Ignorar excepción de wakelock
        }

        handler.post {
            initRecognizer()
        }
    }

    private fun initRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-MX")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                Log.d(TAG, "Detector 'Oye Yui' escuchando en segundo plano...")
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                isListening = false
                // Reiniciar escucha continua tras un breve retraso
                handler.postDelayed({
                    if (wakeLock?.isHeld == true) {
                        initRecognizer()
                    }
                }, 1000L)
            }

            override fun onResults(results: Bundle?) {
                checkSpeechForKeywords(results)
                restartListening()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                checkSpeechForKeywords(partialResults)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando reconocimiento de Wake Word", e)
            restartListening()
        }
    }

    private fun checkSpeechForKeywords(bundle: Bundle?) {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        for (match in matches) {
            val lower = match.lowercase().trim()
            for (keyword in WAKE_KEYWORDS) {
                if (lower.contains(keyword)) {
                    Log.d(TAG, "¡Palabra de activación detectada!: '$lower'")
                    isListening = false
                    speechRecognizer?.stopListening()
                    onWakeWordDetected.invoke()
                    return
                }
            }
        }
    }

    private fun restartListening() {
        isListening = false
        handler.postDelayed({
            initRecognizer()
        }, 500L)
    }

    fun stopListening() {
        isListening = false
        handler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
            speechRecognizer = null
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deteniendo WakeWordListener", e)
        }
    }
}
