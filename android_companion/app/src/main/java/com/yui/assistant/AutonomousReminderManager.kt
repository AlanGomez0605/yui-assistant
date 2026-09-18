package com.yui.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

object AutonomousReminderManager {

    private const val TAG = "ReminderManager"
    private const val PREFS_NAME = "yui_reminders_store"
    private const val KEY_REMINDERS_JSON = "reminders_json"

    data class LocalReminder(
        val id: Int,
        val title: String,
        val timestampMillis: Long,
        val description: String,
        val isSynced: Boolean = false
    )

    fun scheduleLocalReminder(
        context: Context,
        title: String,
        triggerMillis: Long,
        description: String = "",
        stableKey: String? = null
    ): Int {
        val reminderId = stableKey?.hashCode()?.and(0x7fffffff)
            ?: (System.currentTimeMillis() % 100000).toInt()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, AutonomousReminderReceiver::class.java).apply {
            putExtra(AutonomousReminderReceiver.EXTRA_TITLE, title)
            putExtra(AutonomousReminderReceiver.EXTRA_DESCRIPTION, description)
            putExtra(AutonomousReminderReceiver.EXTRA_REMINDER_ID, reminderId)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminderId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
            }
            saveReminderLocally(context, LocalReminder(reminderId, title, triggerMillis, description))
            Log.d(TAG, "Recordatorio autónomo programado para timestamp $triggerMillis ($title)")
        } catch (e: Exception) {
            Log.e(TAG, "Error programando alarma exacta en AlarmManager", e)
        }

        return reminderId
    }

    private fun saveReminderLocally(context: Context, reminder: LocalReminder) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawJson = prefs.getString(KEY_REMINDERS_JSON, "[]") ?: "[]"
        val array = JSONArray(rawJson)

        val obj = JSONObject().apply {
            put("id", reminder.id)
            put("title", reminder.title)
            put("timestamp", reminder.timestampMillis)
            put("description", reminder.description)
            put("is_synced", reminder.isSynced)
        }
        val updated = JSONArray()
        for (i in 0 until array.length()) {
            val existing = array.optJSONObject(i) ?: continue
            if (existing.optInt("id") != reminder.id) updated.put(existing)
        }
        updated.put(obj)
        prefs.edit().putString(KEY_REMINDERS_JSON, updated.toString()).apply()
    }

    fun getLocalReminders(context: Context): List<LocalReminder> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawJson = prefs.getString(KEY_REMINDERS_JSON, "[]") ?: "[]"
        val array = JSONArray(rawJson)
        val list = mutableListOf<LocalReminder>()
        val now = System.currentTimeMillis()

        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val ts = item.getLong("timestamp")
            if (ts > now - 86400000) { // Mostrar recordatorios de las últimas 24h en adelante
                list.add(
                    LocalReminder(
                        id = item.getInt("id"),
                        title = item.getString("title"),
                        timestampMillis = ts,
                        description = item.optString("description", ""),
                        isSynced = item.optBoolean("is_synced", false)
                    )
                )
            }
        }
        return list.sortedBy { it.timestampMillis }
    }

    suspend fun syncCloudReminders(context: Context): Int = withContext(Dispatchers.IO) {
        try {
            val baseUrl = ApiClient.getBackendUrl(context)
            val url = URL("$baseUrl/api/memory/reminders")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                ApiClient.authorize(context, this)
            }

            if (conn.responseCode in 200..299) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }

                // El backend puede devolver un array JSON directamente o {reminders: [...]}
                val jsonArray: JSONArray = try {
                    JSONArray(response)
                } catch (e: Exception) {
                    val obj = org.json.JSONObject(response)
                    obj.optJSONArray("reminders") ?: obj.optJSONArray("data") ?: JSONArray()
                }

                var count = 0
                val formatsToTry = listOf(
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()),
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()),
                    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                )
                formatsToTry.forEach { it.isLenient = false }

                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    val title = item.optString("title", "Recordatorio de Yui")
                    val dueStr = item.optString("due_datetime", "")
                    val desc = item.optString("description", "")
                    val cloudId = item.optString("id", "$title|$dueStr")
                    if (item.optBoolean("is_notified", false)) continue
                    val reminderTimeZone = java.util.TimeZone.getTimeZone(
                        item.optString("timezone", java.util.TimeZone.getDefault().id)
                    )
                    formatsToTry.forEach { it.timeZone = reminderTimeZone }

                    if (dueStr.isEmpty()) continue

                    var parsedDate: java.util.Date? = null
                    for (fmt in formatsToTry) {
                        try { parsedDate = fmt.parse(dueStr); break } catch (e: Exception) { /* intentar siguiente */ }
                    }

                    if (parsedDate != null && parsedDate.time > System.currentTimeMillis()) {
                        scheduleLocalReminder(context, title, parsedDate.time, desc, cloudId)
                        count++
                        Log.d(TAG, "Recordatorio sincronizado: '$title' a las $dueStr")
                    } else if (parsedDate != null) {
                        Log.d(TAG, "Recordatorio ya vencido ignorado: '$title' ($dueStr)")
                    } else {
                        Log.w(TAG, "No se pudo parsear la fecha del recordatorio: '$dueStr'")
                    }
                }
                count
            } else {
                0
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo sincronizar recordatorios de la nube: ${e.message}")
            0
        }
    }
}
