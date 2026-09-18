package com.yui.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat

class AutonomousReminderReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "yui_autonomous_reminders"
        const val EXTRA_TITLE = "extra_reminder_title"
        const val EXTRA_DESCRIPTION = "extra_reminder_desc"
        const val EXTRA_REMINDER_ID = "extra_reminder_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Recordatorio de Yui"
        val desc = intent.getStringExtra(EXTRA_DESCRIPTION) ?: "Tienes una tarea pendiente, Alan."
        val reminderId = intent.getIntExtra(EXTRA_REMINDER_ID, 100)

        // WakeLock para despertar el procesador de inmediato
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "Yui:AutonomousReminderWakeLock"
        )
        wakeLock?.acquire(8000)

        // 1. Hablar mediante TTS
        OfflineCommandEngine.initTts(context)
        OfflineCommandEngine.speak(context, "Alan, tienes un recordatorio importante: $title")

        // 2. Vibración háptica
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(longArrayOf(0, 400, 200, 400), -1)
        }

        // 3. Notificación Heads-Up de alta prioridad
        showAutonomousNotification(context, title, desc, reminderId)

        wakeLock?.release()
    }

    private fun showAutonomousNotification(context: Context, title: String, desc: String, id: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Recordatorios Autónomos de Yui",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones y alarmas autónomas de Yui MHCP-0001"
                enableVibration(true)
                enableLights(true)
            }
            manager.createNotificationChannel(channel)
        }

        val openIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            id,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("🌸 Yui: $title")
            .setContentText(desc)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(id, notification)
    }
}
