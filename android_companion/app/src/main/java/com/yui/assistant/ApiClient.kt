package com.yui.assistant

import android.content.Context
import android.content.SharedPreferences

/**
 * Guarda la URL HTTPS del backend de Yui que se abre en el chat (WebView).
 */
object ApiClient {
    private const val PREFS_NAME = "yui_prefs"
    private const val KEY_BACKEND_URL = "backend_url"
    const val DEFAULT_URL = "https://web-production-7eeaa.up.railway.app"

    fun getBackendUrl(context: Context): String {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawUrl = prefs.getString(KEY_BACKEND_URL, DEFAULT_URL) ?: DEFAULT_URL
        if (rawUrl.isBlank() || !rawUrl.startsWith("https://")) {
            setBackendUrl(context, DEFAULT_URL)
            return DEFAULT_URL
        }
        return rawUrl.replace(" ", "").replace("\n", "").replace("\r", "").trim().trimEnd('/')
    }

    fun setBackendUrl(context: Context, url: String) {
        val cleanUrl = url.replace(" ", "").replace("\n", "").replace("\r", "").trim().trimEnd('/')
        require(cleanUrl.startsWith("https://")) {
            "La URL debe usar HTTPS."
        }
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_BACKEND_URL, cleanUrl).apply()
    }
}
