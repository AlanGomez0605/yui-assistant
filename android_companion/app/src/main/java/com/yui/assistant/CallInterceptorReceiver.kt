package com.yui.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * CallInterceptorReceiver — Interceptor de llamadas telefónicas de Yui.
 *
 * LÓGICA:
 * - Número DESCONOCIDO (no está en contactos de MongoDB): cuelga automáticamente a los 5 segundos.
 * - Número REGISTRADO (existe en la agenda de Alan): NO interviene. Deja sonar normalmente.
 * - Funciona siempre en segundo plano mientras [isCallFilterEnabled] sea true.
 */
class CallInterceptorReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "YuiCallFilter"
        private const val UNKNOWN_HANG_DELAY_MS = 5000L   // 5 segundos para colgar desconocido
        private const val PREFS_NAME = "yui_call_filter"
        private const val KEY_ENABLED = "call_filter_enabled"

        @Volatile
        private var isCurrentlyRinging = false

        @Volatile
        private var currentRingingNumber: String? = null

        /**
         * Habilita o deshabilita el filtro de llamadas desde cualquier parte de la app.
         */
        fun setEnabled(context: Context, enabled: Boolean) {
            getPrefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
            Log.d(TAG, "Filtro de llamadas ${if (enabled) "ACTIVADO" else "DESACTIVADO"} por el usuario.")
        }

        fun isEnabled(context: Context): Boolean {
            return getPrefs(context).getBoolean(KEY_ENABLED, false)
        }

        private fun getPrefs(context: Context): SharedPreferences {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        // Si el filtro está desactivado, Yui no toca las llamadas
        if (!isEnabled(context)) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        Log.d(TAG, "Estado: $state | Número: ${incomingNumber ?: "sin número"}")

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (isCurrentlyRinging) return  // Evitar doble disparo
                isCurrentlyRinging = true
                currentRingingNumber = incomingNumber

                val pendingResult = goAsync()
                handleIncomingCall(context.applicationContext, incomingNumber) {
                    pendingResult.finish()
                }
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                // Alan contestó manualmente — cancelar cualquier acción pendiente
                isCurrentlyRinging = false
                currentRingingNumber = null
                Log.d(TAG, "Alan contestó la llamada manualmente.")
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                // Llamada terminó o fue rechazada
                isCurrentlyRinging = false
                currentRingingNumber = null
                Log.d(TAG, "Llamada finalizada.")
            }
        }
    }

    /**
     * Decide en función de si el número es conocido o no.
     * - Conocido  → no hace nada (deja sonar)
     * - Desconocido → cuelga a los 5 segundos
     */
    private fun handleIncomingCall(context: Context, number: String?, onComplete: () -> Unit) {
        val phoneNumber = number ?: ""

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val isKnown = isNumberInContacts(context, phoneNumber)

                if (isKnown) {
                    Log.d(TAG, "Número registrado o no verificable ($phoneNumber). Yui no interviene.")
                    return@launch
                }

                Log.d(TAG, "Número desconocido ($phoneNumber). Verificando durante ${UNKNOWN_HANG_DELAY_MS / 1000}s...")
                delay(UNKNOWN_HANG_DELAY_MS)

                if (isCurrentlyRinging && currentRingingNumber == phoneNumber) {
                    Log.d(TAG, "Colgando llamada desconocida: $phoneNumber")
                    rejectCall(context)
                }
            } finally {
                onComplete()
            }
        }
    }

    /**
     * Verifica si el número existe en los contactos sincronizados de MongoDB.
     * Usa el ApiClient para consultar rápidamente.
     */
    private suspend fun isNumberInContacts(context: Context, phoneNumber: String): Boolean {
        if (phoneNumber.isBlank()) return true
        return try {
            // Normaliza: elimina espacios y guiones para comparar solo dígitos
            val normalized = phoneNumber.replace(Regex("[^0-9+]"), "")
            val contacts = ContactsSyncManager.readAllContacts(context)
            if (contacts.isEmpty()) return true
            contacts.any { contact ->
                val contactPhone = contact.optString("phone", "").replace(Regex("[^0-9+]"), "")
                contactPhone.isNotBlank() && (
                    contactPhone.endsWith(normalized.takeLast(8)) ||
                    normalized.endsWith(contactPhone.takeLast(8))
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron consultar contactos. La llamada se conserva: ${e.message}")
            true
        }
    }

    /**
     * Cuelga la llamada usando TelecomManager.
     */
    private fun rejectCall(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                telecomManager?.endCall()
                Log.d(TAG, "Llamada desconocida rechazada correctamente.")
            } else {
                Log.w(TAG, "API < Android 9: no se puede rechazar llamada programáticamente.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al rechazar llamada: ${e.message}")
        }
    }
}
