package net.bunny.bunnystreamplayer.download

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
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

    @Volatile
    private var downloadManager: DownloadManager? = null

    /** Kept so the Wi-Fi-only preference can be changed at runtime. */
    @Volatile
    private var wifiOnly: Boolean = true

    @Synchronized
    fun get(context: Context): DownloadManager {
        downloadManager?.let { return it }

        val appContext = context.applicationContext
        val databaseProvider = StandaloneDatabaseProvider(appContext)

        // Downloads are written through the encrypted store, so what lands on
        // disk is ciphertext even though the transfer itself is ordinary HTTP.
        val cacheDataSourceFactory = BunnyDownloadStore.cacheDataSourceFactory(
            appContext,
            upstream = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true),
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
        downloadManager?.requirements = requirementsFor(enabled)
            ?: get(context).let { requirementsFor(enabled) }
    }

    private fun requirementsFor(wifiOnly: Boolean): Requirements =
        if (wifiOnly) {
            Requirements(Requirements.NETWORK_UNMETERED)
        } else {
            Requirements(Requirements.NETWORK)
        }

    private const val MAX_PARALLEL_DOWNLOADS = 2
}
