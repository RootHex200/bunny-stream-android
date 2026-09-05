package net.bunny.bunnystreamplayer.download

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.gson.Gson
import com.google.gson.JsonParser

/**
 * Holds the play-config payload each download needs in order to be played back
 * with no network (KTD5).
 *
 * The player normally gets its video model and settings from Bunny's
 * play-config call. Offline that call cannot happen, so the payload is
 * captured at download time and replayed from here.
 *
 * Stored in [EncryptedSharedPreferences] rather than the prototype's plaintext
 * `meta_<key>.json`: a readable JSON file sitting in the sandbox advertises
 * exactly which lessons a student downloaded, which is metadata worth not
 * leaking even when the media itself is ciphertext.
 */
internal object BunnyOfflineMetadataStore {

    private const val PREFS_FILE = "bunny_download_metadata"
    private val gson = Gson()

    fun save(context: Context, cacheKey: String, video: Any, settings: Any) {
        val payload = mapOf("video" to video, "settings" to settings)
        prefs(context).edit()
            .putString(cacheKey, gson.toJson(payload))
            .apply()
    }

    /**
     * The stored payload, or null when this download has none — which is how a
     * caller distinguishes "downloaded and replayable" from "content present
     * but unusable offline".
     */
    fun <V, S> load(
        context: Context,
        cacheKey: String,
        videoClass: Class<V>,
        settingsClass: Class<S>,
    ): Pair<V, S>? {
        val raw = prefs(context).getString(cacheKey, null) ?: return null
        return try {
            val json = JsonParser.parseString(raw).asJsonObject
            val video = gson.fromJson(json.get("video"), videoClass)
            val settings = gson.fromJson(json.get("settings"), settingsClass)
            if (video == null || settings == null) null else Pair(video, settings)
        } catch (e: Exception) {
            // A corrupt payload means this download cannot be played offline;
            // treat it as absent rather than crashing the player.
            null
        }
    }

    fun has(context: Context, cacheKey: String): Boolean =
        prefs(context).contains(cacheKey)

    fun delete(context: Context, cacheKey: String) {
        prefs(context).edit().remove(cacheKey).apply()
    }

    fun deleteAll(context: Context) {
        prefs(context).edit().clear().apply()
    }

    @Suppress("DEPRECATION")
    private fun prefs(context: Context) =
        EncryptedSharedPreferences.create(
            PREFS_FILE,
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context.applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
}
