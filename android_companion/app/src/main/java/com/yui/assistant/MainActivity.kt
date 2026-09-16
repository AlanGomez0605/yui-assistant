package com.yui.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var etBackendUrl: EditText
    private lateinit var btnSaveUrl: Button
    private lateinit var btnStartOverlay: Button
    private lateinit var btnSyncContacts: Button
    private lateinit var btnToggleSettings: Button
    private lateinit var panelSettings: android.view.View
    private lateinit var tvStatus: TextView
    private lateinit var webView: WebView

    companion object {
        private const val PERMISSION_REQUEST_CODE = 200
        private const val OVERLAY_PERMISSION_REQUEST_CODE = 201
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        checkAndRequestPermissions()
        loadSavedUrl()
    }

    private fun initViews() {
        etBackendUrl = findViewById(R.id.etBackendUrl)
        btnSaveUrl = findViewById(R.id.btnSaveUrl)
        btnStartOverlay = findViewById(R.id.btnStartOverlay)
        btnSyncContacts = findViewById(R.id.btnSyncContacts)
        btnToggleSettings = findViewById(R.id.btnToggleSettings)
        panelSettings = findViewById(R.id.panelSettings)
        tvStatus = findViewById(R.id.tvStatus)
        webView = findViewById(R.id.webView)

        setupWebView()

        btnToggleSettings.setOnClickListener {
            panelSettings.visibility = if (panelSettings.visibility == android.view.View.VISIBLE) {
                android.view.View.GONE
            } else {
                android.view.View.VISIBLE
            }
        }

        btnSaveUrl.setOnClickListener {
            val url = etBackendUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                ApiClient.setBackendUrl(this, url)
                Toast.makeText(this, "URL de Yui Cloud guardada", Toast.LENGTH_SHORT).show()
                webView.loadUrl(url)
                panelSettings.visibility = android.view.View.GONE
            }
        }

        btnStartOverlay.setOnClickListener {
            checkOverlayPermissionAndStart()
        }

        btnSyncContacts.setOnClickListener {
            syncDeviceContacts()
        }
    }

    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(YuiAndroidBridge(this), "AndroidYuiBridge")
    }

    private fun loadSavedUrl() {
        val savedUrl = ApiClient.getBackendUrl(this)
        etBackendUrl.setText(savedUrl)
        webView.loadUrl(savedUrl)
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissions.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    private fun checkOverlayPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
                return
            }
        }
        startFloatingService()
    }

    private fun startFloatingService() {
        val serviceIntent = Intent(this, FloatingOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "🌸 Yui Overlay Flotante Iniciada", Toast.LENGTH_SHORT).show()
    }

    private fun syncDeviceContacts() {
        tvStatus.text = "Sincronizando contactos..."
        CoroutineScope(Dispatchers.Main).launch {
            val success = ContactsSyncManager.syncContactsToCloud(this@MainActivity)
            if (success) {
                tvStatus.text = "✅ Contactos sincronizados en MongoDB Atlas."
                Toast.makeText(this@MainActivity, "Contactos guardados en la Nube", Toast.LENGTH_SHORT).show()
            } else {
                tvStatus.text = "❌ Error al sincronizar. Revisa conexión."
            }
        }
    }

    inner class YuiAndroidBridge(private val context: Context) {

        @JavascriptInterface
        fun createCalendarEvent(title: String, description: String, startMillis: Long, endMillis: Long): Boolean {
            return CalendarSyncManager.createCalendarEvent(context, title, description, startMillis, endMillis)
        }

        @JavascriptInterface
        fun setSystemAlarm(hour: Int, minute: Int, message: String): Boolean {
            return DeviceControlManager.setAlarm(context, hour, minute, message)
        }

        @JavascriptInterface
        fun openApplication(appName: String): Boolean {
            return DeviceControlManager.openApp(context, appName)
        }

        @JavascriptInterface
        fun isNativeCompanion(): Boolean {
            return true
        }
    }
}
