package net.bunny.bunnystreamplayer.download

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.bunny.bunnystreamplayer.util.BunnyDownloadKeyProvider
import net.bunny.bunnystreamplayer.util.BunnyDownloadStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import android.net.Uri
import java.io.File

/**
 * Proves the protection bar on a real Android runtime: bytes written through
 * the store are ciphertext on disk, the store lives where the OS will not back
 * it up or evict it, and the key survives a process-lifetime boundary.
 *
 * Instrumented rather than a unit test because Keystore-backed
 * EncryptedSharedPreferences and media3's cache both need a real device.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class BunnyDownloadStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** A recognisable plaintext pattern, long enough to survive block framing. */
    private val plaintext = ("BUNNY-PLAINTEXT-MARKER-" + "0123456789".repeat(64))
        .toByteArray()

    @Before
    fun setUp() {
        BunnyDownloadStore.deleteAll(context)
    }

    @After
    fun tearDown() {
        BunnyDownloadStore.deleteAll(context)
        BunnyDownloadKeyProvider.clearKey(context)
    }

    @Test
    fun downloadDirectoryIsAppPrivateAndExcludedFromBackup() {
        val dir = BunnyDownloadStore.downloadDirectory(context)

        // noBackupFilesDir is both durable under storage pressure and skipped
        // by Android auto-backup by construction.
        assertTrue(
            "store must live under noBackupFilesDir, was ${dir.absolutePath}",
            dir.absolutePath.startsWith(context.noBackupFilesDir.absolutePath)
        )
        assertFalse(
            "store must not live in the evictable cacheDir",
            dir.absolutePath.startsWith(context.cacheDir.absolutePath)
        )
    }

    @Test
    fun contentRoundTripsThroughTheStore() {
        val uri = Uri.parse("https://example.test/segment-roundtrip.ts")
        writeThroughStore(uri, plaintext)

        val readBack = readThroughStore(uri, plaintext.size)

        assertArrayEquals("what goes in must come out", plaintext, readBack)
    }

    @Test
    fun onDiskBytesAreNotPlaintext() {
        val uri = Uri.parse("https://example.test/segment-secret.ts")
        writeThroughStore(uri, plaintext)

        val marker = "BUNNY-PLAINTEXT-MARKER".toByteArray()
        val offenders = BunnyDownloadStore.downloadDirectory(context)
            .walkTopDown()
            .filter { it.isFile }
            .filter { it.readBytes().containsSequence(marker) }
            .toList()

        assertTrue(
            "plaintext marker found on disk in: ${offenders.map(File::getName)}",
            offenders.isEmpty()
        )
    }

    @Test
    fun cacheIsASingleSharedInstance() {
        // Two SimpleCache instances over one directory corrupt the index.
        val first = BunnyDownloadStore.getCache(context)
        val second = BunnyDownloadStore.getCache(context)

        assertTrue("store must hand out one shared cache", first === second)
    }

    @Test
    fun keyIsStableAcrossCacheClears() {
        val first = BunnyDownloadKeyProvider.getOrCreateKey(context)
        // Simulates a fresh process reading the persisted key back.
        BunnyDownloadStore.release()
        val second = BunnyDownloadKeyProvider.getOrCreateKey(context)

        assertArrayEquals(
            "a changed key would orphan every existing download",
            first,
            second
        )
    }

    @Test
    fun keyIsUsableRawBytesOfExpectedLength() {
        val key = BunnyDownloadKeyProvider.getOrCreateKey(context)

        // The check that would have caught a Keystore-resident AES key, whose
        // encoded form is null and cannot feed media3's cipher layer.
        assertNotNull(key)
        assertEquals("AES-128 raw key expected", 16, key.size)
        assertFalse("key must not be all zeroes", key.all { it == 0.toByte() })
    }

    @Test
    fun deleteAllRemovesEveryDownloadedByte() {
        val uri = Uri.parse("https://example.test/segment-delete.ts")
        writeThroughStore(uri, plaintext)

        BunnyDownloadStore.deleteAll(context)

        val remaining = BunnyDownloadStore.downloadDirectory(context)
            .walkTopDown()
            .filter { it.isFile }
            .toList()
        assertTrue(
            "logout must leave nothing behind, found: ${remaining.map(File::getName)}",
            remaining.isEmpty()
        )
    }

    // --- helpers ---

    private fun writeThroughStore(uri: Uri, bytes: ByteArray) {
        val factory = BunnyDownloadStore.cacheDataSourceFactory(
            context,
            upstream = { FakeUpstreamDataSource(bytes) },
        )
        val sink = factory.createDataSource()
        val spec = DataSpec.Builder().setUri(uri).build()
        sink.open(spec)
        try {
            val buffer = ByteArray(4096)
            while (sink.read(buffer, 0, buffer.size) != -1) {
                // Draining pulls the bytes through the cipher sink onto disk.
            }
        } finally {
            sink.close()
        }
    }

    private fun readThroughStore(uri: Uri, length: Int): ByteArray {
        // No upstream: a miss must fail rather than silently refetch, so a
        // successful read here proves the bytes came from the encrypted store.
        val factory = BunnyDownloadStore.cacheDataSourceFactory(context, upstream = null)
        val source = factory.createDataSource()
        source.open(DataSpec.Builder().setUri(uri).build())
        try {
            val out = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = source.read(out, read, length - read)
                if (n == -1) break
                read += n
            }
            return out.copyOf(read)
        } finally {
            source.close()
        }
    }

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || size < needle.size) return false
        outer@ for (i in 0..(size - needle.size)) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
