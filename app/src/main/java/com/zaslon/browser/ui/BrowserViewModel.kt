package com.zaslon.browser.ui

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaslon.browser.BuildConfig
import com.zaslon.browser.engine.HardenedGeckoEngine
import com.zaslon.browser.engine.InspectorLogEntry
import com.zaslon.browser.engine.SecurityLevel
import com.zaslon.browser.engine.ZaslonContainer
import com.zaslon.browser.i18n.ZaslonLanguage
import com.zaslon.browser.i18n.ZaslonLanguageStore
import com.zaslon.browser.media.BackgroundMediaService
import com.zaslon.browser.network.AndroidDohResolver
import com.zaslon.browser.network.ByeDpiMode
import com.zaslon.browser.network.ByeDpiVpnService
import com.zaslon.browser.storage.AndroidUpdatePhase
import com.zaslon.browser.storage.AndroidUpdateState
import com.zaslon.browser.storage.ApkInstallResult
import com.zaslon.browser.storage.DiscoveredLanPeer
import com.zaslon.browser.storage.EncryptedMobileVault
import com.zaslon.browser.storage.GitHubAndroidUpdater
import com.zaslon.browser.storage.LanSyncClient
import com.zaslon.browser.storage.MobileBookmark
import com.zaslon.browser.storage.MobileHistoryItem
import com.zaslon.browser.storage.VaultSnapshot
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import java.net.URI
import java.net.URLEncoder
import java.util.UUID

data class MobileTab(
    val id: String = UUID.randomUUID().toString(),
    val container: ZaslonContainer = ZaslonContainer.PERSONAL,
    val title: String = ZaslonLanguageStore.strings().newTab,
    val url: String = "zaslon://newtab",
    val progress: Int = 0,
    val isSecure: Boolean = true,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val session: GeckoSession? = null
)

data class BrowserUiState(
    val language: ZaslonLanguage = ZaslonLanguageStore.current,
    val tabs: List<MobileTab> = emptyList(),
    val activeTabId: String = "",
    val activeContainer: ZaslonContainer = ZaslonContainer.PERSONAL,
    val shieldEnabled: Boolean = true,
    val securityLevel: SecurityLevel = SecurityLevel.STANDARD,
    val byeDpiActive: Boolean = false,
    val byeDpiMode: ByeDpiMode = ByeDpiMode.TLS_RECORD_SPLIT,
    val byeDpiSplitCount: Long = 0,
    val torActive: Boolean = false,
    val dohProviderName: String = ZaslonLanguageStore.strings().dohQuad9Name,
    val dohProviderUrl: String = AndroidDohResolver.DEFAULT_DOH_URL,
    val backgroundAudioEnabled: Boolean = true,
    val sponsorBlockEnabled: Boolean = true,
    val videoSpeed: Float = 1.0f,
    val adsBlocked: Long = 0,
    val trackersBlocked: Long = 0,
    val urlsCleaned: Long = 0,
    val sponsorsSkipped: Long = 0,
    val savedSponsorSeconds: Long = 0,
    val inspectorLogs: List<InspectorLogEntry> = emptyList(),
    val customBlockedDomains: Set<String> = emptySet(),
    val bookmarks: List<MobileBookmark> = emptyList(),
    val history: List<MobileHistoryItem> = emptyList(),
    val notes: String = "",
    val lanPeers: List<DiscoveredLanPeer> = emptyList(),
    val localIp: String = "127.0.0.1",
    val statusBannerMessage: String? = null,
    val updateState: AndroidUpdateState = AndroidUpdateState()
) {
    val activeTab: MobileTab?
        get() = tabs.find { it.id == activeTabId } ?: tabs.firstOrNull()
}

class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val vault = EncryptedMobileVault(appContext)
    private var githubTokenInMemory: String = ""

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    val geckoEngine = HardenedGeckoEngine(
        appContext = appContext,
        onRequestLogged = { entry -> handleInspectorLog(entry) },
        onUrlCleaned = { _, count -> incrementUrlsCleaned(count) },
        onSponsorSkipped = { _, category, savedSec -> handleSponsorSkipped(category, savedSec) },
        onMediaPlayingChanged = { playing, title, url -> handleMediaStateChanged(playing, title, url) },
        onTorBlocked = { _ -> handleTorBlocked() }
    )

    val lanSyncClient = LanSyncClient(
        onPeersUpdated = { peers ->
            _uiState.value = _uiState.value.copy(
                lanPeers = peers,
                localIp = lanSyncClient.getLocalIpv4Addresses().firstOrNull() ?: "127.0.0.1"
            )
        }
    )

    init {
        val initialLang = ZaslonLanguageStore.init(appContext)
        val initialStrings = ZaslonLanguageStore.strings(initialLang)
        val snap = vault.loadSnapshot()
        githubTokenInMemory = snap.githubToken
        ByeDpiVpnService.proxyEngine.dohProviderUrl = AndroidDohResolver.DEFAULT_DOH_URL
        _uiState.value = _uiState.value.copy(
            language = initialLang,
            dohProviderName = initialStrings.dohQuad9Name,
            dohProviderUrl = AndroidDohResolver.DEFAULT_DOH_URL,
            bookmarks = snap.bookmarks,
            history = snap.history,
            notes = snap.notes,
            adsBlocked = snap.adsBlocked,
            trackersBlocked = snap.trackersBlocked,
            sponsorsSkipped = snap.sponsorsSkipped,
            urlsCleaned = snap.urlsCleaned,
            localIp = lanSyncClient.getLocalIpv4Addresses().firstOrNull() ?: "127.0.0.1",
            updateState = AndroidUpdateState(
                repo = snap.githubRepo.ifBlank { "BeybarsSamuel/zaslon-android" },
                hasToken = snap.githubToken.isNotBlank()
            )
        )

        // İlk varsayılan sekmeyi oluştur
        createNewTab(ZaslonContainer.PERSONAL, "zaslon://newtab")

        // ByeDPI tünel istatistiklerini dinle
        viewModelScope.launch {
            ByeDpiVpnService.tunnelStatus.collect { status ->
                _uiState.value = _uiState.value.copy(
                    byeDpiActive = status.running,
                    byeDpiMode = status.mode,
                    byeDpiSplitCount = status.splitPacketsCount
                )
                geckoEngine.updateProxyRouting(
                    activeContainer = _uiState.value.activeContainer,
                    byeDpiEnabled = status.running
                )
            }
        }
    }

    fun setLanDiscoveryActive(active: Boolean) {
        if (active) {
            lanSyncClient.startDiscovery()
        } else {
            lanSyncClient.stopDiscovery()
        }
    }

    fun setLanguage(lang: ZaslonLanguage) {
        ZaslonLanguageStore.setLanguage(appContext, lang)
        val s = ZaslonLanguageStore.strings(lang)
        val cur = _uiState.value
        val remappedDohName = when {
            cur.dohProviderUrl.contains("cloudflare", ignoreCase = true) -> s.dohClfName
            cur.dohProviderUrl.contains("mullvad", ignoreCase = true) -> s.dohMullvadName
            else -> s.dohQuad9Name
        }
        val updatedTabs = cur.tabs.map { tab ->
            if (tab.url == "zaslon://newtab") {
                tab.copy(title = s.newTabWithContainer(s.containerLabel(tab.container)))
            } else {
                tab
            }
        }
        _uiState.value = cur.copy(
            language = lang,
            dohProviderName = remappedDohName,
            tabs = updatedTabs,
            statusBannerMessage = s.languageChanged(lang.nativeLabel)
        )
    }

    fun createNewTab(
        container: ZaslonContainer = _uiState.value.activeContainer,
        initialUrl: String = "zaslon://newtab"
    ) {
        val tabId = UUID.randomUUID().toString()
        val session = geckoEngine.createIsolatedSession(container)
        attachSessionDelegates(tabId, session)

        val s = ZaslonLanguageStore.strings(_uiState.value.language)
        val newTab = MobileTab(
            id = tabId,
            container = container,
            title = if (initialUrl == "zaslon://newtab") s.newTabWithContainer(s.containerLabel(container)) else initialUrl,
            url = initialUrl,
            session = session
        )

        _uiState.value = _uiState.value.copy(
            tabs = _uiState.value.tabs + newTab,
            activeTabId = tabId,
            activeContainer = container
        )
        syncContainerProxyRouting(container)

        if (initialUrl != "zaslon://newtab") {
            loadInputInTab(tabId, initialUrl)
        }
    }

    fun selectTab(tabId: String) {
        val target = _uiState.value.tabs.find { it.id == tabId } ?: return
        _uiState.value = _uiState.value.copy(
            activeTabId = tabId,
            activeContainer = target.container
        )
        syncContainerProxyRouting(target.container)
    }

    fun closeTab(tabId: String) {
        val currentTabs = _uiState.value.tabs
        val closing = currentTabs.find { it.id == tabId } ?: return
        try {
            closing.session?.close()
        } catch (_: Exception) {}

        val remaining = currentTabs.filterNot { it.id == tabId }
        if (closing.container == ZaslonContainer.TOR && remaining.none { it.container == ZaslonContainer.TOR }) {
            geckoEngine.endTorSession()
        }
        if (remaining.isEmpty()) {
            _uiState.value = _uiState.value.copy(tabs = emptyList(), activeTabId = "")
            createNewTab(_uiState.value.activeContainer, "zaslon://newtab")
        } else {
            val nextTab = if (_uiState.value.activeTabId == tabId) remaining.last() else remaining.find { it.id == _uiState.value.activeTabId } ?: remaining.last()
            _uiState.value = _uiState.value.copy(
                tabs = remaining,
                activeTabId = nextTab.id,
                activeContainer = nextTab.container
            )
            syncContainerProxyRouting(nextTab.container)
        }
    }

    fun switchContainer(container: ZaslonContainer) {
        val existingInContainer = _uiState.value.tabs.lastOrNull { it.container == container }
        if (existingInContainer != null) {
            _uiState.value = _uiState.value.copy(
                activeContainer = container,
                activeTabId = existingInContainer.id
            )
            syncContainerProxyRouting(container)
        } else {
            createNewTab(container, "zaslon://newtab")
        }
    }

    private fun syncContainerProxyRouting(container: ZaslonContainer) {
        geckoEngine.updateProxyRouting(
            activeContainer = container,
            byeDpiEnabled = _uiState.value.byeDpiActive
        )
        if (container == ZaslonContainer.TOR) {
            viewModelScope.launch {
                val port = withContext(Dispatchers.IO) { geckoEngine.verifyTorSocks5() }
                _uiState.value = _uiState.value.copy(torActive = port != null)
            }
        }
    }

    private fun isOnionUri(uri: String): Boolean {
        val host = try {
            URI(uri).host?.lowercase()
        } catch (_: Exception) {
            null
        } ?: return false
        return host == "onion" || host.endsWith(".onion")
    }

    private fun handleTorBlocked() {
        val s = ZaslonLanguageStore.strings(_uiState.value.language)
        _uiState.value = _uiState.value.copy(
            torActive = false,
            statusBannerMessage = s.bannerTorRequiredFailClosed
        )
    }

    /**
     * Adres çubuğu girdisini (!z, !d, !b, !g, !y, !w Bangs veya doğrudan URL) çözer ve yükler.
     */
    fun navigateActiveTab(rawInput: String) {
        val active = _uiState.value.activeTab ?: return
        loadInputInTab(active.id, rawInput)
    }

    private fun loadInputInTab(tabId: String, rawInput: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return

        if (trimmed.equals("zaslon://newtab", ignoreCase = true)) {
            val s = ZaslonLanguageStore.strings(_uiState.value.language)
            updateTab(tabId) {
                it.copy(
                    url = "zaslon://newtab",
                    title = s.newTabWithContainer(s.containerLabel(it.container)),
                    progress = 0
                )
            }
            return
        }

        val resolvedUrl = resolveOmniboxInput(trimmed)
        val tab = _uiState.value.tabs.find { it.id == tabId } ?: return

        // Non-Tor konteynerde .onion bağlantısını kesin engelle
        if (tab.container != ZaslonContainer.TOR && isOnionUri(resolvedUrl)) {
            handleTorBlocked()
            return
        }

        // Tor konteynerinde gerçek SOCKS5 el sıkışması doğrulanmadan yüklemeyi fail-closed engelle
        if (tab.container == ZaslonContainer.TOR && (resolvedUrl.startsWith("http://") || resolvedUrl.startsWith("https://"))) {
            updateTab(tabId) { it.copy(url = resolvedUrl, progress = 5) }
            viewModelScope.launch {
                val verifiedPort = withContext(Dispatchers.IO) { geckoEngine.verifyTorSocks5() }
                geckoEngine.updateProxyRouting(
                    activeContainer = ZaslonContainer.TOR,
                    byeDpiEnabled = _uiState.value.byeDpiActive,
                    torUrlHint = resolvedUrl
                )
                if (verifiedPort == null) {
                    updateTab(tabId) { it.copy(progress = 0) }
                    handleTorBlocked()
                    return@launch
                }
                _uiState.value = _uiState.value.copy(torActive = true)
                updateTab(tabId) { it.copy(progress = 15) }
                tab.session?.loadUri(resolvedUrl)
            }
            return
        }

        geckoEngine.updateProxyRouting(
            activeContainer = tab.container,
            byeDpiEnabled = _uiState.value.byeDpiActive
        )
        updateTab(tabId) { it.copy(url = resolvedUrl, progress = 15) }
        tab.session?.loadUri(resolvedUrl)
    }

    private fun resolveOmniboxInput(input: String): String {
        // Bang kısayolları (!z, !d, !b, !g, !y, !w)
        val lower = input.lowercase()
        when {
            lower.startsWith("!d ") -> {
                val q = URLEncoder.encode(input.substring(3).trim(), "UTF-8")
                return "https://duckduckgo.com/?q=$q"
            }
            lower.startsWith("!b ") -> {
                val q = URLEncoder.encode(input.substring(3).trim(), "UTF-8")
                return "https://search.brave.com/search?q=$q"
            }
            lower.startsWith("!w ") -> {
                val q = URLEncoder.encode(input.substring(3).trim(), "UTF-8")
                val wikiHost = ZaslonLanguageStore.strings(_uiState.value.language).wikipediaHost
                return "https://$wikiHost/w/index.php?search=$q"
            }
            lower.startsWith("!y ") -> {
                val q = URLEncoder.encode(input.substring(3).trim(), "UTF-8")
                return "https://yandex.com.tr/search/?text=$q"
            }
            lower.startsWith("!g ") -> {
                val q = URLEncoder.encode(input.substring(3).trim(), "UTF-8")
                return "https://www.google.com/search?q=$q&udm=14"
            }
            lower.startsWith("!z ") -> {
                val q = URLEncoder.encode(input.substring(3).trim(), "UTF-8")
                return "https://duckduckgo.com/?q=$q"
            }
        }

        if (input.startsWith("http://") || input.startsWith("https://") || input.startsWith("about:")) {
            return input
        }

        val looksLikeDomain = !input.contains(" ") && input.contains(".") && input.length > 3
        return if (looksLikeDomain) {
            "https://$input"
        } else {
            val encoded = URLEncoder.encode(input, "UTF-8")
            "https://duckduckgo.com/?q=$encoded"
        }
    }

    fun goBack() {
        _uiState.value.activeTab?.session?.goBack()
    }

    fun goForward() {
        _uiState.value.activeTab?.session?.goForward()
    }

    fun reload() {
        val tab = _uiState.value.activeTab ?: return
        if (tab.url != "zaslon://newtab") {
            tab.session?.reload()
        }
    }

    // ------------------------------------------------------------------------
    // ByeDPI Yerel Proxy & VPN Tünel Kontrolleri
    // ------------------------------------------------------------------------
    fun toggleByeDpi(mode: ByeDpiMode = _uiState.value.byeDpiMode) {
        val nextState = !_uiState.value.byeDpiActive
        val s = ZaslonLanguageStore.strings(_uiState.value.language)
        if (nextState) {
            val started = ByeDpiVpnService.startStandaloneProxy(mode, _uiState.value.dohProviderUrl)
            geckoEngine.writeHardenedConfigFile(
                byeDpiActive = started,
                byeDpiPort = 10808,
                torActive = _uiState.value.torActive,
                dohProviderUrl = _uiState.value.dohProviderUrl
            )
            geckoEngine.updateProxyRouting(
                activeContainer = _uiState.value.activeContainer,
                byeDpiEnabled = started
            )
            _uiState.value = _uiState.value.copy(
                byeDpiActive = started,
                byeDpiMode = mode,
                statusBannerMessage = if (started) s.bannerByedpiOn(s.byeDpiModeLabel(mode)) else s.bannerByedpiFail
            )
        } else {
            ByeDpiVpnService.stopStandaloneProxy()
            try {
                appContext.startService(
                    Intent(appContext, ByeDpiVpnService::class.java).apply {
                        action = ByeDpiVpnService.ACTION_STOP
                    }
                )
            } catch (_: Exception) {}
            geckoEngine.writeHardenedConfigFile(
                byeDpiActive = false,
                torActive = _uiState.value.torActive,
                dohProviderUrl = _uiState.value.dohProviderUrl
            )
            geckoEngine.updateProxyRouting(
                activeContainer = _uiState.value.activeContainer,
                byeDpiEnabled = false
            )
            _uiState.value = _uiState.value.copy(
                byeDpiActive = false,
                statusBannerMessage = s.bannerByedpiOff
            )
        }
    }

    fun selectDohProvider(name: String, url: String) {
        val s = ZaslonLanguageStore.strings(_uiState.value.language)
        ByeDpiVpnService.proxyEngine.dohProviderUrl = url
        AndroidDohResolver.clearCache()
        geckoEngine.writeHardenedConfigFile(
            byeDpiActive = _uiState.value.byeDpiActive,
            torActive = _uiState.value.torActive,
            dohProviderUrl = url
        )
        _uiState.value = _uiState.value.copy(
            dohProviderName = name,
            dohProviderUrl = url,
            statusBannerMessage = s.bannerDohUpdated(name)
        )
    }

    // ------------------------------------------------------------------------
    // Kalkan, Güvenlik Seviyesi, Video Hız & Arka Plan Ses Kontrolleri
    // ------------------------------------------------------------------------
    fun toggleShield(enabled: Boolean) {
        geckoEngine.setShieldActive(enabled)
        _uiState.value = _uiState.value.copy(shieldEnabled = enabled)
    }

    fun changeSecurityLevel(level: SecurityLevel) {
        val s = ZaslonLanguageStore.strings(_uiState.value.language)
        geckoEngine.setSecurityLevel(level)
        _uiState.value = _uiState.value.copy(
            securityLevel = level,
            statusBannerMessage = s.bannerSecLevel(s.securityTitle(level))
        )
    }

    fun toggleCustomDomainBlock(domain: String) {
        val s = ZaslonLanguageStore.strings(_uiState.value.language)
        val blockedNow = geckoEngine.toggleCustomDomainBlock(domain)
        _uiState.value = _uiState.value.copy(
            customBlockedDomains = geckoEngine.customBlockedDomains.toSet(),
            statusBannerMessage = if (blockedNow) s.bannerDomainBlocked(domain) else s.bannerDomainUnblocked(domain)
        )
    }

    fun toggleBackgroundAudio(enabled: Boolean) {
        val s = ZaslonLanguageStore.strings(_uiState.value.language)
        geckoEngine.setBackgroundAudioActive(enabled)
        _uiState.value = _uiState.value.copy(
            backgroundAudioEnabled = enabled,
            statusBannerMessage = if (enabled) s.bannerBgAudioOn else s.bannerBgAudioOff
        )
        if (!enabled) {
            try {
                appContext.startService(
                    Intent(appContext, BackgroundMediaService::class.java).apply {
                        action = BackgroundMediaService.ACTION_STOP
                    }
                )
            } catch (_: Exception) {}
        }
    }

    fun toggleSponsorBlock(enabled: Boolean) {
        geckoEngine.setSponsorBlockActive(enabled)
        _uiState.value = _uiState.value.copy(sponsorBlockEnabled = enabled)
    }

    fun setVideoSpeed(speed: Float) {
        val s = ZaslonLanguageStore.strings(_uiState.value.language)
        val clamped = speed.coerceIn(0.25f, 4.0f)
        geckoEngine.applyVideoPlaybackSpeed(_uiState.value.activeTab?.session, clamped)
        _uiState.value = _uiState.value.copy(
            videoSpeed = clamped,
            statusBannerMessage = s.bannerVideoSpeed(clamped)
        )
    }

    // ------------------------------------------------------------------------
    // Yer İmleri & Şifreli Kasa
    // ------------------------------------------------------------------------
    fun toggleBookmarkCurrentTab() {
        val tab = _uiState.value.activeTab ?: return
        if (tab.url == "zaslon://newtab" || tab.url.isBlank()) return

        val s = ZaslonLanguageStore.strings(_uiState.value.language)
        val existing = _uiState.value.bookmarks
        val updated = if (existing.any { it.url == tab.url }) {
            existing.filterNot { it.url == tab.url }
        } else {
            listOf(MobileBookmark(tab.title.ifBlank { tab.url }, tab.url)) + existing
        }

        _uiState.value = _uiState.value.copy(
            bookmarks = updated,
            statusBannerMessage = if (updated.size > existing.size) s.bannerBookmarkAdded else s.bannerBookmarkRemoved
        )
        persistVaultSnapshot()
    }

    fun updateNotes(newNotes: String) {
        _uiState.value = _uiState.value.copy(notes = newNotes)
        persistVaultSnapshot()
    }

    // ------------------------------------------------------------------------
    // Bulutsuz LAN Wi-Fi Eşitleme (PC <-> Android)
    // ------------------------------------------------------------------------
    fun pushActiveTabToDesktop(host: String, port: Int, pin: String) {
        val tab = _uiState.value.activeTab ?: return
        val s = ZaslonLanguageStore.strings(_uiState.value.language)
        viewModelScope.launch {
            val payload = JSONObject().apply {
                put("type", "tab")
                put("title", tab.title)
                put("url", tab.url)
            }
            val res = lanSyncClient.pushToPeer(host, port, pin, payload)
            _uiState.value = _uiState.value.copy(
                statusBannerMessage = res.fold(
                    onSuccess = { s.bannerTabSent },
                    onFailure = { s.bannerLanErr(it.message ?: "") }
                )
            )
        }
    }

    fun pushAllBookmarksToDesktop(host: String, port: Int, pin: String) {
        val s = ZaslonLanguageStore.strings(_uiState.value.language)
        viewModelScope.launch {
            val bmArr = JSONArray()
            _uiState.value.bookmarks.forEach { b ->
                bmArr.put(
                    JSONObject().apply {
                        put("title", b.title)
                        put("url", b.url)
                    }
                )
            }
            val payload = JSONObject().apply {
                put("type", "bookmarks")
                put("bms", bmArr)
            }
            val res = lanSyncClient.pushToPeer(host, port, pin, payload)
            _uiState.value = _uiState.value.copy(
                statusBannerMessage = res.fold(
                    onSuccess = { s.bannerBookmarksSent(_uiState.value.bookmarks.size) },
                    onFailure = { s.bannerLanErr(it.message ?: "") }
                )
            )
        }
    }

    fun pullAllFromDesktop(host: String, port: Int, pin: String) {
        val s = ZaslonLanguageStore.strings(_uiState.value.language)
        viewModelScope.launch {
            val res = lanSyncClient.pullFromPeer(host, port, pin)
            res.onSuccess { bundle ->
                val mergedBms = (bundle.bookmarks + _uiState.value.bookmarks)
                    .distinctBy { it.url }
                val mergedNotes = if (bundle.notes.isNotBlank()) bundle.notes else _uiState.value.notes

                _uiState.value = _uiState.value.copy(
                    bookmarks = mergedBms,
                    notes = mergedNotes,
                    statusBannerMessage = s.bannerLanPulled(bundle.deviceName, bundle.tabs.size, bundle.bookmarks.size)
                )
                persistVaultSnapshot()

                // Masaüstünden gelen açık sekmelerden ilkini yeni sekmede aç
                bundle.tabs.firstOrNull()?.let { (_, url) ->
                    if (url.startsWith("http")) {
                        createNewTab(_uiState.value.activeContainer, url)
                    }
                }
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    statusBannerMessage = s.bannerLanPullErr(err.message ?: "")
                )
            }
        }
    }

    // ------------------------------------------------------------------------
    // 🔥 Tek Tıkla Panik / Her Şeyi Yak (Forensic Burn)
    // ------------------------------------------------------------------------
    fun executePanicBurn() {
        lanSyncClient.stopDiscovery()
        ByeDpiVpnService.stopStandaloneProxy()
        _uiState.value.tabs.forEach { tab ->
            try {
                tab.session?.close()
            } catch (_: Exception) {}
        }
        geckoEngine.endTorSession()
        geckoEngine.panicWipeAllEngineStorage()
        vault.panicWipeVault()

        val activeLang = _uiState.value.language
        val s = ZaslonLanguageStore.strings(activeLang)
        githubTokenInMemory = ""
        ByeDpiVpnService.proxyEngine.dohProviderUrl = AndroidDohResolver.DEFAULT_DOH_URL
        _uiState.value = BrowserUiState(
            language = activeLang,
            dohProviderName = s.dohQuad9Name,
            dohProviderUrl = AndroidDohResolver.DEFAULT_DOH_URL,
            localIp = lanSyncClient.getLocalIpv4Addresses().firstOrNull() ?: "127.0.0.1",
            statusBannerMessage = s.bannerPanicBurned
        )
        createNewTab(ZaslonContainer.PERSONAL, "zaslon://newtab")
    }

    fun clearStatusBanner() {
        _uiState.value = _uiState.value.copy(statusBannerMessage = null)
    }

    // ------------------------------------------------------------------------
    // 🔄 GitHub Releases Manuel APK Güncelleyici (Otomatik/Zorunlu Güncelleme Yok)
    // ------------------------------------------------------------------------
    fun saveGitHubUpdateConfig(repoRaw: String, tokenRaw: String, clearToken: Boolean = false) {
        val normRepo = GitHubAndroidUpdater.normalizeRepo(repoRaw)
        val nextToken = when {
            clearToken -> ""
            tokenRaw.isNotBlank() -> tokenRaw.trim()
            else -> githubTokenInMemory
        }
        githubTokenInMemory = nextToken
        val s = ZaslonLanguageStore.strings(_uiState.value.language)
        _uiState.value = _uiState.value.copy(
            updateState = _uiState.value.updateState.copy(
                repo = normRepo,
                hasToken = nextToken.isNotBlank(),
                errorMessage = ""
            ),
            statusBannerMessage = s.updateConfigSavedBanner
        )
        persistVaultSnapshot()
    }

    fun checkForAppUpdates(repoRaw: String, tokenRaw: String) {
        val normRepo = GitHubAndroidUpdater.normalizeRepo(repoRaw)
        val effectiveToken = if (tokenRaw.isNotBlank()) tokenRaw.trim() else githubTokenInMemory
        if (normRepo.isNotEmpty()) {
            githubTokenInMemory = effectiveToken
        }

        _uiState.value = _uiState.value.copy(
            updateState = _uiState.value.updateState.copy(
                repo = normRepo.ifEmpty { repoRaw.trim() },
                hasToken = effectiveToken.isNotBlank(),
                phase = AndroidUpdatePhase.CHECKING,
                errorMessage = ""
            )
        )
        if (normRepo.isNotEmpty()) {
            persistVaultSnapshot()
        }

        viewModelScope.launch {
            val res = GitHubAndroidUpdater.checkForUpdates(
                repoRaw = normRepo,
                tokenRaw = effectiveToken,
                currentVersionName = BuildConfig.VERSION_NAME
            )
            res.onSuccess { (hasUpdate, rel) ->
                _uiState.value = _uiState.value.copy(
                    updateState = _uiState.value.updateState.copy(
                        phase = if (hasUpdate && rel != null) AndroidUpdatePhase.AVAILABLE else AndroidUpdatePhase.UP_TO_DATE,
                        latestRelease = rel,
                        errorMessage = ""
                    )
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    updateState = _uiState.value.updateState.copy(
                        phase = AndroidUpdatePhase.ERROR,
                        errorMessage = err.message ?: "Error"
                    )
                )
            }
        }
    }

    fun downloadAppUpdate() {
        val release = _uiState.value.updateState.latestRelease ?: return
        _uiState.value = _uiState.value.copy(
            updateState = _uiState.value.updateState.copy(
                phase = AndroidUpdatePhase.DOWNLOADING,
                progressPct = 0,
                errorMessage = ""
            )
        )
        viewModelScope.launch {
            val res = GitHubAndroidUpdater.downloadApk(
                context = appContext,
                release = release,
                tokenRaw = githubTokenInMemory,
                onProgress = { pct ->
                    _uiState.value = _uiState.value.copy(
                        updateState = _uiState.value.updateState.copy(progressPct = pct)
                    )
                }
            )
            res.onSuccess { file ->
                _uiState.value = _uiState.value.copy(
                    updateState = _uiState.value.updateState.copy(
                        phase = AndroidUpdatePhase.DOWNLOADED,
                        progressPct = 100,
                        downloadedApkPath = file.absolutePath,
                        errorMessage = ""
                    )
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    updateState = _uiState.value.updateState.copy(
                        phase = AndroidUpdatePhase.ERROR,
                        errorMessage = err.message ?: "Download error"
                    )
                )
            }
        }
    }

    fun installDownloadedApk() {
        val path = _uiState.value.updateState.downloadedApkPath
        if (path.isBlank()) return
        val s = ZaslonLanguageStore.strings(_uiState.value.language)
        when (val res = GitHubAndroidUpdater.installApk(appContext, File(path))) {
            is ApkInstallResult.NeedsInstallPermission -> {
                _uiState.value = _uiState.value.copy(
                    statusBannerMessage = s.updateNeedInstallPerm
                )
            }
            is ApkInstallResult.Error -> {
                _uiState.value = _uiState.value.copy(
                    updateState = _uiState.value.updateState.copy(
                        phase = AndroidUpdatePhase.ERROR,
                        errorMessage = res.message
                    )
                )
            }
            is ApkInstallResult.LaunchedInstaller -> {}
        }
    }

    private fun attachSessionDelegates(tabId: String, session: GeckoSession) {
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny> {
                val uri = request.uri
                val tab = _uiState.value.tabs.find { it.id == tabId }
                val isHttp = uri.startsWith("http://") || uri.startsWith("https://")
                if (tab?.container == ZaslonContainer.TOR && isHttp) {
                    geckoEngine.addTorUrlHint(uri)
                    val result = GeckoResult<AllowOrDeny>()
                    viewModelScope.launch {
                        val verifiedPort = withContext(Dispatchers.IO) { geckoEngine.verifyTorSocks5() }
                        geckoEngine.updateProxyRouting(
                            activeContainer = _uiState.value.activeContainer,
                            byeDpiEnabled = _uiState.value.byeDpiActive,
                            torUrlHint = uri
                        )
                        if (verifiedPort == null) {
                            handleTorBlocked()
                            result.complete(AllowOrDeny.DENY)
                        } else {
                            _uiState.value = _uiState.value.copy(torActive = true)
                            result.complete(AllowOrDeny.ALLOW)
                        }
                    }
                    return result
                }
                if (tab?.container != ZaslonContainer.TOR && isOnionUri(uri)) {
                    handleTorBlocked()
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }

            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                val safeUrl = url ?: return
                if (safeUrl == "about:blank") return
                updateTab(tabId) {
                    it.copy(
                        url = safeUrl,
                        isSecure = safeUrl.startsWith("https://") || safeUrl.startsWith("zaslon://")
                    )
                }
                val tab = _uiState.value.tabs.find { it.id == tabId }
                if (tab != null && !tab.container.isPrivateRamOnly && safeUrl.startsWith("http")) {
                    recordHistory(tab.title, safeUrl)
                }
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                updateTab(tabId) { it.copy(canGoBack = canGoBack) }
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                updateTab(tabId) { it.copy(canGoForward = canGoForward) }
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onProgressChange(session: GeckoSession, progress: Int) {
                updateTab(tabId) { it.copy(progress = if (progress >= 100) 0 else progress) }
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                if (!title.isNullOrBlank()) {
                    updateTab(tabId) { it.copy(title = title) }
                }
            }
        }
    }

    private fun updateTab(tabId: String, transform: (MobileTab) -> MobileTab) {
        _uiState.value = _uiState.value.copy(
            tabs = _uiState.value.tabs.map { if (it.id == tabId) transform(it) else it }
        )
    }

    private fun recordHistory(title: String, url: String) {
        val item = MobileHistoryItem(title = title.ifBlank { url }, url = url)
        val updated = (listOf(item) + _uiState.value.history.filterNot { it.url == url }).take(250)
        _uiState.value = _uiState.value.copy(history = updated)
        persistVaultSnapshot()
    }

    private fun handleInspectorLog(entry: InspectorLogEntry) {
        val s = _uiState.value
        val addAd = if (entry.blocked && entry.category == "ad") 1L else 0L
        val addTrk = if (entry.blocked && entry.category != "ad") 1L else 0L
        val logs = (listOf(entry) + s.inspectorLogs).take(120)

        _uiState.value = s.copy(
            adsBlocked = s.adsBlocked + addAd,
            trackersBlocked = s.trackersBlocked + addTrk,
            inspectorLogs = logs
        )
    }

    private fun incrementUrlsCleaned(count: Int) {
        val s = _uiState.value
        _uiState.value = s.copy(urlsCleaned = s.urlsCleaned + count)
    }

    private fun handleSponsorSkipped(category: String, savedSeconds: Int) {
        val st = _uiState.value
        val strings = ZaslonLanguageStore.strings(st.language)
        _uiState.value = st.copy(
            sponsorsSkipped = st.sponsorsSkipped + 1,
            savedSponsorSeconds = st.savedSponsorSeconds + savedSeconds,
            statusBannerMessage = strings.bannerSponsorSkipped(strings.sponsorCategory(category), savedSeconds)
        )
    }

    private fun handleMediaStateChanged(playing: Boolean, title: String, url: String) {
        if (playing && _uiState.value.backgroundAudioEnabled) {
            try {
                val intent = Intent(appContext, BackgroundMediaService::class.java).apply {
                    action = BackgroundMediaService.ACTION_START_OR_UPDATE
                    putExtra(BackgroundMediaService.EXTRA_TITLE, title)
                    putExtra(BackgroundMediaService.EXTRA_URL, url)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            } catch (_: Exception) {}
        }
    }

    private fun persistVaultSnapshot() {
        val s = _uiState.value
        viewModelScope.launch {
            vault.saveSnapshot(
                VaultSnapshot(
                    bookmarks = s.bookmarks,
                    history = s.history,
                    notes = s.notes,
                    adsBlocked = s.adsBlocked,
                    trackersBlocked = s.trackersBlocked,
                    sponsorsSkipped = s.sponsorsSkipped,
                    urlsCleaned = s.urlsCleaned,
                    githubRepo = s.updateState.repo,
                    githubToken = githubTokenInMemory
                )
            )
        }
    }

    override fun onCleared() {
        lanSyncClient.stopDiscovery()
        super.onCleared()
    }
}
