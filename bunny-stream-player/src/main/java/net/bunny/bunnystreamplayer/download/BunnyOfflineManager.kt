package net.bunny.bunnystreamplayer.download

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.bunny.api.BunnyStreamApi
import net.bunny.bunnystreamplayer.model.toVideoModel
import org.openapitools.client.models.VideoModel
import net.bunny.bunnystreamplayer.util.BunnyDownloadStore

/**
 * Where a download stands, in terms the caller cares about rather than
 * media3's internal states.
 */
enum class BunnyDownloadState { QUEUED, DOWNLOADING, DOWNLOADED, FAILED, CANCELLED }

/** Coarse failure reasons, so a caller can say something actionable. */
enum class BunnyDownloadError { NETWORK, STORAGE_FULL, UNAUTHORIZED, NOT_FOUND, UNKNOWN }

data class BunnyDownloadProgress(
    val cacheKey: String,
    val state: BunnyDownloadState,
    /** 0..1, or -1 when the total is not yet known. */
    val progress: Float,
    val bytesDownloaded: Long,
    val error: BunnyDownloadError? = null,
)

data class BunnyOfflineVideo(
    val cacheKey: String,
    val bytesDownloaded: Long,
)

/**
 * Public API for downloading videos for offline playback.
 *
 * Deliberately mirrors the iOS fork's `BunnyOfflineManager` in shape so the
 * two Flutter plugins can expose one identical channel contract; a divergence
 * here forces the app layer to fork per platform.
 */
@UnstableApi
object BunnyOfflineManager {

    private const val TAG = "BunnyOfflineManager"

    private val listeners = mutableSetOf<(BunnyDownloadProgress) -> Unit>()

    private var managerListener: DownloadManager.Listener? = null

    /**
     * Starts downloading the video behind [playlistUrl].
     *
     * The caller resolves the playlist through Bunny's play-config endpoint
     * and passes the resulting [video] and [settings] so they can be replayed
     * offline — the endpoint is unreachable at playback time by definition.
     */
    fun startDownload(
        context: Context,
        cacheKey: String,
        playlistUrl: String,
        title: String?,
        video: Any?,
        settings: Any?,
        referer: String? = null,
    ) {
        ensureListener(context)

        // The CDN sees the download exactly as it sees playback. A library
        // with "Block direct URL access" enabled rejects a request whose
        // Referer is not on its allow-list, which is how a video that streams
        // fine still fails to download.
        BunnyDownloadManagerProvider.setReferer(referer)

        if (video != null && settings != null) {
            BunnyOfflineMetadataStore.save(context, cacheKey, video, settings)
        }

        val request = DownloadRequest.Builder(cacheKey, Uri.parse(playlistUrl))
            .setData((title ?: "").toByteArray())
            .build()

        DownloadService.sendAddDownload(
            context,
            BunnyDownloadService::class.java,
            request,
            /* foreground = */ true,
        )
        emit(BunnyDownloadProgress(cacheKey, BunnyDownloadState.QUEUED, 0f, 0))
    }

    /**
     * Resolves the play-config for [videoId] and starts the download.
     *
     * This is the entry point the Flutter plugin uses: it has the lesson's
     * Bunny identity and short-lived auth, not a playlist URL. Mirrors the iOS
     * fork's `downloadVideo` so both plugins can expose one channel contract.
     *
     * [onResult] reports whether the download was accepted, with a coarse
     * reason when it was not — resolution failures happen before any progress
     * event exists to carry them.
     */
    fun startDownload(
        context: Context,
        scope: CoroutineScope,
        cacheKey: String,
        videoId: String,
        libraryId: Long,
        token: String?,
        expires: Long?,
        referer: String?,
        title: String?,
        onResult: (Boolean, BunnyDownloadError?) -> Unit,
    ) {
        // A video the player just resolved needs no second /play call: the
        // response it already holds carries an authorized playlist URL. Taking
        // it skips the re-signing step entirely, which is the whole reason a
        // playing video could still fail to download.
        val cached = BunnyPlayConfigCache.get(libraryId, videoId)
        if (cached != null && cached.settings.videoUrl.isNotEmpty()) {
            if (cached.settings.drmEnabled) {
                Log.w(TAG, "Refusing download of DRM-protected video $videoId")
                onResult(false, BunnyDownloadError.UNAUTHORIZED)
                return
            }
            Log.d(TAG, "Reusing the play config the player resolved for $videoId")
            startDownload(
                context = context,
                cacheKey = cacheKey,
                playlistUrl = cached.settings.videoUrl,
                title = title ?: cached.video.title,
                video = cached.video,
                settings = cached.settings,
                referer = referer ?: cached.referer,
            )
            onResult(true, null)
            return
        }

        // Nothing reusable, so this one has to be signed after all. A config
        // cached without a playlist URL still remembers the pair that resolved
        // it, which beats treating the video as unauthenticated.
        val effectiveToken = token ?: cached?.token
        val effectiveExpires = expires ?: cached?.expires

        // The token is a signature over (securityKey + videoId + expires), so
        // it only validates when both halves travel together and still match
        // the video being asked for. Half a pair reaches Bunny as a failed
        // signature check, which is why the very token that is playing right
        // now can come back 401 here.
        val auth = normalizeTokenAuth(videoId, effectiveToken, effectiveExpires)
        if (auth is TokenAuth.Invalid) {
            Log.w(TAG, "Refusing download of $videoId: ${auth.reason}")
            onResult(false, BunnyDownloadError.UNAUTHORIZED)
            return
        }
        val authToken = (auth as? TokenAuth.Signed)?.token
        val authExpires = (auth as? TokenAuth.Signed)?.expires

        scope.launch {
            try {
                val video: VideoModel? = withContext(Dispatchers.IO) {
                    BunnyStreamApi.getInstance().videosApi.videoGetVideoPlayData(
                        libraryId,
                        videoId,
                        token = authToken,
                        expires = authExpires,
                    ).video?.toVideoModel()
                }
                val settings = BunnyStreamApi.getInstance()
                    .fetchPlayerSettingsWithToken(
                        libraryId, videoId, authToken, authExpires, referer,
                    ).getOrNull()

                val url = settings?.videoUrl
                if (video == null || settings == null || url.isNullOrEmpty()) {
                    onResult(false, BunnyDownloadError.NOT_FOUND)
                    return@launch
                }

                // Bunny can mark a video DRM-protected. This feature is
                // explicitly not a DRM path, so refuse rather than download
                // something that will not play.
                if (settings.drmEnabled) {
                    Log.w(TAG, "Refusing download of DRM-protected video $videoId")
                    onResult(false, BunnyDownloadError.UNAUTHORIZED)
                    return@launch
                }

                startDownload(
                    context = context,
                    cacheKey = cacheKey,
                    playlistUrl = url,
                    title = title ?: video.title,
                    video = video,
                    settings = settings,
                    referer = referer,
                )
                onResult(true, null)
            } catch (e: Exception) {
                if (classify(e) == BunnyDownloadError.UNAUTHORIZED) {
                    Log.w(
                        TAG,
                        "Play config for $videoId in library $libraryId was refused. " +
                            "token=${authToken.orEmpty().take(8).ifEmpty { "<none>" }}… " +
                            "expires=${authExpires ?: "<none>"}. A token that plays but " +
                            "will not download is almost always signed for a different " +
                            "videoId/expires than the one sent here, or signed with a " +
                            "different library's security key.",
                        e,
                    )
                } else {
                    Log.w(TAG, "Failed to resolve play config for $videoId", e)
                }
                onResult(false, classify(e))
            }
        }
    }

    fun cancelDownload(context: Context, cacheKey: String) {
        DownloadService.sendRemoveDownload(
            context,
            BunnyDownloadService::class.java,
            cacheKey,
            /* foreground = */ false,
        )
        BunnyOfflineMetadataStore.delete(context, cacheKey)
        emit(BunnyDownloadProgress(cacheKey, BunnyDownloadState.CANCELLED, 0f, 0))
    }

    /** Same mechanics as cancel; separate name because the intent differs. */
    fun deleteDownload(context: Context, cacheKey: String) =
        cancelDownload(context, cacheKey)

    /**
     * Drops every download and its metadata. Used by the logout wipe, where
     * removing one key at a time would leave orphans if the index and the
     * store had already diverged.
     */
    fun deleteAll(context: Context) {
        DownloadService.sendRemoveAllDownloads(
            context,
            BunnyDownloadService::class.java,
            /* foreground = */ false,
        )
        BunnyOfflineMetadataStore.deleteAll(context)
        BunnyDownloadStore.deleteAll(context)
        // Holding a signed playlist URL past logout would let the next user
        // start a download against the previous session's authorization.
        BunnyPlayConfigCache.clear()
    }

    /** Every completed download the store is holding. */
    fun listDownloads(context: Context): List<BunnyOfflineVideo> {
        val index = BunnyDownloadManagerProvider.get(context).downloadIndex
        val downloads = mutableListOf<BunnyOfflineVideo>()

        index.getDownloads(Download.STATE_COMPLETED).use { cursor ->
            while (cursor.moveToNext()) {
                val download = cursor.download
                downloads.add(
                    BunnyOfflineVideo(
                        cacheKey = download.request.id,
                        bytesDownloaded = download.bytesDownloaded,
                    ),
                )
            }
        }
        return downloads
    }

    /** True when [cacheKey] is downloaded and replayable with no network. */
    fun isDownloaded(context: Context, cacheKey: String): Boolean {
        val download = BunnyDownloadManagerProvider.get(context)
            .downloadIndex
            .getDownload(cacheKey)
        return download?.state == Download.STATE_COMPLETED &&
            BunnyOfflineMetadataStore.has(context, cacheKey)
    }

    /** The offline media item for [cacheKey], or null when not downloaded. */
    fun offlineMediaItem(context: Context, cacheKey: String): MediaItem? {
        val download = BunnyDownloadManagerProvider.get(context)
            .downloadIndex
            .getDownload(cacheKey) ?: return null
        if (download.state != Download.STATE_COMPLETED) return null

        return download.request.toMediaItem()
    }

    fun addProgressListener(listener: (BunnyDownloadProgress) -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeProgressListener(listener: (BunnyDownloadProgress) -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private fun ensureListener(context: Context) {
        if (managerListener != null) return

        val manager = BunnyDownloadManagerProvider.get(context)
        val listener = object : DownloadManager.Listener {
            override fun onDownloadChanged(
                downloadManager: DownloadManager,
                download: Download,
                finalException: Exception?,
            ) {
                emit(download.toProgress(finalException))
            }

            override fun onDownloadRemoved(
                downloadManager: DownloadManager,
                download: Download,
            ) {
                emit(
                    BunnyDownloadProgress(
                        download.request.id,
                        BunnyDownloadState.CANCELLED,
                        0f,
                        0,
                    ),
                )
            }
        }
        manager.addListener(listener)
        managerListener = listener
    }

    private fun emit(progress: BunnyDownloadProgress) {
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach {
            try {
                it(progress)
            } catch (e: Exception) {
                Log.w(TAG, "Download progress listener threw", e)
            }
        }
    }

    private fun Download.toProgress(finalException: Exception?): BunnyDownloadProgress {
        val state = when (state) {
            Download.STATE_QUEUED, Download.STATE_RESTARTING -> BunnyDownloadState.QUEUED
            Download.STATE_DOWNLOADING -> BunnyDownloadState.DOWNLOADING
            Download.STATE_COMPLETED -> BunnyDownloadState.DOWNLOADED
            Download.STATE_FAILED -> BunnyDownloadState.FAILED
            Download.STATE_REMOVING -> BunnyDownloadState.CANCELLED
            // STATE_STOPPED means a requirement is unmet — waiting for Wi-Fi
            // reads as queued rather than failed, because it will resume.
            Download.STATE_STOPPED -> BunnyDownloadState.QUEUED
            else -> BunnyDownloadState.QUEUED
        }

        return BunnyDownloadProgress(
            cacheKey = request.id,
            state = state,
            progress = if (percentDownloaded < 0) -1f else percentDownloaded / 100f,
            bytesDownloaded = bytesDownloaded,
            error = if (state == BunnyDownloadState.FAILED) {
                classify(finalException)
            } else {
                null
            },
        )
    }

    /** Outcome of checking a caller-supplied `token`/`expires` pair. */
    private sealed interface TokenAuth {
        /** No token auth requested; the library had better not require it. */
        data object None : TokenAuth

        data class Signed(val token: String, val expires: Long) : TokenAuth

        data class Invalid(val reason: String) : TokenAuth
    }

    /**
     * Anything past this is a millisecond timestamp: as seconds it lands in
     * the year 5138, which nobody is signing a lesson for.
     */
    private const val MILLIS_THRESHOLD = 100_000_000_000L

    /**
     * Checks the token pair before spending a round trip on it.
     *
     * Bunny answers 401 for every malformed variant — no expires, a
     * millisecond expires, an elapsed expires — so without this the caller
     * gets one indistinguishable failure for four different mistakes.
     */
    private fun normalizeTokenAuth(
        videoId: String,
        token: String?,
        expires: Long?,
        nowSeconds: Long = System.currentTimeMillis() / 1000L,
    ): TokenAuth {
        val cleanToken = token?.trim()?.takeIf { it.isNotEmpty() }
        val rawExpires = expires?.takeIf { it > 0L }

        if (cleanToken == null) {
            return if (rawExpires == null) {
                TokenAuth.None
            } else {
                TokenAuth.Invalid(
                    "expires=$rawExpires was supplied without a token; Bunny validates " +
                        "the pair together and refuses half of it",
                )
            }
        }

        if (rawExpires == null) {
            return TokenAuth.Invalid(
                "a token was supplied without expires. The token is " +
                    "SHA256(securityKey + videoId + expires), so Bunny cannot check it " +
                    "without the same expires it was signed with",
            )
        }

        // A caller that passed System.currentTimeMillis() straight through is
        // making a fixable mistake, not an unrecoverable one.
        val expiresSeconds = if (rawExpires >= MILLIS_THRESHOLD) {
            Log.w(
                TAG,
                "expires=$rawExpires for $videoId looks like milliseconds; Bunny wants " +
                    "seconds. Using ${rawExpires / 1000}.",
            )
            rawExpires / 1000
        } else {
            rawExpires
        }

        if (expiresSeconds <= nowSeconds) {
            return TokenAuth.Invalid(
                "the token expired at $expiresSeconds (now $nowSeconds). Downloads are " +
                    "started long after playback began, so a token minted for playback " +
                    "has often lapsed by the time the download runs — sign a fresh one",
            )
        }

        return TokenAuth.Signed(cleanToken, expiresSeconds)
    }

    /**
     * Maps a download failure onto something a student can act on. "Your
     * storage is full" and "check your connection" call for different
     * responses, so collapsing both into a generic failure would be a
     * regression in the UI's usefulness.
     */
    private fun classify(exception: Exception?): BunnyDownloadError {
        val message = exception?.message?.lowercase().orEmpty()
        val causeMessage = exception?.cause?.message?.lowercase().orEmpty()
        val combined = "$message $causeMessage"

        return when {
            combined.contains("enospc") ||
                combined.contains("no space") ||
                combined.contains("disk full") -> BunnyDownloadError.STORAGE_FULL
            combined.contains("401") ||
                combined.contains("403") ||
                combined.contains("unauthor") -> BunnyDownloadError.UNAUTHORIZED
            combined.contains("404") ||
                combined.contains("not found") -> BunnyDownloadError.NOT_FOUND
            combined.contains("unable to resolve host") ||
                combined.contains("timeout") ||
                combined.contains("connection") ||
                combined.contains("network") -> BunnyDownloadError.NETWORK
            else -> BunnyDownloadError.UNKNOWN
        }
    }
}
