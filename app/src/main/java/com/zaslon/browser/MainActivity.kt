package com.zaslon.browser

import android.app.PictureInPictureParams
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.zaslon.browser.network.ByeDpiMode
import com.zaslon.browser.network.ByeDpiVpnService
import com.zaslon.browser.ui.BrowserViewModel
import com.zaslon.browser.ui.ZaslonBrowserScreen
import com.zaslon.browser.ui.theme.ZaslonTheme

/**
 * Faz 6: Zaslon Browser Android Ana Aktivitesi
 * - Jetpack Compose + Mozilla GeckoView yaşam döngüsünü yönetir.
 * - Gömülü ByeDPI VpnService tünel izin akışını ve Zorunlu PiP (Picture-in-Picture) modunu sağlar.
 */
class MainActivity : ComponentActivity() {

    private val browserViewModel: BrowserViewModel by viewModels()
    private var pendingByeDpiMode: ByeDpiMode = ByeDpiMode.TLS_RECORD_SPLIT

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        startByeDpiServiceAndToggle(pendingByeDpiMode)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Harici bağlantı (ACTION_VIEW) ile açıldıysa ilgili URL'yi yükle
        intent?.dataString?.let { incomingUrl ->
            if (incomingUrl.startsWith("http://") || incomingUrl.startsWith("https://")) {
                browserViewModel.navigateActiveTab(incomingUrl)
            }
        }

        setContent {
            ZaslonTheme {
                ZaslonBrowserScreen(
                    viewModel = browserViewModel,
                    onRequestVpnPermissionAndToggle = { mode ->
                        requestOrToggleByeDpi(mode)
                    },
                    onEnterPipMode = {
                        enterForcedPictureInPicture()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { incomingUrl ->
            if (incomingUrl.startsWith("http://") || incomingUrl.startsWith("https://")) {
                browserViewModel.navigateActiveTab(incomingUrl)
            }
        }
    }

    private fun requestOrToggleByeDpi(mode: ByeDpiMode) {
        val currentlyActive = browserViewModel.uiState.value.byeDpiActive
        if (currentlyActive) {
            browserViewModel.toggleByeDpi(mode)
            return
        }

        pendingByeDpiMode = mode
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            startByeDpiServiceAndToggle(mode)
        }
    }

    private fun startByeDpiServiceAndToggle(mode: ByeDpiMode) {
        try {
            val svcIntent = Intent(this, ByeDpiVpnService::class.java).apply {
                action = ByeDpiVpnService.ACTION_START
                putExtra(ByeDpiVpnService.EXTRA_MODE, mode.id)
                putExtra(ByeDpiVpnService.EXTRA_DOH_URL, browserViewModel.uiState.value.dohProviderUrl)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svcIntent)
            } else {
                startService(svcIntent)
            }
        } catch (_: Exception) {}
        browserViewModel.toggleByeDpi(mode)
    }

    private fun enterForcedPictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            } catch (_: Exception) {}
        }
    }
}
