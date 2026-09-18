package com.yui.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat

class FloatingOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var layoutFloatingMenu: LinearLayout? = null
    private var layoutAvatarBubble: FrameLayout? = null
    private var tvConnectivityStatus: TextView? = null
    private var isMenuExpanded = false

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "yui_overlay_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        OfflineCommandEngine.initTts(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        createFloatingWidget()
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Yui SAO Overlay Activo",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Yui - MHCP-0001")
            .setContentText("Avatar 2D flotante activo y listo para interactuar")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createFloatingWidget() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 260
        }

        val inflater = LayoutInflater.from(this)
        floatingView = inflater.inflate(R.layout.layout_floating_avatar, null)

        layoutFloatingMenu = floatingView?.findViewById(R.id.layoutFloatingMenu)
        layoutAvatarBubble = floatingView?.findViewById(R.id.layoutAvatarBubble)
        tvConnectivityStatus = floatingView?.findViewById(R.id.tvConnectivityStatus)

        val btnVoice = floatingView?.findViewById<Button>(R.id.btnFloatingVoice)
        val btnOpenApp = floatingView?.findViewById<Button>(R.id.btnFloatingOpenApp)
        val btnReminders = floatingView?.findViewById<Button>(R.id.btnFloatingReminders)
        val btnCloseMenu = floatingView?.findViewById<Button>(R.id.btnFloatingCloseMenu)

        updateConnectivityBadge()

        // 1. Botón Hablar con Yui (Micrófono rápido)
        btnVoice?.setOnClickListener {
            toggleMenu(false)
            val voiceIntent = Intent(this, VoiceQuickDialogActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(voiceIntent)
        }

        // 2. Botón Abrir App Principal
        btnOpenApp?.setOnClickListener {
            toggleMenu(false)
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        }

        // 3. Botón Recordatorios
        btnReminders?.setOnClickListener {
            toggleMenu(false)
            val reminders = AutonomousReminderManager.getLocalReminders(this)
            if (reminders.isEmpty()) {
                Toast.makeText(this, "🌸 No tienes recordatorios pendientes próximos.", Toast.LENGTH_SHORT).show()
                OfflineCommandEngine.speak(this, "No tienes recordatorios pendientes próximos, Alan.")
            } else {
                val first = reminders.first()
                val text = "Próximo recordatorio: ${first.title}"
                Toast.makeText(this, "⏰ $text", Toast.LENGTH_LONG).show()
                OfflineCommandEngine.speak(this, text)
            }
        }

        // 4. Botón Minimizar
        btnCloseMenu?.setOnClickListener {
            toggleMenu(false)
        }

        // Arrastre táctil y toque del Avatar
        layoutAvatarBubble?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isClick = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams!!.x
                        initialY = layoutParams!!.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isClick = true
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 12 || Math.abs(dy) > 12) {
                            isClick = false
                        }
                        layoutParams!!.x = initialX + dx
                        layoutParams!!.y = initialY + dy
                        windowManager?.updateViewLayout(floatingView, layoutParams)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isClick) {
                            // Al hacer clic en el avatar, alternar menú
                            toggleMenu(!isMenuExpanded)
                        }
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager?.addView(floatingView, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun toggleMenu(expand: Boolean) {
        isMenuExpanded = expand
        if (expand) {
            updateConnectivityBadge()
            layoutFloatingMenu?.visibility = View.VISIBLE
        } else {
            layoutFloatingMenu?.visibility = View.GONE
        }
    }

    private fun updateConnectivityBadge() {
        val online = NetworkChangeReceiver.isConnected(this)
        if (online) {
            tvConnectivityStatus?.text = "● Online"
            tvConnectivityStatus?.setTextColor(0xFF50FA7B.toInt())
        } else {
            tvConnectivityStatus?.text = "● Offline Ready"
            tvConnectivityStatus?.setTextColor(0xFFF1FA8C.toInt())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (floatingView != null) {
            windowManager?.removeView(floatingView)
        }
    }
}
