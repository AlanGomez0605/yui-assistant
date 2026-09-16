package com.yui.assistant

import android.content.Context
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject

object ContactsSyncManager {

    fun readAllContacts(context: Context): JSONArray {
        val contactsArray = JSONArray()
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
                val name = if (nameIndex >= 0) it.getString(nameIndex) else "Sin nombre"
                var number = if (numberIndex >= 0) it.getString(numberIndex) else ""
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

        return contactsArray
    }

    suspend fun syncContactsToCloud(context: Context): Boolean {
        val contacts = readAllContacts(context)
        return ApiClient.syncContacts(context, contacts)
    }
}
