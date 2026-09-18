package com.yui.assistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class VoiceQuickDialogActivity : Activity() {

    private lateinit var tvPrompt: TextView
    private lateinit var tvRecognized: TextView
    private lateinit var btnCancel: Button
    private var speechRecognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        // Ventana flotante estilo diálogo SAO
        window.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )
        window.setGravity(Gravity.CENTER)

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            val bg = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xF0090D16.toInt())
                setStroke(3, 0xFFFF79C6.toInt())
                cornerRadius = 32f
            }
            background = bg
        }

        tvPrompt = TextView(this).apply {
            text = "🌸 YUI te escucha..."
            setTextColor(0xFFFF79C6.toInt())
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        tvRecognized = TextView(this).apply {
            text = "Di tu comando o pregunta..."
            setTextColor(0xFF8BE9FD.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }

        btnCancel = Button(this).apply {
            text = "✖ Cancelar"
            setTextColor(0xFFF8F8F2.toInt())
            val btnBg = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF1A2234.toInt())
                cornerRadius = 16f
            }
            background = btnBg
            setOnClickListener { finish() }
        }

        layout.addView(tvPrompt)
        layout.addView(tvRecognized)
        layout.addView(btnCancel)

        setContentView(layout)
        startListening()
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Reconocimiento de voz no disponible", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-MX")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "es-MX")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                tvRecognized.text = "Escuchando tu voz..."
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                tvRecognized.text = "Procesando con Yui..."
            }

            override fun onError(error: Int) {
                tvRecognized.text = "No logré escucharte bien. Intenta de nuevo."
                OfflineCommandEngine.speak(this@VoiceQuickDialogActivity, "No te escuché bien, Alan.")
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val userText = matches[0]
                    tvRecognized.text = "«$userText»"
                    handleUserSpeech(userText)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer?.startListening(intent)
    }

    private fun handleUserSpeech(text: String) {
        val isOnline = NetworkChangeReceiver.isConnected(this)

        if (!isOnline) {
            // Modo 100% Offline
            val result = OfflineCommandEngine.processCommand(this, text)
            if (result.handled) {
                Toast.makeText(this, result.responseMessage, Toast.LENGTH_LONG).show()
            } else {
                val reply = "No tengo conexión y no reconocí un comando local directo, Alan."
                OfflineCommandEngine.speak(this, reply)
                Toast.makeText(this, reply, Toast.LENGTH_LONG).show()
            }
            tvRecognized.postDelayed({ finish() }, 2000)
        } else {
            // Modo Online (intenta comando local o consulta a la nube)
            val offlineCmd = OfflineCommandEngine.processCommand(this, text)
            if (offlineCmd.handled) {
                Toast.makeText(this, offlineCmd.responseMessage, Toast.LENGTH_LONG).show()
                tvRecognized.postDelayed({ finish() }, 1800)
            } else {
                sendToCloudChat(text)
            }
        }
    }

    private fun sendToCloudChat(text: String) {
        tvRecognized.text = "Consultando a Yui Cloud..."
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val baseUrl = ApiClient.getBackendUrl(this@VoiceQuickDialogActivity)
                val url = URL("$baseUrl/api/chat")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    doOutput = true
                    connectTimeout = 12000
                    readTimeout = 12000
                }

                val payload = JSONObject().apply {
                    put("message", text)
                }

                BufferedWriter(OutputStreamWriter(conn.outputStream, "UTF-8")).use {
                    it.write(payload.toString())
                    it.flush()
                }

                if (conn.responseCode in 200..299) {
                    val res = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(res)
                    val reply = json.optString("reply", "Estoy aquí, Alan.")

                    runOnUiThread {
                        tvRecognized.text = reply
                        OfflineCommandEngine.speak(this@VoiceQuickDialogActivity, reply)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    OfflineCommandEngine.speak(this@VoiceQuickDialogActivity, "Hubo un error de conexión, Alan.")
                }
            } finally {
                tvRecognized.postDelayed({ finish() }, 3000)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
    }
}
