package app.vkturn

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import app.vkturn.daemon.createDaemonClient
import app.vkturn.daemon.defaultDaemonAddress
import app.vkturn.persistence.createSettingsStore
import app.vkturn.proxy.createBinaryResolver
import app.vkturn.proxy.createProcessHost
import app.vkturn.ui.App
import app.vkturn.vm.AppViewModel
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: AppViewModel

    /**
     * Consent launcher for [VpnService]. Android requires an Activity-scoped
     * result flow before the system will allow a VpnService to start; we
     * set up the launcher once and trigger it lazily from [ensureVpnConsent].
     */
    private val vpnConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { /* result — either RESULT_OK (granted) or RESULT_CANCELED (refused) */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Bind the application context into the actual factories. Must happen
        // before any store / resolver is constructed.
        app.vkturn.persistence.androidPersistenceContext = applicationContext
        app.vkturn.proxy.androidBinaryResolverContext = applicationContext
        app.vkturn.proxy.androidVpnRunnerContext = applicationContext
        androidActivityRef = this

        viewModel = AppViewModel(
            store = createSettingsStore(),
            resolver = createBinaryResolver(),
            hostFactory = ::createProcessHost,
            daemonClient = createDaemonClient(defaultDaemonAddress()),
        )
        app.vkturn.tunnel.TunnelLogSink.bus = viewModel.logs
        // see the dialog again on this device unless they revoke it manually.
        ensureVpnConsent()

        setContent {
            App(
                viewModel = viewModel,
                onSaveConfig = { name, content -> saveConfigToAppFiles(name, content) },
            )
        }
    }

    fun ensureVpnConsent() {
        val intent: Intent? = VpnService.prepare(this)
        if (intent != null) vpnConsentLauncher.launch(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        app.vkturn.tunnel.TunnelLogSink.bus = null
        viewModel.dispose()
        if (androidActivityRef === this) androidActivityRef = null
    }

    private fun saveConfigToAppFiles(name: String, content: String) {
        val dir = getExternalFilesDir(null) ?: filesDir
        File(dir, name).writeText(content)
    }
}

/** Set from [MainActivity.onCreate] so background code (VpnRunner) can ask
 *  the live Activity to re-request VPN consent. Null outside Android's
 *  Activity lifecycle. */
internal var androidActivityRef: MainActivity? = null
