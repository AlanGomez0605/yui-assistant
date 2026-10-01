package com.yui.assistant

import android.Manifest
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * CallInterceptorReceiver — Interceptor de llamadas telefónicas de Yui (respaldo).
 *
 * En Android 10+ el filtrado lo hace [YuiCallScreeningService] cuando Yui tiene el rol de
 * filtro de llamadas. Este receiver actúa si ese rol no está concedido (o en Android 9), y
 * siempre para los contactos de [CallBlocklist], que el servicio oficial no recibe:
 * - Número DESCONOCIDO o contacto BLOQUEADO: cuelga automáticamente a los 5 segundos.
 * - Número REGISTRADO: NO interviene. Deja sonar normalmente.
 *
 * Requiere READ_CALL_LOG (para recibir el número) y ANSWER_PHONE_CALLS (para colgar).
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

        /**
         * true si Yui tiene el rol de filtro de llamadas (Android 10+), en cuyo caso
         * [YuiCallScreeningService] se encarga y este receiver no interviene.
         */
        fun hasScreeningRole(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
            val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
            return roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
                roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        }

        /**
         * Verifica si el número existe en la agenda del teléfono usando PhoneLookup,
         * que compara números ignorando formato y prefijos de país.
         * Ante cualquier duda (número oculto, sin permiso, agenda vacía, error) devuelve true
         * para no colgar nunca una llamada legítima por error.
         */
        fun isKnownNumber(context: Context, phoneNumber: String): Boolean {
            if (phoneNumber.isBlank()) return true
            // Contactos que Alan eligió bloquear se tratan como desconocidos
            if (CallBlocklist.isBlockedNumber(context, phoneNumber)) return false
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Sin permiso READ_CONTACTS: no se puede verificar, la llamada se conserva.")
                return true
            }
            return try {
                val resolver = context.contentResolver
                val lookupUri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(phoneNumber)
                )
                val found = resolver.query(lookupUri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)
                    ?.use { it.count > 0 } ?: false
                if (found) return true

                // Agenda vacía → no filtrar (evita colgar todo si los contactos aún no cargan)
                resolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone._ID), null, null, null
                )?.use { it.count == 0 } ?: true
            } catch (e: Exception) {
                Log.w(TAG, "No se pudieron consultar contactos. La llamada se conserva: ${e.message}")
                true
            }
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
                // En Android 9+ el sistema envía este broadcast dos veces: una sin número y otra
                // con número (solo si hay READ_CALL_LOG). Se ignora la que no trae número.
                if (incomingNumber == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
                        Log.w(TAG, "Sin READ_CALL_LOG: Yui no puede ver el número entrante.")
                    }
                    return
                }

                // Con el rol de filtro, el servicio oficial ya decidió sobre los desconocidos.
                // Android no le pasa las llamadas de contactos de la agenda, así que los
                // contactos bloqueados por Alan se cuelgan desde aquí.
                if (hasScreeningRole(context) && !CallBlocklist.isBlockedNumber(context, incomingNumber)) return

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
                if (isKnownNumber(context, phoneNumber)) {
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
     * Cuelga la llamada usando TelecomManager (requiere ANSWER_PHONE_CALLS).
     */
    private fun rejectCall(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.w(TAG, "API < Android 9: no se puede rechazar llamada programáticamente.")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Sin permiso ANSWER_PHONE_CALLS: no se puede colgar.")
            return
        }
        try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            @Suppress("DEPRECATION")
            val ended = telecomManager?.endCall() ?: false
            Log.d(TAG, if (ended) "Llamada desconocida rechazada correctamente." else "El sistema no colgó la llamada.")
        } catch (e: Exception) {
            Log.e(TAG, "Error al rechazar llamada: ${e.message}")
        }
    }
}
