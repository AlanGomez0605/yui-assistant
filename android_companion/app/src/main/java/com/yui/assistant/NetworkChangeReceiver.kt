package com.yui.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NetworkChangeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NetworkChangeReceiver"

        fun isConnected(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val net = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(net) ?: return false
                return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                val info = cm.activeNetworkInfo
                @Suppress("DEPRECATION")
                return info != null && info.isConnected
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (isConnected(context)) {
            Log.d(TAG, "Conexión a Internet detectada. Procesando cola offline...")
            CoroutineScope(Dispatchers.IO).launch {
                val flushed = OfflineSyncQueue.flushQueue(context)
                val remindersSynced = AutonomousReminderManager.syncCloudReminders(context)
                Log.d(TAG, "Sincronización completada: $flushed tareas enviadas, $remindersSynced recordatorios actualizados.")
            }
        }
    }
}
