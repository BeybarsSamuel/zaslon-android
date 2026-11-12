# Zaslon Browser for Android

Zaslon Android is a hardened mobile browser built on Mozilla GeckoView, Kotlin, and Jetpack Compose. It is designed to provide high-assurance privacy, censorship circumvention, and local peer-to-peer synchronization with the desktop Zaslon Browser without cloud intermediaries.

All Mozilla telemetry, Glean SDK hooks, Normandy experiment frameworks, and Google SafeBrowsing services are completely removed or disabled at the engine level.

---

## Architecture and Core Modules

### 1. Hardened GeckoView Engine (`engine/HardenedGeckoEngine.kt`)

The browser core runs an isolated GeckoView runtime configured through `assets/geckoview-config.yaml` and dynamic runtime prefs:

- **Telemetry Stripped:** All Mozilla analytics (`toolkit.telemetry.*`, `datareporting.*`, `browser.ping-centre.*`) and crash reporters are disabled.
- **WebRTC Leak Prevention:** Enforces `media.peerconnection.ice.default_address_only: true` and `no_host: true` to prevent real local or public IP leaks behind proxies.
- **Isolated Tab Containers:** Supports Personal, Work, Social, Shopping, Ephemeral (RAM-only), and Tor Armored containers. Each container maps to an independent `contextId` cookie, cache, and storage boundary.
- **Multi-Level Security:** Standard, Safer (disables JIT optimizations and OpenType SVG rendering), and Safest / Paranoid (disables JavaScript and non-essential media APIs).

### 2. Built-in ByeDPI Tunnel (`network/ByeDpiProxyEngine.kt` & `network/ByeDpiVpnService.kt`)

Zaslon includes an embedded ByeDPI engine that circumvents middlebox SNI filtering without requiring root privileges:

- Operates as a local SOCKS5 proxy on `127.0.0.1:10808` or an Android `VpnService` tunnel.
- Intercepts outbound TLS handshakes and fragments the initial TLS `ClientHello` packet across multiple TCP segments or TLS records (`TLS Record Split` and `TCP_NODELAY`).
- Allows bypassing domain-based blocks and throttling imposed by ISP deep packet inspection equipment.

### 3. Integrated WebExtension Shield (`assets/extensions/zaslon-shield/`)

A built-in WebExtension provides network-level filtering and inspection:

- **Ad and Tracker Blocking:** Sub-millisecond URL and cosmetic filtering based on curated lists.
- **URL Sanitization:** Strips tracking query parameters (`utm_*`, `fbclid`, `gclid`, `si`, `igshid`, `yclid`) from links prior to navigation.
- **Request Inspector:** Real-time visibility into third-party domains contacted by the active tab, with one-tap domain-blocking rules.

### 4. Background Media and Usability (`media/`)

- **Screen-Off Audio Playback:** Employs a Page Visibility API override (`document.hidden = false`) and an Android Foreground Service with audio focus locks (`WakeLock` and `WifiLock`) to prevent YouTube and web audio streams from pausing when the screen turns off.
- **SponsorBlock Client:** Automatically skips sponsored segments in YouTube videos using 4-character SHA-256 k-anonymity, ensuring video IDs are never submitted in plaintext to any server.
- **Video Playback Rate:** Global video playback speed adjustments (0.25x to 4.0x) and forced Picture-in-Picture.

### 5. Encrypted Vault and Panic Trigger (`storage/EncryptedMobileVault.kt`)

- **Hardware-Backed Encryption:** Bookmarks, history, and notes are encrypted at rest using AES-256-GCM backed by the `AndroidKeyStore`.
- **Panic Action:** A single tap destroys active tabs, wipes memory caches, and securely shreds the local vault database without leaving persistent traces.

### 6. Cloudless LAN Peer-to-Peer Sync (`storage/LanSyncClient.kt`)

Zaslon Android communicates directly with Zaslon Browser on desktop over your local Wi-Fi:

- Discovers desktop peers via UDP broadcast packets on port `49441`.
- Synchronizes tabs, bookmarks, and notes over an encrypted HTTP endpoint on port `49440`.
- Authentication uses a 6-digit one-time PIN and key derivation through `scrypt` and AES-256-GCM.
- No third-party accounts, cloud servers, or external connections are involved.

---

## Project Structure

```
zaslon-android/
├── app/
│   ├── src/main/
│   │   ├── java/com/zaslon/browser/
│   │   │   ├── engine/       # GeckoView engine initialization and preference wiring
│   │   │   ├── media/        # Background audio service, SponsorBlock, media controls
│   │   │   ├── network/      # ByeDPI engine, VPN service, SOCKS5 client
│   │   │   ├── storage/      # AndroidKeyStore encrypted vault, LAN sync client
│   │   │   └── ui/           # Jetpack Compose UI (tabs, toolbar, settings, drawers)
│   │   └── assets/
│   │       ├── geckoview-config.yaml  # Base engine configuration and privacy flags
│   │       └── extensions/zaslon-shield/ # Built-in content blocking WebExtension
│   └── build.gradle.kts
└── build.gradle.kts
```

---

## Building and Development

### Prerequisites

- Android Studio (Ladybug, Koala, or newer)
- JDK 17 or JDK 21
- Android SDK with API 34+ and NDK tools installed

### Building from Android Studio

1. Open the `zaslon-android` folder in Android Studio.
2. Wait for Gradle to download dependencies and GeckoView artifacts.
3. Select an emulator or physical device running Android 8.0 (API 26) or higher.
4. Click **Run** (`Shift + F10`) or navigate to **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

### Building via Command Line (CLI)

```bash
cd zaslon-android
./gradlew assembleDebug
```

The resulting debug APK will be located at:
```
app/build/outputs/apk/debug/app-universal-debug.apk
```

---

## Security Guidelines

- **No Telemetry:** Zaslon Android does not send diagnostics, crash logs, or telemetry to Mozilla, Google, or any central server.
- **Fail-Closed by Design:** In Tor and VPN modes, connections will refuse to proceed if the proxy circuit is not established, preventing clearnet IP leakage.
- **Local Isolation:** Storage, cookies, and network state remain strictly separated across tab containers.

---

## License

This project is licensed under the MIT License. See the `LICENSE` file for details.
