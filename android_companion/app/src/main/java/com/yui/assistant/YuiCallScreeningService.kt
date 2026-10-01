package com.yui.assistant

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log

/**
 * YuiCallScreeningService — Filtro oficial de llamadas (Android 10+).
 *
 * Android entrega aquí cada llamada entrante ANTES de que suene, con el número incluido
 * (no requiere READ_CALL_LOG). Requiere que el usuario conceda a Yui el rol
 * "App de identificación de llamadas y spam" (RoleManager.ROLE_CALL_SCREENING).
 *
 * - Filtro desactivado, número oculto o contacto registrado → la llamada suena normal.
 * - Número desconocido → se rechaza de inmediato (el sistema exige responder en ~5s).
 */
class YuiCallScreeningService : CallScreeningService() {

    companion object {
        private const val TAG = "YuiCallScreening"
    }

    override fun onScreenCall(callDetails: Call.Details) {
        val allow = CallResponse.Builder().build()

        val isIncoming = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            callDetails.callDirection == Call.Details.DIRECTION_INCOMING
        if (!isIncoming || !CallInterceptorReceiver.isEnabled(this)) {
            respondToCall(callDetails, allow)
            return
        }

        val number = callDetails.handle?.schemeSpecificPart.orEmpty()
        if (CallInterceptorReceiver.isKnownNumber(this, number)) {
            Log.d(TAG, "Número registrado u oculto ($number). Yui no interviene.")
            respondToCall(callDetails, allow)
            return
        }

        Log.d(TAG, "Número desconocido ($number). Rechazando llamada.")
        respondToCall(
            callDetails,
            CallResponse.Builder()
                .setDisallowCall(true)
                .setRejectCall(true)
                .setSkipCallLog(false)
                .setSkipNotification(false)
                .build()
        )
    }
}
