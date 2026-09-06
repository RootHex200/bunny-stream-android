package net.bunny.android.demo.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.bunny.android.demo.App
import net.bunny.bunnystreamplayer.download.BunnyDownloadError
import net.bunny.bunnystreamplayer.download.BunnyDownloadManagerProvider
import net.bunny.bunnystreamplayer.download.BunnyDownloadProgress
import net.bunny.bunnystreamplayer.download.BunnyDownloadState
import net.bunny.bunnystreamplayer.download.BunnyOfflineManager

data class DownloadRow(
    val cacheKey: String,
    val sizeBytes: Long,
)

data class DownloadsUiState(
    val videoId: String = "",
    val libraryId: String = "",
    val token: String = "",
    val expires: String = "",
    val referer: String = "",
    val cacheKey: String = "",
    val wifiOnly: Boolean = false,
    val completed: List<DownloadRow> = emptyList(),
    val live: Map<String, BunnyDownloadProgress> = emptyMap(),
    val message: String? = null,
    val starting: Boolean = false,
)

/**
 * Drives the offline API directly rather than through
 * `BunnyStreamPlayer.downloadCurrentVideo`, so a download can be exercised
 * without first resolving a video in a player. This is the same entry point
 * the Flutter plugin uses, which makes this screen the place to reproduce a
 * plugin bug natively.
 */
@UnstableApi
class DownloadsViewModel : ViewModel() {

    private val context = App.di.context

    private val _state = MutableStateFlow(DownloadsUiState())
    val state = _state.asStateFlow()

    /**
     * Kept as a field because the manager stores listeners in a Set — removing
     * one needs the identical instance that was added.
     */
    private val progressListener: (BunnyDownloadProgress) -> Unit = { progress ->
        // Fires on media3's download thread, not main. StateFlow tolerates that.
        _state.update { it.copy(live = it.live + (progress.cacheKey to progress)) }
        if (progress.state == BunnyDownloadState.DOWNLOADED) refresh()
    }

    init {
        BunnyOfflineManager.addProgressListener(progressListener)
        viewModelScope.launch(Dispatchers.IO) {
            // setWifiOnly cannot apply a requirement to a manager that does not
            // exist yet, so force it into being before anyone toggles it.
            BunnyDownloadManagerProvider.get(context)
        }
        refresh()
    }

    override fun onCleared() {
        BunnyOfflineManager.removeProgressListener(progressListener)
        super.onCleared()
    }

    fun refresh() {
        viewModelScope.launch {
            // listDownloads reads the download database; it must not run on main.
            val rows = withContext(Dispatchers.IO) {
                BunnyOfflineManager.listDownloads(context)
                    .map { DownloadRow(cacheKey = it.cacheKey, sizeBytes = it.bytesDownloaded) }
            }
            _state.update { it.copy(completed = rows) }
        }
    }

    fun start() {
        val current = _state.value
        val videoId = current.videoId.trim()
        val libraryId = current.libraryId.trim().toLongOrNull()

        if (videoId.isEmpty() || libraryId == null) {
            _state.update { it.copy(message = "Video ID and a numeric Library ID are required") }
            return
        }

        // Defaulting the key to the video id keeps the common case one field shorter.
        val cacheKey = current.cacheKey.trim().ifEmpty { videoId }

        _state.update { it.copy(starting = true, message = null) }
        BunnyDownloadManagerProvider.setWifiOnly(context, current.wifiOnly)

        BunnyOfflineManager.startDownload(
            context = context,
            scope = viewModelScope,
            cacheKey = cacheKey,
            videoId = videoId,
            libraryId = libraryId,
            token = current.token.trim().ifEmpty { null },
            expires = current.expires.trim().toLongOrNull(),
            referer = current.referer.trim().ifEmpty { null },
            title = null,
        ) { accepted, error ->
            _state.update {
                it.copy(
                    starting = false,
                    message = if (accepted) {
                        "Queued $cacheKey"
                    } else {
                        "Refused: ${error.label()}"
                    },
                )
            }
        }
    }

    fun cancel(cacheKey: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { BunnyOfflineManager.cancelDownload(context, cacheKey) }
            _state.update { it.copy(message = "Cancelled $cacheKey") }
            refresh()
        }
    }

    fun delete(cacheKey: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { BunnyOfflineManager.deleteDownload(context, cacheKey) }
            _state.update { it.copy(message = "Deleted $cacheKey", live = it.live - cacheKey) }
            refresh()
        }
    }

    fun deleteAll() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { BunnyOfflineManager.deleteAll(context) }
            _state.update { it.copy(message = "Deleted every download", live = emptyMap()) }
            refresh()
        }
    }

    fun setWifiOnly(enabled: Boolean) {
        _state.update { it.copy(wifiOnly = enabled) }
        viewModelScope.launch(Dispatchers.IO) {
            BunnyDownloadManagerProvider.get(context)
            BunnyDownloadManagerProvider.setWifiOnly(context, enabled)
        }
    }

    fun onVideoId(value: String) = _state.update { it.copy(videoId = value) }
    fun onLibraryId(value: String) = _state.update { it.copy(libraryId = value) }
    fun onToken(value: String) = _state.update { it.copy(token = value) }
    fun onExpires(value: String) = _state.update { it.copy(expires = value) }
    fun onReferer(value: String) = _state.update { it.copy(referer = value) }
    fun onCacheKey(value: String) = _state.update { it.copy(cacheKey = value) }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}

internal fun BunnyDownloadError?.label(): String = when (this) {
    BunnyDownloadError.NETWORK -> "network"
    BunnyDownloadError.STORAGE_FULL -> "storage full"
    // The manager refuses DRM videos outright, and this is how that surfaces.
    BunnyDownloadError.UNAUTHORIZED -> "unauthorized — is DRM enabled on the library?"
    BunnyDownloadError.NOT_FOUND -> "not found"
    BunnyDownloadError.UNKNOWN, null -> "unknown"
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%d KB".format(bytes / (1L shl 10))
    else -> "$bytes B"
}
