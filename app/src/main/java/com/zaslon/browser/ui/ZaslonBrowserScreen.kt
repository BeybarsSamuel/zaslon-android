package com.zaslon.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.zaslon.browser.BuildConfig
import com.zaslon.browser.engine.SecurityLevel
import com.zaslon.browser.engine.ZaslonContainer
import com.zaslon.browser.i18n.ZaslonLanguage
import com.zaslon.browser.i18n.ZaslonLanguageStore
import com.zaslon.browser.i18n.ZaslonStrings
import com.zaslon.browser.network.ByeDpiMode
import com.zaslon.browser.storage.AndroidUpdatePhase
import com.zaslon.browser.ui.theme.ZaslonAccentBlue
import com.zaslon.browser.ui.theme.ZaslonAmber
import com.zaslon.browser.ui.theme.ZaslonBgCard
import com.zaslon.browser.ui.theme.ZaslonBgDeep
import com.zaslon.browser.ui.theme.ZaslonBgInput
import com.zaslon.browser.ui.theme.ZaslonBorder
import com.zaslon.browser.ui.theme.ZaslonDangerRed
import com.zaslon.browser.ui.theme.ZaslonEmerald
import com.zaslon.browser.ui.theme.ZaslonTextMuted
import com.zaslon.browser.ui.theme.ZaslonTextPrimary
import kotlinx.coroutines.delay
import org.mozilla.geckoview.GeckoView

private enum class ActiveSheet {
    NONE,
    SHIELD_INSPECTOR,
    BYEDPI_DOH,
    LAN_SYNC,
    TABS_MANAGER,
    APP_UPDATE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZaslonBrowserScreen(
    viewModel: BrowserViewModel,
    onRequestVpnPermissionAndToggle: (ByeDpiMode) -> Unit,
    onEnterPipMode: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val strings = remember(state.language) { ZaslonLanguageStore.strings(state.language) }
    val activeTab = state.activeTab
    val focusManager = LocalFocusManager.current

    var urlInput by remember(activeTab?.id, activeTab?.url) {
        mutableStateOf(if (activeTab?.url == "zaslon://newtab") "" else (activeTab?.url ?: ""))
    }
    var showSpeedBar by remember { mutableStateOf(false) }
    var activeSheet by remember { mutableStateOf(ActiveSheet.NONE) }

    // LAN Wi-Fi keşif yayınını yalnızca LAN Eşitleme paneli açıkken çalıştır
    LaunchedEffect(activeSheet) {
        viewModel.setLanDiscoveryActive(activeSheet == ActiveSheet.LAN_SYNC)
    }

    // Durum bildirimi bandını 3.5 saniye sonra otomatik gizle
    LaunchedEffect(state.statusBannerMessage) {
        if (state.statusBannerMessage != null) {
            delay(3500)
            viewModel.clearStatusBanner()
        }
    }

    Scaffold(
        containerColor = ZaslonBgDeep,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ZaslonBgDeep)
            ) {
                // 1. Üst İzole Konteyner Seçici Şerit
                ContainerSelectorStrip(
                    activeContainer = state.activeContainer,
                    tabs = state.tabs,
                    strings = strings,
                    onSelectContainer = { viewModel.switchContainer(it) }
                )

                // 2. Adres Çubuğu (Omnibox) + Kalkan Sayacı + 🔥 Panik Butonu
                OmniboxToolbar(
                    urlText = urlInput,
                    onUrlTextChange = { urlInput = it },
                    activeContainer = state.activeContainer,
                    totalBlocked = state.adsBlocked + state.trackersBlocked,
                    isBookmarked = state.bookmarks.any { it.url == activeTab?.url },
                    strings = strings,
                    onNavigate = {
                        focusManager.clearFocus()
                        viewModel.navigateActiveTab(urlInput)
                    },
                    onToggleBookmark = { viewModel.toggleBookmarkCurrentTab() },
                    onOpenShield = { activeSheet = ActiveSheet.SHIELD_INSPECTOR },
                    onPanicBurn = { viewModel.executePanicBurn() }
                )

                // Sayfa Yükleme İlerleme Çubuğu
                if ((activeTab?.progress ?: 0) in 1..99) {
                    LinearProgressIndicator(
                        progress = { (activeTab?.progress ?: 0) / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = Color(state.activeContainer.colorHex),
                        trackColor = ZaslonBgCard
                    )
                }

                // Durum Bildirim Bandı
                state.statusBannerMessage?.let { msg ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF132747))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = msg,
                            color = ZaslonTextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Yüzer 0.25x - 4.0x Video Hız & Zorunlu PiP Çubuğu
                if (showSpeedBar) {
                    VideoSpeedFloatingBar(
                        currentSpeed = state.videoSpeed,
                        strings = strings,
                        onSelectSpeed = { viewModel.setVideoSpeed(it) },
                        onTriggerPip = onEnterPipMode
                    )
                }

                // Alt Araç Çubuğu
                BottomActionDock(
                    canGoBack = activeTab?.canGoBack == true,
                    canGoForward = activeTab?.canGoForward == true,
                    byeDpiActive = state.byeDpiActive,
                    bgAudioActive = state.backgroundAudioEnabled,
                    speedBarVisible = showSpeedBar,
                    tabCount = state.tabs.size,
                    strings = strings,
                    onBack = { viewModel.goBack() },
                    onForward = { viewModel.goForward() },
                    onOpenByeDpi = { activeSheet = ActiveSheet.BYEDPI_DOH },
                    onToggleBgAudio = { viewModel.toggleBackgroundAudio(!state.backgroundAudioEnabled) },
                    onToggleSpeedBar = { showSpeedBar = !showSpeedBar },
                    onOpenLanSync = { activeSheet = ActiveSheet.LAN_SYNC },
                    onOpenTabs = { activeSheet = ActiveSheet.TABS_MANAGER }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(ZaslonBgDeep)
        ) {
            if (activeTab == null || activeTab.url == "zaslon://newtab") {
                ZaslonMobileNewTabDashboard(
                    state = state,
                    strings = strings,
                    onSelectLanguage = { viewModel.setLanguage(it) },
                    onNavigate = { targetUrl ->
                        urlInput = targetUrl
                        viewModel.navigateActiveTab(targetUrl)
                    },
                    onOpenByeDpi = { activeSheet = ActiveSheet.BYEDPI_DOH },
                    onOpenLanSync = { activeSheet = ActiveSheet.LAN_SYNC },
                    onOpenShield = { activeSheet = ActiveSheet.SHIELD_INSPECTOR },
                    onOpenAppUpdate = { activeSheet = ActiveSheet.APP_UPDATE }
                )
            } else {
                // Gerçek Mozilla GeckoView Motor Görünümü
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        GeckoView(context).apply {
                            activeTab.session?.let { setSession(it) }
                        }
                    },
                    update = { geckoView ->
                        val targetSession = activeTab.session
                        if (targetSession != null && geckoView.session !== targetSession) {
                            geckoView.releaseSession()
                            geckoView.setSession(targetSession)
                        }
                    }
                )
            }
        }
    }

    // ------------------------------------------------------------------------
    // Alt Çekmece Panelleri (Bottom Sheets)
    // ------------------------------------------------------------------------
    when (activeSheet) {
        ActiveSheet.SHIELD_INSPECTOR -> {
            ModalBottomSheet(
                onDismissRequest = { activeSheet = ActiveSheet.NONE },
                containerColor = ZaslonBgCard
            ) {
                ShieldAndInspectorSheet(
                    state = state,
                    strings = strings,
                    onSelectLanguage = { viewModel.setLanguage(it) },
                    onToggleShield = { viewModel.toggleShield(it) },
                    onToggleSponsorBlock = { viewModel.toggleSponsorBlock(it) },
                    onSelectSecurityLevel = { viewModel.changeSecurityLevel(it) },
                    onToggleDomainBlock = { viewModel.toggleCustomDomainBlock(it) },
                    onOpenAppUpdate = { activeSheet = ActiveSheet.APP_UPDATE }
                )
            }
        }

        ActiveSheet.BYEDPI_DOH -> {
            ModalBottomSheet(
                onDismissRequest = { activeSheet = ActiveSheet.NONE },
                containerColor = ZaslonBgCard
            ) {
                ByeDpiAndDohSheet(
                    state = state,
                    strings = strings,
                    onToggleByeDpi = { mode -> onRequestVpnPermissionAndToggle(mode) },
                    onSelectDoh = { name, url -> viewModel.selectDohProvider(name, url) }
                )
            }
        }

        ActiveSheet.LAN_SYNC -> {
            ModalBottomSheet(
                onDismissRequest = { activeSheet = ActiveSheet.NONE },
                containerColor = ZaslonBgCard
            ) {
                LanSyncBottomSheet(
                    state = state,
                    strings = strings,
                    onPushTab = { host, port, pin -> viewModel.pushActiveTabToDesktop(host, port, pin) },
                    onPushBookmarks = { host, port, pin -> viewModel.pushAllBookmarksToDesktop(host, port, pin) },
                    onPullFromDesktop = { host, port, pin -> viewModel.pullAllFromDesktop(host, port, pin) }
                )
            }
        }

        ActiveSheet.TABS_MANAGER -> {
            ModalBottomSheet(
                onDismissRequest = { activeSheet = ActiveSheet.NONE },
                containerColor = ZaslonBgCard
            ) {
                TabsAndVaultSheet(
                    state = state,
                    strings = strings,
                    onSelectTab = {
                        viewModel.selectTab(it)
                        activeSheet = ActiveSheet.NONE
                    },
                    onCloseTab = { viewModel.closeTab(it) },
                    onNewTabInContainer = {
                        viewModel.createNewTab(it, "zaslon://newtab")
                        activeSheet = ActiveSheet.NONE
                    },
                    onNotesChanged = { viewModel.updateNotes(it) }
                )
            }
        }

        ActiveSheet.APP_UPDATE -> {
            ModalBottomSheet(
                onDismissRequest = { activeSheet = ActiveSheet.NONE },
                containerColor = ZaslonBgCard
            ) {
                AppUpdateBottomSheet(
                    state = state,
                    strings = strings,
                    onSaveConfig = { repo, token, clearTok ->
                        viewModel.saveGitHubUpdateConfig(repo, token, clearTok)
                    },
                    onCheckUpdates = { repo, token ->
                        viewModel.checkForAppUpdates(repo, token)
                    },
                    onDownloadUpdate = { viewModel.downloadAppUpdate() },
                    onInstallApk = { viewModel.installDownloadedApk() }
                )
            }
        }

        ActiveSheet.NONE -> {}
    }
}

@Composable
private fun ContainerSelectorStrip(
    activeContainer: ZaslonContainer,
    tabs: List<MobileTab>,
    strings: ZaslonStrings,
    onSelectContainer: (ZaslonContainer) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ZaslonContainer.entries.forEach { container ->
            val selected = container == activeContainer
            val count = tabs.count { it.container == container }
            val accent = Color(container.colorHex)

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) accent.copy(alpha = 0.20f) else ZaslonBgCard)
                    .border(
                        width = 1.dp,
                        color = if (selected) accent else ZaslonBorder,
                        shape = RoundedCornerShape(10.dp)
                    )
                    .clickable { onSelectContainer(container) }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = container.badgeEmoji, fontSize = 12.sp)
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = strings.containerLabel(container),
                        color = if (selected) accent else ZaslonTextMuted,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                    )
                    if (count > 0) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "($count)",
                            color = ZaslonTextMuted,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OmniboxToolbar(
    urlText: String,
    onUrlTextChange: (String) -> Unit,
    activeContainer: ZaslonContainer,
    totalBlocked: Long,
    isBookmarked: Boolean,
    strings: ZaslonStrings,
    onNavigate: () -> Unit,
    onToggleBookmark: () -> Unit,
    onOpenShield: () -> Unit,
    onPanicBurn: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Adres ve Meta-Arama Girişi
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(ZaslonBgInput)
                .border(1.dp, Color(activeContainer.colorHex).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = activeContainer.badgeEmoji, fontSize = 14.sp)
            Spacer(modifier = Modifier.width(6.dp))
            OutlinedTextField(
                value = urlText,
                onValueChange = onUrlTextChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        text = strings.searchPlaceholder,
                        color = ZaslonTextMuted,
                        fontSize = 13.sp,
                        maxLines = 1
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onNavigate() })
            )
            Text(
                text = if (isBookmarked) "★" else "☆",
                color = if (isBookmarked) ZaslonAmber else ZaslonTextMuted,
                fontSize = 18.sp,
                modifier = Modifier
                    .clickable { onToggleBookmark() }
                    .padding(horizontal = 4.dp)
            )
        }

        // 🛡️ Canlı Kalkan & İstek Denetçisi Butonu
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(ZaslonBgCard)
                .border(1.dp, ZaslonEmerald.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                .clickable { onOpenShield() }
                .padding(horizontal = 9.dp, vertical = 8.dp)
        ) {
            Text(
                text = "🛡️ $totalBlocked",
                color = ZaslonEmerald,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // 🔥 Tek Tıkla Panik Yakıcı Butonu
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(ZaslonDangerRed.copy(alpha = 0.18f))
                .border(1.dp, ZaslonDangerRed, RoundedCornerShape(10.dp))
                .clickable { onPanicBurn() }
                .padding(horizontal = 9.dp, vertical = 8.dp)
        ) {
            Text(
                text = "🔥",
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun VideoSpeedFloatingBar(
    currentSpeed: Float,
    strings: ZaslonStrings,
    onSelectSpeed: (Float) -> Unit,
    onTriggerPip: () -> Unit
) {
    val speeds = listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f, 4.0f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZaslonBgCard)
            .border(1.dp, ZaslonBorder)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = strings.speedLabel,
            color = ZaslonTextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        speeds.forEach { spd ->
            val active = spd == currentSpeed
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) ZaslonAccentBlue else ZaslonBgInput)
                    .clickable { onSelectSpeed(spd) }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "${spd}x",
                    color = if (active) Color(0xFF040914) else ZaslonTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(modifier = Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(ZaslonEmerald.copy(alpha = 0.2f))
                .border(1.dp, ZaslonEmerald, RoundedCornerShape(8.dp))
                .clickable { onTriggerPip() }
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = strings.forcedPip,
                color = ZaslonEmerald,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun BottomActionDock(
    canGoBack: Boolean,
    canGoForward: Boolean,
    byeDpiActive: Boolean,
    bgAudioActive: Boolean,
    speedBarVisible: Boolean,
    tabCount: Int,
    strings: ZaslonStrings,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onOpenByeDpi: () -> Unit,
    onToggleBgAudio: () -> Unit,
    onToggleSpeedBar: () -> Unit,
    onOpenLanSync: () -> Unit,
    onOpenTabs: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZaslonBgCard)
            .border(1.dp, ZaslonBorder)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        DockButton(label = "←", active = canGoBack, onClick = onBack)
        DockButton(label = "→", active = canGoForward, onClick = onForward)
        DockButton(
            label = if (byeDpiActive) strings.dockDpiOn else strings.dockDpiOff,
            active = byeDpiActive,
            highlightColor = ZaslonEmerald,
            onClick = onOpenByeDpi
        )
        DockButton(
            label = if (bgAudioActive) strings.dockBgOn else strings.dockBgOff,
            active = bgAudioActive,
            highlightColor = ZaslonAccentBlue,
            onClick = onToggleBgAudio
        )
        DockButton(
            label = strings.dockSpeedPip,
            active = speedBarVisible,
            highlightColor = ZaslonAmber,
            onClick = onToggleSpeedBar
        )
        DockButton(
            label = strings.dockLan,
            active = true,
            onClick = onOpenLanSync
        )
        DockButton(
            label = "🗂️ [$tabCount]",
            active = true,
            onClick = onOpenTabs
        )
    }
}

@Composable
private fun DockButton(
    label: String,
    active: Boolean,
    highlightColor: Color = ZaslonTextPrimary,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (active && highlightColor != ZaslonTextPrimary) highlightColor.copy(alpha = 0.16f) else ZaslonBgInput)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = if (active) highlightColor else ZaslonTextMuted.copy(alpha = 0.5f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun LanguageSelectorRow(
    currentLanguage: ZaslonLanguage,
    strings: ZaslonStrings,
    onSelectLanguage: (ZaslonLanguage) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = strings.languageSectionTitle,
            color = ZaslonAccentBlue,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ZaslonLanguage.entries.forEach { lang ->
                val selected = lang == currentLanguage
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) ZaslonAccentBlue.copy(alpha = 0.22f) else ZaslonBgInput)
                        .border(
                            width = 1.dp,
                            color = if (selected) ZaslonAccentBlue else ZaslonBorder,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { onSelectLanguage(lang) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "${lang.shortCode} · ${lang.nativeLabel}",
                        color = if (selected) ZaslonAccentBlue else ZaslonTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun ZaslonMobileNewTabDashboard(
    state: BrowserUiState,
    strings: ZaslonStrings,
    onSelectLanguage: (ZaslonLanguage) -> Unit,
    onNavigate: (String) -> Unit,
    onOpenByeDpi: () -> Unit,
    onOpenLanSync: () -> Unit,
    onOpenShield: () -> Unit,
    onOpenAppUpdate: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Başlık & Zırh Durumu
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = strings.androidHeader,
                    color = ZaslonAccentBlue,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = strings.androidSubtitle,
                    color = ZaslonTextMuted,
                    fontSize = 12.sp
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(state.activeContainer.colorHex).copy(alpha = 0.18f))
                    .border(1.dp, Color(state.activeContainer.colorHex), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "${state.activeContainer.badgeEmoji} ${strings.containerLabel(state.activeContainer)}",
                    color = Color(state.activeContainer.colorHex),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Canlı Koruma Sayaç Kartları
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                value = state.adsBlocked.toString(),
                label = strings.statAdsBlocked,
                color = ZaslonEmerald
            )
            StatCard(
                modifier = Modifier.weight(1f),
                value = state.trackersBlocked.toString(),
                label = strings.statTrackersBlocked,
                color = ZaslonAccentBlue
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                value = strings.sponsorStatVal(state.sponsorsSkipped, state.savedSponsorSeconds),
                label = strings.statSponsorSkipped,
                color = ZaslonAmber
            )
            StatCard(
                modifier = Modifier.weight(1f),
                value = state.urlsCleaned.toString(),
                label = strings.statUrlsCleaned,
                color = Color(0xFFC084FC)
            )
        }

        // Hızlı Zırh & Erişim Kontrolleri
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(ZaslonBgCard)
                .border(1.dp, ZaslonBorder, RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = strings.quickModulesTitle,
                color = ZaslonAccentBlue,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpenByeDpi,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.byeDpiActive) ZaslonEmerald else ZaslonBgInput
                    )
                ) {
                    Text(
                        text = if (state.byeDpiActive) strings.quickDpiOn else strings.quickDpiOff,
                        color = if (state.byeDpiActive) Color(0xFF040914) else ZaslonTextPrimary,
                        fontSize = 12.sp
                    )
                }
                Button(
                    onClick = onOpenLanSync,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = ZaslonBgInput)
                ) {
                    Text(
                        text = strings.quickLanSync(state.lanPeers.size),
                        color = ZaslonTextPrimary,
                        fontSize = 12.sp
                    )
                }
            }
            Button(
                onClick = onOpenShield,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = ZaslonBgInput)
            ) {
                Text(
                    text = strings.quickShieldBtn(strings.securityTitle(state.securityLevel)),
                    color = ZaslonEmerald,
                    fontSize = 12.sp
                )
            }
            Button(
                onClick = onOpenAppUpdate,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = ZaslonBgInput)
            ) {
                Text(
                    text = strings.quickUpdateBtn,
                    color = ZaslonAccentBlue,
                    fontSize = 12.sp
                )
            }
            HorizontalDivider(color = ZaslonBorder)
            LanguageSelectorRow(
                currentLanguage = state.language,
                strings = strings,
                onSelectLanguage = onSelectLanguage
            )
        }

        // Şifreli Kasa Yer İmleri
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(ZaslonBgCard)
                .border(1.dp, ZaslonBorder, RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = strings.encryptedBookmarksTitle,
                color = ZaslonAccentBlue,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            state.bookmarks.forEach { bm ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(ZaslonBgInput)
                        .clickable { onNavigate(bm.url) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = bm.title,
                            color = ZaslonTextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = bm.url,
                            color = ZaslonTextMuted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(text = "→", color = ZaslonAccentBlue, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    value: String,
    label: String,
    color: Color
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(ZaslonBgCard)
            .border(1.dp, ZaslonBorder, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(
            text = value,
            color = color,
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            text = label,
            color = ZaslonTextMuted,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun ShieldAndInspectorSheet(
    state: BrowserUiState,
    strings: ZaslonStrings,
    onSelectLanguage: (ZaslonLanguage) -> Unit,
    onToggleShield: (Boolean) -> Unit,
    onToggleSponsorBlock: (Boolean) -> Unit,
    onSelectSecurityLevel: (SecurityLevel) -> Unit,
    onToggleDomainBlock: (String) -> Unit,
    onOpenAppUpdate: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = strings.shieldSheetTitle,
            color = ZaslonEmerald,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(strings.uBlockToggle, color = ZaslonTextPrimary, fontSize = 14.sp)
            Switch(checked = state.shieldEnabled, onCheckedChange = onToggleShield)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(strings.sponsorBlockToggle, color = ZaslonTextPrimary, fontSize = 14.sp)
            Switch(checked = state.sponsorBlockEnabled, onCheckedChange = onToggleSponsorBlock)
        }

        LanguageSelectorRow(
            currentLanguage = state.language,
            strings = strings,
            onSelectLanguage = onSelectLanguage
        )

        Button(
            onClick = onOpenAppUpdate,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = ZaslonBgInput)
        ) {
            Text(
                text = strings.quickUpdateBtn,
                color = ZaslonAccentBlue,
                fontSize = 12.sp
            )
        }

        HorizontalDivider(color = ZaslonBorder)

        Text(
            text = strings.securitySliderTitle,
            color = ZaslonAccentBlue,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        SecurityLevel.entries.forEach { lvl ->
            val selected = state.securityLevel == lvl
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) ZaslonAccentBlue.copy(alpha = 0.16f) else ZaslonBgInput)
                    .border(1.dp, if (selected) ZaslonAccentBlue else ZaslonBorder, RoundedCornerShape(10.dp))
                    .clickable { onSelectSecurityLevel(lvl) }
                    .padding(10.dp)
            ) {
                Text(
                    text = strings.securityLevelRow(lvl.level, strings.securityTitle(lvl)),
                    color = ZaslonTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(text = strings.securitySummary(lvl), color = ZaslonTextMuted, fontSize = 11.sp)
            }
        }

        HorizontalDivider(color = ZaslonBorder)

        Text(
            text = strings.inspectorTitle,
            color = ZaslonAccentBlue,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(state.inspectorLogs) { entry ->
                val isBlocked = entry.blocked || state.customBlockedDomains.contains(entry.domain)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(ZaslonBgInput)
                        .clickable { onToggleDomainBlock(entry.domain) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.domain,
                            color = if (isBlocked) ZaslonDangerRed else ZaslonTextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${entry.category.uppercase()} • ${entry.url}",
                            color = ZaslonTextMuted,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = if (isBlocked) strings.inspectorBlocked else strings.inspectorAllowed,
                        color = if (isBlocked) ZaslonDangerRed else ZaslonEmerald,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun ByeDpiAndDohSheet(
    state: BrowserUiState,
    strings: ZaslonStrings,
    onToggleByeDpi: (ByeDpiMode) -> Unit,
    onSelectDoh: (String, String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = strings.byedpiSheetTitle,
            color = ZaslonEmerald,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = strings.byedpiSheetDesc,
            color = ZaslonTextMuted,
            fontSize = 12.sp
        )

        ByeDpiMode.entries.forEach { mode ->
            val selected = state.byeDpiMode == mode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) ZaslonEmerald.copy(alpha = 0.16f) else ZaslonBgInput)
                    .border(1.dp, if (selected) ZaslonEmerald else ZaslonBorder, RoundedCornerShape(10.dp))
                    .clickable { onToggleByeDpi(mode) }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.byeDpiModeLabel(mode),
                        color = ZaslonTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = strings.splitTlsCount(state.byeDpiSplitCount),
                        color = ZaslonTextMuted,
                        fontSize = 11.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (state.byeDpiActive && selected) ZaslonEmerald else ZaslonBorder)
                )
            }
        }

        Button(
            onClick = { onToggleByeDpi(state.byeDpiMode) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.byeDpiActive) ZaslonDangerRed else ZaslonEmerald
            )
        ) {
            Text(
                text = if (state.byeDpiActive) strings.byedpiStopBtn else strings.byedpiStartBtn,
                color = Color(0xFF040914),
                fontWeight = FontWeight.Bold
            )
        }

        HorizontalDivider(color = ZaslonBorder)

        Text(
            text = strings.dohProviderTitle,
            color = ZaslonAccentBlue,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        val dohOptions = listOf(
            strings.dohClfName to "https://cloudflare-dns.com/dns-query",
            strings.dohQuad9Name to "https://dns.quad9.net/dns-query",
            strings.dohMullvadName to "https://adblock.dns.mullvad.net/dns-query"
        )
        dohOptions.forEach { (name, url) ->
            val active = state.dohProviderUrl == url
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) ZaslonAccentBlue.copy(alpha = 0.16f) else ZaslonBgInput)
                    .clickable { onSelectDoh(name, url) }
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(name, color = ZaslonTextPrimary, fontSize = 13.sp)
                if (active) {
                    Text(strings.activeCheck, color = ZaslonAccentBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun LanSyncBottomSheet(
    state: BrowserUiState,
    strings: ZaslonStrings,
    onPushTab: (String, Int, String) -> Unit,
    onPushBookmarks: (String, Int, String) -> Unit,
    onPullFromDesktop: (String, Int, String) -> Unit
) {
    val firstPeer = state.lanPeers.firstOrNull()
    var targetIp by remember(firstPeer?.ip) { mutableStateOf(firstPeer?.ip ?: "") }
    var targetPort by remember(firstPeer?.port) { mutableStateOf((firstPeer?.port ?: 49440).toString()) }
    var pinCode by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = strings.lanSheetTitle,
            color = ZaslonAccentBlue,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = strings.lanDeviceIpLine(state.localIp),
            color = ZaslonTextMuted,
            fontSize = 12.sp
        )

        if (state.lanPeers.isNotEmpty()) {
            Text(
                text = strings.lanPeersFound,
                color = ZaslonEmerald,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            state.lanPeers.forEach { peer ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(ZaslonBgInput)
                        .clickable {
                            targetIp = peer.ip
                            targetPort = peer.port.toString()
                        }
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("💻 ${peer.deviceName}", color = ZaslonTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("${peer.ip}:${peer.port}", color = ZaslonAccentBlue, fontSize = 12.sp)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = targetIp,
                onValueChange = { targetIp = it },
                modifier = Modifier.weight(2f),
                label = { Text(strings.lanDesktopIpHint) },
                singleLine = true
            )
            OutlinedTextField(
                value = targetPort,
                onValueChange = { targetPort = it },
                modifier = Modifier.weight(1f),
                label = { Text(strings.lanPortLabel) },
                singleLine = true
            )
        }

        OutlinedTextField(
            value = pinCode,
            onValueChange = { pinCode = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(strings.lanPinHint) },
            singleLine = true
        )

        val portNum = targetPort.toIntOrNull() ?: 49440
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { onPushTab(targetIp, portNum, pinCode) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = ZaslonAccentBlue)
            ) {
                Text(strings.lanSendTabBtn, color = Color(0xFF040914), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = { onPushBookmarks(targetIp, portNum, pinCode) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = ZaslonBgInput)
            ) {
                Text(strings.lanSendBookmarksBtn, color = ZaslonTextPrimary, fontSize = 12.sp)
            }
        }

        Button(
            onClick = { onPullFromDesktop(targetIp, portNum, pinCode) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = ZaslonEmerald)
        ) {
            Text(
                text = strings.lanPullAllBtn,
                color = Color(0xFF040914),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun TabsAndVaultSheet(
    state: BrowserUiState,
    strings: ZaslonStrings,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTabInContainer: (ZaslonContainer) -> Unit,
    onNotesChanged: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = strings.tabsSheetTitle(state.tabs.size),
            color = ZaslonAccentBlue,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ZaslonContainer.entries.forEach { c ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(c.colorHex).copy(alpha = 0.2f))
                        .clickable { onNewTabInContainer(c) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "+ ${c.badgeEmoji} ${strings.containerLabel(c)}",
                        color = Color(c.colorHex),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(state.tabs, key = { it.id }) { tab ->
                val isCurrent = tab.id == state.activeTabId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isCurrent) ZaslonAccentBlue.copy(alpha = 0.18f) else ZaslonBgInput)
                        .border(1.dp, Color(tab.container.colorHex).copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                        .clickable { onSelectTab(tab.id) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${tab.container.badgeEmoji} ${tab.title}",
                            color = ZaslonTextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = tab.url,
                            color = ZaslonTextMuted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "✕",
                        color = ZaslonDangerRed,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { onCloseTab(tab.id) }
                            .padding(start = 10.dp)
                    )
                }
            }
        }

        HorizontalDivider(color = ZaslonBorder)

        Text(
            text = strings.vaultNotesTitle,
            color = ZaslonAccentBlue,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        OutlinedTextField(
            value = state.notes,
            onValueChange = onNotesChanged,
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp),
            placeholder = { Text(strings.vaultNotesPlaceholder) }
        )
    }
}

@Composable
private fun AppUpdateBottomSheet(
    state: BrowserUiState,
    strings: ZaslonStrings,
    onSaveConfig: (String, String, Boolean) -> Unit,
    onCheckUpdates: (String, String) -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallApk: () -> Unit
) {
    val upd = state.updateState
    var repoInput by remember(upd.repo) { mutableStateOf(upd.repo) }
    var tokenInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = strings.updateSheetTitle,
            color = ZaslonAccentBlue,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = strings.updateSheetDesc,
            color = ZaslonTextMuted,
            fontSize = 12.sp
        )
        Text(
            text = strings.updateCurrentVersion(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            color = ZaslonEmerald,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )

        OutlinedTextField(
            value = repoInput,
            onValueChange = { repoInput = it },
            label = { Text(strings.updateRepoHint) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = tokenInput,
            onValueChange = { tokenInput = it },
            label = {
                Text(if (upd.hasToken) strings.updateTokenSavedHint else strings.updateTokenHint)
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    onSaveConfig(repoInput, tokenInput, false)
                    tokenInput = ""
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = ZaslonBgInput)
            ) {
                Text(strings.updateSaveConfigBtn, color = ZaslonTextPrimary, fontSize = 12.sp)
            }
            if (upd.hasToken) {
                Button(
                    onClick = {
                        onSaveConfig(repoInput, "", true)
                        tokenInput = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ZaslonDangerRed.copy(alpha = 0.25f))
                ) {
                    Text(strings.updateClearTokenBtn, color = ZaslonDangerRed, fontSize = 12.sp)
                }
            }
        }

        Button(
            onClick = { onCheckUpdates(repoInput, tokenInput) },
            enabled = upd.phase != AndroidUpdatePhase.CHECKING && upd.phase != AndroidUpdatePhase.DOWNLOADING,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = ZaslonAccentBlue)
        ) {
            Text(strings.updateCheckBtn, color = Color(0xFF040914), fontWeight = FontWeight.Bold)
        }

        when (upd.phase) {
            AndroidUpdatePhase.CHECKING -> {
                Text(strings.updateChecking, color = ZaslonTextMuted, fontSize = 12.sp)
            }
            AndroidUpdatePhase.UP_TO_DATE -> {
                Text(
                    strings.updateUpToDate(BuildConfig.VERSION_NAME),
                    color = ZaslonEmerald,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            AndroidUpdatePhase.AVAILABLE -> {
                val rel = upd.latestRelease
                if (rel != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(ZaslonAccentBlue.copy(alpha = 0.14f))
                            .border(1.dp, ZaslonAccentBlue, RoundedCornerShape(10.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = strings.updateAvailable(rel.version, rel.apkAsset?.name ?: "APK"),
                            color = ZaslonTextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (rel.notes.isNotBlank()) {
                            Text(
                                text = rel.notes.take(500),
                                color = ZaslonTextMuted,
                                fontSize = 11.sp
                            )
                        }
                        Button(
                            onClick = onDownloadUpdate,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = ZaslonEmerald)
                        ) {
                            Text(
                                strings.updateDownloadBtn(rel.version),
                                color = Color(0xFF040914),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            AndroidUpdatePhase.DOWNLOADING -> {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(strings.updateDownloading(upd.progressPct), color = ZaslonTextPrimary, fontSize = 12.sp)
                    LinearProgressIndicator(
                        progress = { upd.progressPct / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = ZaslonEmerald,
                        trackColor = ZaslonBgInput
                    )
                }
            }
            AndroidUpdatePhase.DOWNLOADED -> {
                Button(
                    onClick = onInstallApk,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = ZaslonEmerald)
                ) {
                    Text(strings.updateInstallBtn, color = Color(0xFF040914), fontWeight = FontWeight.Bold)
                }
            }
            AndroidUpdatePhase.ERROR -> {
                Text(
                    text = "❌ ${upd.errorMessage}",
                    color = ZaslonDangerRed,
                    fontSize = 12.sp
                )
            }
            AndroidUpdatePhase.IDLE -> {}
        }
    }
}
