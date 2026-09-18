package com.yui.assistant

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object ApiClient {
    private const val TAG = "ApiClient"
    private const val PREFS_NAME = "yui_prefs"
    private const val KEY_BACKEND_URL = "backend_url"
    const val DEFAULT_URL = "https://web-production-7eeaa.up.railway.app"

    fun getBackendUrl(context: Context): String {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawUrl = prefs.getString(KEY_BACKEND_URL, DEFAULT_URL) ?: DEFAULT_URL
        if (rawUrl.contains("onrender.com") || rawUrl.contains("tu-app") || rawUrl.isBlank() || !rawUrl.startsWith("http")) {
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

    suspend fun syncContacts(context: Context, contactsJsonArray: JSONArray): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val baseUrl = getBackendUrl(context)
            val url = URL("$baseUrl/api/contacts/sync")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                doOutput = true
                instanceFollowRedirects = true
                connectTimeout = 20000
                readTimeout = 20000
            }

            val payload = JSONObject().apply {
                put("contacts", contactsJsonArray)
            }

            BufferedWriter(OutputStreamWriter(conn.outputStream, "UTF-8")).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                Pair(true, "OK")
            } else {
                val errorBody = try {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (e: Exception) {
                    ""
                }
                Log.e(TAG, "Sync error HTTP $responseCode: $errorBody")
                Pair(false, "HTTP $responseCode: ${errorBody.take(100)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Excepción al sincronizar contactos", e)
            Pair(false, e.localizedMessage ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    suspend fun evaluateIncomingCall(context: Context, phoneNumber: String, ringingSeconds: Int = 35): JSONObject? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val baseUrl = getBackendUrl(context)
            val url = URL("$baseUrl/api/telephony/incoming")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                doOutput = true
                instanceFollowRedirects = true
                connectTimeout = 8000
                readTimeout = 8000
            }

            val payload = JSONObject().apply {
                put("phone_number", phoneNumber)
                put("ringing_seconds", ringingSeconds)
            }

            BufferedWriter(OutputStreamWriter(conn.outputStream, "UTF-8")).use { writer ->
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
            Log.e(TAG, "Error evaluating incoming call", e)
            null
        } finally {
            conn?.disconnect()
        }
    }
}
