package com.yui.assistant

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object ApiClient {
    private const val PREFS_NAME = "yui_prefs"
    private const val KEY_BACKEND_URL = "backend_url"
    private const val DEFAULT_URL = "https://web-production-7eeaa.up.railway.app"

    fun getBackendUrl(context: Context): String {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawUrl = prefs.getString(KEY_BACKEND_URL, DEFAULT_URL) ?: DEFAULT_URL
        if (rawUrl.contains("onrender.com") || rawUrl.contains("tu-app") || rawUrl.isBlank()) {
            setBackendUrl(context, DEFAULT_URL)
            return DEFAULT_URL
        }
        return rawUrl.replace(" ", "").replace("\n", "").replace("\r", "").trim().trimEnd('/')
    }

    fun setBackendUrl(context: Context, url: String) {
        val cleanUrl = url.replace(" ", "").replace("\n", "").replace("\r", "").trim().trimEnd('/')
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_BACKEND_URL, cleanUrl).apply()
    }

    suspend fun syncContacts(context: Context, contactsJsonArray: JSONArray): Boolean = withContext(Dispatchers.IO) {
        try {
            val baseUrl = getBackendUrl(context)
            val url = URL("$baseUrl/api/contacts/sync")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val payload = JSONObject().apply {
                put("contacts", contactsJsonArray)
            }

            val bytes = payload.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { os ->
                os.write(bytes)
                os.flush()
            }

            val responseCode = conn.responseCode
            responseCode in 200..299
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun evaluateIncomingCall(context: Context, phoneNumber: String, ringingSeconds: Int = 35): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val baseUrl = getBackendUrl(context)
            val url = URL("$baseUrl/api/telephony/incoming")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 8000

            val payload = JSONObject().apply {
                put("phone_number", phoneNumber)
                put("ringing_seconds", ringingSeconds)
            }

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            if (conn.responseCode in 200..299) {
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(responseText)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
