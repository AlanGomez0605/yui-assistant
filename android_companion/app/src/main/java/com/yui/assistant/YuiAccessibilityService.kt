package com.yui.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.PendingIntent
import android.graphics.Path
import android.util.DisplayMetrics
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
        private const val UNKNOWN_WA_HANG_DELAY_MS = 5000L   // 5s para rechazar desconocidos en WhatsApp

        var instance: YuiAccessibilityService? = null
            private set

        /** Para envío automático de mensajes de WhatsApp */
        var pendingWhatsAppAutoSend = false

        /** Estado de la llamada entrante de WhatsApp */
        @Volatile
        private var isWhatsAppCallRinging = false

        /** Número/Nombre del llamante detectado en la notificación WhatsApp */
        @Volatile
        private var whatsAppCallerName: String = ""

        /** PendingIntents capturados de la notificación de llamada */
        private var lastCallActionPendingIntent: PendingIntent? = null
        private var lastDeclineActionPendingIntent: PendingIntent? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "🌸 YuiAccessibilityService conectado y listo para monitorear WhatsApp y llamadas.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString() ?: ""

        if (pkg == "com.whatsapp" || pkg.contains("whatsapp")) {
            inspectWhatsAppEvent(event)

            if (pendingWhatsAppAutoSend) {
                CoroutineScope(Dispatchers.Main).launch {
                    delay(500L)
                    val sent = tryAutoSendWhatsAppMessage()
                    if (sent) {
                        pendingWhatsAppAutoSend = false
                        Log.d(TAG, "Mensaje de WhatsApp enviado automáticamente con éxito.")
                    }
                }
            }
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
                extractNotificationActions(parcelable)
                // Intentar extraer el nombre del llamante del título de la notificación
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
            // Intentar extraer nombre del llamante del texto de la pantalla
            val callerNameFromScreen = textList.firstOrNull { it.length > 2 } ?: ""
            triggerCallFilter("Pantalla de llamada WhatsApp", callerNameFromScreen)
        }
    }

    private fun extractNotificationActions(notification: Notification) {
        val actions = notification.actions ?: return
        for (action in actions) {
            val title = action.title?.toString()?.lowercase() ?: ""
            if (title.contains("contestar") || title.contains("responder") || title.contains("aceptar") || title.contains("answer") || title.contains("accept")) {
                lastCallActionPendingIntent = action.actionIntent
                Log.d(TAG, "Acción de CONTESTAR capturada desde notificación WhatsApp.")
            } else if (title.contains("rechazar") || title.contains("colgar") || title.contains("declinar") || title.contains("decline") || title.contains("reject")) {
                lastDeclineActionPendingIntent = action.actionIntent
                Log.d(TAG, "Acción de RECHAZAR capturada desde notificación WhatsApp.")
            }
        }
    }

    /**
     * Desencadena la lógica de filtro de llamadas de WhatsApp.
     * - Si el filtro está desactivado, no hace nada.
     * - Si el contacto está en la agenda de Alan → NO interviene.
     * - Si es desconocido → rechaza a los 5 segundos.
     */
    private fun triggerCallFilter(source: String, callerName: String = "") {
        // Si la función de filtro de llamadas está desactivada, no tocar nada
        if (!CallInterceptorReceiver.isEnabled(this)) {
            Log.d(TAG, "Filtro de llamadas desactivado. Yui no intervendrá en la llamada de WhatsApp.")
            return
        }

        if (isWhatsAppCallRinging) return  // Ya estamos procesando esta llamada
        isWhatsAppCallRinging = true
        whatsAppCallerName = callerName

        Log.d(TAG, "Llamada WhatsApp detectada vía [$source]. Llamante: '${callerName.ifBlank { "Desconocido" }}'")

        CoroutineScope(Dispatchers.IO).launch {
            val isKnown = isCallerKnown(callerName)

            if (isKnown) {
                Log.d(TAG, "Llamante WhatsApp REGISTRADO ('$callerName'). Yui no interviene.")
                isWhatsAppCallRinging = false
                return@launch
            }

            // Llamante desconocido → esperar 5s y rechazar
            Log.d(TAG, "Llamante WhatsApp DESCONOCIDO. Rechazando en ${UNKNOWN_WA_HANG_DELAY_MS / 1000}s...")
            delay(UNKNOWN_WA_HANG_DELAY_MS)

            if (isWhatsAppCallRinging) {
                Log.d(TAG, "Rechazando llamada WhatsApp desconocida.")
                CoroutineScope(Dispatchers.Main).launch {
                    val rejected = declineWhatsAppCall()
                    if (!rejected) {
                        Log.w(TAG, "No se pudo rechazar por botón. Puede que la pantalla ya no esté activa.")
                    }
                    isWhatsAppCallRinging = false
                }
            }
        }
    }

    /**
     * Verifica si el nombre del llamante de WhatsApp está en los contactos de Alan.
     * Usa el nombre que WhatsApp muestra en la notificación.
     */
    private suspend fun isCallerKnown(callerName: String): Boolean {
        if (callerName.isBlank()) return true
        return try {
            val contacts = ContactsSyncManager.readAllContacts(this)
            if (contacts.isEmpty()) return true
            contacts.any { contact ->
                val name = contact.optString("name", "").lowercase()
                name.isNotBlank() && name.contains(callerName.lowercase().take(5))
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo verificar contacto WhatsApp; se conserva la llamada: ${e.message}")
            true
        }
    }

    fun answerWhatsAppCall(): Boolean {
        // Método 1: Ejecutar PendingIntent directo de la notificación
        if (lastCallActionPendingIntent != null) {
            try {
                lastCallActionPendingIntent?.send()
                Log.d(TAG, "Llamada WhatsApp contestada mediante PendingIntent de notificación.")
                lastCallActionPendingIntent = null
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Error ejecutando PendingIntent de llamada", e)
            }
        }

        // Método 2: Clic en nodo por ID de WhatsApp
        val root = rootInActiveWindow
        if (root != null) {
            val callButtonIds = listOf(
                "com.whatsapp:id/answer_btn",
                "com.whatsapp:id/accept_btn",
                "com.whatsapp:id/voice_accept_btn",
                "com.whatsapp:id/video_accept_btn",
                "com.whatsapp:id/call_accept"
            )
            for (id in callButtonIds) {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                if (!nodes.isNullOrEmpty()) {
                    for (node in nodes) {
                        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            Log.d(TAG, "Llamada contestada mediante ViewId: $id")
                            return true
                        }
                    }
                }
            }
        }

        // Método 3: Clic por texto o contentDescription
        val answerKeywords = listOf("Contestar", "Responder", "Aceptar", "Answer", "Accept", "Aceptar llamada")
        for (kw in answerKeywords) {
            if (clickElementByTextOrDescription(kw)) {
                Log.d(TAG, "Llamada de WhatsApp contestada con botón de texto/descripción: $kw")
                return true
            }
        }

        // Método 4: Gesto de deslizar hacia arriba
        return performSwipeUpToAnswer()
    }

    fun declineWhatsAppCall(): Boolean {
        if (lastDeclineActionPendingIntent != null) {
            try {
                lastDeclineActionPendingIntent?.send()
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

    fun performSwipeUpToAnswer(): Boolean {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()

        val startX = width / 2f
        val startY = height * 0.85f
        val endY = height * 0.40f

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(startX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()

        Log.d(TAG, "Ejecutando gesto de deslizamiento hacia arriba para contestar...")
        return dispatchGesture(gesture, null, null)
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
        instance = null
        isWhatsAppCallRinging = false
        lastCallActionPendingIntent = null
        lastDeclineActionPendingIntent = null
    }
}
