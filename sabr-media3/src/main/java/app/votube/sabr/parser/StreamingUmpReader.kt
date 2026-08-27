package app.votube.sabr.parser

import java.io.EOFException
import java.io.InputStream

/**
 * Reads UMP parts incrementally from an HTTP response.
 *
 * The reader buffers only bytes that are needed to complete the next UMP part. It does not
 * materialize the complete response in memory.
 */
class StreamingUmpReader(
    private val input: InputStream,
    private val readBufferSize: Int = DEFAULT_READ_BUFFER_SIZE,
) {
    private var buffer = ByteArray(0)
    private var position = 0
    private var endOfInput = false

    fun readPart(): Part? {
        val type = readVarint() ?: return null
        val size = readVarint() ?: throw EOFException("Truncated UMP part size")
        if (size > Int.MAX_VALUE.toUInt()) {
            throw IllegalArgumentException("UMP part is too large: $size")
        }
        val data = readBytes(size.toInt())
        val umpType = video_streaming.UmpPartId.UMPPartId.forNumber(type.toInt())
            ?: video_streaming.UmpPartId.UMPPartId.UNKNOWN
        return Part(umpType, data)
    }

    private fun readVarint(): UInt? {
        val first = readByte() ?: return null
        val varintSize = minOf(first.toUByte().inv().countLeadingZeroBits(), 4) + 1
        var shift = 0
        var result = 0u
        if (varintSize != 5) {
            shift = 8 - varintSize
            result = first.toUInt() and ((1u shl shift) - 1u)
        }
        repeat(varintSize - 1) {
            result = result or (readByteOrThrow().toUInt() shl shift)
            shift += 8
        }
        return result
    }

    private fun readBytes(length: Int): ByteArray {
        val result = ByteArray(length)
        var copied = 0
        while (copied < length) {
            val available = buffer.size - position
            if (available == 0) {
                fill() 
                if (buffer.isEmpty()) throw EOFException("Truncated UMP part data")
                continue
            }
            val count = minOf(available, length - copied)
            buffer.copyInto(result, copied, position, position + count)
            position += count
            copied += count
        }
        compact()
        return result
    }

    private fun readByte(): UByte? {
        while (position >= buffer.size) {
            fill()
            if (buffer.isEmpty()) return null
        }
        return buffer[position++].toUByte()
    }

    private fun readByteOrThrow(): UByte =
        readByte() ?: throw EOFException("Truncated UMP varint")

    private fun fill() {
        if (endOfInput) return
        val chunk = ByteArray(readBufferSize)
        val count = input.read(chunk)
        if (count < 0) {
            endOfInput = true
            buffer = ByteArray(0)
            position = 0
        } else if (count > 0) {
            buffer = chunk.copyOf(count)
            position = 0
        }
    }

    private fun compact() {
        if (position == buffer.size) {
            buffer = ByteArray(0)
            position = 0
        }
    }

    companion object {
        private const val DEFAULT_READ_BUFFER_SIZE = 16 * 1024
    }
}
