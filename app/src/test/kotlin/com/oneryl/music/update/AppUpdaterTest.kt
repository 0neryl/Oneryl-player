package com.oneryl.music.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {

    @Test
    fun versionsCompareByTheirNumbers() {
        assertTrue(AppUpdater.isNewer("0.8.0", "0.7.0"))
        assertTrue(AppUpdater.isNewer("0.10.0", "0.9.1"))
        assertTrue(AppUpdater.isNewer("1.0", "0.99.99"))
        assertTrue(AppUpdater.isNewer("v0.7.1", "0.7.0"))
        assertFalse(AppUpdater.isNewer("0.7.0", "0.7.0"))
        assertFalse(AppUpdater.isNewer("0.7.0", "0.8.0"))
        assertFalse("a test build newer than the release", AppUpdater.isNewer("0.7.0", "0.7.0.1"))
    }

    @Test
    fun theLatestReleaseGivesItsApkAndChecksum() {
        val release = AppUpdater.parseRelease(
            """
            {
              "tag_name": "v0.7.0",
              "draft": false,
              "prerelease": false,
              "body": "notes",
              "assets": [
                {"name": "notes.txt", "browser_download_url": "https://example.com/notes.txt", "size": 5},
                {
                  "name": "Oneryl-0.7.0.apk",
                  "browser_download_url": "https://github.com/0neryl/Oneryl-player/releases/download/v0.7.0/Oneryl-0.7.0.apk",
                  "size": 30864041,
                  "digest": "sha256:4c34cad5ea940a41c4053236832a52226a56455eea003a8e6283826b8b9ea969"
                }
              ]
            }
            """.trimIndent(),
        )
        assertEquals(
            Release(
                version = "0.7.0",
                apkUrl = "https://github.com/0neryl/Oneryl-player/releases/download/v0.7.0/Oneryl-0.7.0.apk",
                sizeBytes = 30864041,
                sha256 = "4c34cad5ea940a41c4053236832a52226a56455eea003a8e6283826b8b9ea969",
            ),
            release,
        )
    }

    @Test
    fun aReleaseWithoutAnApkOrNotFinalIsNoUpdate() {
        assertNull(AppUpdater.parseRelease("""{"tag_name": "v0.7.0", "assets": []}"""))
        assertNull(
            AppUpdater.parseRelease(
                """{"tag_name": "v0.8.0", "prerelease": true, "assets": [{"name": "a.apk", "browser_download_url": "u"}]}""",
            ),
        )
        val noDigest = AppUpdater.parseRelease("""{"tag_name": "v0.8.0", "assets": [{"name": "a.apk", "browser_download_url": "u"}]}""")
        assertNull("older uploads have no checksum", noDigest?.sha256)
    }
}
