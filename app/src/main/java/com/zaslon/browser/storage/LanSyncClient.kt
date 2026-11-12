package com.zaslon.browser.storage

import android.os.Build
import android.util.Base64
import com.zaslon.browser.i18n.ZaslonLanguageStore
import com.zaslon.browser.network.AndroidDohResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class DiscoveredLanPeer(
    val deviceId: String,
    val deviceName: String,
    val ip: String,
    val port: Int,
    val platform: String,
    val lastSeen: Long
)

data class LanSyncBundle(
    val deviceName: String,
    val tabs: List<Pair<String, String>>, // (title, url)
    val bookmarks: List<MobileBookmark>,
    val notes: String,
    val rssFeeds: List<String>
)

/**
 * Faz 6.4: Masaüstü Zaslon (`src/storage/lan-sync.js`) ile %100 Uyumlu Bulutsuz LAN Wi-Fi İstemcisi
 *
 * - Port 49441 üzerinden UDP Broadcast (`ZASLON_LAN_V1`) ile masaüstü Zaslon cihazlarını otomatik keşfeder
 *   ve kendini masaüstü Zaslon'un "Ağdaki Zaslon Cihazları" listesine duyurur.
 * - Port 49440 üzerinden HTTP `/api/sync/push` ve `/api/sync/pull` uç noktalarıyla haberleşir.
 * - Masaüstü `lan-sync.js` içindeki `_deriveKeyFromPin("zaslon-lan-pin:$pin", salt, 32, {N:4096, r:8, p:1})`
 *   algoritmasıyla birebir aynı RFC 7914 `scrypt` + `AES-256-GCM` paket şifrelemesini uygular.
 */
class LanSyncClient(
    private val onPeersUpdated: (List<DiscoveredLanPeer>) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val secureRandom = SecureRandom()
    private val peers = ConcurrentHashMap<String, DiscoveredLanPeer>()

    val deviceId: String = "zaslon-android-" + ByteArray(3).also { secureRandom.nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
    val deviceName: String = "Zaslon Android"

    private var udpSocket: DatagramSocket? = null
    private var discoveryJob: Job? = null
    private var beaconJob: Job? = null

    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return

        discoveryJob = scope.launch {
            try {
                val sock = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(DISCOVERY_UDP_PORT))
                }
                udpSocket = sock

                val buf = ByteArray(2048)
                while (isActive) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(pkt)
                        val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                        val json = JSONObject(text)
                        if (json.optString("magic") == BEACON_MAGIC) {
                            val peerId = json.optString("deviceId", "").trim()
                            val senderIp = pkt.address?.hostAddress ?: ""
                            val senderPort = json.optInt("port", DEFAULT_HTTP_PORT)
                            if (
                                peerId.isNotEmpty() &&
                                peerId != deviceId &&
                                parsePrivateLanIpv4(senderIp) != null &&
                                senderPort in 1024..65535
                            ) {
                                val rawName = json.optString("deviceName", ZaslonLanguageStore.strings().lanDesktopFallback)
                                val safeName = rawName.replace(Regex("[\\u0000-\\u001F\\u007F]"), "").trim().take(64)
                                    .ifEmpty { ZaslonLanguageStore.strings().lanDesktopFallback }
                                val peer = DiscoveredLanPeer(
                                    deviceId = peerId.take(64),
                                    deviceName = safeName,
                                    ip = senderIp,
                                    port = senderPort,
                                    platform = json.optString("platform", "desktop").take(24),
                                    lastSeen = System.currentTimeMillis()
                                )
                                peers[peerId] = peer
                                publishPeerList()
                            }
                        }
                    } catch (_: Exception) {
                        if (!isActive) break
                    }
                }
            } catch (_: Exception) {}
        }

        beaconJob = scope.launch {
            while (isActive) {
                sendPresenceBeacon()
                pruneStalePeers()
                delay(5000)
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        beaconJob?.cancel()
        try {
            udpSocket?.close()
        } catch (_: Exception) {}
        udpSocket = null
    }

    private fun sendPresenceBeacon() {
        try {
            val sock = udpSocket ?: return
            val packetJson = JSONObject().apply {
                put("magic", BEACON_MAGIC)
                put("deviceId", deviceId)
                put("deviceName", deviceName)
                put("platform", "android")
                put("port", DEFAULT_HTTP_PORT)
                put("timestamp", System.currentTimeMillis())
            }.toString().toByteArray(Charsets.UTF_8)

            val targets = mutableSetOf("255.255.255.255")
            getLocalIpv4Addresses().forEach { ip ->
                val parts = ip.split(".")
                if (parts.size == 4) {
                    targets.add("${parts[0]}.${parts[1]}.${parts[2]}.255")
                }
            }

            for (addr in targets) {
                try {
                    val ipBytes = AndroidDohResolver.parseLiteralIpAddress(addr) ?: continue
                    val dgram = DatagramPacket(
                        packetJson,
                        packetJson.size,
                        ipBytes,
                        DISCOVERY_UDP_PORT
                    )
                    sock.send(dgram)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun pruneStalePeers() {
        val now = System.currentTimeMillis()
        var changed = false
        val it = peers.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (now - entry.value.lastSeen > 35_000L) {
                it.remove()
                changed = true
            }
        }
        if (changed) publishPeerList()
    }

    private fun publishPeerList() {
        val sorted = peers.values.sortedByDescending { it.lastSeen }
        onPeersUpdated(sorted)
    }

    fun getLocalIpv4Addresses(): List<String> {
        val list = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return listOf("127.0.0.1")
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        a.hostAddress?.let { list.add(it) }
                    }
                }
            }
        } catch (_: Exception) {}
        return if (list.isEmpty()) listOf("127.0.0.1") else list
    }

    /**
     * Yalnızca RFC1918 / RFC6598 / link-local / loopback literal IPv4 adreslerini kabul eder;
     * genel internete veya DNS çözümlemesine kesinlikle çıkmaz.
     */
    fun parsePrivateLanIpv4(host: String): InetAddress? {
        val clean = host.trim()
        val addr = AndroidDohResolver.parseLiteralIpAddress(clean) ?: return null
        val bytes = addr.address
        if (bytes.size != 4) return null
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val isPrivate = (b0 == 127) ||
            (b0 == 10) ||
            (b0 == 172 && b1 in 16..31) ||
            (b0 == 192 && b1 == 168) ||
            (b0 == 169 && b1 == 254) ||
            (b0 == 100 && b1 in 64..127)
        return if (isPrivate) addr else null
    }

    private fun isSafeSyncedUrl(url: String): Boolean {
        val trimmed = url.trim().lowercase()
        return trimmed.startsWith("https://") || trimmed.startsWith("http://")
    }

    private fun buildPinAuthHeader(pin: String, method: String, path: String): String {
        val cleanPin = pin.filter { it.isDigit() }
        val ts = System.currentTimeMillis().toString()
        val nonceBytes = ByteArray(8).also { secureRandom.nextBytes(it) }
        val nonce = nonceBytes.joinToString("") { "%02x".format(it) }
        val payload = "$ts:$nonce:${method.uppercase()}:$path"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("zaslon-lan-auth:$cleanPin".toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val sig = mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return "$ts.$nonce.$sig"
    }

    @Throws(IOException::class)
    private fun sendPrivateLanHttp(
        targetAddr: InetAddress,
        port: Int,
        method: String,
        path: String,
        pin: String,
        bodyBytes: ByteArray?
    ): Pair<Int, String> {
        Socket().use { sock ->
            sock.connect(InetSocketAddress(targetAddr, port), 6000)
            sock.soTimeout = 6000

            val hostHeader = targetAddr.hostAddress ?: "127.0.0.1"
            val authProof = buildPinAuthHeader(pin, method, path)
            val reqHeaders = buildString {
                append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(hostHeader).append(':').append(port).append("\r\n")
                append("Accept: application/json\r\n")
                append("X-Zaslon-Auth: ").append(authProof).append("\r\n")
                if (bodyBytes != null) {
                    append("Content-Type: application/json; charset=utf-8\r\n")
                    append("Content-Length: ").append(bodyBytes.size).append("\r\n")
                }
                append("Connection: close\r\n\r\n")
            }.toByteArray(Charsets.US_ASCII)

            val out = sock.getOutputStream()
            out.write(reqHeaders)
            if (bodyBytes != null) out.write(bodyBytes)
            out.flush()

            val rawResp = sock.getInputStream().readBytes()
            val sep = findHeaderEnd(rawResp)
            if (sep < 0) throw IOException("Malformed HTTP response from LAN peer")

            val headerText = String(rawResp, 0, sep, Charsets.ISO_8859_1)
            val lines = headerText.split("\r\n")
            val statusCode = lines.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: 0
            val isChunked = lines.any {
                it.startsWith("Transfer-Encoding:", ignoreCase = true) &&
                    it.contains("chunked", ignoreCase = true)
            }
            val rawBody = rawResp.copyOfRange(sep + 4, rawResp.size)
            val bodyText = if (isChunked) {
                String(decodeChunkedBytes(rawBody), Charsets.UTF_8)
            } else {
                String(rawBody, Charsets.UTF_8)
            }
            return statusCode to bodyText
        }
    }

    private fun findHeaderEnd(buf: ByteArray): Int {
        for (i in 0 until buf.size - 3) {
            if (buf[i] == '\r'.code.toByte() &&
                buf[i + 1] == '\n'.code.toByte() &&
                buf[i + 2] == '\r'.code.toByte() &&
                buf[i + 3] == '\n'.code.toByte()
            ) {
                return i
            }
        }
        return -1
    }

    private fun decodeChunkedBytes(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var pos = 0
        while (pos < raw.size) {
            var lineEnd = -1
            for (i in pos until raw.size - 1) {
                if (raw[i] == '\r'.code.toByte() && raw[i + 1] == '\n'.code.toByte()) {
                    lineEnd = i
                    break
                }
            }
            if (lineEnd < 0) break
            val sizeStr = String(raw, pos, lineEnd - pos, Charsets.US_ASCII).substringBefore(';').trim()
            val chunkLen = sizeStr.toIntOrNull(16) ?: break
            pos = lineEnd + 2
            if (chunkLen == 0) break
            val end = minOf(pos + chunkLen, raw.size)
            out.write(raw, pos, end - pos)
            pos = end + 2
        }
        return out.toByteArray()
    }

    /**
     * PIN doğrulama ön kontrolü (/api/ping) — kimlik sızdırmadan yalnızca PIN yetkisi doğrular.
     */
    suspend fun verifyPeerAuth(
        targetHost: String,
        targetPort: Int = DEFAULT_HTTP_PORT,
        pin: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val cleanHost = targetHost.trim().removePrefix("http://").removePrefix("https://").substringBefore("/")
            val hostOnly = cleanHost.substringBefore(":")
            val port = cleanHost.substringAfter(":", "").toIntOrNull() ?: targetPort
            val cleanPin = pin.filter { it.isDigit() }
            val s = ZaslonLanguageStore.strings()

            if (hostOnly.isBlank()) return@withContext Result.failure(IllegalArgumentException(s.lanErrEmptyIp))
            val targetAddr = parsePrivateLanIpv4(hostOnly)
                ?: return@withContext Result.failure(SecurityException("Only private LAN IPv4 addresses are allowed"))
            if (port !in 1..65535) return@withContext Result.failure(IllegalArgumentException("Invalid LAN port"))
            if (cleanPin.length < 4) return@withContext Result.failure(IllegalArgumentException(s.lanErrPin))

            val (status, respText) = sendPrivateLanHttp(targetAddr, port, "GET", "/api/ping", cleanPin, null)
            val respJson = if (respText.isNotBlank()) JSONObject(respText) else JSONObject()
            if (status == 200 && respJson.optBoolean("ok", false)) {
                Result.success(true)
            } else {
                Result.failure(SecurityException(respJson.optString("error", "Auth failed (HTTP $status)")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Masaüstü Zaslon cihazına (veya başka bir Zaslon'a) AES-256-GCM şifreli veri paketi gönderir.
     */
    suspend fun pushToPeer(
        targetHost: String,
        targetPort: Int = DEFAULT_HTTP_PORT,
        pin: String,
        payload: JSONObject
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val cleanHost = targetHost.trim().removePrefix("http://").removePrefix("https://").substringBefore("/")
            val hostOnly = cleanHost.substringBefore(":")
            val port = cleanHost.substringAfter(":", "").toIntOrNull() ?: targetPort
            val cleanPin = pin.filter { it.isDigit() }

            val s = ZaslonLanguageStore.strings()
            if (hostOnly.isBlank()) return@withContext Result.failure(IllegalArgumentException(s.lanErrEmptyIp))
            val targetAddr = parsePrivateLanIpv4(hostOnly)
                ?: return@withContext Result.failure(SecurityException("Only private LAN IPv4 addresses are allowed"))
            if (port !in 1..65535) return@withContext Result.failure(IllegalArgumentException("Invalid LAN port"))
            if (cleanPin.length < 4) return@withContext Result.failure(IllegalArgumentException(s.lanErrPin))

            val fullPacket = JSONObject(payload.toString()).apply {
                put("deviceName", deviceName)
                put("deviceId", deviceId)
                put("timestamp", System.currentTimeMillis())
            }

            val envelope = encryptLanPacket(fullPacket, cleanPin)
            val requestBody = JSONObject().apply {
                put("envelope", envelope)
            }.toString().toByteArray(Charsets.UTF_8)

            val (status, respText) = sendPrivateLanHttp(targetAddr, port, "POST", "/api/sync/push", cleanPin, requestBody)
            val respJson = if (respText.isNotBlank()) JSONObject(respText) else JSONObject()

            if (status == 200 && respJson.optBoolean("ok", false)) {
                Result.success(s.lanSentToPeer(hostOnly, port))
            } else {
                Result.failure(IllegalStateException(respJson.optString("error", "HTTP $status")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Masaüstü Zaslon cihazından açık sekmeleri, yer imlerini ve notları ZORUNLU AES-256-GCM ile çeker.
     */
    suspend fun pullFromPeer(
        targetHost: String,
        targetPort: Int = DEFAULT_HTTP_PORT,
        pin: String
    ): Result<LanSyncBundle> = withContext(Dispatchers.IO) {
        try {
            val cleanHost = targetHost.trim().removePrefix("http://").removePrefix("https://").substringBefore("/")
            val hostOnly = cleanHost.substringBefore(":")
            val port = cleanHost.substringAfter(":", "").toIntOrNull() ?: targetPort
            val cleanPin = pin.filter { it.isDigit() }
            val s = ZaslonLanguageStore.strings()

            if (hostOnly.isBlank()) return@withContext Result.failure(IllegalArgumentException(s.lanErrEmptyIp))
            val targetAddr = parsePrivateLanIpv4(hostOnly)
                ?: return@withContext Result.failure(SecurityException("Only private LAN IPv4 addresses are allowed"))
            if (port !in 1..65535) return@withContext Result.failure(IllegalArgumentException("Invalid LAN port"))
            if (cleanPin.length < 4) return@withContext Result.failure(IllegalArgumentException(s.lanErrPin))

            val reqBody = JSONObject().apply {
                put("wantEncrypted", true)
            }.toString().toByteArray(Charsets.UTF_8)

            val (status, respText) = sendPrivateLanHttp(targetAddr, port, "POST", "/api/sync/pull", cleanPin, reqBody)
            val respJson = if (respText.isNotBlank()) JSONObject(respText) else JSONObject()

            if (status != 200 || !respJson.optBoolean("ok", false)) {
                return@withContext Result.failure(
                    IllegalStateException(respJson.optString("error", s.lanSyncHttpErr(status)))
                )
            }

            // Fail-closed: Şifresiz JSON yanıtı kesinlikle reddet, yalnızca AES-256-GCM zarfını çöz
            val envelope = respJson.optJSONObject("envelope")
                ?: return@withContext Result.failure(SecurityException("Unencrypted LAN response rejected (AES-256-GCM envelope required)"))
            val dataJson = decryptLanPacket(envelope, cleanPin)

            val tabs = mutableListOf<Pair<String, String>>()
            dataJson.optJSONArray("tabs")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val t = arr.optJSONObject(i) ?: continue
                    val u = t.optString("url", "")
                    if (isSafeSyncedUrl(u)) {
                        tabs.add(t.optString("title", u) to u)
                    }
                }
            }

            val bms = mutableListOf<MobileBookmark>()
            dataJson.optJSONArray("bms")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val b = arr.optJSONObject(i) ?: continue
                    val u = b.optString("url", "")
                    if (isSafeSyncedUrl(u)) {
                        bms.add(MobileBookmark(b.optString("title", u), u))
                    }
                }
            }

            val feeds = mutableListOf<String>()
            dataJson.optJSONArray("rssFeeds")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val f = arr.optString(i, "")
                    if (isSafeSyncedUrl(f)) feeds.add(f)
                }
            }

            Result.success(
                LanSyncBundle(
                    deviceName = dataJson.optString("deviceName", hostOnly),
                    tabs = tabs,
                    bookmarks = bms,
                    notes = dataJson.optString("notes", ""),
                    rssFeeds = feeds
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Masaüstü `lan-sync.js` ile birebir uyumlu AES-256-GCM paket şifreleme:
     * Key = scrypt("zaslon-lan-pin:$cleanPin", salt, 32, N=4096, r=8, p=1)
     */
    fun encryptLanPacket(payload: JSONObject, pin: String): JSONObject {
        val cleanPin = pin.filter { it.isDigit() }
        val salt = ByteArray(16).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }
        val keyBytes = deriveScryptKey("zaslon-lan-pin:$cleanPin", salt, 4096, 8, 1, 32)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        val plain = payload.toString().toByteArray(Charsets.UTF_8)
        val combined = cipher.doFinal(plain) // ciphertext + 16-byte GCM AuthTag

        val ctLen = combined.size - 16
        val ct = combined.copyOfRange(0, ctLen)
        val tag = combined.copyOfRange(ctLen, combined.size)

        return JSONObject().apply {
            put("encrypted", true)
            put("cipher", "AES-256-GCM")
            put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            put("tag", Base64.encodeToString(tag, Base64.NO_WRAP))
            put("ciphertext", Base64.encodeToString(ct, Base64.NO_WRAP))
        }
    }

    /**
     * Masaüstü `lan-sync.js` tarafından şifrelenmiş AES-256-GCM zarfını çözer.
     */
    fun decryptLanPacket(envelope: JSONObject, pin: String): JSONObject {
        val cleanPin = pin.filter { it.isDigit() }
        val salt = Base64.decode(envelope.getString("salt"), Base64.NO_WRAP)
        val iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)
        val tag = Base64.decode(envelope.getString("tag"), Base64.NO_WRAP)
        val ct = Base64.decode(envelope.getString("ciphertext"), Base64.NO_WRAP)

        val keyBytes = deriveScryptKey("zaslon-lan-pin:$cleanPin", salt, 4096, 8, 1, 32)
        val combined = ByteArray(ct.size + tag.size)
        System.arraycopy(ct, 0, combined, 0, ct.size)
        System.arraycopy(tag, 0, combined, ct.size, tag.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(combined)
        return JSONObject(String(plain, Charsets.UTF_8))
    }

    /**
     * Saf Kotlin RFC 7914 scrypt(N=4096, r=8, p=1) Anahtar Türetici
     * Node.js `crypto.scryptSync(password, salt, 32, { N: 4096, r: 8, p: 1 })` ile %100 aynı çıktıyı üretir.
     */
    private fun deriveScryptKey(
        password: String,
        salt: ByteArray,
        n: Int,
        r: Int,
        p: Int,
        dkLen: Int
    ): ByteArray {
        val blockSize = 128 * r
        val b = pbkdf2HmacSha256(password.toByteArray(Charsets.UTF_8), salt, p * blockSize)
        val v = IntArray(32 * r * n)
        val xy = IntArray(64 * r)

        for (i in 0 until p) {
            smix(b, i * blockSize, r, n, v, xy)
        }
        return pbkdf2HmacSha256(password.toByteArray(Charsets.UTF_8), b, dkLen)
    }

    private fun pbkdf2HmacSha256(passwordBytes: ByteArray, salt: ByteArray, dkLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(passwordBytes, "HmacSHA256"))
        val out = ByteArray(dkLen)
        val blockCount = (dkLen + 31) / 32
        val intBuf = ByteArray(4)
        var offset = 0
        for (blockIdx in 1..blockCount) {
            intBuf[0] = ((blockIdx ushr 24) and 0xFF).toByte()
            intBuf[1] = ((blockIdx ushr 16) and 0xFF).toByte()
            intBuf[2] = ((blockIdx ushr 8) and 0xFF).toByte()
            intBuf[3] = (blockIdx and 0xFF).toByte()
            mac.reset()
            mac.update(salt)
            val u1 = mac.doFinal(intBuf)
            val copyLen = minOf(32, dkLen - offset)
            System.arraycopy(u1, 0, out, offset, copyLen)
            offset += copyLen
        }
        return out
    }

    private fun smix(b: ByteArray, bOffset: Int, r: Int, n: Int, v: IntArray, xy: IntArray) {
        val intCount = 32 * r
        for (i in 0 until intCount) {
            val p = bOffset + i * 4
            xy[i] = (b[p].toInt() and 0xFF) or
                ((b[p + 1].toInt() and 0xFF) shl 8) or
                ((b[p + 2].toInt() and 0xFF) shl 16) or
                ((b[p + 3].toInt() and 0xFF) shl 24)
        }
        for (i in 0 until n) {
            System.arraycopy(xy, 0, v, i * intCount, intCount)
            blockMixSalsa8(xy, 0, intCount, r)
            System.arraycopy(xy, intCount, xy, 0, intCount)
        }
        for (i in 0 until n) {
            val j = xy[intCount - 16] and (n - 1)
            for (k in 0 until intCount) {
                xy[k] = xy[k] xor v[j * intCount + k]
            }
            blockMixSalsa8(xy, 0, intCount, r)
            System.arraycopy(xy, intCount, xy, 0, intCount)
        }
        for (i in 0 until intCount) {
            val p = bOffset + i * 4
            val w = xy[i]
            b[p] = (w and 0xFF).toByte()
            b[p + 1] = ((w ushr 8) and 0xFF).toByte()
            b[p + 2] = ((w ushr 16) and 0xFF).toByte()
            b[p + 3] = ((w ushr 24) and 0xFF).toByte()
        }
    }

    private fun blockMixSalsa8(by: IntArray, bOff: Int, yOff: Int, r: Int) {
        val x = IntArray(16)
        System.arraycopy(by, bOff + (2 * r - 1) * 16, x, 0, 16)
        for (i in 0 until 2 * r) {
            for (j in 0 until 16) {
                x[j] = x[j] xor by[bOff + i * 16 + j]
            }
            salsa20_8(x)
            val dest = yOff + (i / 2 + (if (i % 2 == 1) r else 0)) * 16
            System.arraycopy(x, 0, by, dest, 16)
        }
    }

    private fun salsa20_8(b: IntArray) {
        val x = b.clone()
        for (i in 0 until 4) {
            x[4] = x[4] xor Integer.rotateLeft(x[0] + x[12], 7)
            x[8] = x[8] xor Integer.rotateLeft(x[4] + x[0], 9)
            x[12] = x[12] xor Integer.rotateLeft(x[8] + x[4], 13)
            x[0] = x[0] xor Integer.rotateLeft(x[12] + x[8], 18)
            x[9] = x[9] xor Integer.rotateLeft(x[5] + x[1], 7)
            x[13] = x[13] xor Integer.rotateLeft(x[9] + x[5], 9)
            x[1] = x[1] xor Integer.rotateLeft(x[13] + x[9], 13)
            x[5] = x[5] xor Integer.rotateLeft(x[1] + x[13], 18)
            x[14] = x[14] xor Integer.rotateLeft(x[10] + x[6], 7)
            x[2] = x[2] xor Integer.rotateLeft(x[14] + x[10], 9)
            x[6] = x[6] xor Integer.rotateLeft(x[2] + x[14], 13)
            x[10] = x[10] xor Integer.rotateLeft(x[6] + x[2], 18)
            x[3] = x[3] xor Integer.rotateLeft(x[15] + x[11], 7)
            x[7] = x[7] xor Integer.rotateLeft(x[3] + x[15], 9)
            x[11] = x[11] xor Integer.rotateLeft(x[7] + x[3], 13)
            x[15] = x[15] xor Integer.rotateLeft(x[11] + x[7], 18)

            x[1] = x[1] xor Integer.rotateLeft(x[0] + x[3], 7)
            x[2] = x[2] xor Integer.rotateLeft(x[1] + x[0], 9)
            x[3] = x[3] xor Integer.rotateLeft(x[2] + x[1], 13)
            x[0] = x[0] xor Integer.rotateLeft(x[3] + x[2], 18)
            x[6] = x[6] xor Integer.rotateLeft(x[5] + x[4], 7)
            x[7] = x[7] xor Integer.rotateLeft(x[6] + x[5], 9)
            x[4] = x[4] xor Integer.rotateLeft(x[7] + x[6], 13)
            x[5] = x[5] xor Integer.rotateLeft(x[4] + x[7], 18)
            x[11] = x[11] xor Integer.rotateLeft(x[10] + x[9], 7)
            x[8] = x[8] xor Integer.rotateLeft(x[11] + x[10], 9)
            x[9] = x[9] xor Integer.rotateLeft(x[8] + x[11], 13)
            x[10] = x[10] xor Integer.rotateLeft(x[9] + x[8], 18)
            x[12] = x[12] xor Integer.rotateLeft(x[15] + x[14], 7)
            x[13] = x[13] xor Integer.rotateLeft(x[12] + x[15], 9)
            x[14] = x[14] xor Integer.rotateLeft(x[13] + x[12], 13)
            x[15] = x[15] xor Integer.rotateLeft(x[14] + x[13], 18)
        }
        for (i in 0 until 16) {
            b[i] += x[i]
        }
    }

    companion object {
        const val DEFAULT_HTTP_PORT = 49440
        const val DISCOVERY_UDP_PORT = 49441
        const val BEACON_MAGIC = "ZASLON_LAN_V1"
    }
}
