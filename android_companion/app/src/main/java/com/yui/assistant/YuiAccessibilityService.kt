package com.yui.assistant

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.PendingIntent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * YuiAccessibilityService — Filtro de llamadas de WhatsApp.
 *
 * Detecta llamadas entrantes de WhatsApp y, si el filtro está activo y quien llama
 * es desconocido, las rechaza a los 5 segundos. WhatsApp muestra el nombre del contacto
 * cuando está en la agenda y el número cuando no lo está, así que:
 * - Título con nombre → contacto registrado → no interviene.
 * - Título con número → se verifica en la agenda; si no existe, se rechaza.
 */
class YuiAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "YuiAccessibility"
        private const val UNKNOWN_WA_HANG_DELAY_MS = 5000L   // 5s para rechazar desconocidos en WhatsApp

        /** Estado de la llamada entrante de WhatsApp */
        @Volatile
        private var isWhatsAppCallRinging = false

        /** PendingIntent de "Rechazar" capturado de la notificación de llamada */
        private var lastDeclineActionPendingIntent: PendingIntent? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "🌸 YuiAccessibilityService conectado: filtro de llamadas de WhatsApp listo.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString() ?: ""
        if (pkg == "com.whatsapp" || pkg.contains("whatsapp")) {
            inspectWhatsAppEvent(event)
        }
    }

    private fun inspectWhatsAppEvent(event: AccessibilityEvent) {
        val className = event.className?.toString() ?: ""
        val contentDesc = event.contentDescription?.toString() ?: ""
        val textList = event.text.map { it.toString() }

        // 1. Detección a través de Notificación de Llamada Entrante (Heads-Up)
        val parcelable = event.parcelableData
        if (parcelable is Notification) {
            val isCallNotification = parcelable.category == Notification.CATEGORY_CALL ||
                    contentDesc.contains("llamada", ignoreCase = true) ||
                    contentDesc.contains("call", ignoreCase = true) ||
                    textList.any { it.contains("llamada", ignoreCase = true) || it.contains("llamando", ignoreCase = true) }

            if (isCallNotification) {
                Log.d(TAG, "Notificación de llamada de WhatsApp interceptada.")
                extractDeclineAction(parcelable)
                val callerName = parcelable.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                triggerCallFilter("Notificación de WhatsApp", callerName)
                return
            }
        }

        // 2. Detección a través de Ventana de Llamada Full-Screen (VoipActivity / VoipActivityV2)
        val isVoipWindow = className.contains("Voip", ignoreCase = true) ||
                className.contains("calling", ignoreCase = true) ||
                className.contains("InCall", ignoreCase = true) ||
                contentDesc.contains("llamada entrante", ignoreCase = true) ||
                textList.any { it.contains("llamada entrante", ignoreCase = true) || it.contains("incoming call", ignoreCase = true) }

        if (isVoipWindow) {
            Log.d(TAG, "Pantalla de llamada entrante de WhatsApp detectada: $className")
            val callerNameFromScreen = textList.firstOrNull { it.length > 2 } ?: ""
            triggerCallFilter("Pantalla de llamada WhatsApp", callerNameFromScreen)
        }
    }

    private fun extractDeclineAction(notification: Notification) {
        val actions = notification.actions ?: return
        for (action in actions) {
            val title = action.title?.toString()?.lowercase() ?: ""
            if (title.contains("rechazar") || title.contains("colgar") || title.contains("declinar") || title.contains("decline") || title.contains("reject")) {
                lastDeclineActionPendingIntent = action.actionIntent
                Log.d(TAG, "Acción de RECHAZAR capturada desde notificación WhatsApp.")
            }
        }
    }

    /**
     * Si el contacto está en la agenda → NO interviene. Si es desconocido → rechaza a los 5 segundos.
     */
    private fun triggerCallFilter(source: String, callerName: String) {
        if (!CallInterceptorReceiver.isEnabled(this)) {
            Log.d(TAG, "Filtro de llamadas desactivado. Yui no intervendrá en la llamada de WhatsApp.")
            return
        }

        if (isWhatsAppCallRinging) return  // Ya estamos procesando esta llamada
        isWhatsAppCallRinging = true

        Log.d(TAG, "Llamada WhatsApp detectada vía [$source]. Llamante: '${callerName.ifBlank { "Desconocido" }}'")

        CoroutineScope(Dispatchers.IO).launch {
            if (isCallerKnown(callerName)) {
                Log.d(TAG, "Llamante WhatsApp REGISTRADO ('$callerName'). Yui no interviene.")
                isWhatsAppCallRinging = false
                return@launch
            }

            Log.d(TAG, "Llamante WhatsApp DESCONOCIDO. Rechazando en ${UNKNOWN_WA_HANG_DELAY_MS / 1000}s...")
            delay(UNKNOWN_WA_HANG_DELAY_MS)

            CoroutineScope(Dispatchers.Main).launch {
                if (!declineWhatsAppCall()) {
                    Log.w(TAG, "No se pudo rechazar por botón. Puede que la pantalla ya no esté activa.")
                }
                isWhatsAppCallRinging = false
            }
        }
    }

    /**
     * WhatsApp solo muestra un nombre cuando el número está guardado en la agenda;
     * si el título parece un número telefónico se comprueba contra los contactos.
     */
    private fun isCallerKnown(callerName: String): Boolean {
        val digits = callerName.filter { it.isDigit() }
        val looksLikeNumber = digits.length >= 7 && callerName.all { it.isDigit() || it in "+-() ." }
        if (!looksLikeNumber) return !CallBlocklist.isBlockedName(this, callerName)
        return CallInterceptorReceiver.isKnownNumber(this, callerName)
    }

    private fun declineWhatsAppCall(): Boolean {
        lastDeclineActionPendingIntent?.let { pending ->
            try {
                pending.send()
                Log.d(TAG, "Llamada WhatsApp rechazada mediante PendingIntent.")
                lastDeclineActionPendingIntent = null
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Error rechazando llamada", e)
            }
        }

        val declineKeywords = listOf("Rechazar", "Colgar", "Declinar", "Decline", "Reject")
        for (kw in declineKeywords) {
            if (clickElementByTextOrDescription(kw)) {
                Log.d(TAG, "Llamada de WhatsApp rechazada con botón: $kw")
                return true
            }
        }
        return false
    }

    private fun clickElementByTextOrDescription(targetText: String): Boolean {
        val root = rootInActiveWindow ?: return false

        // Buscar por texto
        val nodesByText = root.findAccessibilityNodeInfosByText(targetText)
        if (!nodesByText.isNullOrEmpty()) {
            for (node in nodesByText) {
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    parent = parent.parent
                }
            }
        }

        // Buscar por ContentDescription recursivamente
        return findAndClickByContentDescription(root, targetText)
    }

    private fun findAndClickByContentDescription(node: AccessibilityNodeInfo, text: String): Boolean {
        val desc = node.contentDescription?.toString() ?: ""
        if (desc.contains(text, ignoreCase = true)) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                parent = parent.parent
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickByContentDescription(child, text)) return true
        }
        return false
    }

    override fun onInterrupt() {
        isWhatsAppCallRinging = false
    }

    override fun onDestroy() {
        super.onDestroy()
        isWhatsAppCallRinging = false
        lastDeclineActionPendingIntent = null
    }
}
