package app.votube.sabr.manifest

import androidx.media3.common.C.ROLE_FLAG_DESCRIBES_VIDEO
import androidx.media3.common.C.ROLE_FLAG_DUB
import androidx.media3.common.C.ROLE_FLAG_MAIN
import androidx.media3.common.C.ROLE_FLAG_SUPPLEMENTARY
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import misc.Common.FormatId

/**
 * Neutral stream metadata extracted from the YouTube player response.
 *
 * Replaces LibreTube's PipedStream DTO so that any extractor can feed this module.
 */
data class SabrStreamInfo(
    val itag: Int,
    val lastModified: Long,
    val xtags: String? = null,
    val mimeType: String,
    val codec: String? = null,
    val bitrate: Int = -1,
    val fps: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val audioTrackId: String? = null,
    val audioTrackType: String? = null,
    val audioTrackLocale: String? = null,
    val isDrc: Boolean = false,
)

/** A Sabr representation.  */
@UnstableApi
data class Representation(
    /** The format of the representation.  */
    val format: Format,
    /** Metadata about the stream.  */
    val streamInfo: SabrStreamInfo,
) {
    fun formatId(): FormatId = FormatId.newBuilder()
        .setItag(streamInfo.itag)
        .setLastModified(streamInfo.lastModified)
        .setXtags(streamInfo.xtags ?: "")
        .build()

    constructor(stream: SabrStreamInfo) : this(buildFormat(stream), stream)

    companion object {
        private fun buildFormat(stream: SabrStreamInfo): Format {
            return if (MimeTypes.isVideo(stream.mimeType)) {
                Format.Builder()
                    .setCodecs(stream.codec)
                    .setContainerMimeType(stream.mimeType)
                    .setSampleMimeType(MimeTypes.getVideoMediaMimeType(stream.codec))
                    .setAverageBitrate(stream.bitrate)
                    .setFrameRate(stream.fps?.toFloat() ?: -1f)
                    .setWidth(stream.width ?: -1)
                    .setHeight(stream.height ?: -1).build()
            } else {
                val xtagsLanguage = try {
                    app.votube.sabr.parser.Xtags(stream.xtags.orEmpty()).language()
                } catch (e: Exception) {
                    null
                }
                Format.Builder()
                    .setCodecs(stream.codec)
                    .setContainerMimeType(stream.mimeType)
                    .setSampleMimeType(MimeTypes.getAudioMediaMimeType(stream.codec))
                    .setAverageBitrate(stream.bitrate)
                    .setChannelCount(2)
                    .setLanguage(
                        stream.audioTrackId?.take(2) ?: xtagsLanguage
                        ?: stream.audioTrackLocale
                    )
                    .setRoleFlags(
                        when (stream.audioTrackType?.lowercase()) {
                            "descriptive" -> ROLE_FLAG_DESCRIBES_VIDEO
                            "original" -> ROLE_FLAG_MAIN
                            "dubbed", "auto-dubbed", "dubbed-auto" -> ROLE_FLAG_DUB
                            "secondary" -> ROLE_FLAG_SUPPLEMENTARY
                            else -> 0
                        }
                    )
                    .build()
            }
        }
    }
}
