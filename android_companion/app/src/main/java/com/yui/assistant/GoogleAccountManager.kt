package com.yui.assistant

import android.accounts.AccountManager
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

object GoogleAccountManager {

    private const val TAG = "GoogleAccountManager"

    fun getDeviceGoogleAccounts(context: Context): List<String> {
        val accountList = mutableListOf<String>()
        try {
            val manager = AccountManager.get(context)
            val accounts = manager.getAccountsByType("com.google")
            for (acc in accounts) {
                accountList.add(acc.name)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Permiso GET_ACCOUNTS no concedido: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo cuentas de Google del dispositivo", e)
        }
        return accountList
    }

    suspend fun linkGoogleAccountToCloud(
        context: Context,
        email: String,
        appPassword: String? = null,
        displayName: String? = null
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = ApiClient.getBackendUrl(context)
            val url = URL("$baseUrl/api/google/accounts")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                doOutput = true
                instanceFollowRedirects = true
                connectTimeout = 15000
                readTimeout = 15000
            }

            val payload = JSONObject().apply {
                put("email", email.trim().lowercase())
                put("display_name", displayName ?: email.split("@")[0])
                if (!appPassword.isNullOrBlank()) {
                    put("app_password", appPassword.trim())
                }
                put("scopes", JSONArray(listOf("gmail", "calendar", "drive")))
            }

            BufferedWriter(OutputStreamWriter(conn.outputStream, "UTF-8")).use {
                it.write(payload.toString())
                it.flush()
            }

            val code = conn.responseCode
            if (code in 200..299) {
                Pair(true, "Cuenta vinculada exitosamente con Yui")
            } else {
                val error = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Pair(false, "HTTP $code: $error")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error vinculando cuenta de Google", e)
            Pair(false, e.localizedMessage ?: e.javaClass.simpleName)
        }
    }

    suspend fun getLinkedGoogleAccounts(context: Context): List<JSONObject> = withContext(Dispatchers.IO) {
        val list = mutableListOf<JSONObject>()
        try {
            val baseUrl = ApiClient.getBackendUrl(context)
            val url = URL("$baseUrl/api/google/accounts")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
            }

            if (conn.responseCode in 200..299) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val array = JSONArray(response)
                for (i in 0 until array.length()) {
                    list.add(array.getJSONObject(i))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error obteniendo cuentas de Google: ${e.message}")
        }
        list
    }
}
