package app.votube.sabr.player

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import app.votube.sabr.parser.CompositeBuffer
import app.votube.sabr.parser.PlaybackRequest
import app.votube.sabr.parser.SabrClient
import java.io.IOException

@OptIn(UnstableApi::class)
class SabrDataSource(
    private val sabrClient: SabrClient,
) : BaseDataSource(true) {
    private var data: CompositeBuffer? = null

    class Factory(
        private val sabrClient: SabrClient
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = SabrDataSource(sabrClient)
    }

    override fun open(dataSpec: DataSpec): Long {
        val playbackRequest = dataSpec.customData as PlaybackRequest?

        transferInitializing(dataSpec)
        transferStarted(dataSpec)
        val segment = try {
            sabrClient.getNextSegment(playbackRequest!!)!!
        } catch (e: Exception) {
            Log.e(
                SabrClient::class.java.name,
                "open: failed to get segment ${playbackRequest!!.segment} for ${playbackRequest.format.itag}: $e"
            )
            throw IOException(e)
        }

        data = CompositeBuffer(segment.data)
        return data!!.remaining().toLong()
    }

    override fun getUri(): Uri? {
        if (data?.hasRemaining() != true) {
            // signal that this data source failed to be opened
            return null
        }
        return Uri.parse("sabr://segment")
    }

    override fun close() {
        data = null
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val lengthToRead = minOf(maxOf(length, 0), data?.remaining() ?: 0)
        if (lengthToRead == 0) {
            return C.RESULT_END_OF_INPUT
        }
        data!!.read(buffer, offset, lengthToRead)
        return lengthToRead
    }
}
