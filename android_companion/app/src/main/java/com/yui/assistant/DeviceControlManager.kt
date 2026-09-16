package com.yui.assistant

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log

object DeviceControlManager {

    private const val TAG = "YuiDeviceControl"

    fun setAlarm(context: Context, hour: Int, minute: Int, message: String): Boolean {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, message)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error configurando alarma nativa", e)
            false
        }
    }

    fun openApp(context: Context, packageOrAppName: String): Boolean {
        return try {
            val pm = context.packageManager
            var launchIntent = pm.getLaunchIntentForPackage(packageOrAppName)

            if (launchIntent == null) {
                // Mapear nombres comunes a paquetes
                val mappedPackage = when (packageOrAppName.lowercase()) {
                    "whatsapp" -> "com.whatsapp"
                    "youtube" -> "com.google.android.youtube"
                    "spotify" -> "com.spotify.music"
                    "chrome", "navegador" -> "com.android.chrome"
                    "camara", "cámara" -> "com.google.android.GoogleCamera"
                    "galeria", "fotos" -> "com.google.android.apps.photos"
                    "gmail", "correo" -> "com.google.android.gm"
                    "maps", "mapas" -> "com.google.android.apps.maps"
                    else -> null
                }
                if (mappedPackage != null) {
                    launchIntent = pm.getLaunchIntentForPackage(mappedPackage)
                }
            }

            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error abriendo app", e)
            false
        }
    }

    fun sendWhatsApp(context: Context, rawPhoneNumber: String, message: String): Boolean {
        return try {
            var phone = rawPhoneNumber.replace(" ", "").replace("-", "").replace("+", "").replace("(", "").replace(")", "")
            if (phone.length == 10) {
                phone = "52$phone"
            }

            YuiAccessibilityService.pendingWhatsAppAutoSend = true

            val encodedMsg = java.net.URLEncoder.encode(message, "UTF-8")
            val uri = android.net.Uri.parse("https://api.whatsapp.com/send?phone=$phone&text=$encodedMsg")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error enviando WhatsApp", e)
            false
        }
    }
}
