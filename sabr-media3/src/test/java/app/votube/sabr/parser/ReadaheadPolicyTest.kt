package app.votube.sabr.parser

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import app.votube.sabr.manifest.Representation
import app.votube.sabr.manifest.SabrStreamInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import video_streaming.NextRequestPolicyOuterClass.NextRequestPolicy

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
        value(policy, representation, false)

    private fun minimum(policy: NextRequestPolicy, representation: Representation): Int? =
        value(policy, representation, true)

    private fun value(policy: NextRequestPolicy, representation: Representation, minimum: Boolean): Int? {
        val audio = MimeTypes.isAudio(representation.format.containerMimeType)
        val value = when {
            audio && minimum && policy.hasMinAudioReadaheadMs() -> policy.minAudioReadaheadMs
            audio && !minimum && policy.hasTargetAudioReadaheadMs() -> policy.targetAudioReadaheadMs
            !audio && minimum && policy.hasMinVideoReadaheadMs() -> policy.minVideoReadaheadMs
            !audio && !minimum && policy.hasTargetVideoReadaheadMs() -> policy.targetVideoReadaheadMs
            else -> null
        }
        return value?.takeIf { it >= 0 }
    }

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
