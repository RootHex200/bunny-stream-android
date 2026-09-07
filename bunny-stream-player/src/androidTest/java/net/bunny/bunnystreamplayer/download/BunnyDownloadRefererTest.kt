package net.bunny.bunnystreamplayer.download

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Proves the download path actually sends a Referer.
 *
 * This is the header a Bunny library with "Block direct URL access" enabled
 * checks against its allowed-referrers list. Playback has always sent it and
 * downloads did not, which is how a video could stream perfectly while every
 * download of it came back 403 — a difference invisible in the SDK's own logs.
 *
 * Asserted against a real socket rather than by inspecting the factory:
 * `DefaultHttpDataSource` keeps its request properties private, so the only
 * honest check is what comes out on the wire.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class BunnyDownloadRefererTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val server = RecordingServer().apply { start() }

    @After
    fun tearDown() {
        server.stop()
        // The referer is process-global; leaving a custom one set would leak
        // into whatever test runs next.
        BunnyDownloadManagerProvider.setReferer(null)
    }

    @Test
    fun sendsTheEmbedRefererByDefault() {
        BunnyDownloadManagerProvider.setReferer(null)

        assertEquals(
            BunnyDownloadManagerProvider.DEFAULT_REFERER,
            fetchThroughDownloadSource()["referer"],
        )
    }

    @Test
    fun sendsAConfiguredRefererWhenTheLibraryAllowsADifferentOrigin() {
        BunnyDownloadManagerProvider.setReferer("https://lessons.example.com/")

        assertEquals(
            "https://lessons.example.com/",
            fetchThroughDownloadSource()["referer"],
        )
    }

    @Test
    fun treatsABlankRefererAsUnset() {
        BunnyDownloadManagerProvider.setReferer("   ")

        assertEquals(
            BunnyDownloadManagerProvider.DEFAULT_REFERER,
            fetchThroughDownloadSource()["referer"],
        )
    }

    /**
     * Runs one request through the very factory the [DownloadManager] downloads
     * with, and returns the headers the server saw, keyed lower-case.
     */
    private fun fetchThroughDownloadSource(): Map<String, String> {
        val source = BunnyDownloadManagerProvider
            .httpDataSourceFactory(context)
            .createDataSource()

        try {
            source.open(
                DataSpec.Builder()
                    .setUri(Uri.parse("http://127.0.0.1:${server.port}/playlist.m3u8"))
                    .build(),
            )
            val buffer = ByteArray(64)
            while (source.read(buffer, 0, buffer.size) != C.RESULT_END_OF_INPUT) {
                // Drain, so the exchange finishes the way a real segment does.
            }
        } finally {
            source.close()
        }

        return server.awaitRequestHeaders()
    }
}

/**
 * A one-shot HTTP server that records the request headers it was sent.
 *
 * Hand-rolled rather than pulling in MockWebServer: one request, one fixed
 * response, and no new dependency on the player module.
 */
private class RecordingServer {

    private val serverSocket = ServerSocket(0)
    private val received = CountDownLatch(1)

    @Volatile
    private var headers: Map<String, String> = emptyMap()

    val port: Int get() = serverSocket.localPort

    fun start() {
        Thread {
            try {
                serverSocket.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val captured = mutableMapOf<String, String>()

                    reader.readLine() // request line, not under test
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        val separator = line.indexOf(':')
                        if (separator > 0) {
                            captured[line.substring(0, separator).trim().lowercase()] =
                                line.substring(separator + 1).trim()
                        }
                    }
                    headers = captured

                    val body = "ok"
                    socket.getOutputStream().apply {
                        write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Length: ${body.length}\r\n" +
                                    "Connection: close\r\n\r\n" +
                                    body
                                ).toByteArray(),
                        )
                        flush()
                    }
                }
            } catch (_: Exception) {
                // A closed socket during teardown is not a test failure.
            } finally {
                received.countDown()
            }
        }.apply { isDaemon = true }.start()
    }

    fun awaitRequestHeaders(): Map<String, String> {
        check(received.await(10, TimeUnit.SECONDS)) { "No request reached the test server" }
        return headers
    }

    fun stop() {
        runCatching { serverSocket.close() }
    }
}
