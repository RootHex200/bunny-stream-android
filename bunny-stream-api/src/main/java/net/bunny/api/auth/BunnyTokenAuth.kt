package net.bunny.api.auth

import java.security.MessageDigest

/**
 * A `token`/`expires` pair for a library with Embed View Token
 * Authentication turned on.
 */
data class BunnyPlaybackToken(
    val token: String,
    /** UNIX time in **seconds** — Bunny rejects milliseconds. */
    val expires: Long,
)

/**
 * Signs playback requests for libraries with Embed View Token Authentication
 * enabled.
 *
 * With that setting on, `/library/{libraryId}/videos/{videoId}/play` answers
 * 401 to any request that does not carry a matching `token` and `expires`; an
 * AccessKey does not substitute for it. The token is
 * `SHA256_HEX(tokenSecurityKey + videoId + expires)`.
 *
 * **The security key belongs on your server, not in the APK.** Anyone who
 * unpacks the app can read a key compiled into it and mint tokens for the
 * whole library, which defeats the setting they just enabled. Ship a small
 * backend endpoint that returns [BunnyPlaybackToken] and pass the result to
 * `playVideoWithToken` / `BunnyOfflineManager.startDownload`. This helper
 * exists so that server can share one implementation with the demo app and
 * instrumentation tests — not so the key can travel with the client.
 */
object BunnyTokenAuth {

    /**
     * The token Bunny expects for [videoId] up to [expires].
     *
     * @param expires UNIX time in seconds, in the future.
     */
    fun token(securityKey: String, videoId: String, expires: Long): String {
        require(securityKey.isNotBlank()) { "securityKey is required" }
        require(videoId.isNotBlank()) { "videoId is required" }

        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$securityKey$videoId$expires".toByteArray(Charsets.UTF_8))

        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * A token valid for the next [ttlSeconds].
     *
     * [nowSeconds] is injectable so a test can pin the clock; callers should
     * leave it alone.
     */
    fun sign(
        securityKey: String,
        videoId: String,
        ttlSeconds: Long = 3600L,
        nowSeconds: Long = System.currentTimeMillis() / 1000L,
    ): BunnyPlaybackToken {
        require(ttlSeconds > 0) { "ttlSeconds must be positive" }

        val expires = nowSeconds + ttlSeconds
        return BunnyPlaybackToken(token(securityKey, videoId, expires), expires)
    }
}
