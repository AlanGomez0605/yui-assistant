package com.yui.assistant

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object OfflineSyncQueue {

    private const val TAG = "OfflineSyncQueue"
    private const val PREFS_NAME = "yui_offline_queue"
    private const val KEY_QUEUE = "pending_tasks"

    data class QueueItem(
        val taskId: String,
        val type: String,
        val payload: JSONObject,
        val createdAt: Long = System.currentTimeMillis()
    )

    fun enqueueTask(context: Context, type: String, data: Map<String, Any>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_QUEUE, "[]") ?: "[]"
        val array = JSONArray(raw)

        val taskObj = JSONObject().apply {
            put("taskId", "task_${System.currentTimeMillis()}")
            put("type", type)
            put("payload", JSONObject(data))
            put("createdAt", System.currentTimeMillis())
        }
        array.put(taskObj)
        prefs.edit().putString(KEY_QUEUE, array.toString()).apply()
        Log.d(TAG, "Tarea encolada para sincronización offline: $type")
    }

    suspend fun flushQueue(context: Context): Int = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_QUEUE, "[]") ?: "[]"
        val array = JSONArray(raw)
        if (array.length() == 0) return@withContext 0

        val remainingTasks = JSONArray()
        var syncedCount = 0
        val baseUrl = ApiClient.getBackendUrl(context)

        for (i in 0 until array.length()) {
            val task = array.getJSONObject(i)
            val type = task.getString("type")
            val payload = task.getJSONObject("payload")

            val success = when (type) {
                "REMINDER_CREATE" -> postJson("$baseUrl/api/memory/reminders", payload)
                "MEMORY_CREATE" -> postJson("$baseUrl/api/memory", payload)
                else -> false
            }

            if (success) {
                syncedCount++
                Log.d(TAG, "Tarea offline sincronizada exitosamente: $type")
            } else {
                remainingTasks.put(task)
            }
        }

        prefs.edit().putString(KEY_QUEUE, remainingTasks.toString()).apply()
        syncedCount
    }

    private fun postJson(urlString: String, json: JSONObject): Boolean {
        return try {
            val url = URL(urlString)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                doOutput = true
                instanceFollowRedirects = true
                connectTimeout = 8000
                readTimeout = 8000
            }

            BufferedWriter(OutputStreamWriter(conn.outputStream, "UTF-8")).use {
                it.write(json.toString())
                it.flush()
            }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            Log.w(TAG, "Fallo al enviar tarea offline: ${e.message}")
            false
        }
    }
}
