package net.bunny.bunnystreamplayer.download

import net.bunny.api.settings.domain.model.PlayerSettings
import org.openapitools.client.models.VideoModel

/**
 * Remembers the play-config the player just resolved, so a download of the
 * same video does not have to ask Bunny for it a second time.
 *
 * The second ask is where token-authenticated libraries break. `/play` returns
 * an already-authorized `videoPlaylistUrl`, so a successful playback has
 * *already* produced everything a download needs. Re-resolving it means
 * re-signing it, and a token minted for playback has usually lapsed — or was
 * never threaded through to the download call at all — by the time the user
 * taps download. The result is a 401 on a video that is visibly playing.
 *
 * In memory only. This is a short-lived hand-off between two calls seconds
 * apart, not a persistence layer; [BunnyOfflineMetadataStore] owns the durable
 * copy once the download starts.
 */
object BunnyPlayConfigCache {

    /**
     * How long a resolved config is worth reusing.
     *
     * The playlist URL `/play` hands back is itself time-limited by the CDN,
     * so an old entry buys a 401 at resolve time in exchange for a 403 midway
     * through the download — a worse trade. Past this, re-resolve.
     */
    private const val MAX_AGE_MILLIS = 5 * 60 * 1000L

    /** Bounded so a long browsing session cannot grow this without limit. */
    private const val MAX_ENTRIES = 16

    data class Entry(
        val video: VideoModel,
        val settings: PlayerSettings,
        /** The pair this config was resolved under, for callers that must re-resolve. */
        val token: String?,
        val expires: Long?,
        val referer: String?,
        val resolvedAtMillis: Long,
    )

    private val entries = LinkedHashMap<String, Entry>()

    fun put(
        libraryId: Long,
        videoId: String,
        video: VideoModel,
        settings: PlayerSettings,
        token: String?,
        expires: Long?,
        referer: String?,
        nowMillis: Long = System.currentTimeMillis(),
    ) = synchronized(entries) {
        val key = key(libraryId, videoId)
        // Re-inserting moves the key to the back, which is what makes the
        // eldest-first eviction below actually evict the least recent.
        entries.remove(key)
        entries[key] = Entry(video, settings, token, expires, referer, nowMillis)

        while (entries.size > MAX_ENTRIES) {
            entries.remove(entries.keys.first())
        }
    }

    /** The cached config, or null when absent or too old to trust. */
    fun get(
        libraryId: Long,
        videoId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Entry? = synchronized(entries) {
        val key = key(libraryId, videoId)
        val entry = entries[key] ?: return null

        if (nowMillis - entry.resolvedAtMillis > MAX_AGE_MILLIS) {
            entries.remove(key)
            return null
        }
        return entry
    }

    /** Dropped on logout, where holding a signed playlist URL would outlive the session. */
    fun clear() = synchronized(entries) { entries.clear() }

    private fun key(libraryId: Long, videoId: String) = "$libraryId/$videoId"
}
