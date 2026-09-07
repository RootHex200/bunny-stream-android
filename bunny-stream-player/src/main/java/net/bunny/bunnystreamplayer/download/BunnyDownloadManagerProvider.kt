package net.bunny.bunnystreamplayer.download

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.scheduler.Requirements
import net.bunny.bunnystreamplayer.util.BunnyDownloadStore
import java.util.concurrent.Executors

/**
 * The single [DownloadManager] the SDK downloads through.
 *
 * Replaces the prototype's `GlobalScope` coroutine, which could not survive
 * process death, could not be cancelled, and had no notion of a network
 * constraint — the three things R2, R3 and R4 all need. `DownloadManager`
 * gives queueing, restart resumption, requirement-based gating and a download
 * index to list from, none of which would be worth reimplementing by hand.
 */
@UnstableApi
object BunnyDownloadManagerProvider {

    /**
     * What the official Bunny embed sends, and what a library with "Block
     * direct URL access" enabled expects to see. Without it the CDN answers
     * the playlist and every segment with 403 — playback sets this header
     * (see `DefaultBunnyPlayer`) which is why streaming can work while a
     * download silently fails.
     */
    const val DEFAULT_REFERER = "https://iframe.mediadelivery.net/"

    @Volatile
    private var downloadManager: DownloadManager? = null

    /** Kept so the Wi-Fi-only preference can be changed at runtime. */
    @Volatile
    private var wifiOnly: Boolean = true

    /**
     * Referer sent with every download request.
     *
     * Held here rather than on the request because media3's [DownloadRequest]
     * carries no headers, and the one [DownloadManager] serves every download.
     * Callers that use a custom allowed-referrer set it through
     * [BunnyOfflineManager.startDownload] before the download is enqueued.
     */
    @Volatile
    private var referer: String = DEFAULT_REFERER

    @Synchronized
    fun get(context: Context): DownloadManager {
        downloadManager?.let { return it }

        val appContext = context.applicationContext
        val databaseProvider = StandaloneDatabaseProvider(appContext)

        // Downloads are written through the encrypted store, so what lands on
        // disk is ciphertext even though the transfer itself is ordinary HTTP.
        val cacheDataSourceFactory = BunnyDownloadStore.cacheDataSourceFactory(
            appContext,
            upstream = httpDataSourceFactory(appContext),
        )

        return DownloadManager(
            appContext,
            DefaultDownloadIndex(databaseProvider),
            DefaultDownloaderFactory(
                cacheDataSourceFactory,
                Executors.newFixedThreadPool(MAX_PARALLEL_DOWNLOADS),
            ),
        ).apply {
            maxParallelDownloads = MAX_PARALLEL_DOWNLOADS
            requirements = requirementsFor(wifiOnly)
            downloadManager = this
        }
    }

    /**
     * Sets the Referer for subsequent downloads; null or blank restores
     * [DEFAULT_REFERER]. Read per data source rather than baked into the
     * factory, so a change applies to downloads that resume later too.
     */
    fun setReferer(value: String?) {
        referer = value?.takeIf { it.isNotBlank() } ?: DEFAULT_REFERER
    }

    /**
     * Mirrors the playback data source in `DefaultBunnyPlayer`: same Referer,
     * same user agent, same redirect handling. A download that presents itself
     * differently from playback is a download that a hardened library rejects.
     *
     * Internal rather than private so `BunnyDownloadRefererTest` can prove the
     * header reaches the wire; reading it back off a live `DownloadManager` is
     * not something media3 exposes.
     */
    internal fun httpDataSourceFactory(context: Context): DataSource.Factory {
        val factory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(Util.getUserAgent(context, "BunnyStreamPlayer"))

        return DataSource.Factory {
            val dataSource: HttpDataSource = factory.createDataSource()
            dataSource.setRequestProperty("Referer", referer)
            dataSource
        }
    }

    /**
     * Applies the Wi-Fi-only preference.
     *
     * Unmetered-network is what makes a cellular drop pause the download and a
     * Wi-Fi return resume it, with no app involvement — the platform handles
     * the transition. Changeable at runtime, which is why Android needs no
     * equivalent of the iOS two-session workaround.
     */
    @Synchronized
    fun setWifiOnly(context: Context, enabled: Boolean) {
        wifiOnly = enabled
        get(context).requirements = requirementsFor(enabled)
    }

    private fun requirementsFor(wifiOnly: Boolean): Requirements =
        if (wifiOnly) {
            Requirements(Requirements.NETWORK_UNMETERED)
        } else {
            Requirements(Requirements.NETWORK)
        }

    private const val MAX_PARALLEL_DOWNLOADS = 2
}
