package com.dmujeres.traccar.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {

    @Test
    fun olderSeesNewer() {
        assertTrue(UpdateManager.isNewer("1.0.45", "1.0.46"))
        assertTrue(UpdateManager.isNewer("1.0.45", "1.0.47"))
    }

    @Test
    fun sameOrNewerInstalledSeesNothing() {
        assertFalse(UpdateManager.isNewer("1.0.46", "1.0.46"))
        assertFalse(UpdateManager.isNewer("1.0.46", "1.0.45"))
        assertFalse(UpdateManager.isNewer("1.0.47", "1.0.46"))
    }

    @Test
    fun numericNotLexicographic() {
        // "1.0.9" < "1.0.17" aunque '9' > '1' como texto
        assertTrue(UpdateManager.isNewer("1.0.9", "1.0.17"))
        assertFalse(UpdateManager.isNewer("1.0.17", "1.0.9"))
    }

    @Test
    fun toleratesSuffixesAndSpaces() {
        assertTrue(UpdateManager.isNewer("1.0.45", " 1.0.46 "))
        assertFalse(UpdateManager.isNewer("1.0.46-beta", "1.0.46-beta"))
    }

    @Test
    fun githubReleaseParsesTagAndApk() {
        val parsed = UpdateManager.parseGithubRelease(
            tagName = "v1.0.47",
            assetUrls = listOf(
                "https://example.com/app.zip",
                "https://example.com/DMujeres-Tracking-1.0.47.apk",
            ),
            notes = "notas",
        )
        assertEquals("1.0.47", parsed?.version)
        assertEquals("https://example.com/DMujeres-Tracking-1.0.47.apk", parsed?.url)
        assertTrue(UpdateManager.isNewer("1.0.45", parsed!!.version))
    }

    @Test
    fun githubReleaseWithoutApkIsNull() {
        assertNull(
            UpdateManager.parseGithubRelease(
                tagName = "v1.0.47",
                assetUrls = listOf("https://example.com/app.zip"),
                notes = null,
            ),
        )
        assertNull(UpdateManager.parseGithubRelease("", emptyList(), null))
    }
}
