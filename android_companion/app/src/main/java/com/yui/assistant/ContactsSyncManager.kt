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
    private const val BATCH_SIZE = 50

    fun readAllContacts(context: Context): List<JSONObject> {
        val contactList = mutableListOf<JSONObject>()

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Permiso READ_CONTACTS no concedido todavía.")
            return contactList
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
                        contactList.add(contactObj)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error leyendo contactos de la agenda", e)
        }

        return contactList
    }

    suspend fun syncContactsToCloud(
        context: Context,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): Triple<Boolean, Int, String> {
        val contacts = readAllContacts(context)
        val total = contacts.size
        if (total == 0) {
            return Triple(false, 0, "No se encontraron contactos en la agenda")
        }

        var uploadedCount = 0
        val batches = contacts.chunked(BATCH_SIZE)

        for (batch in batches) {
            val jsonArray = JSONArray()
            for (contact in batch) {
                jsonArray.put(contact)
            }

            val (success, errorMsg) = ApiClient.syncContacts(context, jsonArray)
            if (!success) {
                return Triple(false, uploadedCount, errorMsg)
            }

            uploadedCount += batch.size
            onProgress?.invoke(uploadedCount, total)
        }

        return Triple(true, total, "OK")
    }
}
