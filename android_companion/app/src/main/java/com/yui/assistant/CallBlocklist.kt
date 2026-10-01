package com.yui.assistant

import android.content.Context
import android.content.SharedPreferences
import android.telephony.PhoneNumberUtils
import org.json.JSONArray
import org.json.JSONObject

/**
 * CallBlocklist — Contactos de la agenda que Alan eligió rechazar igualmente.
 *
 * El filtro deja pasar a los contactos registrados; los que estén en esta lista
 * se tratan como desconocidos y sus llamadas se rechazan (normales y de WhatsApp).
 */
object CallBlocklist {

    private const val PREFS_NAME = "yui_call_filter"
    private const val KEY_BLOCKED = "blocked_contacts"

    data class Entry(val name: String, val number: String)

    fun getAll(context: Context): List<Entry> {
        val raw = getPrefs(context).getString(KEY_BLOCKED, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                Entry(obj.optString("name"), obj.optString("number"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Agrega un contacto; devuelve false si ese número ya estaba bloqueado. */
    fun add(context: Context, name: String, number: String): Boolean {
        val entries = getAll(context)
        if (number.isBlank() || entries.any { PhoneNumberUtils.compare(it.number, number) }) return false
        save(context, entries + Entry(name.ifBlank { number }, number))
        return true
    }

    fun remove(context: Context, entry: Entry) {
        save(context, getAll(context).filterNot { it == entry })
    }

    fun isBlockedNumber(context: Context, number: String?): Boolean {
        if (number.isNullOrBlank()) return false
        return getAll(context).any { PhoneNumberUtils.compare(it.number, number) }
    }

    /** Para WhatsApp, que muestra el nombre del contacto en lugar del número. */
    fun isBlockedName(context: Context, name: String): Boolean {
        val target = name.trim()
        if (target.isBlank()) return false
        return getAll(context).any { it.name.trim().equals(target, ignoreCase = true) }
    }

    private fun save(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("name", it.name).put("number", it.number)) }
        getPrefs(context).edit().putString(KEY_BLOCKED, array.toString()).apply()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
