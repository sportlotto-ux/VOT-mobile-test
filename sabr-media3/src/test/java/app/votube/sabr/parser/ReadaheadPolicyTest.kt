package app.votube.sabr.parser

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import app.votube.sabr.manifest.Representation
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

        assertEquals(1200, valueFor(policy, audio, false))
        assertEquals(3400, valueFor(policy, video, false))
        assertEquals(300, valueFor(policy, audio, true))
        assertEquals(900, valueFor(policy, video, true))
    }

    @Test
    fun `missing policy value returns null`() {
        val policy = NextRequestPolicy.newBuilder().setTargetVideoReadaheadMs(1000).build()

        assertNull(valueFor(policy, representation(MimeTypes.AUDIO_MP4), false))
        assertEquals(1000, valueFor(policy, representation(MimeTypes.VIDEO_MP4), false))
    }

    @Test
    fun `negative policy value is ignored`() {
        val policy = NextRequestPolicy.newBuilder().setTargetVideoReadaheadMs(-1).build()

        assertNull(valueFor(policy, representation(MimeTypes.VIDEO_MP4), false))
    }

    private fun valueFor(policy: NextRequestPolicy, representation: Representation, minimum: Boolean): Int? {
        val value = if (MimeTypes.isAudio(representation.format.containerMimeType)) {
            if (minimum) {
                if (policy.hasMinAudioReadaheadMs()) policy.minAudioReadaheadMs else null
            } else {
                if (policy.hasTargetAudioReadaheadMs()) policy.targetAudioReadaheadMs else null
            }
        } else {
            if (minimum) {
                if (policy.hasMinVideoReadaheadMs()) policy.minVideoReadaheadMs else null
            } else {
                if (policy.hasTargetVideoReadaheadMs()) policy.targetVideoReadaheadMs else null
            }
        }
        return value?.takeIf { it >= 0 }
    }

    private fun representation(mimeType: String): Representation = Representation(
        Format.Builder()
            .setContainerMimeType(mimeType)
            .setSampleMimeType(mimeType)
            .build(),
        app.votube.sabr.manifest.SabrStreamInfo(
            itag = 1,
            lastModified = 1,
            mimeType = mimeType,
        ),
    )
}
