package com.zaslon.browser.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

data class SponsorSegment(
    val start: Double,
    val end: Double,
    val category: String,
    val uuid: String
)

/**
 * Faz 6.4: YouTube SponsorBlock SHA-256 K-Anonimlik İstemcisi
 *
 * İzlenen videonun tam kimliğini (videoID) sunucuya göndermek yerine SHA-256 özetinin
 * yalnızca ilk 4 karakterini göndererek gizlilikten ödün vermeden sponsorlu bölümleri çeker.
 */
object SponsorBlockClient {

    fun sha256HexPrefix(input: String, length: Int = 4): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return hex.take(length.coerceIn(4, 32))
    }

    suspend fun fetchSegments(
        videoId: String,
        categories: List<String> = listOf("sponsor", "selfpromo", "interaction", "intro", "outro"),
        torRequired: Boolean = false,
        torSocksPort: Int? = null
    ): List<SponsorSegment> = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext emptyList()
        if (torRequired && (torSocksPort == null || torSocksPort <= 0)) {
            return@withContext emptyList()
        }
        try {
            val prefix = sha256HexPrefix(videoId, 4)
            val catsJson = JSONArray(categories).toString()
            val encodedCats = URLEncoder.encode(catsJson, "UTF-8")
            val endpoint = URL("https://sponsor.ajay.app/api/skipSegments/$prefix?categories=$encodedCats")

            val conn = if (torSocksPort != null && torSocksPort > 0) {
                val socksProxy = Proxy(
                    Proxy.Type.SOCKS,
                    InetSocketAddress.createUnresolved("127.0.0.1", torSocksPort)
                )
                endpoint.openConnection(socksProxy) as HttpURLConnection
            } else {
                endpoint.openConnection() as HttpURLConnection
            }.apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Language", "en-US,en;q=0.5")
                setRequestProperty("Sec-GPC", "1")
            }

            if (conn.responseCode != 200) return@withContext emptyList()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(body)
            val result = mutableListOf<SponsorSegment>()

            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                if (item.optString("videoID") == videoId) {
                    val segs = item.optJSONArray("segments") ?: continue
                    for (j in 0 until segs.length()) {
                        val s = segs.getJSONObject(j)
                        val pair = s.optJSONArray("segment") ?: continue
                        if (pair.length() >= 2) {
                            result.add(
                                SponsorSegment(
                                    start = pair.getDouble(0),
                                    end = pair.getDouble(1),
                                    category = s.optString("category", "sponsor"),
                                    uuid = s.optString("UUID", "")
                                )
                            )
                        }
                    }
                }
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }
}
