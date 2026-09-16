package com.yui.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CallInterceptorReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "YuiCallInterceptor"
        private const val RINGING_THRESHOLD_MS = 35000L // 35 segundos
        private var isCurrentlyRinging = false
        private var currentRingingNumber: String? = null
        private var ringingStartTime: Long = 0
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

            Log.d(TAG, "Estado de telefonía: $state | Número: $incomingNumber")

            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    isCurrentlyRinging = true
                    currentRingingNumber = incomingNumber ?: "Desconocido"
                    ringingStartTime = System.currentTimeMillis()

                    // Iniciar monitoreo del temporizador de 35 segundos
                    handleRingingTimer(context, currentRingingNumber!!)
                }

                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    // Alan o Yui contestó la llamada
                    isCurrentlyRinging = false
                    Log.d(TAG, "Llamada descolgada (Off-hook). Deteniendo temporizador.")
                }

                TelephonyManager.EXTRA_STATE_IDLE -> {
                    // La llamada terminó o fue rechazada
                    isCurrentlyRinging = false
                    currentRingingNumber = null
                    Log.d(TAG, "Llamada finalizada (Idle).")
                }
            }
        }
    }

    private fun handleRingingTimer(context: Context, incomingNumber: String) {
        CoroutineScope(Dispatchers.Main).launch {
            Log.d(TAG, "Iniciando conteo de 35 segundos para número: $incomingNumber")
            delay(RINGING_THRESHOLD_MS)

            // Si después de 35s el teléfono sigue timbrando (Alan no contestó ni colgó):
            if (isCurrentlyRinging && currentRingingNumber == incomingNumber) {
                Log.d(TAG, "Tiempo agotado (35s). Yui asume el control automático...")

                val decision = ApiClient.evaluateIncomingCall(context, incomingNumber, 35)

                if (decision != null) {
                    val action = decision.optString("action")
                    val callerName = decision.optString("caller_name")
                    val messageSpoken = decision.optString("message_spoken")

                    if (action == "answered_with_courtesy_message") {
                        Log.d(TAG, "Yui contesta llamada de contacto registrado: $callerName")
                        answerCallAutomatically(context)
                    } else {
                        Log.d(TAG, "Yui rechaza / cuelga llamada desconocida/spam: $incomingNumber")
                        rejectCallAutomatically(context)
                    }
                }
            }
        }
    }

    private fun answerCallAutomatically(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            try {
                @Suppress("DEPRECATION")
                telecomManager?.acceptRingingCall()
            } catch (e: Exception) {
                Log.e(TAG, "Permiso o error al contestar", e)
            }
        }
    }

    private fun rejectCallAutomatically(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            try {
                telecomManager?.endCall()
            } catch (e: Exception) {
                Log.e(TAG, "Permiso o error al finalizar", e)
            }
        }
    }
}
