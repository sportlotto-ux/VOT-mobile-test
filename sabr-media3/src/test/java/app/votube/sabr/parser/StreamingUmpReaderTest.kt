package app.votube.sabr.parser

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import video_streaming.UmpPartId.UMPPartId
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream

class StreamingUmpReaderTest {

    /** InputStream that returns at most [chunkSize] bytes per read() call. */
    private class ChunkedInputStream(
        private val bytes: ByteArray,
        private val chunkSize: Int,
    ) : InputStream() {
        private var position = 0

        override fun read(): Int =
            if (position < bytes.size) bytes[position++].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(chunkSize, len, bytes.size - position)
            bytes.copyInto(b, off, position, position + count)
            position += count
            return count
        }
    }

    @Test
    fun `parses parts split across arbitrary read boundaries`() {
        val fixture = listOf(
            UMPPartId.MEDIA_HEADER to ByteArray(0),
            // Larger than the reader's 16 KiB internal buffer: forces multiple fills
            // while reading a single part.
            UMPPartId.MEDIA to ByteArray(30000) { (it % 251).toByte() },
            UMPPartId.MEDIA_END to byteArrayOf(9, 8, 7),
        )
        val stream = concat(*fixture.map { (type, data) -> encodePart(type, data) }.toTypedArray())

        for (chunkSize in listOf(1, 3, 4096, 100000)) {
            val reader = StreamingUmpReader(ChunkedInputStream(stream, chunkSize))
            for ((type, data) in fixture) {
                val part = reader.readPart()
                assertEquals("chunkSize=$chunkSize", type, part?.type)
                assertArrayEquals("chunkSize=$chunkSize", data, part?.data)
            }
            assertNull("chunkSize=$chunkSize", reader.readPart())
        }
    }

    @Test
    fun `throws EOFException on truncated part data`() {
        val full = encodePart(UMPPartId.MEDIA, ByteArray(100))
        val reader = StreamingUmpReader(ByteArrayInputStream(full.copyOf(full.size - 10)))
        assertThrows(EOFException::class.java) { reader.readPart() }
    }

    @Test
    fun `throws EOFException on truncated varint`() {
        val type = encodeVarint(UMPPartId.MEDIA.number.toUInt())
        // Size varint claims 5 bytes but only 1 is present.
        val reader = StreamingUmpReader(ByteArrayInputStream(concat(type, encodeVarint(0xFFFFFFFFu).copyOf(1))))
        assertThrows(EOFException::class.java) { reader.readPart() }
    }

    @Test
    fun `throws IllegalArgumentException on oversized part`() {
        // size = 0xFFFFFFFF exceeds Int.MAX_VALUE.
        val bytes = concat(encodeVarint(UMPPartId.MEDIA.number.toUInt()), encodeVarint(0xFFFFFFFFu))
        val reader = StreamingUmpReader(ByteArrayInputStream(bytes))
        assertThrows(IllegalArgumentException::class.java) { reader.readPart() }
    }

    @Test
    fun `returns null on empty input`() {
        assertNull(StreamingUmpReader(ByteArrayInputStream(ByteArray(0))).readPart())
    }
}
