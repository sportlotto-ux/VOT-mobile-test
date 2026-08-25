package app.votube.sabr.manifest

import android.net.Uri
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.StreamKey
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.FilterableManifest

/**
 * Represents server adaptive-bitrate streaming media metadata.
 */
@UnstableApi
class SabrManifest(
    /**
     * Identifier of the video being streamed.
     */
    val videoId: String,
    /**
     * URL of the streaming server.
     */
    val serverAbrStreamingUri: Uri,
    /**
     * Required config for media playback.
     */
    val videoPlaybackUstreamerConfig: ByteArray,
    /**
     * The duration of the presentation in milliseconds, or [C.TIME_UNSET] if not applicable.
     */
    val durationMs: Long,
) : FilterableManifest<SabrManifest?> {
    var adaptationSets: List<AdaptationSet> = emptyList()
        private set

    /**
     * Builds adaptation sets from extracted stream infos (video + audio).
     */
    constructor(
        videoId: String,
        serverAbrStreamingUrl: String,
        ustreamerConfigBase64UrlSafe: String,
        durationMs: Long,
        streamInfos: List<SabrStreamInfo>,
    ) : this(
        videoId,
        Uri.parse(serverAbrStreamingUrl),
        Base64.decode(ustreamerConfigBase64UrlSafe, Base64.URL_SAFE),        durationMs,
        streamInfos,
    )

    constructor(
        videoId: String,
        serverAbrStreamingUri: Uri,
        ustreamerConfig: ByteArray,
        durationMs: Long,
        streamInfos: List<SabrStreamInfo>,
    ) : this(videoId, serverAbrStreamingUri, ustreamerConfig, durationMs) {
        val videoAdaptionSets = streamInfos.filter { it.mimeType.startsWith("video") }
            .groupBy { it.mimeType }
            .map { (_, streams) ->
                AdaptationSet(C.TRACK_TYPE_VIDEO, streams.map { Representation(it) })
            }

        val audioAdaptationSets = streamInfos.filter { it.mimeType.startsWith("audio") }
            .groupBy { it.mimeType + it.audioTrackId }
            .map { (_, streams) ->
                AdaptationSet(C.TRACK_TYPE_AUDIO, streams.map { Representation(it) })
            }
        adaptationSets = videoAdaptionSets + audioAdaptationSets
    }

    override fun copy(streamKeys: List<StreamKey>): SabrManifest {
        return this
    }
}
