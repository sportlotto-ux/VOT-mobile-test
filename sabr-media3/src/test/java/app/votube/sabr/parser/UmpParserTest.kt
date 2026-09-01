package app.votube.sabr.parser

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import video_streaming.UmpPartId.UMPPartId

class UmpParserTest {

    @Test
    fun `varint round trips boundary values`() {
        val values = listOf(
            0u, 1u, 127u, 128u, 16383u, 16384u,
            0x1FFFFFu, 0x200000u, 0x0FFFFFFFu, 0x10000000u, 0xFFFFFFFFu,
        )
        for (value in values) {
            assertEquals("value=$value", value, UmpParser(encodeVarint(value)).readVarint())
        }
    }

    @Test
    fun `readPart parses type and data`() {
        val data = byteArrayOf(1, 2, 3, 4)
        val part = UmpParser(encodePart(UMPPartId.MEDIA_HEADER, data)).readPart()
        assertEquals(UMPPartId.MEDIA_HEADER, part?.type)
        assertArrayEquals(data, part?.data)
    }

    @Test
    fun `readPart handles empty data`() {
        val part = UmpParser(encodePart(UMPPartId.MEDIA_END, ByteArray(0))).readPart()
        assertEquals(UMPPartId.MEDIA_END, part?.type)
        assertArrayEquals(ByteArray(0), part?.data)
    }

    @Test
    fun `readPart returns null on truncated data and restores position`() {
        val bytes = encodePart(UMPPartId.MEDIA, byteArrayOf(1, 2, 3, 4))
        val parser = UmpParser(bytes.copyOf(bytes.size - 2))
        assertNull(parser.readPart())
        assertEquals(0, parser.consumedBytes())
    }

    @Test
    fun `readPart returns null at end of input`() {
        assertNull(UmpParser(ByteArray(0)).readPart())
    }

    @Test
    fun `readPart parses consecutive parts`() {
        val stream = concat(
            encodePart(UMPPartId.MEDIA_HEADER, byteArrayOf(9)),
            encodePart(UMPPartId.MEDIA, byteArrayOf(1, 2)),
            encodePart(UMPPartId.MEDIA_END, ByteArray(0)),
        )
        val parser = UmpParser(stream)
        assertEquals(UMPPartId.MEDIA_HEADER, parser.readPart()?.type)
        assertEquals(UMPPartId.MEDIA, parser.readPart()?.type)
        assertEquals(UMPPartId.MEDIA_END, parser.readPart()?.type)
        assertNull(parser.readPart())
    }
}
