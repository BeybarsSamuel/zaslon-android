package com.zaslon.browser.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.zaslon.browser.MainActivity
import com.zaslon.browser.i18n.ZaslonLanguageStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ByeDpiTunnelStatus(
    val running: Boolean = false,
    val vpnInterfaceEstablished: Boolean = false,
    val socksPort: Int = 10808,
    val mode: ByeDpiMode = ByeDpiMode.TLS_RECORD_SPLIT,
    val activeConnections: Int = 0,
    val splitPacketsCount: Long = 0,
    val relayedBytes: Long = 0
)

/**
 * Faz 6.3: Gömülü ByeDPI Android VpnService & Yerel SOCKS5 Tünel Servisi
 *
 * Hem GeckoView için yerel SOCKS5 (127.0.0.1:10808) paket parçalayıcıyı çalıştırır,
 * hem de kullanıcı VPN izni verdiğinde Android VpnService tünel arayüzünü (`tun0`)
 * ayağa kaldırarak işletim sisteminin arka planda servisi kapatmasını engeller.
 */
class ByeDpiVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val modeName = intent?.getStringExtra(EXTRA_MODE)
                val dohUrl = intent?.getStringExtra(EXTRA_DOH_URL)
                if (!dohUrl.isNullOrBlank()) {
                    proxyEngine.dohProviderUrl = dohUrl
                }
                val selectedMode = ByeDpiMode.entries.find { it.id == modeName } ?: ByeDpiMode.TLS_RECORD_SPLIT
                startTunnel(selectedMode)
                return START_STICKY
            }
        }
    }

    private fun startTunnel(selectedMode: ByeDpiMode) {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(selectedMode))

        proxyEngine.protectSocket = { sock -> protect(sock) }
        proxyEngine.mode = selectedMode
        val proxyStarted = proxyEngine.start()

        // VpnService izni verilmişse yerel sanal ağ arayüzünü (10.215.173.1/30) kur
        if (vpnInterface == null && prepare(this) == null) {
            vpnInterface = try {
                val primaryDns = AndroidDohResolver.bootstrapAddressForUrl(proxyEngine.dohProviderUrl) ?: "9.9.9.9"
                Builder()
                    .setSession("Zaslon ByeDPI Tunnel")
                    .setMtu(1400)
                    .addAddress("10.215.173.1", 30)
                    .addDnsServer(primaryDns)
                    .addDnsServer("149.112.112.112")
                    // Yalnızca uygulama içi döngüyü koru, sistem trafiğini yavaşlatmadan ByeDPI SOCKS5'i canlı tut
                    .addAllowedApplication(packageName)
                    .establish()
            } catch (_: Exception) {
                null
            }
        }

        _tunnelStatus.value = _tunnelStatus.value.copy(
            running = proxyStarted,
            vpnInterfaceEstablished = vpnInterface != null,
            mode = selectedMode
        )
    }

    private fun stopTunnel() {
        proxyEngine.protectSocket = null
        proxyEngine.stop()
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null
        _tunnelStatus.value = ByeDpiTunnelStatus(running = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val s = ZaslonLanguageStore.strings()
            val channel = NotificationChannel(
                CHANNEL_ID,
                s.byedpiChannelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = s.byedpiChannelDesc
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(mode: ByeDpiMode): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ByeDpiVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val s = ZaslonLanguageStore.strings()
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(s.byedpiNotifTitle(s.byeDpiModeLabel(mode)))
            .setContentText(s.byedpiNotifText)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(
                    null,
                    s.closeWord,
                    stopIntent
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.zaslon.browser.action.START_BYEDPI"
        const val ACTION_STOP = "com.zaslon.browser.action.STOP_BYEDPI"
        const val EXTRA_MODE = "extra_byedpi_mode"
        const val EXTRA_DOH_URL = "extra_doh_url"

        private const val CHANNEL_ID = "zaslon_byedpi_channel"
        private const val NOTIFICATION_ID = 49401

        private val _tunnelStatus = MutableStateFlow(ByeDpiTunnelStatus())
        val tunnelStatus: StateFlow<ByeDpiTunnelStatus> = _tunnelStatus.asStateFlow()

        val proxyEngine = ByeDpiProxyEngine(
            listenPort = 10808,
            onStatsUpdated = { activeConns, splitPkts, bytes ->
                _tunnelStatus.value = _tunnelStatus.value.copy(
                    running = true,
                    activeConnections = activeConns,
                    splitPacketsCount = splitPkts,
                    relayedBytes = bytes
                )
            }
        )

        /**
         * VPN izni diyaloğu gerekmeden bile doğrudan uygulama içi ByeDPI SOCKS5 sunucusunu başlatır.
         */
        fun startStandaloneProxy(
            mode: ByeDpiMode = ByeDpiMode.TLS_RECORD_SPLIT,
            dohUrl: String = AndroidDohResolver.DEFAULT_DOH_URL
        ): Boolean {
            proxyEngine.mode = mode
            proxyEngine.dohProviderUrl = dohUrl
            val ok = proxyEngine.start()
            _tunnelStatus.value = _tunnelStatus.value.copy(
                running = ok,
                mode = mode
            )
            return ok
        }

        fun stopStandaloneProxy() {
            proxyEngine.stop()
            _tunnelStatus.value = ByeDpiTunnelStatus(running = false)
        }
    }
}
