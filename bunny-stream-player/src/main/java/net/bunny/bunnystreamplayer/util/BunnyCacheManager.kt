package net.bunny.bunnystreamplayer.util

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

@UnstableApi
object BunnyCacheManager {
    private const val CACHE_SIZE_BYTES = 500 * 1024 * 1024L // 500MB
    private var simpleCache: SimpleCache? = null

    @Synchronized
    fun getSimpleCache(context: Context): SimpleCache {
        return simpleCache ?: run {
            val cacheDir = File(context.cacheDir, "media_cache")
            val evictor = LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES)
            val databaseProvider: DatabaseProvider = StandaloneDatabaseProvider(context)
            SimpleCache(cacheDir, evictor, databaseProvider).also {
                simpleCache = it
            }
        }
    }

    fun saveMetadata(context: Context, cacheKey: String, video: Any, settings: Any) {
        try {
            val file = File(context.cacheDir, "meta_$cacheKey.json")
            val data = mapOf("video" to video, "settings" to settings)
            val json = com.google.gson.Gson().toJson(data)
            file.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Need to return concrete types. passing Class<T> is better but generic erasure.
    // For simplicity, returning wrapper object or Pair
    fun loadMetadata(context: Context, cacheKey: String, videoClass: Class<*>, settingsClass: Class<*>): Pair<Any, Any>? {
        return try {
            val file = File(context.cacheDir, "meta_$cacheKey.json")
            if (!file.exists()) return null
            val json = file.readText()
            val jsonObject = com.google.gson.JsonParser.parseString(json).asJsonObject
            val video = com.google.gson.Gson().fromJson(jsonObject.get("video"), videoClass)
            val settings = com.google.gson.Gson().fromJson(jsonObject.get("settings"), settingsClass)
            Pair(video, settings)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
