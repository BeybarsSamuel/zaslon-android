package com.zaslon.browser.network

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Faz F: Katı RFC 8484 DNS-over-HTTPS (DoH) Çözümleyici
 *
 * ByeDpiProxyEngine ve diğer ağ katmanları için sistem/ISS UDP DNS çağrılarını
 * (InetAddress.getByName) kesinlikle kullanmadan yalnızca şifreli DoH + önyükleme IP'si
 * üzerinden alan adı çözer. DoH başarısız olursa fail-closed hata fırlatır.
 */
object AndroidDohResolver {

    const val DEFAULT_DOH_URL = "https://dns.quad9.net/dns-query"

    private data class CacheEntry(
        val address: InetAddress,
        val expiresAtMs: Long
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    // Sistem DNS sızıntısını sıfırlamak için bilinen DoH sunucularının önyükleme IPv4 adresleri
    private val BOOTSTRAP_IPV4: Map<String, ByteArray> = mapOf(
        "dns.quad9.net" to byteArrayOf(9, 9, 9, 9),
        "dns9.quad9.net" to byteArrayOf(9, 9, 9, 9),
        "dns11.quad9.net" to byteArrayOf(9, 9, 9, 11),
        "cloudflare-dns.com" to byteArrayOf(1, 1, 1, 1),
        "1dot1dot1dot1.cloudflare-dns.com" to byteArrayOf(1, 1, 1, 1),
        "adblock.dns.mullvad.net" to byteArrayOf(194.toByte(), 242.toByte(), 2, 4),
        "dns.mullvad.net" to byteArrayOf(194.toByte(), 242.toByte(), 2, 2),
        "dns.adguard-dns.com" to byteArrayOf(94.toByte(), 140.toByte(), 14, 14)
    )

    fun bootstrapAddressForUrl(dohUrl: String): String? {
        val host = try {
            URI(dohUrl).host?.lowercase()
        } catch (_: Exception) {
            null
        } ?: return null
        val bytes = BOOTSTRAP_IPV4[host] ?: return null
        return bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
    }

    fun clearCache() {
        cache.clear()
    }

    /**
     * Bir alan adını veya literal IP'yi sistem DNS'ine (getByName) hiç düşmeden çözer.
     * .onion alan adları Tor dışında çözülemez; fail-closed IOException fırlatır.
     */
    @Throws(IOException::class)
    fun resolveHostStrict(
        host: String,
        dohUrl: String = DEFAULT_DOH_URL,
        protectSocket: ((Socket) -> Boolean)? = null,
        transportOverride: ((url: String, queryBytes: ByteArray) -> ByteArray)? = null
    ): InetAddress {
        val cleanHost = host.trim().trimEnd('.').lowercase()
        if (cleanHost.isEmpty()) {
            throw IOException("Empty hostname")
        }

        // Literal IPv4 / IPv6 ise sıfır DNS çağrısıyla doğrudan InetAddress.getByAddress döndür
        parseLiteralIpAddress(cleanHost)?.let { return it }

        if (cleanHost == "localhost") {
            return InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        }

        if (cleanHost == "onion" || cleanHost.endsWith(".onion")) {
            throw IOException("Refusing to resolve .onion domain outside Tor SOCKS5")
        }

        val now = System.currentTimeMillis()
        val cacheKey = "$dohUrl|$cleanHost"
        cache[cacheKey]?.let { entry ->
            if (entry.expiresAtMs > now) {
                return entry.address
            }
            cache.remove(cacheKey)
        }

        val queryPacket = buildDnsQueryPacket(cleanHost, qtype = 1)
        val responseWire = if (transportOverride != null) {
            transportOverride(dohUrl, queryPacket)
        } else {
            executeDohHttpsPost(dohUrl, queryPacket, protectSocket)
        }

        val parsed = parseDnsResponseAddresses(responseWire, expectedQtype = 1)
        val first = parsed.firstOrNull()
            ?: throw IOException("Strict DoH resolved zero A records for $cleanHost")

        val ttlSec = first.second.coerceIn(30, 300)
        cache[cacheKey] = CacheEntry(
            address = first.first,
            expiresAtMs = now + ttlSec * 1000L
        )
        return first.first
    }

    /**
     * Literal IPv4 veya IPv6 dizesini sistem DNS'i çağırmadan byte dizisine çevirir.
     */
    fun parseLiteralIpAddress(host: String): InetAddress? {
        val unbracketed = if (host.startsWith("[") && host.endsWith("]")) {
            host.substring(1, host.length - 1)
        } else {
            host
        }

        // IPv4 kontrolü
        val ipv4Parts = unbracketed.split('.')
        if (ipv4Parts.size == 4 && ipv4Parts.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
            val out = ByteArray(4)
            for (i in 0 until 4) {
                val n = ipv4Parts[i].toIntOrNull() ?: return null
                if (n !in 0..255) return null
                out[i] = n.toByte()
            }
            return InetAddress.getByAddress(out)
        }

        // IPv6 kontrolü
        if (unbracketed.contains(':')) {
            val cleanV6 = unbracketed.substringBefore('%')
            val bytes = parseIpv6Bytes(cleanV6) ?: return null
            return InetAddress.getByAddress(bytes)
        }

        return null
    }

    private fun parseIpv6Bytes(ipv6: String): ByteArray? {
        if (ipv6.isEmpty()) return null
        val halves = ipv6.split("::", limit = 3)
        if (halves.size > 2) return null

        val leftGroups = if (halves[0].isEmpty()) emptyList() else halves[0].split(':')
        val rightGroups = if (halves.size == 2 && halves[1].isNotEmpty()) halves[1].split(':') else emptyList()

        val hasDoubleColon = halves.size == 2
        val totalProvided = leftGroups.size + rightGroups.size
        if (!hasDoubleColon && totalProvided != 8) return null
        if (hasDoubleColon && totalProvided >= 8) return null

        val missing = 8 - totalProvided
        val fullGroups = ArrayList<String>(8)
        fullGroups.addAll(leftGroups)
        repeat(missing) { fullGroups.add("0") }
        fullGroups.addAll(rightGroups)

        val out = ByteArray(16)
        for (i in 0 until 8) {
            val g = fullGroups[i]
            if (g.isEmpty() || g.length > 4) return null
            val v = g.toIntOrNull(16) ?: return null
            out[i * 2] = ((v ushr 8) and 0xFF).toByte()
            out[i * 2 + 1] = (v and 0xFF).toByte()
        }
        return out
    }

    /**
     * RFC 1035 / RFC 8484 DNS wireformat sorgu paketi üretir (QTYPE 1=A, 28=AAAA).
     */
    fun buildDnsQueryPacket(hostname: String, qtype: Int = 1): ByteArray {
        val clean = hostname.trim().trimEnd('.')
        val labels = clean.split('.')
        val out = ByteArrayOutputStream(64)

        // RFC 8484: ID = 0x0000, RD = 1 (0x0100), QDCOUNT = 1
        out.write(
            byteArrayOf(
                0x00, 0x00,
                0x01, 0x00,
                0x00, 0x01,
                0x00, 0x00,
                0x00, 0x00,
                0x00, 0x00
            )
        )

        for (label in labels) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            if (bytes.isEmpty() || bytes.size > 63) {
                throw IllegalArgumentException("Invalid DNS label: $label")
            }
            out.write(bytes.size and 0xFF)
            out.write(bytes)
        }
        out.write(0x00) // Root label

        // QTYPE + QCLASS (IN = 1)
        out.write((qtype ushr 8) and 0xFF)
        out.write(qtype and 0xFF)
        out.write(0x00)
        out.write(0x01)

        return out.toByteArray()
    }

    /**
     * RFC 1035 DNS yanıt paketinden eşleşen IP adreslerini ve TTL değerlerini çözer.
     */
    fun parseDnsResponseAddresses(wire: ByteArray, expectedQtype: Int = 1): List<Pair<InetAddress, Int>> {
        if (wire.size < 12) return emptyList()
        val rcode = wire[3].toInt() and 0x0F
        if (rcode != 0) return emptyList()

        val qdCount = ((wire[4].toInt() and 0xFF) shl 8) or (wire[5].toInt() and 0xFF)
        val anCount = ((wire[6].toInt() and 0xFF) shl 8) or (wire[7].toInt() and 0xFF)

        var pos = 12
        for (i in 0 until qdCount) {
            pos = skipDnsName(wire, pos)
            if (pos < 0 || pos + 4 > wire.size) return emptyList()
            pos += 4 // QTYPE + QCLASS
        }

        val results = mutableListOf<Pair<InetAddress, Int>>()
        for (i in 0 until anCount) {
            pos = skipDnsName(wire, pos)
            if (pos < 0 || pos + 10 > wire.size) break

            val rtype = ((wire[pos].toInt() and 0xFF) shl 8) or (wire[pos + 1].toInt() and 0xFF)
            val ttl = ((wire[pos + 4].toInt() and 0x7F) shl 24) or
                ((wire[pos + 5].toInt() and 0xFF) shl 16) or
                ((wire[pos + 6].toInt() and 0xFF) shl 8) or
                (wire[pos + 7].toInt() and 0xFF)
            val rdLength = ((wire[pos + 8].toInt() and 0xFF) shl 8) or (wire[pos + 9].toInt() and 0xFF)
            pos += 10

            if (pos + rdLength > wire.size) break

            if (rtype == expectedQtype) {
                if (rtype == 1 && rdLength == 4) {
                    val addr = InetAddress.getByAddress(wire.copyOfRange(pos, pos + 4))
                    results.add(addr to ttl)
                } else if (rtype == 28 && rdLength == 16) {
                    val addr = InetAddress.getByAddress(wire.copyOfRange(pos, pos + 16))
                    results.add(addr to ttl)
                }
            }
            pos += rdLength
        }
        return results
    }

    private fun skipDnsName(wire: ByteArray, start: Int): Int {
        var pos = start
        var steps = 0
        while (pos < wire.size && steps < 128) {
            steps++
            val len = wire[pos].toInt() and 0xFF
            if (len == 0) {
                return pos + 1
            }
            if ((len and 0xC0) == 0xC0) {
                return if (pos + 2 <= wire.size) pos + 2 else -1
            }
            pos += 1 + len
        }
        return -1
    }

    @Throws(IOException::class)
    private fun executeDohHttpsPost(
        dohUrl: String,
        queryPacket: ByteArray,
        protectSocket: ((Socket) -> Boolean)?
    ): ByteArray {
        val uri = try {
            URI(dohUrl)
        } catch (e: Exception) {
            throw IOException("Invalid DoH URL: $dohUrl", e)
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            throw IOException("DoH endpoint must use HTTPS")
        }

        val dohHost = uri.host?.trim()?.lowercase()
            ?: throw IOException("Missing DoH host")
        val port = if (uri.port > 0) uri.port else 443
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/dns-query"
        val pathWithQuery = if (uri.rawQuery.isNullOrEmpty()) path else "$path?${uri.rawQuery}"

        val bootstrapIp = parseLiteralIpAddress(dohHost)
            ?: BOOTSTRAP_IPV4[dohHost]?.let { InetAddress.getByAddress(it) }
            ?: throw IOException("No bootstrap IP for DoH host $dohHost (refusing ISP DNS lookup)")

        val rawSocket = Socket()
        protectSocket?.invoke(rawSocket)
        rawSocket.connect(InetSocketAddress(bootstrapIp, port), 5000)

        val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val sslSocket = sslFactory.createSocket(rawSocket, dohHost, port, true) as SSLSocket
        sslSocket.use { sock ->
            sock.soTimeout = 5000
            val params = sock.sslParameters
            params.serverNames = listOf(SNIHostName(dohHost))
            params.endpointIdentificationAlgorithm = "HTTPS"
            sock.sslParameters = params
            sock.startHandshake()

            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(dohHost, sock.session)) {
                throw IOException("TLS hostname verification failed for DoH host $dohHost")
            }

            val reqHeaders = buildString {
                append("POST ").append(pathWithQuery).append(" HTTP/1.1\r\n")
                append("Host: ").append(dohHost).append("\r\n")
                append("Accept: application/dns-message\r\n")
                append("Content-Type: application/dns-message\r\n")
                append("Content-Length: ").append(queryPacket.size).append("\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(Charsets.US_ASCII)

            val out = sock.outputStream
            out.write(reqHeaders)
            out.write(queryPacket)
            out.flush()

            return readHttpResponseBody(sock.inputStream)
        }
    }

    @Throws(IOException::class)
    private fun readHttpResponseBody(input: InputStream): ByteArray {
        val headerBuf = ByteArrayOutputStream()
        var matched = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("Unexpected EOF reading DoH HTTP headers")
            headerBuf.write(b)
            matched = when {
                (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                matched == 1 && b == '\n'.code -> 2
                matched == 3 && b == '\n'.code -> 4
                b == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) break
            if (headerBuf.size() > 16384) throw IOException("DoH response headers too large")
        }

        val headerStr = String(headerBuf.toByteArray(), Charsets.ISO_8859_1)
        val lines = headerStr.split("\r\n")
        val statusCode = lines.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: 0
        if (statusCode != 200) {
            throw IOException("DoH HTTP error status $statusCode")
        }

        var contentLength = -1
        var isChunked = false
        for (line in lines.drop(1)) {
            val idx = line.indexOf(':')
            if (idx > 0) {
                val hName = line.substring(0, idx).trim()
                val hVal = line.substring(idx + 1).trim()
                if (hName.equals("Content-Length", ignoreCase = true)) {
                    contentLength = hVal.toIntOrNull() ?: -1
                } else if (hName.equals("Transfer-Encoding", ignoreCase = true) &&
                    hVal.contains("chunked", ignoreCase = true)
                ) {
                    isChunked = true
                }
            }
        }

        return when {
            isChunked -> readChunkedHttpBody(input)
            contentLength >= 0 -> {
                val body = ByteArray(contentLength)
                var off = 0
                while (off < contentLength) {
                    val r = input.read(body, off, contentLength - off)
                    if (r < 0) throw EOFException("Truncated DoH body")
                    off += r
                }
                body
            }
            else -> input.readBytes()
        }
    }

    @Throws(IOException::class)
    private fun readChunkedHttpBody(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readAsciiLine(input) ?: throw EOFException("Unexpected EOF in chunk size")
            val chunkSize = sizeLine.substringBefore(';').trim().toIntOrNull(16)
                ?: throw IOException("Invalid chunk size: $sizeLine")
            if (chunkSize == 0) break
            val buf = ByteArray(chunkSize)
            var off = 0
            while (off < chunkSize) {
                val r = input.read(buf, off, chunkSize - off)
                if (r < 0) throw EOFException("Truncated chunk data")
                off += r
            }
            out.write(buf)
            // Consume trailing CRLF after chunk payload
            readAsciiLine(input)
        }
        return out.toByteArray()
    }

    @Throws(IOException::class)
    private fun readAsciiLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') {
                    sb.setLength(sb.length - 1)
                }
                return sb.toString()
            }
            sb.append(b.toChar())
            if (sb.length > 4096) throw IOException("Line too long")
        }
    }
}
