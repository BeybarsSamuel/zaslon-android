package com.zaslon.browser.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.zaslon.browser.i18n.ZaslonLanguageStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class MobileBookmark(
    val title: String,
    val url: String,
    val addedAt: Long = System.currentTimeMillis()
)

data class MobileHistoryItem(
    val title: String,
    val url: String,
    val visitedAt: Long = System.currentTimeMillis()
)

data class VaultSnapshot(
    val bookmarks: List<MobileBookmark> = emptyList(),
    val history: List<MobileHistoryItem> = emptyList(),
    val notes: String = "",
    val rssFeeds: List<String> = emptyList(),
    val adsBlocked: Long = 0,
    val trackersBlocked: Long = 0,
    val sponsorsSkipped: Long = 0,
    val urlsCleaned: Long = 0,
    val githubRepo: String = "",
    val githubToken: String = ""
)

/**
 * Faz 6: Android Keystore + AES-256-GCM Şifreli Yerel Kasa
 *
 * Yer imlerini, geçmişi, özel notları ve kalkan istatistiklerini düz metin yerine
 * donanım destekli AndroidKeyStore (AES-256-GCM, 12-byte IV, 128-bit AuthTag) ile
 * şifreleyerek yerel diskte saklar. 🔥 Panik Yakıcı tetiklendiğinde dosyayı rastgele
 * baytlarla ezerek (Forensic Wipe) kalıntısız imha eder.
 */
class EncryptedMobileVault(private val context: Context) {

    private val vaultFile = File(context.filesDir, "zaslon_mobile_vault.enc")
    private val secureRandom = SecureRandom()

    @Synchronized
    fun loadSnapshot(masterPassword: String? = null): VaultSnapshot {
        if (!vaultFile.exists()) return defaultSnapshot()
        return try {
            val rawText = vaultFile.readText(Charsets.UTF_8)
            val env = JSONObject(rawText)
            val iv = Base64.decode(env.getString("iv"), Base64.NO_WRAP)
            val ct = Base64.decode(env.getString("ciphertext"), Base64.NO_WRAP)

            val secretKey: SecretKey = if (env.optBoolean("passwordProtected", false) && !masterPassword.isNullOrEmpty()) {
                val salt = Base64.decode(env.getString("salt"), Base64.NO_WRAP)
                deriveKeyFromPassword(masterPassword, salt)
            } else {
                getOrCreateHardwareKey()
            }

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            val plainBytes = cipher.doFinal(ct)
            parseSnapshotJson(JSONObject(String(plainBytes, Charsets.UTF_8)))
        } catch (_: Exception) {
            defaultSnapshot()
        }
    }

    @Synchronized
    fun saveSnapshot(snapshot: VaultSnapshot, masterPassword: String? = null): Boolean {
        return try {
            val plainJson = serializeSnapshotJson(snapshot).toString().toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val env = JSONObject()

            if (!masterPassword.isNullOrEmpty()) {
                val salt = ByteArray(16).also { secureRandom.nextBytes(it) }
                val iv = ByteArray(12).also { secureRandom.nextBytes(it) }
                val key = deriveKeyFromPassword(masterPassword, salt)
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
                val ct = cipher.doFinal(plainJson)

                env.put("passwordProtected", true)
                env.put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                env.put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                env.put("ciphertext", Base64.encodeToString(ct, Base64.NO_WRAP))
            } else {
                val key = getOrCreateHardwareKey()
                cipher.init(Cipher.ENCRYPT_MODE, key)
                val ct = cipher.doFinal(plainJson)

                env.put("passwordProtected", false)
                env.put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                env.put("ciphertext", Base64.encodeToString(ct, Base64.NO_WRAP))
            }

            env.put("cipher", "AES-256-GCM")
            env.put("updatedAt", System.currentTimeMillis())
            vaultFile.writeText(env.toString(), Charsets.UTF_8)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 🔥 Forensic Panik İmha: Kasa dosyasının üzerine kriptografik rastgele veri yazar ve siler.
     */
    @Synchronized
    fun panicWipeVault() {
        try {
            if (vaultFile.exists()) {
                val len = vaultFile.length().coerceIn(512L, 1024 * 1024L).toInt()
                val noise = ByteArray(len).also { secureRandom.nextBytes(it) }
                vaultFile.writeBytes(noise)
                vaultFile.delete()
            }
        } catch (_: Exception) {}
    }

    private fun getOrCreateHardwareKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun deriveKeyFromPassword(password: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, 65536, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val raw = factory.generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    private fun defaultSnapshot(): VaultSnapshot {
        val s = ZaslonLanguageStore.strings()
        return VaultSnapshot(
            bookmarks = listOf(
                MobileBookmark("DuckDuckGo", "https://duckduckgo.com"),
                MobileBookmark("Brave Search", "https://search.brave.com"),
                MobileBookmark("Wikipedia", "https://${s.wikipediaHost}"),
                MobileBookmark(s.youtubeAdFreeLabel, "https://m.youtube.com")
            )
        )
    }

    private fun parseSnapshotJson(obj: JSONObject): VaultSnapshot {
        val defaultBookmarkTitle = ZaslonLanguageStore.strings().bookmarkDefaultTitle
        val bms = mutableListOf<MobileBookmark>()
        obj.optJSONArray("bookmarks")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                bms.add(
                    MobileBookmark(
                        title = o.optString("title", defaultBookmarkTitle),
                        url = o.optString("url", ""),
                        addedAt = o.optLong("addedAt", System.currentTimeMillis())
                    )
                )
            }
        }

        val hist = mutableListOf<MobileHistoryItem>()
        obj.optJSONArray("history")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                hist.add(
                    MobileHistoryItem(
                        title = o.optString("title", ""),
                        url = o.optString("url", ""),
                        visitedAt = o.optLong("visitedAt", System.currentTimeMillis())
                    )
                )
            }
        }

        val rss = mutableListOf<String>()
        obj.optJSONArray("rssFeeds")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "")
                if (s.isNotBlank()) rss.add(s)
            }
        }

        return VaultSnapshot(
            bookmarks = bms.ifEmpty { defaultSnapshot().bookmarks },
            history = hist,
            notes = obj.optString("notes", ""),
            rssFeeds = rss,
            adsBlocked = obj.optLong("adsBlocked", 0L),
            trackersBlocked = obj.optLong("trackersBlocked", 0L),
            sponsorsSkipped = obj.optLong("sponsorsSkipped", 0L),
            urlsCleaned = obj.optLong("urlsCleaned", 0L),
            githubRepo = obj.optString("githubRepo", ""),
            githubToken = obj.optString("githubToken", "")
        )
    }

    private fun serializeSnapshotJson(s: VaultSnapshot): JSONObject {
        val obj = JSONObject()
        val bmArr = JSONArray()
        s.bookmarks.forEach { b ->
            bmArr.put(
                JSONObject().apply {
                    put("title", b.title)
                    put("url", b.url)
                    put("addedAt", b.addedAt)
                }
            )
        }
        val histArr = JSONArray()
        s.history.take(300).forEach { h ->
            histArr.put(
                JSONObject().apply {
                    put("title", h.title)
                    put("url", h.url)
                    put("visitedAt", h.visitedAt)
                }
            )
        }
        val rssArr = JSONArray()
        s.rssFeeds.forEach { rssArr.put(it) }

        obj.put("bookmarks", bmArr)
        obj.put("history", histArr)
        obj.put("notes", s.notes)
        obj.put("rssFeeds", rssArr)
        obj.put("adsBlocked", s.adsBlocked)
        obj.put("trackersBlocked", s.trackersBlocked)
        obj.put("sponsorsSkipped", s.sponsorsSkipped)
        obj.put("urlsCleaned", s.urlsCleaned)
        obj.put("githubRepo", s.githubRepo)
        obj.put("githubToken", s.githubToken)
        return obj
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "zaslon_mobile_vault_aes256_key"
    }
}
