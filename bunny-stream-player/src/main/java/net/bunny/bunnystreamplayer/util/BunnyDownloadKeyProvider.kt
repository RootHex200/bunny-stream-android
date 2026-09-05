package net.bunny.bunnystreamplayer.util

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.security.SecureRandom

/**
 * Owns the AES key that protects downloaded video.
 *
 * The key is a raw 16-byte value held in [EncryptedSharedPreferences], which
 * is itself sealed under a hardware-backed Keystore master key. It is
 * deliberately *not* a Keystore-resident AES key: media3's cipher data source
 * takes raw key bytes, and a Keystore-resident key is non-exportable — its
 * `encoded` is null by design. Wrapping a raw key under a Keystore master key
 * gets the same at-rest protection while still handing media3 something it
 * can use.
 *
 * The key is per install, not per user. Logout deletes the downloaded content
 * rather than rotating this, so an interrupted wipe can never strand files
 * that are undecryptable but still occupying space.
 */
internal object BunnyDownloadKeyProvider {

    private const val PREFS_FILE = "bunny_download_keys"
    private const val KEY_NAME = "bunny_download_key_v1"

    /** AES-128. Matches what media3's cipher data source expects. */
    private const val KEY_LENGTH_BYTES = 16

    @Volatile
    private var cachedKey: ByteArray? = null

    /**
     * The content key, minting one on first use.
     *
     * A stored value of the wrong length is replaced rather than thrown on:
     * anything encrypted under a corrupt key is unrecoverable either way, and
     * the caller re-downloads.
     */
    @Synchronized
    fun getOrCreateKey(context: Context): ByteArray {
        cachedKey?.let { return it }

        val prefs = encryptedPrefs(context)
        val stored = prefs.getString(KEY_NAME, null)
        if (stored != null) {
            val decoded = Base64.decode(stored, Base64.NO_WRAP)
            if (decoded.size == KEY_LENGTH_BYTES) {
                return decoded.also { cachedKey = it }
            }
        }

        val key = ByteArray(KEY_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_NAME, Base64.encodeToString(key, Base64.NO_WRAP))
            .apply()
        return key.also { cachedKey = it }
    }

    /**
     * Drops the key, orphaning anything encrypted under it.
     *
     * Not called at logout — see the class docs. Present for tests and for a
     * full uninstall-equivalent reset.
     */
    @Synchronized
    fun clearKey(context: Context) {
        cachedKey = null
        encryptedPrefs(context).edit().remove(KEY_NAME).apply()
    }

    /**
     * Uses the security-crypto 1.0.0 [MasterKeys] API rather than the newer
     * `MasterKey.Builder`, which only exists in 1.1.0-alpha. A shipped SDK
     * should not carry an alpha dependency for an API whose stable equivalent
     * does the same job.
     */
    @Suppress("DEPRECATION")
    private fun encryptedPrefs(context: Context) =
        EncryptedSharedPreferences.create(
            PREFS_FILE,
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context.applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
}
