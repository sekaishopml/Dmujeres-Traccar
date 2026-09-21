package com.dmujeres.traccar.platform

import com.dmujeres.traccar.BuildConfig
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

    // ─── R3.5-14: verificación de checksum del APK ───

    @Test
    fun githubReleaseCarriesSha256Digest() {
        val parsed = UpdateManager.parseGithubRelease(
            tagName = "v1.1.5",
            assetUrls = listOf("https://example.com/DMujeres-Tracking-1.1.5.apk"),
            notes = null,
            sha256 = "abc123",
        )
        assertEquals("abc123", parsed?.sha256)
        assertNull(UpdateManager.parseGithubRelease("v1.1.5", listOf("https://example.com/x.apk"), null)?.sha256)
    }

    @Test
    fun apkSha256MatchesWhenNoExpectation() {
        // latest.json viejo (sin sha256): se instala igual que antes.
        val f = java.io.File.createTempFile("upd", ".apk", java.io.File("/tmp"))
        try {
            f.writeText("contenido de prueba")
            assertTrue(UpdateManager.apkSha256Matches(f, null))
            assertTrue(UpdateManager.apkSha256Matches(f, " "))
        } finally {
            f.delete()
        }
    }

    @Test
    fun apkSha256MatchesVerifiesContent() {
        val f = java.io.File.createTempFile("apk", ".bin", java.io.File("/tmp"))
        try {
            f.writeBytes(ByteArray(2048) { it.toByte() })
            val real = UpdateManager.sha256Hex(f)
            assertTrue(real.matches(Regex("[0-9a-f]{64}")))
            assertTrue(UpdateManager.apkSha256Matches(f, real))
            assertTrue(UpdateManager.apkSha256Matches(f, real.uppercase())) // case-insensitive
            assertFalse(UpdateManager.apkSha256Matches(f, "de" + real.drop(2)))
        } finally {
            f.delete()
        }
    }

    // ─── R8: endpoint de rollout OTA (/api/mobile/v1/ota) ───

    @Test
    fun rolloutDeniedMeansNoUpdateWithoutError() {
        // El servidor respondió {"update": false}: sin update y sin tocar el
        // error de la última comprobación (parseOtaResponse es puro).
        val parsed = UpdateManager.parseOtaResponse("""{"update": false}""", installedCode = 123)
        assertEquals(BuildConfig.VERSION_NAME, parsed?.version)
        assertEquals("", parsed?.url)
        assertEquals(123, parsed?.versionCode)
        // La política de versión de la app lo clasifica como al día.
        assertEquals(
            OtaVersionPolicy.Decision.UP_TO_DATE,
            OtaVersionPolicy.decide(123, parsed!!.versionCode, parsed.version, BuildConfig.VERSION_NAME),
        )
    }

    @Test
    fun rolloutMetadataParsesLikeLatestJson() {
        val parsed = UpdateManager.parseOtaResponse(
            """
            {
              "version": "1.1.14",
              "versionCode": 124,
              "url": "https://ota.example.com/DMujeres-Tracking-1.1.14.apk",
              "notes": "notas",
              "sha256": "abc123",
              "minVersionCode": 120
            }
            """.trimIndent(),
            installedCode = 123,
        )
        assertEquals("1.1.14", parsed?.version)
        assertEquals(124, parsed?.versionCode)
        assertEquals("https://ota.example.com/DMujeres-Tracking-1.1.14.apk", parsed?.url)
        assertEquals("notas", parsed?.notes)
        assertEquals("abc123", parsed?.sha256)
        assertEquals(120, parsed?.minVersionCode)
    }

    @Test
    fun rolloutMalformedFallsBackToLatestJson() {
        // null = check() sigue con latest.json (red/HTTP/JSON inválido).
        assertNull(UpdateManager.parseOtaResponse("", 123))
        assertNull(UpdateManager.parseOtaResponse("no-es-json", 123))
        assertNull(UpdateManager.parseOtaResponse("{}", 123))
        assertNull(UpdateManager.parseOtaResponse("""{"version":"1.1.14"}""", 123))
        assertNull(UpdateManager.parseOtaResponse("""{"version":"1.1.14","url":""}""", 123))
    }

    @Test
    fun sha256IsDeterministicAndContentSensitive() {
        val a = java.io.File.createTempFile("upd-a", ".bin", java.io.File("/tmp"))
        val b = java.io.File.createTempFile("upd-b", ".bin", java.io.File("/tmp"))
        try {
            a.writeText("hola")
            b.writeBytes(byteArrayOf(0x68, 0x6F, 0x6C, 0x61, 0x21)) // "hola!"
            val ha = UpdateManager.sha256Hex(a)
            assertEquals(ha, UpdateManager.sha256Hex(a))
            assertTrue(ha != UpdateManager.sha256Hex(b))
        } finally {
            a.delete()
            b.delete()
        }
    }
}
