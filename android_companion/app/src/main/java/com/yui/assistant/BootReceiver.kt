package com.yui.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            YuiReminderSyncService.start(context.applicationContext)
        } catch (e: Exception) {
            Log.w("YuiBoot", "No se pudo reiniciar la sincronización: ${e.message}")
        }
    }
}
