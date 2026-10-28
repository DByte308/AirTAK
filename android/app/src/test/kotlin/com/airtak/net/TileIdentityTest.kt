package com.airtak.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Identity is per provider stream, not per host. These cases are the ones that used to collide:
 * same host with a different style path, a different port, or a query carrying a style or account.
 */
class TileIdentityTest {

    private fun key(url: String): String = TileIdentity.identityKey(url) ?: error("no identity for $url")

    @Test
    fun sameHostDifferentStylePathIsADifferentIdentity() {
        assertNotEquals(key("https://t.example.com/styleA/{z}/{x}/{y}.png"), key("https://t.example.com/styleB/{z}/{x}/{y}.png"))
    }

    @Test
    fun sameHostDifferentPortIsADifferentIdentity() {
        assertNotEquals(key("https://t.example.com/{z}/{x}/{y}.png"), key("https://t.example.com:8443/{z}/{x}/{y}.png"))
    }

    @Test
    fun sameHostDifferentQueryIsADifferentIdentity() {
        assertNotEquals(
            key("https://t.example.com/v1/{z}/{x}/{y}.png?key=AAA"),
            key("https://t.example.com/v1/{z}/{x}/{y}.png?key=BBB"),
        )
        assertNotEquals(
            key("https://t.example.com/v1/{z}/{x}/{y}.png"),
            key("https://t.example.com/v1/{z}/{x}/{y}.png?mapstyle=light"),
        )
    }

    @Test
    fun onlySchemeAndHostCaseFold() {
        assertEquals(
            key("HTTPS://T.Example.COM/a/{z}/{x}/{y}.png?K=1"),
            key("https://t.example.com/a/{z}/{x}/{y}.png?K=1"),
        )
        // Path and query case are significant and must not be folded.
        assertNotEquals(key("https://t.example.com/A/{z}/{x}/{y}.png"), key("https://t.example.com/a/{z}/{x}/{y}.png"))
        assertNotEquals(key("https://t.example.com/a?K=1"), key("https://t.example.com/a?k=1"))
    }

    @Test
    fun apiKeyNeverReachesTheProvenanceLabel() {
        val label = TileIdentity.provenanceLabel("https://t.example.com/v1/{z}/{x}/{y}.png?key=SECRET_TOKEN")
        assertEquals("t.example.com/v1/{z}/{x}/{y}.png", label)
        assert(!label.contains("SECRET_TOKEN"))
    }

    @Test
    fun identityIsStableAndShortEnoughForAFilename() {
        val k = key("https://t.example.com/a/{z}/{x}/{y}.png")
        assertEquals(16, k.length)
        assertEquals(k, key("https://t.example.com/a/{z}/{x}/{y}.png"))
    }

    @Test
    fun unusableUrlsHaveNoIdentity() {
        assertNull(TileIdentity.identityKey("tiles.example.com/{z}/{x}/{y}.png"))
        assertNull(TileIdentity.identityKey("ftp://t.example.com/{z}/{x}/{y}.png"))
        assertNull(TileIdentity.identityKey("https://"))
    }
}
