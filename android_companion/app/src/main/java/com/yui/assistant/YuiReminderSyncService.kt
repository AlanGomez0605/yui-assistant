package com.yui.assistant

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale
import org.json.JSONArray

/**
 * YuiReminderSyncService — Servicio en segundo plano que sincroniza
 * recordatorios de la nube y los programa en el AlarmManager local.
 *
 * SOLUCIONA:
 * 1. Poll cada 60 segundos para detectar recordatorios nuevos del backend.
 * 2. Intenta conexión WebSocket (/ws/live) para recibir disparos en tiempo real.
 * 3. Como fallback, si no hay WS disponible, usa el poll HTTP.
 *
 * Corre como servicio en primer plano para sobrevivir en segundo plano en Android.
 */
class YuiReminderSyncService : Service() {

    companion object {
        private const val TAG = "YuiReminderSync"
        private const val POLL_INTERVAL_MS = 60_000L   // 1 minuto de poll
        private const val CHANNEL_ID = "yui_sync_channel"
        private const val NOTIFICATION_ID = 1002

        fun start(context: Context) {
            val intent = Intent(context, YuiReminderSyncService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, YuiReminderSyncService::class.java))
        }
    }

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var pollJob: Job? = null
    private var wsJob: Job? = null
    @Volatile private var activeWebSocket: NativeWebSocketClient? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, createNotification())
        Log.d(TAG, "🌸 YuiReminderSyncService iniciado — sincronizando recordatorios cada ${POLL_INTERVAL_MS / 1000}s")
        startSync()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Si el sistema mató el servicio, volver a iniciarlo
        return START_STICKY
    }

    private fun startSync() {
        // 1. Intenta conectar por WebSocket para recibir recordatorios en tiempo real
        wsJob = scope.launch { connectWebSocket() }

        // 2. Poll HTTP de respaldo cada 60 segundos
        pollJob = scope.launch {
            while (isActive) {
                try {
                    syncRemindersFromCloud()
                } catch (e: Exception) {
                    Log.w(TAG, "Error en poll de recordatorios: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Conecta al WebSocket del backend para recibir recordatorios en tiempo real.
     * Si falla o se desconecta, reintenta cada 30 segundos.
     */
    private suspend fun connectWebSocket() {
        while (true) {
            try {
                val baseUrl = ApiClient.getBackendUrl(this)
                val wsUrl = baseUrl
                    .replace("https://", "wss://")
                    .replace("http://", "ws://")
                    .trimEnd('/') + "/api/ws/live"

                Log.d(TAG, "Conectando WebSocket a $wsUrl ...")

                // Usamos java.net nativo para evitar dependencias adicionales
                val uri = URI(wsUrl)
                val client = NativeWebSocketClient(uri, ApiClient.getApiToken(this)) { messageJson ->
                    handleWebSocketMessage(messageJson)
                }
                activeWebSocket = client
                client.connect()

                // Mantener el WS vivo con pings cada 20 segundos
                while (client.isOpen) {
                    client.send("ping")
                    delay(20_000L)
                }
                Log.w(TAG, "WebSocket desconectado. Reintentando en 30s...")
            } catch (e: Exception) {
                Log.w(TAG, "WebSocket no disponible: ${e.message}. Reintentando en 30s...")
            }
            delay(30_000L)
        }
    }

    /**
     * Procesa mensajes recibidos por WebSocket del backend.
     */
    private fun handleWebSocketMessage(json: String) {
        try {
            val obj = org.json.JSONObject(json)
            val type = obj.optString("type")

            if (type == "proactive_reminder") {
                val title = obj.optString("title", "Recordatorio de Yui")
                val message = obj.optString("message", "")
                Log.d(TAG, "⚡ Recordatorio en tiempo real recibido por WS: $title")

                // Disparar inmediatamente la notificación y TTS
                val reminderId = obj.optString("reminder_id", title)
                triggerImmediateReminder(title, message, reminderId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error procesando mensaje WS: ${e.message}")
        }
    }

    /**
     * Dispara un recordatorio inmediato con TTS + notificación.
     * Usado tanto por WS en tiempo real como por el poll cuando detecta hora cumplida.
     */
    private fun triggerImmediateReminder(title: String, message: String, reminderId: String) {
        if (wasDeliveredRecently(reminderId)) return
        // TTS
        OfflineCommandEngine.initTts(this)
        val ttsText = if (message.isNotBlank()) message else "Alan, tienes un recordatorio: $title"
        OfflineCommandEngine.speak(this, ttsText)

        // Vibración
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator?.vibrate(
                android.os.VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500), -1)
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(longArrayOf(0, 500, 200, 500), -1)
        }

        // Notificación heads-up
        showReminderNotification(title, message, reminderId)
    }

    /**
     * Consulta la API del backend para obtener recordatorios activos,
     * programa los futuros en AlarmManager y dispara los que ya vencieron.
     */
    private fun syncRemindersFromCloud() {
        var conn: HttpURLConnection? = null
        try {
            val baseUrl = ApiClient.getBackendUrl(this)
            val url = java.net.URL("$baseUrl/api/memory/reminders")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                ApiClient.authorize(this@YuiReminderSyncService, this)
            }

            if (conn.responseCode !in 200..299) return

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val jsonArray = JSONArray(response)
            val now = System.currentTimeMillis()
            val sdfFull = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val sdfShort = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            sdfFull.isLenient = false
            sdfShort.isLenient = false

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val title = item.optString("title", "Recordatorio de Yui")
                val dueStr = item.optString("due_datetime", "")
                val description = item.optString("description", "")
                val reminderId = item.optString("id", "$title|$dueStr")
                if (item.optBoolean("is_notified", false)) continue
                val reminderTimeZone = java.util.TimeZone.getTimeZone(
                    item.optString("timezone", java.util.TimeZone.getDefault().id)
                )
                sdfFull.timeZone = reminderTimeZone
                sdfShort.timeZone = reminderTimeZone

                if (dueStr.isEmpty()) continue

                var parsedDate: java.util.Date? = null
                for (formatter in listOf(sdfFull, sdfShort)) {
                    try {
                        parsedDate = formatter.parse(dueStr)
                        if (parsedDate != null) break
                    } catch (_: Exception) {
                    }
                }
                if (parsedDate == null) continue

                val triggerMs = parsedDate.time
                val diffMs = triggerMs - now

                when {
                    // Recordatorio en el futuro → programar en AlarmManager
                    diffMs > 0 -> {
                        Log.d(TAG, "Programando recordatorio futuro: '$title' en ${diffMs / 60000}min")
                        AutonomousReminderManager.scheduleLocalReminder(this, title, triggerMs, description, reminderId)
                    }

                    // Recordatorio que venció hace menos de 3 minutos → disparar AHORA
                    diffMs >= -180_000L -> {
                        Log.d(TAG, "⚡ Recordatorio vencido detectado en poll: '$title'. Disparando!")
                        triggerImmediateReminder(title, "", reminderId)
                        acknowledgeReminder(reminderId)
                    }

                    // Muy viejo → ignorar (el backend lo limpiará)
                    else -> {
                        Log.d(TAG, "Recordatorio viejo ignorado: '$title' (venció hace ${-diffMs / 60000}min)")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "syncRemindersFromCloud error: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    private fun wasDeliveredRecently(reminderId: String): Boolean {
        val prefs = getSharedPreferences("yui_delivered_reminders", Context.MODE_PRIVATE)
        val key = "delivered_${reminderId.hashCode().and(0x7fffffff)}"
        val now = System.currentTimeMillis()
        val previous = prefs.getLong(key, 0L)
        if (now - previous < 10 * 60_000L) return true
        prefs.edit().putLong(key, now).apply()
        return false
    }

    private fun acknowledgeReminder(reminderId: String) {
        var connection: HttpURLConnection? = null
        try {
            val encodedId = java.net.URLEncoder.encode(reminderId, "UTF-8")
            val url = java.net.URL("${ApiClient.getBackendUrl(this)}/api/reminders/$encodedId/notified")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                ApiClient.authorize(this@YuiReminderSyncService, this)
            }
            connection.outputStream.use { }
            if (connection.responseCode !in 200..299) {
                Log.w(TAG, "No se pudo confirmar el recordatorio $reminderId: HTTP ${connection.responseCode}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo confirmar el recordatorio $reminderId: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun showReminderNotification(title: String, body: String, reminderId: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                AutonomousReminderReceiver.CHANNEL_ID,
                "Recordatorios de Yui",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                enableLights(true)
            }
            manager.createNotificationChannel(channel)
        }

        val notification = androidx.core.app.NotificationCompat.Builder(this, AutonomousReminderReceiver.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("🌸 Yui: $title")
            .setContentText(body.ifBlank { "Recordatorio programado por Yui" })
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()

        manager.notify(reminderId.hashCode().and(0x7fffffff), notification)
    }

    private fun createNotification(): android.app.Notification {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                "Yui Sync de Recordatorios",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        return androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Yui — Sincronizando recordatorios")
            .setContentText("Yui monitorea tus recordatorios en segundo plano")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        activeWebSocket?.close()
        activeWebSocket = null
        super.onDestroy()
        serviceJob.cancel()
        Log.d(TAG, "YuiReminderSyncService detenido.")
    }
}
