package com.yui.assistant

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var etBackendUrl: EditText
    private lateinit var btnSaveUrl: Button
    private lateinit var btnCallFilter: Button
    private lateinit var btnBlockedContacts: Button
    private lateinit var btnToggleSettings: Button
    private lateinit var panelSettings: android.view.View
    private lateinit var tvStatus: TextView
    private lateinit var webView: WebView

    companion object {
        private const val PERMISSION_REQUEST_CODE = 200
        private const val CALL_SCREENING_ROLE_REQUEST_CODE = 202
    }

    /** Selector de contactos del sistema para elegir a quién bloquear */
    private val pickContactLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) {
            addPickedContactToBlocklist(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        loadSavedUrl()

        // Si el filtro quedó activo, confirmar que sigue teniendo permisos
        if (CallInterceptorReceiver.isEnabled(this)) {
            checkAndRequestCallPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        updateCallFilterUi()
    }

    private fun initViews() {
        etBackendUrl = findViewById(R.id.etBackendUrl)
        btnSaveUrl = findViewById(R.id.btnSaveUrl)
        btnCallFilter = findViewById(R.id.btnCallFilter)
        btnBlockedContacts = findViewById(R.id.btnBlockedContacts)
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
                    Toast.makeText(this, "Conexion segura guardada", Toast.LENGTH_SHORT).show()
                    webView.loadUrl(ApiClient.getBackendUrl(this))
                    panelSettings.visibility = android.view.View.GONE
                } catch (error: IllegalArgumentException) {
                    Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
                }
            }
        }

        btnCallFilter.setOnClickListener {
            val newState = !CallInterceptorReceiver.isEnabled(this)
            CallInterceptorReceiver.setEnabled(this, newState)
            updateCallFilterUi()
            if (newState) {
                Toast.makeText(this, "📵 Filtro de llamadas ACTIVADO", Toast.LENGTH_SHORT).show()
                checkAndRequestCallPermissions(forceCallScreeningPrompt = true)
            } else {
                Toast.makeText(this, "📞 Filtro de llamadas desactivado", Toast.LENGTH_SHORT).show()
            }
        }

        btnBlockedContacts.setOnClickListener {
            showBlockedContactsDialog()
        }

        // Tocar el estado abre Accesibilidad para habilitar el filtro de WhatsApp
        tvStatus.setOnClickListener {
            if (CallInterceptorReceiver.isEnabled(this) && !isAccessibilityServiceEnabled()) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
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
    }

    private fun loadSavedUrl() {
        val savedUrl = ApiClient.getBackendUrl(this)
        etBackendUrl.setText(savedUrl)
        webView.loadUrl(savedUrl)
    }

    private fun updateCallFilterUi() {
        val enabled = CallInterceptorReceiver.isEnabled(this)
        btnCallFilter.text = if (enabled) "📵 Filtro: ON" else "📞 Filtro: OFF"

        tvStatus.text = when {
            !enabled -> "Filtro de llamadas desactivado."
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !CallInterceptorReceiver.hasScreeningRole(this) ->
                "⚠️ Filtro en modo respaldo: acepta a Yui como app de filtro de llamadas."
            !isAccessibilityServiceEnabled() ->
                "📵 Filtro activo. Toca aquí para activar también el de WhatsApp (Accesibilidad)."
            else -> "📵 Filtro activo para llamadas normales y de WhatsApp."
        }
    }

    // ==========================================================================
    // CONTACTOS BLOQUEADOS (registrados, pero Alan quiere rechazar sus llamadas)
    // ==========================================================================

    private fun showBlockedContactsDialog() {
        val entries = CallBlocklist.getAll(this)
        val builder = AlertDialog.Builder(this)
            .setTitle("🚫 Contactos bloqueados")
            .setPositiveButton("+ Agregar contacto") { _, _ -> launchContactPicker() }
            .setNegativeButton("Cerrar", null)

        if (entries.isEmpty()) {
            builder.setMessage("Ningún contacto bloqueado.\n\nLos contactos que agregues aquí serán rechazados aunque estén en tu agenda, siempre que el filtro esté activo.")
        } else {
            val labels = entries.map { "${it.name}  ·  ${it.number}" }.toTypedArray()
            builder.setItems(labels) { _, which -> confirmUnblock(entries[which]) }
        }
        builder.show()
    }

    private fun confirmUnblock(entry: CallBlocklist.Entry) {
        AlertDialog.Builder(this)
            .setTitle("Desbloquear contacto")
            .setMessage("¿Dejar que las llamadas de ${entry.name} vuelvan a sonar?")
            .setPositiveButton("Desbloquear") { _, _ ->
                CallBlocklist.remove(this, entry)
                Toast.makeText(this, "📞 ${entry.name} desbloqueado", Toast.LENGTH_SHORT).show()
                showBlockedContactsDialog()
            }
            .setNegativeButton("Cancelar") { _, _ -> showBlockedContactsDialog() }
            .show()
    }

    private fun launchContactPicker() {
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
        try {
            pickContactLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No se encontró la app de contactos", Toast.LENGTH_LONG).show()
        }
    }

    private fun addPickedContactToBlocklist(uri: Uri) {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val picked = try {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) null
                else (cursor.getString(0) ?: "") to (cursor.getString(1) ?: "")
            }
        } catch (e: Exception) {
            null
        }

        if (picked == null || picked.second.isBlank()) {
            Toast.makeText(this, "No se pudo leer el número del contacto", Toast.LENGTH_LONG).show()
            return
        }

        val (name, number) = picked
        val added = CallBlocklist.add(this, name, number)
        val msg = if (added) "🚫 ${name.ifBlank { number }} bloqueado" else "Ese número ya estaba bloqueado"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        if (added && !CallInterceptorReceiver.isEnabled(this)) {
            Toast.makeText(this, "Activa el filtro para que el bloqueo funcione", Toast.LENGTH_LONG).show()
        }
        showBlockedContactsDialog()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, YuiAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun checkAndRequestCallPermissions(forceCallScreeningPrompt: Boolean = false) {
        val permissions = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissions.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }

        // Respaldo del filtro de llamadas: sin este permiso Android 9+ no entrega el número entrante
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.READ_CALL_LOG)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            requestCallScreeningRoleIfNeeded(forceCallScreeningPrompt)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            requestCallScreeningRoleIfNeeded(force = false)
            updateCallFilterUi()
        }
    }

    /**
     * Android 10+: pide a Yui como "App de identificación de llamadas y spam" para que
     * YuiCallScreeningService pueda rechazar desconocidos. Solo si el filtro está activo.
     */
    private fun requestCallScreeningRoleIfNeeded(force: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!force && !CallInterceptorReceiver.isEnabled(this)) return
        if (CallInterceptorReceiver.hasScreeningRole(this)) return

        val roleManager = getSystemService(RoleManager::class.java) ?: return
        if (!roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) return
        @Suppress("DEPRECATION")
        startActivityForResult(
            roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING),
            CALL_SCREENING_ROLE_REQUEST_CODE
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CALL_SCREENING_ROLE_REQUEST_CODE) {
            val msg = if (CallInterceptorReceiver.hasScreeningRole(this)) {
                "📵 Filtro de llamadas listo: Yui rechazará números desconocidos."
            } else {
                "⚠️ Sin el rol de filtro de llamadas Yui usará el modo de respaldo (menos fiable)."
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            updateCallFilterUi()
        }
    }
}
