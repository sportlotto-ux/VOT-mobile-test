package app.votube.sabr.parser

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import app.votube.sabr.manifest.Representation
import app.votube.sabr.manifest.SabrStreamInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import video_streaming.NextRequestPolicyOuterClass.NextRequestPolicy

/**
 * Селектор readahead-политики: аудио/видео значения выбираются независимо.
 * Тестирует реальную функцию [SabrSegmentMatcher.selectReadahead] (см. SabrClient),
 * а не дубликат логики.
 */
class ReadaheadPolicyTest {
    @Test
    fun `audio and video values are selected independently`() {
        val policy = NextRequestPolicy.newBuilder()
            .setTargetAudioReadaheadMs(1200)
            .setTargetVideoReadaheadMs(3400)
            .setMinAudioReadaheadMs(300)
            .setMinVideoReadaheadMs(900)
            .build()
        val audio = representation(MimeTypes.AUDIO_MP4)
        val video = representation(MimeTypes.VIDEO_MP4)

        assertEquals(1200, target(policy, audio))
        assertEquals(3400, target(policy, video))
        assertEquals(300, minimum(policy, audio))
        assertEquals(900, minimum(policy, video))
    }

    @Test
    fun `missing policy value returns null`() {
        val policy = NextRequestPolicy.newBuilder().setTargetVideoReadaheadMs(1000).build()

        assertNull(target(policy, representation(MimeTypes.AUDIO_MP4)))
        assertEquals(1000, target(policy, representation(MimeTypes.VIDEO_MP4)))
        assertNull(minimum(policy, representation(MimeTypes.VIDEO_MP4)))
    }

    @Test
    fun `negative policy value is ignored`() {
        val policy = NextRequestPolicy.newBuilder()
            .setTargetVideoReadaheadMs(-1)
            .setMinVideoReadaheadMs(-1)
            .build()

        assertNull(target(policy, representation(MimeTypes.VIDEO_MP4)))
        assertNull(minimum(policy, representation(MimeTypes.VIDEO_MP4)))
    }

    private fun target(policy: NextRequestPolicy, representation: Representation): Int? =
        SabrSegmentMatcher.selectReadahead(policy, isAudio(representation), minimum = false)

    private fun minimum(policy: NextRequestPolicy, representation: Representation): Int? =
        SabrSegmentMatcher.selectReadahead(policy, isAudio(representation), minimum = true)

    private fun isAudio(representation: Representation): Boolean =
        MimeTypes.isAudio(representation.format.containerMimeType)

    private fun representation(mimeType: String): Representation = Representation(
        Format.Builder()
            .setContainerMimeType(mimeType)
            .setSampleMimeType(mimeType)
            .build(),
        SabrStreamInfo(
            itag = 1,
            lastModified = 1,
            mimeType = mimeType,
        ),
    )
}
