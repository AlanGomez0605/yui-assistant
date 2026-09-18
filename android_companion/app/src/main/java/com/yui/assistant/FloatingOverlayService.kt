package com.yui.assistant

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
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
    private var ivAvatarSprite: ImageView? = null
    private var viewStatusDot: View? = null
    private var tvConnectivityStatus: TextView? = null
    private var isMenuExpanded = false

    private var bobbingAnimator: ObjectAnimator? = null
    private var pulseAnimator: ObjectAnimator? = null
    private var wakeWordListener: WakeWordListener? = null
    private var btnCallFilter: Button? = null

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
        initWakeWordListener()
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
            .setContentText("Avatar 2D flotante activo y escuchando 'Oye Yui'")
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
        ivAvatarSprite = floatingView?.findViewById(R.id.ivAvatarSprite)
        viewStatusDot = floatingView?.findViewById(R.id.viewStatusDot)
        tvConnectivityStatus = floatingView?.findViewById(R.id.tvConnectivityStatus)

        val btnVoice = floatingView?.findViewById<Button>(R.id.btnFloatingVoice)
        val btnOpenApp = floatingView?.findViewById<Button>(R.id.btnFloatingOpenApp)
        val btnReminders = floatingView?.findViewById<Button>(R.id.btnFloatingReminders)
        val btnCloseMenu = floatingView?.findViewById<Button>(R.id.btnFloatingCloseMenu)
        btnCallFilter = floatingView?.findViewById<Button>(R.id.btnFloatingCallFilter)

        updateCallFilterButton()
        updateConnectivityBadge()
        startSpriteAnimations()

        // 1. Botón Hablar con Yui (Micrófono rápido)
        btnVoice?.setOnClickListener {
            toggleMenu(false)
            launchVoiceDialog()
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

        // 4. Toggle Filtro de Llamadas
        btnCallFilter?.setOnClickListener {
            val currentlyEnabled = CallInterceptorReceiver.isEnabled(this)
            val newState = !currentlyEnabled
            CallInterceptorReceiver.setEnabled(this, newState)
            updateCallFilterButton()
            val msg = if (newState) "Filtro de llamadas ACTIVADO. Cuelgo desconocidos en 5s." else "Filtro de llamadas desactivado."
            Toast.makeText(this, if (newState) "📵 $msg" else "📞 $msg", Toast.LENGTH_SHORT).show()
            OfflineCommandEngine.speak(this, msg)
        }

        // 5. Botón Minimizar
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

    private fun startSpriteAnimations() {
        // Animación de Levitación Suave (Idle Floating Bobbing)
        layoutAvatarBubble?.let { bubble ->
            bobbingAnimator = ObjectAnimator.ofFloat(bubble, "translationY", 0f, -12f, 0f).apply {
                duration = 2400
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }

        // Animación de Pulso de Estado (Glow Pulse)
        viewStatusDot?.let { dot ->
            pulseAnimator = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.35f, 1f).apply {
                duration = 1600
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }
    }

    private fun initWakeWordListener() {
        wakeWordListener = WakeWordListener(this) {
            // Callback al detectar "Oye Yui" o "Yui"
            onWakeWordTriggered()
        }
        wakeWordListener?.startListening()
    }

    private fun onWakeWordTriggered() {
        // 1. Vibración háptica suave
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(100)
        }

        // 2. Animación de Escucha activa en el Sprite (Halo Cyan)
        layoutAvatarBubble?.let { bubble ->
            bubble.animate()
                .scaleX(1.2f)
                .scaleY(1.2f)
                .setDuration(250)
                .withEndAction {
                    bubble.animate().scaleX(1.0f).scaleY(1.0f).setDuration(250).start()
                }
                .start()
        }

        // 3. Abrir Diálogo de Voz Inmediato Manos Libres
        launchVoiceDialog()
    }

    private fun launchVoiceDialog() {
        val voiceIntent = Intent(this, VoiceQuickDialogActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(voiceIntent)
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

    private fun updateCallFilterButton() {
        val enabled = CallInterceptorReceiver.isEnabled(this)
        btnCallFilter?.apply {
            text = if (enabled) "📵 Filtro ON" else "📞 Filtro OFF"
            setBackgroundColor(
                if (enabled) 0xFFFF5555.toInt()   // Rojo suave = activo (bloquea)
                else 0xFF44475A.toInt()             // Gris = inactivo
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bobbingAnimator?.cancel()
        pulseAnimator?.cancel()
        wakeWordListener?.stopListening()
        if (floatingView != null) {
            windowManager?.removeView(floatingView)
        }
    }
}
