package com.zaslon.browser.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class GitHubReleaseAsset(
    val id: Long,
    val name: String,
    val size: Long,
    val apiUrl: String,
    val browserDownloadUrl: String
)

data class AndroidReleaseInfo(
    val version: String,
    val tagName: String,
    val name: String,
    val notes: String,
    val apkAsset: GitHubReleaseAsset?,
    val checksumAsset: GitHubReleaseAsset?
)

enum class AndroidUpdatePhase {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    AVAILABLE,
    DOWNLOADING,
    DOWNLOADED,
    ERROR
}

data class AndroidUpdateState(
    val repo: String = "",
    val hasToken: Boolean = false,
    val phase: AndroidUpdatePhase = AndroidUpdatePhase.IDLE,
    val latestRelease: AndroidReleaseInfo? = null,
    val progressPct: Int = 0,
    val downloadedApkPath: String = "",
    val errorMessage: String = ""
)

sealed class ApkInstallResult {
    data object LaunchedInstaller : ApkInstallResult()
    data object NeedsInstallPermission : ApkInstallResult()
    data class Error(val message: String) : ApkInstallResult()
}

/**
 * Zaslon Browser Android — İsteğe Bağlı Manuel GitHub Releases Güncelleyici
 *
 * - Başlangıçta veya arka planda otomatik istek ATMAZ; yalnızca kullanıcı
 *   "Güncellemeleri Denetle" butonuna bastığında çalışır.
 * - Hem Public hem Private (PAT Token ile) GitHub depolarını destekler.
 * - Cihazın CPU mimarisine (arm64-v8a / armeabi-v7a / x86_64 / universal) en uygun APK'yı seçer.
 * - Varsa SHA256SUMS / .sha256 dosyasıyla bütünlük doğrulaması yapar.
 */
object GitHubAndroidUpdater {

    private const val GITHUB_API_BASE = "https://api.github.com"
    private val REPO_REGEX = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

    fun normalizeRepo(raw: String?): String {
        var s = (raw ?: "").trim()
        if (s.isEmpty()) return ""
        s = s.replace(Regex("^https?://(www\\.)?github\\.com/", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("\\.git$", RegexOption.IGNORE_CASE), "")
        s = s.trim('/')
        val parts = s.split('/').filter { it.isNotEmpty() }
        if (parts.size >= 2) {
            s = "${parts[0]}/${parts[1]}"
        }
        return if (REPO_REGEX.matches(s)) s else ""
    }

    fun compareSemver(aRaw: String, bRaw: String): Int {
        val pa = parseSemverParts(aRaw)
        val pb = parseSemverParts(bRaw)
        val len = maxOf(pa.size, pb.size)
        for (i in 0 until len) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va > vb) return 1
            if (va < vb) return -1
        }
        return 0
    }

    private fun parseSemverParts(raw: String): List<Int> {
        val cleaned = raw.trim()
            .replace(Regex("^(android-|mobile-)?v", RegexOption.IGNORE_CASE), "")
        val core = cleaned.split('-', '+').firstOrNull().orEmpty()
        val nums = core.split('.').map { it.toIntOrNull()?.coerceAtLeast(0) ?: 0 }.toMutableList()
        while (nums.size < 3) nums.add(0)
        return nums.take(4)
    }

    fun selectBestApkAsset(
        assets: List<GitHubReleaseAsset>,
        supportedAbis: Array<String> = Build.SUPPORTED_ABIS
    ): GitHubReleaseAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) return null

        for (abi in supportedAbis) {
            val abiLower = abi.lowercase()
            val match = apks.firstOrNull { it.name.lowercase().contains(abiLower) }
            if (match != null) return match
        }

        return apks.firstOrNull { it.name.contains("universal", ignoreCase = true) }
            ?: apks.firstOrNull { it.name.contains("release", ignoreCase = true) }
            ?: apks.firstOrNull()
    }

    fun selectChecksumAsset(
        assets: List<GitHubReleaseAsset>,
        apkName: String
    ): GitHubReleaseAsset? {
        val lower = apkName.lowercase()
        return assets.firstOrNull { it.name.lowercase() == "$lower.sha256" }
            ?: assets.firstOrNull {
                it.name.matches(Regex("^sha2-?256sums(-android)?(\\.txt)?$", RegexOption.IGNORE_CASE))
            }
            ?: assets.firstOrNull {
                it.name.contains("sha256", ignoreCase = true) &&
                    (it.name.endsWith(".txt", ignoreCase = true) || it.name.endsWith(".sha256", ignoreCase = true))
            }
    }

    fun parseSha256Text(sumsText: String, targetFileName: String): String {
        val target = File(targetFileName).name.lowercase()
        for (line in sumsText.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val m = Regex("^([a-fA-F0-9]{64})(?:\\s+\\*?(.+))?$").matchEntire(trimmed) ?: continue
            val hash = m.groupValues[1].lowercase()
            val fname = m.groupValues[2].trim()
            if (fname.isEmpty() || File(fname).name.lowercase() == target) {
                return hash
            }
        }
        return ""
    }

    suspend fun checkForUpdates(
        repoRaw: String,
        tokenRaw: String,
        currentVersionName: String
    ): Result<Pair<Boolean, AndroidReleaseInfo?>> = withContext(Dispatchers.IO) {
        val repo = normalizeRepo(repoRaw)
        if (repo.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("INVALID_REPO"))
        }
        val token = tokenRaw.trim()

        var conn: HttpURLConnection? = null
        try {
            val url = URL("$GITHUB_API_BASE/repos/$repo/releases?per_page=15")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "Zaslon-Android-Updater")
                if (token.isNotEmpty()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                return@withContext Result.failure(IllegalStateException("HTTP_$code"))
            }

            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(body)

            var chosenObj: JSONObject? = null
            var chosenAssets: List<GitHubReleaseAsset> = emptyList()
            var chosenApk: GitHubReleaseAsset? = null

            for (i in 0 until arr.length()) {
                val rel = arr.optJSONObject(i) ?: continue
                if (rel.optBoolean("draft", false) || rel.optBoolean("prerelease", false)) continue
                val assets = parseAssets(rel.optJSONArray("assets"))
                val apk = selectBestApkAsset(assets)
                if (apk != null) {
                    chosenObj = rel
                    chosenAssets = assets
                    chosenApk = apk
                    break
                }
            }

            if (chosenObj == null || chosenApk == null) {
                return@withContext Result.success(false to null)
            }

            val rawTag = chosenObj.optString("tag_name", chosenObj.optString("name", "")).trim()
            val latestVer = rawTag.replace(Regex("^(android-|mobile-)?v", RegexOption.IGNORE_CASE), "")
            val hasUpdate = compareSemver(latestVer, currentVersionName) > 0
            val checksumAsset = selectChecksumAsset(chosenAssets, chosenApk.name)

            val info = AndroidReleaseInfo(
                version = latestVer.ifEmpty { rawTag },
                tagName = rawTag,
                name = chosenObj.optString("name", rawTag),
                notes = chosenObj.optString("body", ""),
                apkAsset = chosenApk,
                checksumAsset = checksumAsset
            )

            Result.success(hasUpdate to info)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }

    suspend fun downloadApk(
        context: Context,
        release: AndroidReleaseInfo,
        tokenRaw: String,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val asset = release.apkAsset ?: return@withContext Result.failure(IllegalStateException("NO_APK_ASSET"))
        val token = tokenRaw.trim()

        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val safeName = File(asset.name).name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "zaslon-update.apk" }
        val outFile = File(updatesDir, safeName)
        val tmpFile = File(updatesDir, "$safeName.part")

        try {
            var expectedSha256 = ""
            if (release.checksumAsset != null) {
                val sumText = downloadTextWithRedirect(
                    rawUrl = release.checksumAsset.apiUrl.ifEmpty { release.checksumAsset.browserDownloadUrl },
                    accept = if (release.checksumAsset.apiUrl.isNotEmpty()) "application/octet-stream" else "*/*",
                    token = token
                )
                expectedSha256 = parseSha256Text(sumText, asset.name)
            }

            val targetUrl = asset.apiUrl.ifEmpty { asset.browserDownloadUrl }
            val conn = openFollowingRedirects(
                rawUrl = targetUrl,
                accept = if (asset.apiUrl.isNotEmpty()) "application/octet-stream" else "*/*",
                token = token
            )

            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    return@withContext Result.failure(IllegalStateException("HTTP_$code"))
                }

                val totalBytes = conn.contentLengthLong.takeIf { it > 0 } ?: asset.size
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                val buf = ByteArray(16 * 1024)

                conn.inputStream.use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            digest.update(buf, 0, read)
                            output.write(buf, 0, read)
                            received += read
                            if (totalBytes > 0) {
                                val pct = ((received * 100) / totalBytes).toInt().coerceIn(0, 99)
                                onProgress(pct)
                            }
                        }
                    }
                }

                val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                if (expectedSha256.isNotEmpty() && actualSha != expectedSha256) {
                    tmpFile.delete()
                    return@withContext Result.failure(SecurityException("CHECKSUM_MISMATCH"))
                }

                if (outFile.exists()) outFile.delete()
                if (!tmpFile.renameTo(outFile)) {
                    tmpFile.copyTo(outFile, overwrite = true)
                    tmpFile.delete()
                }
                onProgress(100)
                Result.success(outFile)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            tmpFile.delete()
            Result.failure(e)
        }
    }

    fun installApk(context: Context, apkFile: File): ApkInstallResult {
        if (!apkFile.exists()) {
            return ApkInstallResult.Error("APK file missing")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val permIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(permIntent)
            return ApkInstallResult.NeedsInstallPermission
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
        return ApkInstallResult.LaunchedInstaller
    }

    private fun parseAssets(arr: JSONArray?): List<GitHubReleaseAsset> {
        if (arr == null) return emptyList()
        val list = mutableListOf<GitHubReleaseAsset>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(
                GitHubReleaseAsset(
                    id = o.optLong("id", 0L),
                    name = o.optString("name", ""),
                    size = o.optLong("size", 0L),
                    apiUrl = o.optString("url", ""),
                    browserDownloadUrl = o.optString("browser_download_url", "")
                )
            )
        }
        return list
    }

    private fun downloadTextWithRedirect(rawUrl: String, accept: String, token: String): String {
        val conn = openFollowingRedirects(rawUrl, accept, token)
        return try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP_${conn.responseCode}")
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * GitHub Private Repo varlıklarında api.github.com -> 302 -> objects.githubusercontent.com yönlendirmesi
     * yapılır. Yönlendirme sonrası Authorization başlığı S3'e gönderilirse 400 hatası verir; bu nedenle
     * yalnızca api.github.com üzerinde Bearer token gönderilir.
     */
    private fun openFollowingRedirects(rawUrl: String, accept: String, token: String): HttpURLConnection {
        var currentUrl = rawUrl
        var redirects = 0
        while (redirects < 6) {
            val u = URL(currentUrl)
            val conn = (u.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "GET"
                connectTimeout = 20000
                readTimeout = 45000
                setRequestProperty("Accept", accept)
                setRequestProperty("User-Agent", "Zaslon-Android-Updater")
                if (token.isNotEmpty() && u.host.equals("api.github.com", ignoreCase = true)) {
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                }
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) throw IllegalStateException("REDIRECT_WITHOUT_LOCATION")
                currentUrl = URL(u, loc).toString()
                redirects++
                continue
            }
            return conn
        }
        throw IllegalStateException("TOO_MANY_REDIRECTS")
    }
}
