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
import android.webkit.WebResourceRequest
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
    private lateinit var etApiToken: EditText
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

        OfflineCommandEngine.initTts(this)
        initViews()
        checkAndRequestPermissions()
        loadSavedUrl()

        // Iniciar servicio de sincronización de recordatorios en segundo plano (siempre activo)
        YuiReminderSyncService.start(this)

        // Sincronizar recordatorios autónomos de la nube en segundo plano
        CoroutineScope(Dispatchers.IO).launch {
            AutonomousReminderManager.syncCloudReminders(this@MainActivity)
        }
    }

    private fun initViews() {
        etBackendUrl = findViewById(R.id.etBackendUrl)
        etApiToken = findViewById(R.id.etApiToken)
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
                try {
                    ApiClient.setBackendUrl(this, url)
                    ApiClient.setApiToken(this, etApiToken.text.toString())
                    Toast.makeText(this, "Conexion segura guardada", Toast.LENGTH_SHORT).show()
                    webView.loadUrl(ApiClient.getBackendUrl(this))
                    panelSettings.visibility = android.view.View.GONE
                } catch (error: IllegalArgumentException) {
                    Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
                }
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
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url ?: return true
                val allowed = Uri.parse(ApiClient.getBackendUrl(this@MainActivity))
                return target.scheme != allowed.scheme ||
                    target.host != allowed.host ||
                    target.port != allowed.port
            }
        }
        webView.addJavascriptInterface(YuiAndroidBridge(this), "AndroidYuiBridge")
    }

    private fun loadSavedUrl() {
        val savedUrl = ApiClient.getBackendUrl(this)
        etBackendUrl.setText(savedUrl)
        etApiToken.setText(ApiClient.getApiToken(this))
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), PERMISSION_REQUEST_CODE)
            Toast.makeText(this, "Por favor concede el permiso de Contactos", Toast.LENGTH_SHORT).show()
            return
        }

        tvStatus.text = "Leyendo agenda del teléfono..."
        btnSyncContacts.isEnabled = false

        CoroutineScope(Dispatchers.Main).launch {
            val (success, count, errorMsg) = ContactsSyncManager.syncContactsToCloud(this@MainActivity) { current, total ->
                tvStatus.text = "Sincronizando: $current de $total contactos..."
            }
            btnSyncContacts.isEnabled = true

            if (success) {
                tvStatus.text = "✅ $count contactos sincronizados en MongoDB Atlas."
                Toast.makeText(this@MainActivity, "✅ $count contactos guardados en la Nube", Toast.LENGTH_SHORT).show()
            } else {
                if (count == 0 && errorMsg.contains("No se encontraron")) {
                    tvStatus.text = "⚠️ No se encontraron contactos en tu agenda."
                    Toast.makeText(this@MainActivity, "No se encontraron contactos", Toast.LENGTH_SHORT).show()
                } else {
                    tvStatus.text = "❌ Error al sincronizar: $errorMsg"
                    Toast.makeText(this@MainActivity, "Error: $errorMsg", Toast.LENGTH_LONG).show()
                }
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
        fun sendWhatsApp(phoneNumber: String, message: String): Boolean {
            return DeviceControlManager.sendWhatsApp(context, phoneNumber, message)
        }

        @JavascriptInterface
        fun scheduleAutonomousReminder(title: String, triggerMillis: Long, description: String): Int {
            return AutonomousReminderManager.scheduleLocalReminder(context, title, triggerMillis, description)
        }

        @JavascriptInterface
        fun getDeviceGoogleAccounts(): String {
            val accounts = GoogleAccountManager.getDeviceGoogleAccounts(context)
            return org.json.JSONArray(accounts).toString()
        }

        @JavascriptInterface
        fun processOfflineCommand(command: String): String {
            val res = OfflineCommandEngine.processCommand(context, command)
            return org.json.JSONObject().apply {
                put("handled", res.handled)
                put("response", res.responseMessage)
                put("action", res.actionType)
            }.toString()
        }

        @JavascriptInterface
        fun isNativeCompanion(): Boolean {
            return true
        }
    }
}
