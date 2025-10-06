package net.bunny.bunnystreamplayer.ui

import net.bunny.bunnystreamplayer.model.PlayerIconSet

interface BunnyPlayer {

    /**
     * Apply custom icons to video player interface
     */
    var iconSet: PlayerIconSet

    /**
     * Plays a video and fetches additional info, e.g. chapters, moments and subtitles
     *
     * @param videoId Video ID
     * @param libraryId Library ID
     * @param videoTitle Video title
     * @param referrer Custom referrer value. If null, uses default referrer
     */
    fun playVideo(videoId: String, libraryId: Long?, videoTitle: String, referrer: String? = null)

    /**
     * Plays a video with token-based authentication
     *
     * @param videoId Video ID
     * @param libraryId Library ID
     * @param videoTitle Video title
     * @param token Authentication token
     * @param expires Token expiration timestamp
     * @param referrer Custom referrer value. If null, uses default referrer
     */
    fun playVideoWithToken(videoId: String, libraryId: Long?, videoTitle: String, token: String?, expires: Long?, referrer: String? = null)

    /**
     * Pauses video
     */
    fun pause()

    /**
     * Resumes playing video
     */
    fun play()
}