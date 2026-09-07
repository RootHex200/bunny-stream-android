package net.bunny.api.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BunnyTokenAuthTest {

    /**
     * Pinned against an independent SHA256 of the same concatenation. If this
     * drifts, every token the SDK mints is rejected by Bunny, and the only
     * symptom is a 401 that looks identical to a misconfigured library.
     */
    @Test
    fun `token matches the documented SHA256 of key, video and expiry`() {
        assertEquals(
            "d3ab68c4fe8a6ab251b1aef4e1e37eba77eaf672945d34f693e130d52b1ae203",
            BunnyTokenAuth.token("sec-key-123", "abc-video", 2_000_000_000L),
        )
    }

    /** The signature binds the video, which is why one token is not reusable. */
    @Test
    fun `token is bound to the video id`() {
        assertNotEquals(
            BunnyTokenAuth.token("sec-key-123", "abc-video", 2_000_000_000L),
            BunnyTokenAuth.token("sec-key-123", "other-video", 2_000_000_000L),
        )
    }

    /** …and to the expiry, which is why the pair must travel together. */
    @Test
    fun `token is bound to the expiry`() {
        assertNotEquals(
            BunnyTokenAuth.token("sec-key-123", "abc-video", 2_000_000_000L),
            BunnyTokenAuth.token("sec-key-123", "abc-video", 2_000_000_001L),
        )
    }

    @Test
    fun `sign returns an expiry ttl seconds out, in seconds`() {
        val signed = BunnyTokenAuth.sign(
            securityKey = "sec-key-123",
            videoId = "abc-video",
            ttlSeconds = 600L,
            nowSeconds = 1_700_000_000L,
        )

        assertEquals(1_700_000_600L, signed.expires)
        assertEquals(
            BunnyTokenAuth.token("sec-key-123", "abc-video", 1_700_000_600L),
            signed.token,
        )
    }
}
