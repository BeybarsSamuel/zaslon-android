package com.zaslon.browser.engine

import android.content.Context
import com.zaslon.browser.network.AndroidDohResolver
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Konteyner Kimlikleri — Masaüstü Zaslon ile Birebir Uyumlu Renk ve İzolasyon Katmanları
 */
enum class ZaslonContainer(
    val id: String,
    val label: String,
    val badgeEmoji: String,
    val colorHex: Long,
    val isPrivateRamOnly: Boolean
) {
    PERSONAL("personal", "Kişisel", "🛡️", 0xFF60A5FA, false),
    BANK_WORK("bank", "Banka / İş", "🏦", 0xFF34D399, false),
    SOCIAL("social", "Sosyal", "💬", 0xFFF472B6, false),
    SHOPPING("shopping", "Alışveriş", "🛒", 0xFFFBBF24, false),
    GHOST("ghost", "Geçici (RAM)", "👻", 0xFFA78BFA, true),
    TOR("tor", "Tor Zırhlı", "🧅", 0xFFC084FC, true)
}

/**
 * 3 Kademeli Güvenlik Seviyesi (Tor Security Slider)
 */
enum class SecurityLevel(val level: Int, val title: String, val summary: String) {
    STANDARD(1, "Standart", "Tüm site özellikleri açık + Parmak İzi Kalkanı + Reklam Engelleyici"),
    SAFER(2, "Daha Güvenli", "Yalnızca HTTPS zorunlu, ağır medya otomatik oynatma kısıtlı"),
    PARANOID(3, "Paranoyak (JS Kapalı)", "JavaScript motoru devre dışı; 0-gün istismarlarına karşı tam zırh")
}

/**
 * Canlı İstek Denetçisi (Request Inspector) Kaydı
 */
data class InspectorLogEntry(
    val domain: String,
    val url: String,
    val category: String,
    val blocked: Boolean,
    val timestamp: Long
)

/**
 * Faz 6.1: Sıfır-Telemetri & Glean Sökülmüş Mozilla GeckoView Motor Yöneticisi
 */
class HardenedGeckoEngine(
    private val appContext: Context,
    private val onRequestLogged: (InspectorLogEntry) -> Unit,
    private val onUrlCleaned: (String, Int) -> Unit,
    private val onSponsorSkipped: (String, String, Int) -> Unit,
    private val onMediaPlayingChanged: (Boolean, String, String) -> Unit,
    private val onTorBlocked: ((String) -> Unit)? = null
) {
    @Volatile
    private var geckoRuntime: GeckoRuntime? = null
    private var shieldPort: WebExtension.Port? = null

    val customBlockedDomains = mutableSetOf<String>()
    val whitelistedDomains = mutableSetOf<String>()
    private val torUrlHints = mutableSetOf<String>()

    var shieldEnabled: Boolean = true
        private set
    var sponsorBlockEnabled: Boolean = true
        private set
    var backgroundAudioEnabled: Boolean = true
        private set
    var currentVideoSpeed: Float = 1.0f
        private set
    var currentSecurityLevel: SecurityLevel = SecurityLevel.STANDARD
        private set
    @Volatile
    var activeContainerId: String = ZaslonContainer.PERSONAL.id
        private set
    @Volatile
    var torActive: Boolean = false
        private set
    @Volatile
    var torVerified: Boolean = false
        private set
    @Volatile
    var verifiedTorPort: Int = 9050
        private set
    @Volatile
    var circuitEpoch: Int = 1
        private set
    @Volatile
    var byeDpiActive: Boolean = false
        private set
    @Volatile
    var byeDpiPort: Int = 10808
        private set
    @Volatile
    var currentDohProviderUrl: String = AndroidDohResolver.DEFAULT_DOH_URL
        private set

    /**
     * Telemetri, Glean, SafeBrowsing ve CrashReporter tamamen kapatılmış GeckoRuntime başlatır.
     */
    @Synchronized
    fun getOrCreateRuntime(
        byeDpiActive: Boolean = this.byeDpiActive,
        byeDpiPort: Int = this.byeDpiPort,
        torActive: Boolean = this.torActive,
        torPort: Int = this.verifiedTorPort,
        dohProviderUrl: String = this.currentDohProviderUrl
    ): GeckoRuntime {
        geckoRuntime?.let { return it }

        val runtimeConfigFile = writeHardenedConfigFile(
            byeDpiActive = byeDpiActive,
            byeDpiPort = byeDpiPort,
            torActive = torActive,
            torPort = torPort,
            dohProviderUrl = dohProviderUrl
        )

        val contentBlocking = ContentBlocking.Settings.Builder()
            .antiTracking(
                ContentBlocking.AntiTracking.DEFAULT or
                    ContentBlocking.AntiTracking.STP or
                    ContentBlocking.AntiTracking.FINGERPRINTING or
                    ContentBlocking.AntiTracking.CRYPTOMINING
            )
            .safeBrowsing(ContentBlocking.SafeBrowsing.NONE) // Google SafeBrowsing sızıntısı %100 kapalı
            .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
            .cookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
            .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
            .build()

        val settingsBuilder = GeckoRuntimeSettings.Builder()
            .configFilePath(runtimeConfigFile.absolutePath)
            .javaScriptEnabled(currentSecurityLevel != SecurityLevel.PARANOID)
            .remoteDebuggingEnabled(false)
            .consoleOutput(false)
            .aboutConfigEnabled(false)
            .webFontsEnabled(currentSecurityLevel == SecurityLevel.STANDARD)
            .automaticFontSizeAdjustment(true)
            .contentBlocking(contentBlocking)

        // Glean veya Runtime Telemetry metodları varsa yansıma (reflection) ile de kesin kapat
        disableTelemetryViaReflection(settingsBuilder)

        val runtime = GeckoRuntime.create(appContext, settingsBuilder.build())
        geckoRuntime = runtime

        installBuiltInShieldExtensions(runtime.webExtensionController)
        return runtime
    }

    private fun disableTelemetryViaReflection(builder: GeckoRuntimeSettings.Builder) {
        try {
            val method = builder.javaClass.getMethod("telemetryEnabled", Boolean::class.javaPrimitiveType)
            method.invoke(builder, false)
        } catch (_: Throwable) {}
        try {
            val crashMethod = builder.javaClass.getMethod("crashHandler", Class::class.java)
            crashMethod.invoke(builder, null)
        } catch (_: Throwable) {}
    }

    /**
     * assets/geckoview-config.yaml dosyasını okur ve başlangıç katı DoH (TRR mod 3) +
     * opsiyonel SOCKS5 tercihlerini yazarak çalışma zamanı YAML dosyasını üretir.
     * Çalışma zamanındaki sekme/konteyner bazlı dinamik Tor/ByeDPI yönlendirmesi ise
     * `browser.proxy.onRequest` üzerinden anlık uygulanır.
     */
    fun writeHardenedConfigFile(
        byeDpiActive: Boolean,
        byeDpiPort: Int = 10808,
        torActive: Boolean = false,
        torPort: Int = 9050,
        dohProviderUrl: String = AndroidDohResolver.DEFAULT_DOH_URL
    ): File {
        this.byeDpiActive = byeDpiActive
        this.byeDpiPort = byeDpiPort
        this.torActive = torActive
        if (torPort > 0) this.verifiedTorPort = torPort
        this.currentDohProviderUrl = dohProviderUrl

        val baseYaml = try {
            appContext.assets.open("geckoview-config.yaml").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            "prefs:\n  toolkit.telemetry.enabled: false\n"
        }

        val bootstrapAddr = AndroidDohResolver.bootstrapAddressForUrl(dohProviderUrl) ?: "9.9.9.9"
        val useStaticProxy = torActive && torVerified && torPort > 0
        val proxyHost = if (useStaticProxy || byeDpiActive) "127.0.0.1" else ""
        val proxyPort = when {
            useStaticProxy -> torPort
            byeDpiActive -> byeDpiPort
            else -> 0
        }
        val proxyType = if (proxyPort > 0) 1 else 0 // 1 = Manuel SOCKS5 Proxy

        val trrMode = if (useStaticProxy) 0 else 3
        val dynamicPrefs = buildString {
            appendLine(baseYaml.trimEnd())
            appendLine("  network.trr.mode: $trrMode")
            appendLine("  network.trr.uri: \"$dohProviderUrl\"")
            appendLine("  network.trr.bootstrapAddress: \"$bootstrapAddr\"")
            appendLine("  network.trr.excluded-domains: \"onion\"")
            appendLine("  javascript.enabled: ${currentSecurityLevel != SecurityLevel.PARANOID}")
            appendLine("  javascript.options.ion: ${currentSecurityLevel == SecurityLevel.STANDARD}")
            appendLine("  javascript.options.baselinejit: ${currentSecurityLevel == SecurityLevel.STANDARD}")
            appendLine("  javascript.options.wasm: ${currentSecurityLevel == SecurityLevel.STANDARD}")
            appendLine("  javascript.options.asmjs: ${currentSecurityLevel == SecurityLevel.STANDARD}")
            appendLine("  media.webaudio.enabled: ${currentSecurityLevel != SecurityLevel.PARANOID}")
            appendLine("  svg.disabled: ${currentSecurityLevel == SecurityLevel.PARANOID}")
            appendLine("  gfx.font_rendering.opentype_svg.enabled: ${currentSecurityLevel == SecurityLevel.STANDARD}")
            appendLine("  network.proxy.socks_remote_dns: true")
            appendLine("  network.proxy.type: $proxyType")
            if (proxyType == 1) {
                appendLine("  network.proxy.socks: \"$proxyHost\"")
                appendLine("  network.proxy.socks_port: $proxyPort")
                appendLine("  network.proxy.socks_version: 5")
            }
        }

        val outFile = File(appContext.filesDir, "zaslon-geckoview-runtime.yaml")
        outFile.writeText(dynamicPrefs, Charsets.UTF_8)
        syncConfigToExtension()
        return outFile
    }

    /**
     * Yerleşik Zaslon Shield (+ uBlock Origin filtre çekirdeği, ClearURLs, SponsorBlock, Arka Plan Oynatma)
     * WebExtension eklentisini GeckoView içine yükler.
     */
    private fun installBuiltInShieldExtensions(controller: WebExtensionController) {
        val messageDelegate = object : WebExtension.MessageDelegate {
            override fun onMessage(
                nativeApp: String,
                message: Any,
                sender: WebExtension.MessageSender
            ): GeckoResult<Any>? {
                handleExtensionMessage(message)
                return null
            }

            override fun onConnect(port: WebExtension.Port) {
                shieldPort = port
                port.setDelegate(object : WebExtension.PortDelegate {
                    override fun onPortMessage(message: Any, port: WebExtension.Port) {
                        handleExtensionMessage(message)
                    }

                    override fun onDisconnect(port: WebExtension.Port) {
                        if (shieldPort === port) shieldPort = null
                    }
                })
                syncConfigToExtension()
            }
        }

        controller.ensureBuiltIn(
            "resource://android/assets/extensions/zaslon-shield/",
            "shield@zaslon.browser"
        ).accept(
            { extension ->
                extension?.setMessageDelegate(messageDelegate, "zaslon_shield")
            },
            { _ -> }
        )
    }

    private fun handleExtensionMessage(raw: Any) {
        val json = when (raw) {
            is JSONObject -> raw
            is String -> try { JSONObject(raw) } catch (_: Exception) { return }
            else -> return
        }

        when (json.optString("type")) {
            "REQUEST_LOG" -> {
                val entry = InspectorLogEntry(
                    domain = json.optString("domain", ""),
                    url = json.optString("url", ""),
                    category = json.optString("category", "request"),
                    blocked = json.optBoolean("blocked", false),
                    timestamp = json.optLong("timestamp", System.currentTimeMillis())
                )
                if (entry.domain.isNotEmpty()) {
                    onRequestLogged(entry)
                }
            }
            "URL_CLEANED" -> {
                onUrlCleaned(
                    json.optString("domain", ""),
                    json.optInt("count", 1)
                )
            }
            "SPONSOR_SKIPPED" -> {
                onSponsorSkipped(
                    json.optString("videoId", ""),
                    json.optString("category", "sponsor"),
                    json.optInt("savedSeconds", 0)
                )
            }
            "MEDIA_STATE_CHANGED" -> {
                onMediaPlayingChanged(
                    json.optBoolean("playing", false),
                    json.optString("title", ""),
                    json.optString("url", "")
                )
            }
            "TOR_BLOCKED" -> {
                onTorBlocked?.invoke(json.optString("url", ""))
            }
        }
    }

    /**
     * 127.0.0.1 üzerindeki Tor/Orbot SOCKS5 portunu gerçek SOCKS5 el sıkışması (0x05 0x01 0x00)
     * ile doğrular. Doğrulanmazsa null döner (fail-closed).
     */
    fun verifyTorSocks5(candidatePorts: List<Int> = listOf(9050, 9150), timeoutMs: Int = 1200): Int? {
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        for (port in candidatePorts) {
            if (port !in 1..65535) continue
            val ok = try {
                Socket().use { sock ->
                    sock.connect(InetSocketAddress(loopback, port), timeoutMs)
                    sock.soTimeout = timeoutMs
                    val out = sock.getOutputStream()
                    out.write(byteArrayOf(0x05, 0x01, 0x00))
                    out.flush()
                    val inp = sock.getInputStream()
                    val b0 = inp.read()
                    val b1 = inp.read()
                    b0 == 0x05 && b1 != -1 && b1 != 0xFF
                }
            } catch (_: Exception) {
                false
            }
            if (ok) {
                verifiedTorPort = port
                torVerified = true
                syncConfigToExtension()
                return port
            }
        }
        torVerified = false
        syncConfigToExtension()
        return null
    }

    fun updateProxyRouting(
        activeContainer: ZaslonContainer,
        byeDpiEnabled: Boolean,
        byeDpiPort: Int = this.byeDpiPort,
        torUrlHint: String? = null
    ) {
        this.activeContainerId = activeContainer.id
        this.torActive = (activeContainer == ZaslonContainer.TOR)
        this.byeDpiActive = byeDpiEnabled
        this.byeDpiPort = byeDpiPort
        if (!torUrlHint.isNullOrBlank()) {
            synchronized(torUrlHints) {
                torUrlHints.add(torUrlHint)
            }
        }
        syncConfigToExtension()
    }

    fun addTorUrlHint(torUrlHint: String?) {
        if (!torUrlHint.isNullOrBlank()) {
            synchronized(torUrlHints) {
                torUrlHints.add(torUrlHint)
            }
            syncConfigToExtension()
        }
    }

    fun newTorIdentity() {
        circuitEpoch += 1
        synchronized(torUrlHints) {
            torUrlHints.clear()
        }
        syncConfigToExtension()
    }

    fun syncConfigToExtension(clearTorTabs: Boolean = false, torSessionEnded: Boolean = false) {
        val port = shieldPort ?: return
        try {
            val hintsSnapshot = synchronized(torUrlHints) { torUrlHints.toList() }
            val payload = JSONObject().apply {
                put("type", "SET_CONFIG")
                put("shieldEnabled", shieldEnabled)
                put("sponsorBlockEnabled", sponsorBlockEnabled)
                put("backgroundAudioEnabled", backgroundAudioEnabled)
                put("videoSpeed", currentVideoSpeed.toDouble())
                put("torActive", torActive)
                put("torVerified", torVerified)
                put("torPort", verifiedTorPort)
                put("circuitEpoch", circuitEpoch)
                put("activeContainerId", activeContainerId)
                put("byeDpiActive", byeDpiActive)
                put("byeDpiPort", byeDpiPort)
                put("torUrlHints", JSONArray(hintsSnapshot))
                put("clearTorTabs", clearTorTabs)
                put("torSessionEnded", torSessionEnded)
                put("customBlocked", JSONArray(customBlockedDomains.toList()))
                put("whitelist", JSONArray(whitelistedDomains.toList()))
            }
            port.postMessage(payload)
        } catch (_: Exception) {}
    }

    fun endTorSession() {
        synchronized(torUrlHints) {
            torUrlHints.clear()
        }
        circuitEpoch += 1
        torActive = false
        syncConfigToExtension(clearTorTabs = true, torSessionEnded = true)
    }

    fun setShieldActive(enabled: Boolean) {
        shieldEnabled = enabled
        syncConfigToExtension()
    }

    fun setSponsorBlockActive(enabled: Boolean) {
        sponsorBlockEnabled = enabled
        syncConfigToExtension()
    }

    fun setBackgroundAudioActive(enabled: Boolean) {
        backgroundAudioEnabled = enabled
        syncConfigToExtension()
    }

    fun toggleCustomDomainBlock(domain: String): Boolean {
        val clean = domain.trim().lowercase()
        if (clean.isEmpty()) return false
        val added = if (customBlockedDomains.contains(clean)) {
            customBlockedDomains.remove(clean)
            false
        } else {
            customBlockedDomains.add(clean)
            true
        }
        syncConfigToExtension()
        return added
    }

    fun setSecurityLevel(level: SecurityLevel) {
        currentSecurityLevel = level
        geckoRuntime?.settings?.apply {
            javaScriptEnabled = (level != SecurityLevel.PARANOID)
            webFontsEnabled = (level == SecurityLevel.STANDARD)
        }
        try {
            writeHardenedConfigFile(
                byeDpiActive = byeDpiActive,
                byeDpiPort = byeDpiPort,
                torActive = torActive,
                torPort = verifiedTorPort,
                dohProviderUrl = currentDohProviderUrl
            )
        } catch (_: Exception) {}
    }

    /**
     * Seçilen İzole Konteyner için çerez ve önbelleği ayrıştırılmış yeni bir GeckoSession oluşturur.
     */
    fun createIsolatedSession(container: ZaslonContainer): GeckoSession {
        val runtime = getOrCreateRuntime()
        val sessionSettings = GeckoSessionSettings.Builder()
            .usePrivateMode(container.isPrivateRamOnly)
            .useTrackingProtection(shieldEnabled)
            .contextId("zaslon_ctx_${container.id}")
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .SuspendMediaWhenInactive(!backgroundAudioEnabled)
            .build()

        val session = GeckoSession(sessionSettings)
        session.open(runtime)
        return session
    }

    private fun GeckoSessionSettings.Builder.SuspendMediaWhenInactive(suspend: Boolean): GeckoSessionSettings.Builder {
        try {
            val method = this.javaClass.getMethod("suspendMediaWhenInactive", Boolean::class.javaPrimitiveType)
            method.invoke(this, suspend)
        } catch (_: Throwable) {}
        return this
    }

    /**
     * Sayfadaki videoların hızını (0.25x - 4.0x) WebExtension mesajlaşması üzerinden günceller.
     */
    fun applyVideoPlaybackSpeed(_session: GeckoSession?, speed: Float) {
        val clamped = speed.coerceIn(0.25f, 4.0f)
        currentVideoSpeed = clamped
        syncConfigToExtension()
    }

    /**
     * 🔥 Tek Tıkla Panik Yakıcı: Tüm GeckoView çerezlerini, önbelleğini, IndexedDB ve oturum izlerini siler.
     */
    fun panicWipeAllEngineStorage() {
        AndroidDohResolver.clearCache()
        synchronized(torUrlHints) {
            torUrlHints.clear()
        }
        torActive = false
        torVerified = false
        activeContainerId = ZaslonContainer.PERSONAL.id
        syncConfigToExtension(clearTorTabs = true, torSessionEnded = true)
        try {
            geckoRuntime?.storageController?.clearData(StorageController.ClearFlags.ALL)
        } catch (_: Exception) {}
    }
}
