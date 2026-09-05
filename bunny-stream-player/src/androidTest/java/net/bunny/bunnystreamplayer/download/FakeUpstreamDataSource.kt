package net.bunny.bunnystreamplayer.download

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import kotlin.math.min

/**
 * Serves fixed bytes in place of the network, so the store's encryption can be
 * exercised without a Bunny library, a token, or a real video.
 */
@UnstableApi
class FakeUpstreamDataSource(private val payload: ByteArray) : BaseDataSource(true) {

    private var position = 0
    private var opened = false
    private var uri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        position = dataSpec.position.toInt()
        opened = true
        transferStarted(dataSpec)
        return (payload.size - position).toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= payload.size) return -1

        val toCopy = min(length, payload.size - position)
        System.arraycopy(payload, position, buffer, offset, toCopy)
        position += toCopy
        bytesTransferred(toCopy)
        return toCopy
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
