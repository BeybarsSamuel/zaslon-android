package com.zaslon.browser.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.zaslon.browser.MainActivity
import com.zaslon.browser.i18n.ZaslonLanguageStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BackgroundMediaState(
    val active: Boolean = false,
    val currentTitle: String = ZaslonLanguageStore.strings().bgMediaDefaultTitle,
    val currentUrl: String = ""
)

/**
 * Faz 6.4: Ekran Kapalıyken YouTube / Video Arka Plan Ses Oynatma Servisi
 *
 * Telefon ekranı kilitlendiğinde veya başka bir uygulamaya geçildiğinde Android'in
 * GeckoView medya iş parçacığını uyutmasını engeller (WakeLock + WifiLock + MediaPlayback ForegroundService).
 */
class BackgroundMediaService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                releaseLocks()
                _mediaState.value = BackgroundMediaState(active = false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val title = intent?.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
                    ?: ZaslonLanguageStore.strings().bgMediaDefaultTitle
                val url = intent?.getStringExtra(EXTRA_URL) ?: ""

                createChannel()
                acquireLocks()
                startForeground(NOTIFICATION_ID, buildNotification(title, url))
                _mediaState.value = BackgroundMediaState(
                    active = true,
                    currentTitle = title,
                    currentUrl = url
                )
                return START_STICKY
            }
        }
    }

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "ZaslonBrowser::BackgroundAudioWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire(4 * 60 * 60 * 1000L) // Maksimum 4 saatlik güvenli kilit
                }
            }
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wm.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "ZaslonBrowser::BackgroundAudioWifiLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Exception) {}
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wakeLock = null

        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wifiLock = null
    }

    override fun onDestroy() {
        releaseLocks()
        _mediaState.value = BackgroundMediaState(active = false)
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val s = ZaslonLanguageStore.strings()
            val channel = NotificationChannel(
                CHANNEL_ID,
                s.bgMediaChannelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = s.bgMediaChannelDesc
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, url: String): Notification {
        val s = ZaslonLanguageStore.strings()
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BackgroundMediaService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("🎧 $title")
            .setContentText(if (url.isNotBlank()) url else s.bgMediaNotifFallbackText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(
                    null,
                    s.stopWord,
                    stopIntent
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START_OR_UPDATE = "com.zaslon.browser.action.BG_MEDIA_START"
        const val ACTION_STOP = "com.zaslon.browser.action.BG_MEDIA_STOP"
        const val EXTRA_TITLE = "extra_media_title"
        const val EXTRA_URL = "extra_media_url"

        private const val CHANNEL_ID = "zaslon_bg_media_channel"
        private const val NOTIFICATION_ID = 49402

        private val _mediaState = MutableStateFlow(BackgroundMediaState())
        val mediaState: StateFlow<BackgroundMediaState> = _mediaState.asStateFlow()
    }
}
