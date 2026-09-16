package com.yui.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class YuiAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "YuiAccessibility"
        var instance: YuiAccessibilityService? = null
            private set

        var pendingWhatsAppAutoSend = false
        private var isWhatsAppCallRinging = false
        private var whatsappRingingStartTime = 0L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "🌸 YuiAccessibilityService conectado y listo.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString() ?: ""

        if (pkg == "com.whatsapp") {
            handleWhatsAppCallEvent(event)
            if (pendingWhatsAppAutoSend) {
                CoroutineScope(Dispatchers.Main).launch {
                    delay(600L)
                    val sent = tryAutoSendWhatsAppMessage()
                    if (sent) {
                        pendingWhatsAppAutoSend = false
                        Log.d(TAG, "Mensaje de WhatsApp enviado automáticamente.")
                    }
                }
            }
        }
    }

    fun tryAutoSendWhatsAppMessage(): Boolean {
        val root = rootInActiveWindow ?: return false
        val sendById = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")
        if (!sendById.isNullOrEmpty()) {
            for (node in sendById) {
                if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        val sendKeywords = listOf("Enviar", "Send")
        for (kw in sendKeywords) {
            val nodes = root.findAccessibilityNodeInfosByText(kw)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
        }
        return false
    }

    private fun handleWhatsAppCallEvent(event: AccessibilityEvent) {
        val textList = event.text.map { it.toString() }
        val isCallRelated = textList.any { 
            it.contains("llamada", ignoreCase = true) || 
            it.contains("ringing", ignoreCase = true) || 
            it.contains("llamando", ignoreCase = true) 
        }

        if (isCallRelated && !isWhatsAppCallRinging) {
            isWhatsAppCallRinging = true
            whatsappRingingStartTime = System.currentTimeMillis()
            Log.d(TAG, "Llamada de WhatsApp detectada. Iniciando temporizador de 35s...")

            CoroutineScope(Dispatchers.Main).launch {
                delay(35000L)
                if (isWhatsAppCallRinging) {
                    Log.d(TAG, "35s transcurridos en llamada WhatsApp. Yui contestando automáticamente...")
                    answerWhatsAppCall()
                    isWhatsAppCallRinging = false
                }
            }
        }
    }

    fun answerWhatsAppCall(): Boolean {
        // Buscar botones de contestar típicos en WhatsApp en español e inglés
        val answerKeywords = listOf("Contestar", "Responder", "Aceptar", "Answer", "Accept")
        for (kw in answerKeywords) {
            if (clickElementByText(kw)) {
                Log.d(TAG, "Llamada de WhatsApp contestada con botón: $kw")
                return true
            }
        }
        return false
    }

    fun declineWhatsAppCall(): Boolean {
        val declineKeywords = listOf("Rechazar", "Colgar", "Declinar", "Decline", "Reject")
        for (kw in declineKeywords) {
            if (clickElementByText(kw)) {
                Log.d(TAG, "Llamada de WhatsApp rechazada con botón: $kw")
                return true
            }
        }
        return false
    }

    override fun onInterrupt() {
        isWhatsAppCallRinging = false
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isWhatsAppCallRinging = false
    }

    /**
     * Simula un toque (tap) en coordenadas específicas de la pantalla
     */
    fun performTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Busca un botón o elemento con texto específico y hace clic en él
     */
    fun clickElementByText(targetText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(targetText)
        if (nodes != null) {
            for (node in nodes) {
                if (node.isClickable) {
                    return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    parent = parent.parent
                }
            }
        }
        return false
    }
}
