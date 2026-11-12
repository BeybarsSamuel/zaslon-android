# 🛡️ Zaslon Browser Android (`zaslon-android` — Faz 6)

**Mozilla GeckoView (Sıfır-Telemetri & Glean Sökülmüş)** çekirdeği, **Kotlin + Jetpack Compose** modern arayüzü, gömülü **ByeDPI** yerel paket parçalama tüneli, yerleşik **uBlock / Zaslon Shield** WebExtension motoru, **Arka Plan Ses Oynatma**, **SponsorBlock** ve masaüstü Zaslon ile **Bulutsuz LAN Wi-Fi Eşitleme** özelliklerine sahip zırhlı mobil tarayıcı.

---

## 🏗️ Mimari ve Modüller

1. **Çekirdek — Sıfır-Telemetri GeckoView (`engine/HardenedGeckoEngine.kt` & `assets/geckoview-config.yaml`)**
   - Mozilla telemetri (`toolkit.telemetry.*`, `datareporting.*`, `browser.ping-centre.telemetry`), Glean, Normandy/Shield çalışmaları, CrashReporter ve Google SafeBrowsing (`browser.safebrowsing.*`) motor seviyesinde tamamen kapatılmıştır.
   - **WebRTC Gerçek/Yerel IP Sızıntı Kilidi:** `media.peerconnection.ice.default_address_only: true` ve `no_host: true`.
   - **İzole Sekme Konteynerleri:** `🛡️ Kişisel`, `🏦 Banka/İş`, `💬 Sosyal`, `🛒 Alışveriş`, `👻 Geçici (RAM-Only)` ve `🧅 Tor Zırhlı` konteynerleri her biri bağımsız `contextId` çerez/önbellek alanıyla çalışır.
   - **3 Kademeli Güvenlik Seviyesi (Tor Security Slider):** Standart, Daha Güvenli ve Paranoyak (JS-Kapalı).

2. **Koruma & Erişim — Gömülü ByeDPI + uBlock WebExtension (`network/` & `assets/extensions/zaslon-shield/`)**
   - **`ByeDpiProxyEngine.kt` & `ByeDpiVpnService.kt`:** Root yetkisi gerektirmeden cihaz içinde `127.0.0.1:10808` üzerinde çalışan yerel SOCKS5 sunucusu ve Android `VpnService` tüneli. İlk TLS `ClientHello` paketini yakalayıp SNI uzantısından iki geçerli TLS kaydına (`TLS Record Split`) veya ayrı TCP segmentlerine (`TCP_NODELAY`) bölerek İSS DPI engellerini aşar.
   - **Yerleşik `zaslon-shield` WebExtension (`manifest.json`, `background.js`, `content.js`):**
     - Ağ seviyesinde reklam, izleyici ve telemetri engelleme (EasyList + EasyPrivacy + Türkçe reklam ağları).
     - Genişletilmiş **ClearURLs** (`utm_*`, `fbclid`, `gclid`, `si`, `igshid`, `yclid` vb.) temizleyici.
     - **Canlı İstek Denetçisi (Request Inspector):** Sitenin arka planda bağlandığı alan adlarını canlı listeler ve dokunarak engellemeye izin verir.

3. **Medya & Eşitleme (`media/` & `storage/`)**
   - **Ekran Kapalıyken Arka Plan Ses Oynatma (`BackgroundMediaService.kt` + `content.js`):** Page Visibility API kilidi (`document.hidden = false`) ve Android `mediaPlayback` Foreground Service (`WakeLock` + `WifiLock`) ile ekran kilitlendiğinde YouTube/video sesinin kesilmesini önler.
   - **SponsorBlock (`SponsorBlockClient.kt` + `background.js`):** SHA-256 K-Anonimlik (4 karakter özet ön eki) ile YouTube videolarındaki sponsorlu bölümleri otomatik atlar.
   - **0.25x – 4.0x Video Hız & Zorunlu PiP:** Tüm videoları `0.25x` ile `4.0x` arasında hızlandırma ve `disablePictureInPicture` engelini kaldırma.
   - **AES-256-GCM Şifreli Yerel Kasa (`EncryptedMobileVault.kt`) + 🔥 Panik Yakıcı:** Yer imleri, geçmiş ve notlar donanım destekli `AndroidKeyStore` (`AES/GCM/NoPadding`, 256-bit) ile şifrelenir. Üst çubuktaki **🔥 Panik Butonu** tüm sekmeleri, çerezleri ve yerel kasayı kalıntısız imha eder.
   - **Bulutsuz LAN Wi-Fi Eşitleme (`LanSyncClient.kt`):** Masaüstü `zaslon-browser/src/storage/lan-sync.js` (Port `49440` HTTP + Port `49441` UDP Broadcast + RFC 7914 `scrypt` & `AES-256-GCM` + 6 Haneli PIN) ile %100 uyumlu çift yönlü sekme, yer imi ve not aktarımı.

---

## 🚀 Derleme ve APK Üretimi

1. **Android Studio ile Açma:**
   - Android Studio (Ladybug / Koala veya üzeri, JDK 17/21) ile `zaslon-android` klasörünü açın.
   - Gradle senkronizasyonu tamamlandıktan sonra **Run (`Shift+F10`)** veya **Build > Build Bundle(s) / APK(s) > Build APK(s)** seçeneğiyle APK üretin.

2. **Komut Satırından (CLI) Derleme:**
   ```bash
   cd zaslon-android
   ./gradlew assembleDebug
   # Çıktı: app/build/outputs/apk/debug/app-universal-debug.apk
   ```
