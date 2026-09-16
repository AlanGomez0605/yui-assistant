package com.yui.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var etBackendUrl: EditText
    private lateinit var btnSaveUrl: Button
    private lateinit var btnStartOverlay: Button
    private lateinit var btnSyncContacts: Button
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
        tvStatus = findViewById(R.id.tvStatus)
        webView = findViewById(R.id.webView)

        setupWebView()

        btnSaveUrl.setOnClickListener {
            val url = etBackendUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                ApiClient.setBackendUrl(this, url)
                Toast.makeText(this, "URL de Yui Cloud guardada", Toast.LENGTH_SHORT).show()
                webView.loadUrl(url)
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
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissions.add(Manifest.permission.ANSWER_PHONE_CALLS)
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
        tvStatus.text = "Sincronizando contactos con MongoDB Atlas..."
        CoroutineScope(Dispatchers.Main).launch {
            val success = ContactsSyncManager.syncContactsToCloud(this@MainActivity)
            if (success) {
                tvStatus.text = "✅ Contactos sincronizados con éxito en la Nube."
                Toast.makeText(this@MainActivity, "Contactos guardados en MongoDB Atlas", Toast.LENGTH_LONG).show()
            } else {
                tvStatus.text = "❌ Error al sincronizar contactos. Revisa la URL y conexión."
            }
        }
    }
}
