package com.yui.assistant

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

object CalendarSyncManager {

    private const val TAG = "YuiCalendar"

    fun getPrimaryCalendarId(context: Context): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.ACCOUNT_NAME
        )
        val cursor: Cursor? = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            null,
            null,
            null
        )

        cursor?.use {
            val idIndex = it.getColumnIndex(CalendarContract.Calendars._ID)
            val primaryIndex = it.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)

            var firstId: Long? = null
            while (it.moveToNext()) {
                val id = if (idIndex >= 0) it.getLong(idIndex) else -1
                val isPrimary = if (primaryIndex >= 0) it.getInt(primaryIndex) == 1 else false
                if (firstId == null && id != -1L) firstId = id
                if (isPrimary && id != -1L) return id
            }
            return firstId
        }
        return 1L
    }

    fun createCalendarEvent(
        context: Context,
        title: String,
        description: String,
        startMillis: Long,
        endMillis: Long
    ): Boolean {
        return try {
            val calId = getPrimaryCalendarId(context) ?: 1L
            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, startMillis)
                put(CalendarContract.Events.DTEND, endMillis)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }

            val uri: Uri? = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            uri != null
        } catch (e: SecurityException) {
            Log.e(TAG, "Permiso WRITE_CALENDAR denegado", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error creando evento de calendario", e)
            false
        }
    }

    fun getUpcomingEvents(context: Context, limit: Int = 20): JSONArray {
        val eventsArray = JSONArray()
        val now = System.currentTimeMillis()
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND
        )

        val selection = "${CalendarContract.Events.DTSTART} >= ?"
        val selectionArgs = arrayOf(now.toString())

        val cursor: Cursor? = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${CalendarContract.Events.DTSTART} ASC LIMIT $limit"
        )

        cursor?.use {
            val titleIdx = it.getColumnIndex(CalendarContract.Events.TITLE)
            val descIdx = it.getColumnIndex(CalendarContract.Events.DESCRIPTION)
            val startIdx = it.getColumnIndex(CalendarContract.Events.DTSTART)

            while (it.moveToNext()) {
                val title = if (titleIdx >= 0) it.getString(titleIdx) ?: "Sin título" else ""
                val desc = if (descIdx >= 0) it.getString(descIdx) ?: "" else ""
                val start = if (startIdx >= 0) it.getLong(startIdx) else 0L

                val obj = JSONObject().apply {
                    put("title", title)
                    put("description", desc)
                    put("start_time", start)
                }
                eventsArray.put(obj)
            }
        }
        return eventsArray
    }
}
