package app.votube.sabr.parser

import video_streaming.UmpPartId.UMPPartId

/**
 * A parser to read UMP data.
 */
class UmpParser(private var buf: ByteArray) {
    private var position = 0

    /** Number of bytes consumed from the beginning of the current buffer. */
    fun consumedBytes(): Int = position

    private fun readByte(): UByte? {
        if (position >= buf.size) return null
        return buf[position++].toUByte()
    }

    private fun readBytes(n: Int): ByteArray? {
        if (position + n > buf.size) return null
        val result = buf.copyOfRange(position, position + n)
        position += n
        return result
    }

    /**
     * Read a variable sized integer from the buffer.
     *
     * The implementation follows https://github.com/gsuberland/UMP_Format/blob/main/UMP_Format.md#variable-sized-integers
     */
    fun readVarint(): UInt? {
        val prefix = readByte() ?: return null

        // decode the size from the first 5 bits
        // [0...4] bits corresponds to a size of 1...5 bytes
        val varintSize = minOf(prefix.inv().countLeadingZeroBits(), 4) + 1

        var shift = 0
        var result = 0u

        if (varintSize != 5) {
            shift = 8 - varintSize
            // compute mask of prefix
            val mask = (1u shl shift) - 1u
            result = result or (prefix.toUInt() and mask)
        }

        for (i in 1 until varintSize) {
            val byte = readByte()?.toUInt() ?: return null
            result = result or (byte shl shift)
            shift += 8
        }

        return result
    }

    /**
     * Returns the remaining data of the buffer.
     */
    fun data(): ByteArray {
        return buf.copyOfRange(position, buf.size)
    }

    /**
     * Read a single [Part].
     */
    fun readPart(): Part? {
        val start = position
        val ty = readVarint() ?: return null
        val umpType = UMPPartId.forNumber(ty.toInt()) ?: UMPPartId.UNKNOWN

        val size = readVarint() ?: return null
        val data = readBytes(size.toInt()) ?: run {
            position = start
            return null
        }

        return Part(umpType, data)
    }
}

/**
 * A single segment (part) of a UMP stream.
 */
data class Part(
    val type: UMPPartId,
    val data: ByteArray,
)
