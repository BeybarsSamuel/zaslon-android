package com.zaslon.browser.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubAndroidUpdaterTest {

    @Test
    fun `normalizeRepo parses github urls and rejects invalid input`() {
        assertEquals("Samil/Zaslon-Browser", GitHubAndroidUpdater.normalizeRepo("https://github.com/Samil/Zaslon-Browser.git"))
        assertEquals("Samil/Zaslon-Browser", GitHubAndroidUpdater.normalizeRepo("Samil/Zaslon-Browser/"))
        assertEquals("", GitHubAndroidUpdater.normalizeRepo("only-owner"))
    }

    @Test
    fun `compareSemver handles android-v prefix and prerelease suffixes`() {
        assertEquals(1, GitHubAndroidUpdater.compareSemver("android-v2.1.0", "2.0.0-zaslon"))
        assertEquals(0, GitHubAndroidUpdater.compareSemver("v2.0.0", "2.0.0-zaslon"))
        assertEquals(-1, GitHubAndroidUpdater.compareSemver("1.9.9", "2.0.0-zaslon"))
    }

    @Test
    fun `selectBestApkAsset matches device ABI before universal fallback`() {
        val assets = listOf(
            GitHubReleaseAsset(1, "app-universal-release.apk", 100, "", ""),
            GitHubReleaseAsset(2, "app-arm64-v8a-release.apk", 60, "", ""),
            GitHubReleaseAsset(3, "app-x86_64-release.apk", 70, "", "")
        )
        assertEquals("app-arm64-v8a-release.apk", GitHubAndroidUpdater.selectBestApkAsset(assets, arrayOf("arm64-v8a", "armeabi-v7a"))?.name)
        assertEquals("app-universal-release.apk", GitHubAndroidUpdater.selectBestApkAsset(assets, arrayOf("armeabi-v7a"))?.name)
        assertNull(GitHubAndroidUpdater.selectBestApkAsset(emptyList(), arrayOf("arm64-v8a")))
    }
}
