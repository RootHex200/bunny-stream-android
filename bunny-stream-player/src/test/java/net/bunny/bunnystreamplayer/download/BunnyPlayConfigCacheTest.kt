package net.bunny.bunnystreamplayer.download

import net.bunny.api.settings.domain.model.PlayerSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.openapitools.client.models.VideoModel

class BunnyPlayConfigCacheTest {

    @After
    fun tearDown() = BunnyPlayConfigCache.clear()

    private fun settings(url: String) = PlayerSettings(
        thumbnailUrl = "",
        controls = "",
        keyColor = 0,
        captionsFontSize = 0,
        captionsFontColor = null,
        captionsBackgroundColor = null,
        uiLanguage = "",
        showHeatmap = false,
        fontFamily = "",
        playbackSpeeds = listOf(1.0f),
        drmEnabled = false,
        vastTagUrl = null,
        videoUrl = url,
        seekPath = "",
        captionsPath = "",
    )

    private fun put(videoId: String, at: Long, url: String = "https://cdn/$videoId.m3u8") =
        BunnyPlayConfigCache.put(
            libraryId = 1L,
            videoId = videoId,
            video = VideoModel(guid = videoId),
            settings = settings(url),
            token = "tok-$videoId",
            expires = 2_000_000_000L,
            referer = "https://app/",
            nowMillis = at,
        )

    /** The whole point: playback's config, reusable by the download. */
    @Test
    fun `returns the config the player resolved, with the pair it used`() {
        put("v1", at = 0L)

        val entry = BunnyPlayConfigCache.get(1L, "v1", nowMillis = 1_000L)

        assertNotNull(entry)
        assertEquals("https://cdn/v1.m3u8", entry!!.settings.videoUrl)
        assertEquals("tok-v1", entry.token)
        assertEquals(2_000_000_000L, entry.expires)
    }

    /** A different video must never borrow another's authorized playlist. */
    @Test
    fun `is keyed by library and video`() {
        put("v1", at = 0L)

        assertNull(BunnyPlayConfigCache.get(1L, "v2", nowMillis = 0L))
        assertNull(BunnyPlayConfigCache.get(2L, "v1", nowMillis = 0L))
    }

    /**
     * A stale playlist URL trades a 401 at resolve time for a 403 mid-download,
     * so past the window the caller must re-resolve.
     */
    @Test
    fun `drops entries older than the reuse window`() {
        put("v1", at = 0L)

        assertNotNull(BunnyPlayConfigCache.get(1L, "v1", nowMillis = 5 * 60 * 1000L))
        assertNull(BunnyPlayConfigCache.get(1L, "v1", nowMillis = 5 * 60 * 1000L + 1))
    }

    @Test
    fun `evicts the least recently resolved once full`() {
        repeat(17) { put("v$it", at = it.toLong()) }

        assertNull(BunnyPlayConfigCache.get(1L, "v0", nowMillis = 20L))
        assertNotNull(BunnyPlayConfigCache.get(1L, "v1", nowMillis = 20L))
        assertNotNull(BunnyPlayConfigCache.get(1L, "v16", nowMillis = 20L))
    }

    @Test
    fun `clear drops everything, so logout cannot leak an authorization`() {
        put("v1", at = 0L)
        BunnyPlayConfigCache.clear()

        assertNull(BunnyPlayConfigCache.get(1L, "v1", nowMillis = 0L))
    }
}
