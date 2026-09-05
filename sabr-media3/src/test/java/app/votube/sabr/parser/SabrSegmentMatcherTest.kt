package app.votube.sabr.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import video_streaming.MediaHeaderOuterClass.MediaHeader
import video_streaming.NextRequestPolicyOuterClass.NextRequestPolicy

/**
 * Пилот тестов на чистые функции SABR-планирования, вынесенные в [SabrSegmentMatcher].
 * Без Android и сети — только арифметика сопоставления сегментов по времени.
 */
class SabrSegmentMatcherTest {

    private val EPS = 1_500L
    private val TOLERANCE = 3_000L
    private val SKIP_CAP = 10_000L

    private fun segment(seq: Long, startMs: Long, durationMs: Long = 2000L): Segment {
        val header = MediaHeader.newBuilder()
            .setSequenceNumber(seq)
            .setStartMs(startMs)
            .setDurationMs(durationMs)
            .build()
        return Segment(header, seq, mutableListOf(), durationMs)
    }

    // ---- findTimeMatch -------------------------------------------------------

    @Test
    fun `findTimeMatch picks the earliest valid segment within eps-back to tolerance-forward`() {
        val cached = listOf(
            segment(99, 199_000),  // 2s behind request -> outside window (excluded)
            segment(100, 200_000), // 1s behind -> within SAME_TIME_EPS, valid, earliest
            segment(101, 202_000), // forward, valid
            segment(102, 205_000), // forward, valid
        )
        // request 201_000 -> window [201_000 - 1500, 201_000 + 3000] = [199_500, 204_000]
        val match = SabrSegmentMatcher.findTimeMatch(cached, lastServedSeq = null, 201_000L, EPS, TOLERANCE)
        assertEquals(100L, match?.sequenceNumber)
    }

    @Test
    fun `findTimeMatch picks the earliest forward segment when none behind`() {
        val cached = listOf(segment(101, 202_000), segment(102, 204_000))
        val match = SabrSegmentMatcher.findTimeMatch(cached, lastServedSeq = null, 201_000L, EPS, TOLERANCE)
        assertEquals(101L, match?.sequenceNumber)
    }

    @Test
    fun `findTimeMatch allows same-time renumbering (server resequenced)`() {
        val cached = listOf(segment(5, 300_000))
        // requested 300_000, candidate startMs within EPS behind -> allowed
        val match = SabrSegmentMatcher.findTimeMatch(cached, lastServedSeq = null, 300_000L, EPS, TOLERANCE)
        assertEquals(5L, match?.sequenceNumber)
    }

    @Test
    fun `findTimeMatch excludes the already-served segment`() {
        val cached = listOf(segment(100, 200_000), segment(101, 202_000))
        val match = SabrSegmentMatcher.findTimeMatch(cached, lastServedSeq = 100L, 201_000L, EPS, TOLERANCE)
        assertEquals(101L, match?.sequenceNumber)
    }

    @Test
    fun `findTimeMatch returns null when no candidate is in window`() {
        // only a far-forward segment beyond tolerance
        val cached = listOf(segment(102, 210_000))
        assertNull(SabrSegmentMatcher.findTimeMatch(cached, null, 201_000L, EPS, TOLERANCE))
    }

    @Test
    fun `findTimeMatch returns null on empty cache`() {
        assertNull(SabrSegmentMatcher.findTimeMatch(emptyList(), null, 1000L, EPS, TOLERANCE))
    }

    // ---- findSkipMatch -------------------------------------------------------

    @Test
    fun `findSkipMatch takes the smallest forward skip within cap`() {
        val cached = listOf(segment(103, 206_000), segment(105, 210_000), segment(107, 214_000))
        // hole at 201_000; both 206k and 210k within cap; earliest = 206_000
        val match = SabrSegmentMatcher.findSkipMatch(cached, null, 201_000L, SKIP_CAP, EPS)
        assertEquals(103L, match?.sequenceNumber)
    }

    @Test
    fun `findSkipMatch returns null beyond cap`() {
        val cached = listOf(segment(107, 214_000)) // 13_000 ahead > cap 10_000
        assertNull(SabrSegmentMatcher.findSkipMatch(cached, null, 201_000L, SKIP_CAP, EPS))
    }

    // ---- estimateSeqForTimeMs ------------------------------------------------

    @Test
    fun `estimateSeq exact hit returns the mapped sequence`() {
        val points = mapOf(200_000L to 100L, 202_000L to 101L, 204_000L to 102L)
        assertEquals(101L, SabrSegmentMatcher.estimateSeqForTimeMs(points, 2_000L, 202_000L))
    }

    @Test
    fun `estimateSeq interpolates inside known bracket`() {
        val points = mapOf(200_000L to 100L, 206_000L to 103L)
        // time 204_000: step 2s -> est = 100 + 4000/2000 = 102, within [100,103]
        assertEquals(102L, SabrSegmentMatcher.estimateSeqForTimeMs(points, 2_000L, 204_000L))
    }

    @Test
    fun `estimateSeq falls back to nearest neighbour on degenerate bracket`() {
        // renumbering: time increases but seq does not (upperSeq == lowerSeq)
        val points = mapOf(200_000L to 100L, 206_000L to 100L)
        // 204_000 closer to 206_000 (2k) than to 200_000 (4k) -> upper seq
        assertEquals(100L, SabrSegmentMatcher.estimateSeqForTimeMs(points, 2_000L, 204_000L))
    }

    @Test
    fun `estimateSeq returns null beyond known edges`() {
        val points = mapOf(200_000L to 100L, 206_000L to 103L)
        assertNull(SabrSegmentMatcher.estimateSeqForTimeMs(points, 2_000L, 300_000L))
        assertNull(SabrSegmentMatcher.estimateSeqForTimeMs(points, 2_000L, 100_000L))
    }

    @Test
    fun `estimateSeq returns null on empty points`() {
        assertNull(SabrSegmentMatcher.estimateSeqForTimeMs(emptyMap(), 2_000L, 1000L))
    }

    // ---- selectReadahead -----------------------------------------------------

    @Test
    fun `selectReadahead picks audio and video independently`() {
        val policy = NextRequestPolicy.newBuilder()
            .setTargetAudioReadaheadMs(1200)
            .setTargetVideoReadaheadMs(3400)
            .setMinAudioReadaheadMs(300)
            .setMinVideoReadaheadMs(900)
            .build()
        assertEquals(1200, SabrSegmentMatcher.selectReadahead(policy, isAudio = true, minimum = false))
        assertEquals(3400, SabrSegmentMatcher.selectReadahead(policy, isAudio = false, minimum = false))
        assertEquals(300, SabrSegmentMatcher.selectReadahead(policy, isAudio = true, minimum = true))
        assertEquals(900, SabrSegmentMatcher.selectReadahead(policy, isAudio = false, minimum = true))
    }

    @Test
    fun `selectReadahead returns null on missing field`() {
        val policy = NextRequestPolicy.newBuilder().setTargetVideoReadaheadMs(1000).build()
        assertNull(SabrSegmentMatcher.selectReadahead(policy, isAudio = true, minimum = false))
        assertEquals(1000, SabrSegmentMatcher.selectReadahead(policy, isAudio = false, minimum = false))
        assertNull(SabrSegmentMatcher.selectReadahead(policy, isAudio = false, minimum = true))
    }

    @Test
    fun `selectReadahead ignores negative value`() {
        val policy = NextRequestPolicy.newBuilder()
            .setTargetVideoReadaheadMs(-1)
            .setMinVideoReadaheadMs(-1)
            .build()
        assertNull(SabrSegmentMatcher.selectReadahead(policy, isAudio = false, minimum = false))
        assertNull(SabrSegmentMatcher.selectReadahead(policy, isAudio = false, minimum = true))
    }

    @Test
    fun `selectReadahead returns null when no policy`() {
        assertNull(SabrSegmentMatcher.selectReadahead(null, isAudio = false, minimum = false))
    }
}
