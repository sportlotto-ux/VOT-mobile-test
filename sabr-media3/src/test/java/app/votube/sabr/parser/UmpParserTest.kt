package app.votube.sabr.parser

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import video_streaming.NextRequestPolicyOuterClass.NextRequestPolicy
import video_streaming.PlaybackStartPolicyOuterClass.PlaybackStartPolicy
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
    fun `readPart preserves playback start policy payload`() {
        val policy = PlaybackStartPolicy.newBuilder()
            .setStartMinReadaheadPolicy(
                PlaybackStartPolicy.ReadaheadPolicy.newBuilder()
                    .setMinReadaheadMs(1500)
                    .setMinBandwidthBytesPerSec(128000)
            )
            .setResumeMinReadaheadPolicy(
                PlaybackStartPolicy.ReadaheadPolicy.newBuilder()
                    .setMinReadaheadMs(500)
            )
            .build()
        val part = UmpParser(encodePart(UMPPartId.PLAYBACK_START_POLICY, policy.toByteArray())).readPart()
        val decoded = PlaybackStartPolicy.parseFrom(part?.data)

        assertEquals(UMPPartId.PLAYBACK_START_POLICY, part?.type)
        assertEquals(1500, decoded.startMinReadaheadPolicy.minReadaheadMs)
        assertEquals(128000, decoded.startMinReadaheadPolicy.minBandwidthBytesPerSec)
        assertEquals(500, decoded.resumeMinReadaheadPolicy.minReadaheadMs)
    }

    @Test
    fun `readPart preserves next request policy payload`() {
        val policy = NextRequestPolicy.newBuilder()
            .setTargetAudioReadaheadMs(3000)
            .setTargetVideoReadaheadMs(6000)
            .setMinAudioReadaheadMs(1000)
            .setMinVideoReadaheadMs(2000)
            .setMaxTimeSinceLastRequestMs(8000)
            .setBackoffTimeMs(250)
            .setVideoId("video-id")
            .build()
        val part = UmpParser(encodePart(UMPPartId.NEXT_REQUEST_POLICY, policy.toByteArray())).readPart()
        val decoded = NextRequestPolicy.parseFrom(part?.data)

        assertEquals(UMPPartId.NEXT_REQUEST_POLICY, part?.type)
        assertEquals(3000, decoded.targetAudioReadaheadMs)
        assertEquals(6000, decoded.targetVideoReadaheadMs)
        assertEquals(1000, decoded.minAudioReadaheadMs)
        assertEquals(2000, decoded.minVideoReadaheadMs)
        assertEquals(8000, decoded.maxTimeSinceLastRequestMs)
        assertEquals(250, decoded.backoffTimeMs)
        assertEquals("video-id", decoded.videoId)
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
