package net.bunny.bunnystreamplayer.util

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.AesCipherDataSink
import androidx.media3.datasource.AesCipherDataSource
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * The on-disk home for downloaded video, and the only way in or out of it.
 *
 * Two things make this a *download* store rather than a cache:
 *
 * 1. It lives under the app's no-backup directory. `cacheDir` is evictable
 *    under storage pressure, which is fine for a streaming cache and wrong for
 *    something a student expects to still be there on a plane. The no-backup
 *    location also keeps the media out of Android auto-backup by construction,
 *    not just by manifest rule.
 * 2. It uses [NoOpCacheEvictor]. An LRU evictor silently discarding a
 *    student's download is exactly the failure this feature exists to avoid.
 *
 * Everything written passes through [AesCipherDataSink] and everything read
 * through [AesCipherDataSource], so the segment files on disk are ciphertext.
 * Pulled off the device they are unplayable in any other app, which is the
 * whole protection bar: stop casual sharing, not defeat a rooted attacker.
 */
@UnstableApi
object BunnyDownloadStore {

    /**
     * Directory name under the app's no-backup files dir. Referenced by the
     * host app's backup-exclusion rules, so renaming it means updating those
     * too.
     */
    private const val DOWNLOAD_DIR = "bunny_downloads"

    @Volatile
    private var cache: SimpleCache? = null

    @Synchronized
    fun getCache(context: Context): Cache {
        cache?.let { return it }

        val databaseProvider: DatabaseProvider =
            StandaloneDatabaseProvider(context.applicationContext)

        return SimpleCache(
            downloadDirectory(context),
            // Never evict: a download is content the student asked to keep.
            NoOpCacheEvictor(),
            databaseProvider,
        ).also { cache = it }
    }

    /**
     * Where downloads live: app-private, durable, and excluded from backup.
     */
    fun downloadDirectory(context: Context): File =
        File(context.applicationContext.noBackupFilesDir, DOWNLOAD_DIR)
            .apply { if (!exists()) mkdirs() }

    /**
     * Wraps [upstream] so downloaded bytes are encrypted on the way to disk
     * and decrypted on the way back out.
     *
     * @param upstream where a cache miss is fetched from. Pass null for
     *   offline playback: reads then fail on a miss instead of silently
     *   reaching the network, which is what keeps offline playback honest.
     */
    fun cacheDataSourceFactory(
        context: Context,
        upstream: DataSource.Factory?,
    ): CacheDataSource.Factory {
        val key = BunnyDownloadKeyProvider.getOrCreateKey(context)
        val cache = getCache(context)

        val encryptingSink = DataSink.Factory {
            AesCipherDataSink(key, CacheDataSink(cache, CacheDataSink.DEFAULT_FRAGMENT_SIZE))
        }
        val decryptingSource = DataSource.Factory {
            AesCipherDataSource(key, FileDataSource())
        }

        return CacheDataSource.Factory()
            .setCache(cache)
            .setCacheWriteDataSinkFactory(encryptingSink)
            .setCacheReadDataSourceFactory(decryptingSource)
            .apply {
                if (upstream == null) {
                    // Cache-only: a miss surfaces as an error rather than a
                    // quiet network fetch. Note this deliberately does NOT set
                    // FLAG_IGNORE_CACHE_ON_ERROR, which the old prototype used
                    // and which would fall back to the network.
                    setUpstreamDataSourceFactory(null)
                    setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
                } else {
                    setUpstreamDataSourceFactory(upstream)
                }
            }
    }

    /**
     * Releases the cache. Downloads survive; only the in-process handle goes.
     */
    @Synchronized
    fun release() {
        cache?.release()
        cache = null
    }

    /**
     * Deletes every downloaded byte and the store's own bookkeeping.
     *
     * Used by the logout wipe. The content key is left alone on purpose: it is
     * per install, and rotating it here would risk stranding files that a
     * partially-completed wipe left behind.
     */
    @Synchronized
    fun deleteAll(context: Context) {
        release()
        downloadDirectory(context).deleteRecursively()
    }
}
