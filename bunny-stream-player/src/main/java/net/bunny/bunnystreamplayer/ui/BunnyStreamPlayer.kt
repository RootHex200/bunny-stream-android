package net.bunny.bunnystreamplayer.ui

import android.content.Context
import android.content.pm.PackageManager
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.bunny.api.BunnyStreamApi
import net.bunny.api.playback.PlaybackPosition
import net.bunny.api.playback.ResumeConfig
import net.bunny.api.playback.ResumePositionListener
import net.bunny.api.settings.domain.model.PlayerSettings
import net.bunny.bunnystreamplayer.DefaultBunnyPlayer
import net.bunny.bunnystreamplayer.common.DeviceType
import net.bunny.bunnystreamplayer.config.PlaybackSpeedConfig
import net.bunny.bunnystreamplayer.model.PlayerIconSet
import net.bunny.bunnystreamplayer.model.getSanitizedRetentionData
import net.bunny.bunnystreamplayer.ui.fullscreen.FullScreenPlayerActivity
import net.bunny.bunnystreamplayer.ui.widget.BunnyPlayerView
import net.bunny.bunnystreamplayer.download.BunnyOfflineManager
import net.bunny.bunnystreamplayer.model.toVideoModel
import net.bunny.bunnystreamplayer.download.BunnyOfflineMetadataStore
import net.bunny.bunnystreamplayer.download.BunnyPlayConfigCache
import net.bunny.bunnystreamplayer.util.ScreenshotProtectionUtil
import net.bunny.player.databinding.ViewBunnyVideoPlayerBinding
import org.openapitools.client.models.VideoModel
import org.openapitools.client.models.VideoPlayDataModelVideo

 class BunnyStreamPlayer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr), BunnyPlayer {

    companion object {
        private const val TAG = "BunnyVideoPlayer"
        private const val AUTO_SAVE_INTERVAL = 10_000L // 10 seconds
    }

    private var job: Job? = null
    private var scope: CoroutineScope? = null
    private var loadVideoJob: Job? = null
    private var autoSaveJob: Job? = null // Add auto-save job
    private var pendingJob: (() -> Job)? = null

    private val binding = ViewBunnyVideoPlayerBinding.inflate(LayoutInflater.from(context), this)

    private val playerView by lazy {
        binding.playerView
    }

    override var iconSet: PlayerIconSet = PlayerIconSet()
        set(value) {
            field = value
            playerView.iconSet = value
        }

    private val bunnyPlayer = DefaultBunnyPlayer.getInstance(context)

    // Resume position functionality
    private var resumePositionCallback: ((PlaybackPosition, (Boolean) -> Unit) -> Unit)? = null
    private var currentVideoId: String? = null
    private var currentLibraryId: Long? = null

    /**
     * The token pair the current video was resolved with, kept so a download
     * started later can be signed identically. Re-deriving it in the app layer
     * is how a token that plays fine ends up 401-ing the download.
     */
    private var currentToken: String? = null
    private var currentExpires: Long? = null
    private var resumeConfig: ResumeConfig = ResumeConfig()
    private var isPortraitMode: Boolean = false
    private var screenshotProtectionEnabled: Boolean = false
    /**
     * Check if the app is running on Android TV
     */
    fun isRunningOnTV(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    /**
     * Get the device type (TV, Mobile, or Unknown)
     */
    fun getDeviceType(): DeviceType {
        return when {
            isRunningOnTV() -> DeviceType.TV
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) -> DeviceType.MOBILE
            else -> DeviceType.UNKNOWN
        }
    }
    /**
     * Play video with automatic TV/Mobile detection
     */
    fun playVideoWithTVDetection(videoId: String, libraryId: Long?, isPortrait: Boolean = false) {
        if (isRunningOnTV()) {
            // Launch TV player - need to import from the tv module
            try {
                val tvPlayerClass = Class.forName("net.bunny.tv.ui.BunnyTVPlayerActivity")
                val startMethod = tvPlayerClass.getMethod("start", Context::class.java, String::class.java, Long::class.java, String::class.java)
                startMethod.invoke(null, context, videoId, libraryId ?: -1L, null)
            } catch (e: Exception) {
                Log.w(TAG, "TV player not available, falling back to mobile player", e)
                playVideo(videoId, libraryId, videoTitle = "", refererValue = null, isPortrait = isPortrait) //TODO: must be change to real video title
            }
        } else {
            // Use regular mobile player
            playVideo(videoId, libraryId, videoTitle = "", refererValue = null, isPortrait = isPortrait) //TODO: must be change to real video title
        }
    }
    private val resumePositionListener = object : ResumePositionListener {
        override fun onResumePositionAvailable(videoId: String, position: PlaybackPosition) {
            Log.d(TAG, "Resume position available: $position")
            resumePositionCallback?.invoke(position) { shouldResume ->
                if (shouldResume) {
                    bunnyPlayer.seekTo(position.position)
                }
            }
        }

        override fun onResumePositionSaved(videoId: String, position: PlaybackPosition) {
            Log.d(TAG, "Resume position saved: $position")
        }
    }

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) {
            if (bunnyPlayer.autoPaused) {
                bunnyPlayer.play()
            }
            startAutoSave() // Resume auto-save when active
        }

        override fun onPause(owner: LifecycleOwner) {
            val autoPaused = bunnyPlayer.isPlaying()
            bunnyPlayer.pause(autoPaused)

            // Save immediately on pause - use coroutine
            scope?.launch {
                saveCurrentPosition()
            }
            stopAutoSave()
        }

        override fun onStop(owner: LifecycleOwner) {
            // Save when app goes to background - use coroutine
            scope?.launch {
                saveCurrentPosition()
            }
            stopAutoSave()
        }


        override fun onDestroy(owner: LifecycleOwner) {
            // Final save before destroy - use coroutine
            scope?.launch {
                saveCurrentPosition()
            }
            stopAutoSave()
        }
    }

    init {
        playerView.iconSet = iconSet
        playerView.fullscreenListener = object : BunnyPlayerView.FullscreenListener {
            override fun onFullscreenToggleClicked() {
                saveCurrentPosition() // Save before fullscreen transition
                playerView.bunnyPlayer = null
                FullScreenPlayerActivity.show(context, iconSet, isPortraitMode, screenshotProtectionEnabled) {
                    Log.d(TAG, "onFullscreenExited")
                    playerView.bunnyPlayer = bunnyPlayer
                    startAutoSave() // Resume auto-save after returning from fullscreen
                }
            }
        }

        // Set up resume position listener
        bunnyPlayer.setResumePositionListener(resumePositionListener)

        addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                Log.d(TAG, "onViewAttachedToWindow")
                job = SupervisorJob()
                scope = CoroutineScope(Dispatchers.Main + job!!)

                pendingJob?.let {
                    Log.d(TAG, "there is pending job, executing...")
                    pendingJob?.invoke()
                    pendingJob = null
                }

                findViewTreeLifecycleOwner()?.lifecycle?.addObserver(lifecycleObserver)
            }

            override fun onViewDetachedFromWindow(view: View) {
                Log.d(TAG, "onViewDetachedFromWindow")
                saveCurrentPosition() // Save on detach
                stopAutoSave()
                job?.cancel()
                findViewTreeLifecycleOwner()?.lifecycle?.removeObserver(lifecycleObserver)
            }
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.d(TAG, "onAttachedToWindow")
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Log.d(TAG, "onDetachedFromWindow")

        // Save before detaching - use coroutine scope if available
        scope?.launch {
            saveCurrentPosition()
        }
        stopAutoSave()
        bunnyPlayer.stop()
    }

    fun setPlaybackSpeedConfig(config: PlaybackSpeedConfig) {
        val defaultPlayer = DefaultBunnyPlayer.getInstance(context)
        defaultPlayer.setPlaybackSpeedConfig(config)
    }

    /**
     * Enable or disable screenshot and screen recording protection
     * @param enable true to enable protection, false to disable
     */
    fun setScreenshotProtection(enable: Boolean) {
        screenshotProtectionEnabled = enable
        ScreenshotProtectionUtil.setScreenshotProtection(context, enable)
        Log.d(TAG, "Screenshot protection ${if (enable) "enabled" else "disabled"}")
    }

    /**
     * Check if screenshot protection is currently enabled
     * @return true if protection is enabled, false otherwise
     */
    fun isScreenshotProtectionEnabled(): Boolean {
        return screenshotProtectionEnabled
    }

    /**
     * Enable resume position functionality with auto-save
     */
    fun enableResumePosition(
        config: ResumeConfig = ResumeConfig(),
        onResumePositionCallback: ((PlaybackPosition, (Boolean) -> Unit) -> Unit)? = null
    ) {
        this.resumeConfig = config
        bunnyPlayer.enableResumePosition(config)

        // Set the callback if provided
        onResumePositionCallback?.let { callback ->
            this.resumePositionCallback = callback
        }

        // Start auto-save if enabled in config
        if (config.enableAutoSave) {
            startAutoSave()
        }
    }

    /**
     * Disable resume position functionality
     */
    fun disableResumePosition() {
        bunnyPlayer.disableResumePosition()
        this.resumePositionCallback = null
        stopAutoSave()
    }

    /**
     * Clear saved position for specific video
     */
    fun clearSavedPosition(videoId: String) {
        bunnyPlayer.clearSavedPosition(videoId)
    }

    /**
     * Clear all saved positions
     */
    fun clearAllSavedPositions() {
        scope?.launch {
            bunnyPlayer.positionManager?.clearAllPositions()
        }
    }

    /**
     * Get all saved positions for debugging/management
     */
    fun getAllSavedPositions(callback: (List<PlaybackPosition>) -> Unit) {
        scope?.launch {
            val positions = bunnyPlayer.positionManager?.getAllPositions() ?: emptyList()
            callback(positions)
        }
    }

    /**
     * Turns a failed play-config lookup into something the viewer can act on.
     *
     * A 401 from `/play` almost always means the library has Embed View Token
     * Authentication switched on, so it gets its own wording instead of a
     * generic "couldn't load".
     */
    private fun playbackFailureMessage(failure: Exception?, tokenSupplied: Boolean): String {
        val detail = "${failure?.message.orEmpty()} ${failure?.cause?.message.orEmpty()}"
        val unauthorized = detail.contains("401") || detail.contains("Unauthorized", ignoreCase = true)

        return when {
            unauthorized && tokenSupplied ->
                "Playback was refused (401). The token or expiry does not match this " +
                    "video — the token must be SHA256(securityKey + videoId + expires), " +
                    "and expires must be a future UNIX time in seconds."
            unauthorized ->
                "Playback was refused (401). This library requires Embed View Token " +
                    "Authentication — use playVideoWithToken with a token and expires."
            failure != null -> "Could not load this video: ${failure.message}"
            else -> "Could not load this video."
        }
    }

    override fun playVideo(videoId: String, libraryId: Long?, videoTitle: String, refererValue: String?, isPortrait: Boolean, isScreenshotProtectionEnabled: Boolean, cacheKey: String?) {

        Log.d(TAG, "playVideo videoId=$videoId, isPortrait=$isPortrait, isScreenshotProtectionEnabled=$isScreenshotProtectionEnabled")

        currentVideoId = videoId
        currentLibraryId = libraryId
        currentToken = null
        currentExpires = null
        isPortraitMode = isPortrait
        val providedLibraryId = libraryId ?: BunnyStreamApi.libraryId
        
        // Set screenshot protection based on parameter
        setScreenshotProtection(isScreenshotProtectionEnabled)

        if (!BunnyStreamApi.isInitialized()) {
            Log.e(
                TAG,
                "Unable to play video, initialize the player first using BunnyStreamSdk.initialize"
            )
            return
        }

        // Save previous video position before switching
        saveCurrentPosition()

        loadVideoJob?.cancel()

        pendingJob = {
            // Capture refererValue for use in the lambda
            val capturedRefererValue = refererValue
            scope!!.launch {
                var video: VideoModel? = null
                var settings: arrow.core.Either<String, PlayerSettings>? = null
                var resolveFailure: Exception? = null

                try {
                    video = withContext(Dispatchers.IO) {
                        BunnyStreamApi.getInstance().videosApi.videoGetVideoPlayData(
                            providedLibraryId,
                            videoId,
                            // Explicit nulls, not the generated defaults. Those
                            // are `token = ""` and `expires = 0`, which put
                            // `?token=&expires=0` on the wire — a library with
                            // Embed View Token Authentication on reads that as
                            // an invalid token and answers 401, where omitting
                            // the pair at least produces the honest error.
                            token = null,
                            expires = null
                        ).video?.toVideoModel()!!
                    }
                    settings = BunnyStreamApi.getInstance()
                        .fetchPlayerSettings(providedLibraryId, videoId, capturedRefererValue)
                } catch (e: Exception) {
                    Log.w(TAG, "Error fetching video/settings: $e")
                    resolveFailure = e
                    if (cacheKey != null) {
                         val meta = BunnyOfflineMetadataStore.load(context, cacheKey, VideoModel::class.java, PlayerSettings::class.java)
                         if (meta != null) {
                             video = meta.first as VideoModel
                             settings = arrow.core.Either.Right(meta.second as PlayerSettings)
                         }
                    }
                }

                if (video == null || settings == null) {
                    // Returning quietly here is how a 401 from a
                    // token-authenticated library becomes a blank player with
                    // nothing but a logcat line to explain it.
                    playerView.showError(
                        playbackFailureMessage(resolveFailure, tokenSupplied = false)
                    )
                    return@launch
                }

                // Hand the resolved config to any download of this video, so it
                // reuses the already-authorized playlist instead of re-signing
                // a fresh /play request.
                settings!!.getOrNull()?.let { resolved ->
                    BunnyPlayConfigCache.put(
                        libraryId = providedLibraryId,
                        videoId = videoId,
                        video = video!!,
                        settings = resolved,
                        token = null,
                        expires = null,
                        referer = capturedRefererValue,
                    )
                }

                settings!!.fold(
                    ifLeft = {
                        initializeVideo(
                            video!!, PlayerSettings(
                                thumbnailUrl = "",
                                controls = "",
                                keyColor = 0,
                                captionsFontSize = 0,
                                captionsFontColor = null,
                                captionsBackgroundColor = null,
                                uiLanguage = "",
                                showHeatmap = false,
                                fontFamily = "",
                                playbackSpeeds = listOf(
                                    0.25f,
                                    0.5f,
                                    0.75f,
                                    1.0f,
                                    1.25f,
                                    1.5f,
                                    2.0f,
                                    3.0f,
                                    4.0f
                                ),
                                drmEnabled = false,
                                vastTagUrl = null,
                                videoUrl = "",
                                seekPath = "",
                                captionsPath = ""
                            ), capturedRefererValue,
                            cacheKey
                        )
                        playerView.showError(it)
                    },
                    ifRight = { initializeVideo(video!!, it, capturedRefererValue, cacheKey) }
                )
            }
        }

        if (scope == null) {
            Log.d(TAG, "scope not created yet")
            return
        }

        loadVideoJob = pendingJob?.invoke()
        pendingJob = null
    }

    override fun playVideoWithToken(videoId: String, libraryId: Long?, videoTitle: String, token: String?, expires: Long?, refererValue: String?, isPortrait: Boolean, isScreenshotProtectionEnabled: Boolean, cacheKey: String?) {

        Log.d(TAG, "playVideoWithToken videoId=$videoId, token=$token, expires=$expires refervalue=${refererValue}, isPortrait=$isPortrait, isScreenshotProtectionEnabled=$isScreenshotProtectionEnabled")

        // A blank token is not a token. Sent as `?token=`, it reads to Bunny as
        // a failed signature check rather than an unauthenticated request.
        @Suppress("NAME_SHADOWING") val token = token?.takeIf { it.isNotBlank() }
        @Suppress("NAME_SHADOWING") val expires = expires?.takeIf { it > 0L }

        currentVideoId = videoId
        currentLibraryId = libraryId
        currentToken = token
        currentExpires = expires
        isPortraitMode = isPortrait
        val providedLibraryId = libraryId ?: BunnyStreamApi.libraryId
        
        // Set screenshot protection based on parameter
        setScreenshotProtection(isScreenshotProtectionEnabled)

        if (!BunnyStreamApi.isInitialized()) {
            Log.e(
                TAG,
                "Unable to play video, initialize the player first using BunnyStreamSdk.initialize"
            )
            return
        }

        // Save previous video position before switching
        saveCurrentPosition()

        loadVideoJob?.cancel()

        pendingJob = {
            // Capture refererValue for use in the lambda
            val capturedRefererValue = refererValue
            scope!!.launch {
                var video: VideoModel? = null
                var settings: arrow.core.Either<String, PlayerSettings>? = null
                var resolveFailure: Exception? = null

                try {
                    video = withContext(Dispatchers.IO) {
                        BunnyStreamApi.getInstance().videosApi.videoGetVideoPlayData(
                            providedLibraryId,
                            videoId,
                            token = token,
                            expires = expires
                        ).video?.toVideoModel()!!
                    }
                    settings = BunnyStreamApi.getInstance()
                        .fetchPlayerSettingsWithToken(providedLibraryId, videoId, token, expires, capturedRefererValue)
                } catch (e: Exception) {
                     Log.w(TAG, "Error fetching video/settings: $e")
                    resolveFailure = e
                    if (cacheKey != null) {
                         val meta = BunnyOfflineMetadataStore.load(context, cacheKey, VideoModel::class.java, PlayerSettings::class.java)
                         if (meta != null) {
                             video = meta.first as VideoModel
                             settings = arrow.core.Either.Right(meta.second as PlayerSettings)
                         }
                    }
                }

                if (video == null || settings == null) {
                    playerView.showError(
                        playbackFailureMessage(resolveFailure, tokenSupplied = !token.isNullOrBlank())
                    )
                    return@launch
                }

                // Same hand-off as playVideo, and the pair travels with it:
                // a download that must re-resolve gets the token that just
                // worked rather than one the app layer mints again.
                settings!!.getOrNull()?.let { resolved ->
                    BunnyPlayConfigCache.put(
                        libraryId = providedLibraryId,
                        videoId = videoId,
                        video = video!!,
                        settings = resolved,
                        token = token,
                        expires = expires,
                        referer = capturedRefererValue,
                    )
                }

                settings!!.fold(
                    ifLeft = {
                        initializeVideo(
                            video!!, PlayerSettings(
                                thumbnailUrl = "",
                                controls = "",
                                keyColor = 0,
                                captionsFontSize = 0,
                                captionsFontColor = null,
                                captionsBackgroundColor = null,
                                uiLanguage = "",
                                showHeatmap = false,
                                fontFamily = "",
                                playbackSpeeds = listOf(
                                    0.25f,
                                    0.5f,
                                    0.75f,
                                    1.0f,
                                    1.25f,
                                    1.5f,
                                    2.0f,
                                    3.0f,
                                    4.0f
                                ),
                                drmEnabled = false,
                                vastTagUrl = null,
                                videoUrl = "",
                                seekPath = "",
                                captionsPath = ""
                            ), capturedRefererValue,
                            cacheKey
                        )
                        playerView.showError(it)
                    },
                    ifRight = { initializeVideo(video!!, it, capturedRefererValue, cacheKey) }
                )
            }
        }

        if (scope == null) {
            Log.d(TAG, "scope not created yet")
            return
        }

        loadVideoJob = pendingJob?.invoke()
        pendingJob = null
    }

    override fun pause() {
        scope?.launch {
            saveCurrentPosition()
        }
        bunnyPlayer.pause()
    }

    override fun play() {
        bunnyPlayer.play()
        // Auto-save will start automatically via lifecycle observer
    }

    private suspend fun initializeVideo(video: VideoModel, playerSettings: PlayerSettings, refererValue: String?, cacheKey: String?) {
        playerView.showPreviewThumbnail(playerSettings.thumbnailUrl)

        var retentionData: Map<Int, Int> = mutableMapOf()

        if (playerSettings.showHeatmap) {
            try {
                val retentionDataResponse = withContext(Dispatchers.IO) {
                    BunnyStreamApi.getInstance().videosApi.videoGetVideoHeatmap(
                        video.videoLibraryId!!,
                        video.guid!!
                    )
                }
                retentionData = retentionDataResponse.getSanitizedRetentionData()
            } catch (e: Exception) {
                Log.w(TAG, "Error fetching video heatmap")
            }
        }

        bunnyPlayer.playVideo(binding.playerView, video, retentionData, playerSettings, refererValue, cacheKey)
        playerView.bunnyPlayer = bunnyPlayer

        // Start auto-save after video starts playing
        if (resumeConfig.enableAutoSave) {
            startAutoSave()
        }
    }

    private fun startAutoSave() {
        stopAutoSave() // Stop any existing auto-save job

        autoSaveJob = scope?.launch(Dispatchers.Main) { // <- Use Main dispatcher for timer
            while (isActive) {
                delay(resumeConfig.saveInterval)
                if (bunnyPlayer.isPlaying()) { // Safe on main thread
                    // Move save to background
                    launch(Dispatchers.IO) {
                        val position = withContext(Dispatchers.Main) {
                            bunnyPlayer.getCurrentPosition()
                        }
                        val duration = withContext(Dispatchers.Main) {
                            bunnyPlayer.getDuration()
                        }

                        currentVideoId?.let { videoId ->
                            if (position > 0 && duration > 0) {
                                bunnyPlayer.positionManager?.savePosition(videoId, position, duration)
                            }
                        }
                    }
                }
            }
        }
        Log.d(TAG, "Auto-save started with interval: ${resumeConfig.saveInterval}ms")
    }
    private fun stopAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = null
        Log.d(TAG, "Auto-save stopped")
    }

    private fun saveCurrentPosition() {
        currentVideoId?.let { videoId ->
            scope?.launch {
                try {
                    // Get position and duration on main thread
                    val position = withContext(Dispatchers.Main) {
                        bunnyPlayer.getCurrentPosition()
                    }
                    val duration = withContext(Dispatchers.Main) {
                        bunnyPlayer.getDuration()
                    }

                    // Save on background thread
                    if (position > 0 && duration > 0) {
                        withContext(Dispatchers.IO) {
                            bunnyPlayer.positionManager?.savePosition(videoId, position, duration)
                        }
                        Log.d(TAG, "Position saved for $videoId: ${formatTime(position)}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving current position", e)
                }
            }
        }
    }
    private fun formatTime(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60




        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    /**
     * The `token`/`expires` pair the current video is playing under, or null
     * when it was resolved without token auth.
     *
     * Pass this straight to [net.bunny.bunnystreamplayer.download.BunnyOfflineManager.startDownload]
     * rather than minting a second token in the app layer — the token is a
     * signature over `securityKey + videoId + expires`, so a pair that differs
     * in either half is refused with a 401 even though playback is working.
     * For the video that is already playing, prefer [downloadCurrentVideo],
     * which reuses the resolved playlist and asks Bunny nothing at all.
     */
    fun currentPlaybackToken(): Pair<String, Long>? {
        val token = currentToken?.takeIf { it.isNotBlank() } ?: return null
        val expires = currentExpires?.takeIf { it > 0L } ?: return null
        return token to expires
    }

    override fun downloadCurrentVideo(cacheKey: String) {
        bunnyPlayer.downloadCurrentVideo(cacheKey)
    }

    @OptIn(UnstableApi::class)
    override fun isDownloaded(cacheKey: String): Boolean =
        net.bunny.bunnystreamplayer.download.BunnyOfflineManager
            .isDownloaded(context, cacheKey)

}