package com.zaslon.browser

import android.app.Application
import com.zaslon.browser.i18n.ZaslonLanguageStore
import java.io.File

/**
 * Zaslon Browser Android Uygulama Başlatıcısı
 * - Başlangıçta üçüncü parti telemetri / Glean / Crashpad kalıntı dizinlerini temizler.
 */
class ZaslonApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        ZaslonLanguageStore.init(this)
        purgeTelemetryArtifacts()
    }

    private fun purgeTelemetryArtifacts() {
        try {
            val suspectDirs = listOf(
                File(filesDir, "glean_data"),
                File(filesDir, "mozilla-telemetry"),
                File(cacheDir, "Crash Reports"),
                File(cacheDir, "minidumps")
            )
            suspectDirs.forEach { dir ->
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
            }
        } catch (_: Exception) {}
    }
}
