package com.zaslon.browser.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ByeDPI Çalışma Modları (TLS Record Split / TCP Desync)
 */
enum class ByeDpiMode(val id: String, val label: String, val splitOffset: Int) {
    TLS_RECORD_SPLIT("tls_rec_split", "TLS Record Split + SNI Parçalama (Önerilen)", 2),
    TCP_DESYNC_1("tcp_split_1", "TCP 1-Byte Desync (Agresif DPI Aşma)", 1),
    SNI_MIDDLE("sni_mid", "SNI Alan Adı Ortadan Bölme", 5)
}

/**
 * Faz 6.3: Gömülü ByeDPI Yerel SOCKS5 Proxy & TLS ClientHello Parçalayıcı (Desync)
 *
 * Root yetkisi gerektirmeden cihaz içinde 127.0.0.1:10808 üzerinde çalışır.
 * GeckoView'dan çıkan ilk TLS ClientHello paketini yakalar, SNI (Server Name Indication)
 * uzantısını tespit eder ve paketi birden fazla küçük TLS kaydına / TCP segmentine
 * bölerek (`TCP_NODELAY`) İSS Derin Paket İnceleme (DPI) kutularını atlatır.
 */
class ByeDpiProxyEngine(
    val listenPort: Int = 10808,
    var mode: ByeDpiMode = ByeDpiMode.TLS_RECORD_SPLIT,
    private val onStatsUpdated: ((activeConns: Int, splitPackets: Long, bytesTotal: Long) -> Unit)? = null
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var dohProviderUrl: String = AndroidDohResolver.DEFAULT_DOH_URL

    @Volatile
    var protectSocket: ((Socket) -> Boolean)? = null

    val activeConnections = AtomicInteger(0)
    val fragmentedHandshakes = AtomicLong(0)
    val relayedBytes = AtomicLong(0)

    val isRunning: Boolean
        get() = running.get()

    @Synchronized
    fun start(): Boolean {
        if (running.get()) return true
        return try {
            val srv = ServerSocket()
            srv.reuseAddress = true
            srv.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), listenPort), 128)
            serverSocket = srv
            running.set(true)

            acceptJob = scope.launch {
                while (isActive && running.get()) {
                    val client = try {
                        srv.accept()
                    } catch (_: Exception) {
                        break
                    }
                    launch { handleClientSocket(client) }
                }
            }
            notifyStats()
            true
        } catch (_: Exception) {
            running.set(false)
            false
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        activeConnections.set(0)
        notifyStats()
    }

    private fun notifyStats() {
        onStatsUpdated?.invoke(
            activeConnections.get(),
            fragmentedHandshakes.get(),
            relayedBytes.get()
        )
    }

    private suspend fun handleClientSocket(client: Socket) {
        activeConnections.incrementAndGet()
        notifyStats()
        var remote: Socket? = null

        try {
            client.tcpNoDelay = true
            client.soTimeout = 15000
            val cIn = client.getInputStream()
            val cOut = client.getOutputStream()

            // 1. SOCKS5 El Sıkışması (VER = 0x05)
            val ver = cIn.read()
            if (ver != 0x05) return
            val nMethods = cIn.read()
            if (nMethods <= 0) return
            val methods = ByteArray(nMethods)
            readFully(cIn, methods, nMethods)
            // Kimlik doğrulamasız kabul (0x05, 0x00)
            cOut.write(byteArrayOf(0x05, 0x00))
            cOut.flush()

            // 2. SOCKS5 CONNECT İsteği
            val header = ByteArray(4)
            if (!readFully(cIn, header, 4)) return
            val cmd = header[1].toInt() and 0xFF
            val atyp = header[3].toInt() and 0xFF
            if (cmd != 0x01) {
                // Yalnızca CONNECT (0x01) desteklenir
                cOut.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                cOut.flush()
                return
            }

            val targetAddr: InetAddress = when (atyp) {
                0x01 -> { // IPv4
                    val addr = ByteArray(4)
                    if (!readFully(cIn, addr, 4)) return
                    InetAddress.getByAddress(addr)
                }
                0x03 -> { // Domain Name — Katı DoH ile çöz; sistem/ISS DNS'ine asla düşme
                    val len = cIn.read()
                    if (len <= 0) return
                    val domainBytes = ByteArray(len)
                    if (!readFully(cIn, domainBytes, len)) return
                    val domain = String(domainBytes, Charsets.UTF_8)
                    try {
                        AndroidDohResolver.resolveHostStrict(domain, dohProviderUrl, protectSocket)
                    } catch (_: Exception) {
                        cOut.write(byteArrayOf(0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                        cOut.flush()
                        return
                    }
                }
                0x04 -> { // IPv6
                    val addr = ByteArray(16)
                    if (!readFully(cIn, addr, 16)) return
                    InetAddress.getByAddress(addr)
                }
                else -> return
            }

            val portBytes = ByteArray(2)
            if (!readFully(cIn, portBytes, 2)) return
            val targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

            // 3. Hedef Sunucuya Bağlan (Yalnızca doğrulanmış InetAddress ile; DNS sorgusuz)
            remote = Socket()
            protectSocket?.invoke(remote)
            remote.tcpNoDelay = true
            remote.connect(InetSocketAddress(targetAddr, targetPort), 10000)
            remote.soTimeout = 0
            client.soTimeout = 0

            // SOCKS5 Başarılı Yanıtı
            cOut.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            cOut.flush()

            val rIn = remote.getInputStream()
            val rOut = remote.getOutputStream()

            // 4. İlk Giden Pakette ByeDPI TLS ClientHello / HTTP Host Parçalama Uygula
            val firstBuf = ByteArray(16384)
            val firstRead = cIn.read(firstBuf)
            if (firstRead > 0) {
                sendFirstPacketWithByeDpiDesync(firstBuf, firstRead, rOut)
                relayedBytes.addAndGet(firstRead.toLong())
            } else {
                return
            }

            // 5. Çift Yönlü Tam Hızlı Veri Aktarımı
            val upJob = scope.launch {
                pipeStream(cIn, rOut)
            }
            pipeStream(rIn, cOut)
            upJob.cancel()
        } catch (_: Exception) {
        } finally {
            try { remote?.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
            activeConnections.decrementAndGet()
            notifyStats()
        }
    }

    /**
     * İlk paketin TLS ClientHello (0x16 0x03 ...) veya HTTP isteği olup olmadığını kontrol eder.
     * TLS ClientHello ise TLS Record Split + TCP Segment Desync uygular.
     */
    private fun sendFirstPacketWithByeDpiDesync(buf: ByteArray, length: Int, out: OutputStream) {
        // TLS Handshake kontrolü: ContentType = 0x16 (22), Version Major = 0x03
        val isTlsClientHello = length > 10 &&
            (buf[0].toInt() and 0xFF) == 0x16 &&
            (buf[1].toInt() and 0xFF) == 0x03

        if (isTlsClientHello) {
            val tlsPayloadLen = ((buf[3].toInt() and 0xFF) shl 8) or (buf[4].toInt() and 0xFF)
            if (mode == ByeDpiMode.TLS_RECORD_SPLIT && length >= 5 + tlsPayloadLen && tlsPayloadLen > 4) {
                // TLS Record Split: Tek TLS kaydını iki geçerli TLS kaydına böl (DPI kutularını %99 şaşırtır)
                val splitPoint = findSniSplitOffset(buf, length).coerceIn(2, tlsPayloadLen - 2)
                val secondLen = tlsPayloadLen - splitPoint

                // 1. Parça TLS Kaydı
                val rec1 = ByteArray(5 + splitPoint)
                rec1[0] = buf[0]
                rec1[1] = buf[1]
                rec1[2] = buf[2]
                rec1[3] = ((splitPoint ushr 8) and 0xFF).toByte()
                rec1[4] = (splitPoint and 0xFF).toByte()
                System.arraycopy(buf, 5, rec1, 5, splitPoint)
                out.write(rec1)
                out.flush()

                // 2. Parça TLS Kaydı
                val rec2 = ByteArray(5 + secondLen)
                rec2[0] = buf[0]
                rec2[1] = buf[1]
                rec2[2] = buf[2]
                rec2[3] = ((secondLen ushr 8) and 0xFF).toByte()
                rec2[4] = (secondLen and 0xFF).toByte()
                System.arraycopy(buf, 5 + splitPoint, rec2, 5, secondLen)
                out.write(rec2)
                out.flush()

                // Pakette ek veri varsa gönder
                val remaining = length - (5 + tlsPayloadLen)
                if (remaining > 0) {
                    out.write(buf, 5 + tlsPayloadLen, remaining)
                    out.flush()
                }
            } else {
                // TCP Segment Desync: İlk birkaç byte'ı ayrı TCP paketi olarak gönder
                val s1 = mode.splitOffset.coerceIn(1, length - 1)
                out.write(buf, 0, s1)
                out.flush()

                val s2 = findSniSplitOffset(buf, length).coerceIn(s1 + 1, length)
                if (s2 > s1 && s2 < length) {
                    out.write(buf, s1, s2 - s1)
                    out.flush()
                    out.write(buf, s2, length - s2)
                    out.flush()
                } else {
                    out.write(buf, s1, length - s1)
                    out.flush()
                }
            }
            fragmentedHandshakes.incrementAndGet()
            notifyStats()
            return
        }

        // Şifresiz HTTP Host başlığı parçalama ("Host: " -> "Ho" + "st: ")
        val asciiPreview = String(buf, 0, minOf(length, 512), Charsets.ISO_8859_1)
        val hostIdx = asciiPreview.indexOf("\r\nHost:", ignoreCase = true)
        if (hostIdx > 0 && hostIdx + 4 < length) {
            val splitAt = hostIdx + 4
            out.write(buf, 0, splitAt)
            out.flush()
            out.write(buf, splitAt, length - splitAt)
            out.flush()
            fragmentedHandshakes.incrementAndGet()
            notifyStats()
            return
        }

        out.write(buf, 0, length)
        out.flush()
    }

    /**
     * TLS ClientHello içindeki SNI alan adının ortasını bulur, bulamazsa güvenli varsayılan ofset döner.
     */
    private fun findSniSplitOffset(buf: ByteArray, length: Int): Int {
        // ASCII alan adı karakter dizisi ara (örn. ".com", ".org", ".net", "youtube", "discord")
        for (i in 40 until (length - 8)) {
            val b0 = buf[i].toInt() and 0xFF
            val b1 = buf[i + 1].toInt() and 0xFF
            val b2 = buf[i + 2].toInt() and 0xFF
            if (b0 == 0x2E && b1 in 0x61..0x7A && b2 in 0x61..0x7A) {
                // TLS başlığı (5 byte) çıkarılmış göreceli pozisyon
                return (i - 5).coerceAtLeast(2)
            }
        }
        return 2
    }

    private fun readFully(input: InputStream, dst: ByteArray, len: Int): Boolean {
        var offset = 0
        while (offset < len) {
            val r = input.read(dst, offset, len - offset)
            if (r <= 0) return false
            offset += r
        }
        return true
    }

    private fun pipeStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(16384)
        try {
            while (running.get()) {
                val n = input.read(buffer)
                if (n <= 0) break
                output.write(buffer, 0, n)
                output.flush()
                relayedBytes.addAndGet(n.toLong())
            }
        } catch (_: Exception) {}
    }
}
