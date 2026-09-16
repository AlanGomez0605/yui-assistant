package com.yui.assistant

import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

object ContactsSyncManager {

    private const val TAG = "YuiContacts"

    fun readAllContacts(context: Context): JSONArray {
        val contactsArray = JSONArray()

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Permiso READ_CONTACTS no concedido todavía.")
            return contactsArray
        }

        try {
            val contentResolver = context.contentResolver
            val cursor = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE
                ),
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )

            val seenNumbers = HashSet<String>()

            cursor?.use {
                val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (it.moveToNext()) {
                    val name = if (nameIndex >= 0) it.getString(nameIndex) ?: "Sin nombre" else "Sin nombre"
                    var number = if (numberIndex >= 0) it.getString(numberIndex) ?: "" else ""
                    number = number.replace(" ", "").replace("-", "").replace("(", "").replace(")", "")

                    if (number.isNotEmpty() && !seenNumbers.contains(number)) {
                        seenNumbers.add(number)
                        val contactObj = JSONObject().apply {
                            put("name", name)
                            put("phone", number)
                            put("relationship", "conocido")
                            put("is_vip", false)
                        }
                        contactsArray.put(contactObj)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error leyendo contactos de la agenda", e)
        }

        return contactsArray
    }

    suspend fun syncContactsToCloud(context: Context): Pair<Boolean, Int> {
        val contacts = readAllContacts(context)
        if (contacts.length() == 0) {
            return Pair(false, 0)
        }
        val success = ApiClient.syncContacts(context, contacts)
        return Pair(success, contacts.length())
    }
}
