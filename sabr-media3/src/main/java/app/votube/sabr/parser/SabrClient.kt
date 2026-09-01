package app.votube.sabr.parser

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import app.votube.sabr.manifest.Representation
import app.votube.sabr.manifest.SabrManifest
import com.google.protobuf.ByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import misc.Common.FormatId
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import com.liskovsoft.sharedutils.okhttp.OkHttpManager
import video_streaming.BufferedRangeOuterClass.BufferedRange
import video_streaming.ClientAbrStateOuterClass.ClientAbrState
import video_streaming.FormatInitializationMetadataOuterClass.FormatInitializationMetadata
import video_streaming.MediaHeaderOuterClass.MediaHeader
import video_streaming.LiveMetadataOuterClass.LiveMetadata
import video_streaming.SabrSeekOuterClass.SabrSeek
import video_streaming.NextRequestPolicyOuterClass.NextRequestPolicy
import video_streaming.PlaybackStartPolicyOuterClass.PlaybackStartPolicy
import video_streaming.PlaybackCookieOuterClass.PlaybackCookie
import video_streaming.SabrContextSendingPolicyOuterClass.SabrContextSendingPolicy
import video_streaming.SabrContextUpdateOuterClass.SabrContextUpdate
import video_streaming.SabrContextUpdateOuterClass.SabrContextUpdate.SabrContextWritePolicy
import video_streaming.SabrErrorOuterClass.SabrError
import video_streaming.SabrRedirectOuterClass.SabrRedirect
import video_streaming.StreamProtectionStatusOuterClass.StreamProtectionStatus
import video_streaming.StreamerContextOuterClass.StreamerContext
import video_streaming.StreamerContextOuterClass.StreamerContext.SabrContext
import video_streaming.UmpPartId.UMPPartId
import video_streaming.VideoPlaybackAbrRequestOuterClass.VideoPlaybackAbrRequest
import kotlin.math.max

class PlaybackRequest(
    val format: FormatId,
    val playerPosition: Long,
    val playbackSpeed: Float,
    val segment: Long,
    val segmentStartTimeMs: Long,
    val bufferedSegments: List<Long>,
) {
    companion object {
        fun initRequest(format: FormatId, playerPosition: Long, playbackSpeed: Float) =
            PlaybackRequest(format, playerPosition, playbackSpeed, 0, 0, emptyList())
    }
}

data class Segment(
    val header: MediaHeader,
    val sequenceNumber: Long,
    val data: MutableList<ByteArray>,
    val duration: Long,
) {
    fun length(): Int = data.sumOf { it.size }
}

private data class InitializedFormat(
    val id: FormatId,
    val downloadedSegments: MutableMap<Long, Segment> = mutableMapOf(),
    val bufferedSegments: MutableMap<Long, Segment> = mutableMapOf(),
    val endSegmentNumber: Long,
    var initSegment: Segment? = null,
    val duration: Long,
) {
    fun getSegment(sequenceNumber: Long): Segment? {
        val segment = downloadedSegments.remove(sequenceNumber)
            ?: initSegment?.takeIf { it.sequenceNumber == sequenceNumber }
            ?: return null
        bufferedSegments[sequenceNumber] = segment.copy(data = mutableListOf())
        return segment
    }

    fun buildBufferedRanges(): List<BufferedRange> =
        bufferedSegments.entries.union(downloadedSegments.entries).sortedBy { it.key }
            .fold(mutableListOf<MutableList<Pair<Long, Segment>>>()) { acc, (id, segment) ->
                if (acc.lastOrNull()?.lastOrNull()?.first?.plus(1) != id) acc.add(mutableListOf())
                acc.last().add(id to segment)
                acc
            }.map { partition ->
                val (firstId, firstSegment) = partition.first()
                BufferedRange.newBuilder().setFormatId(id).setStartTimeMs(firstSegment.header.startMs)
                    .setDurationMs(partition.sumOf { it.second.duration })
                    .setStartSegmentIndex(firstId.toInt())
                    .setEndSegmentIndex(partition.last().first.toInt()).build()
            }

    fun hasSegment(segmentNumber: Long): Boolean =
        downloadedSegments.containsKey(segmentNumber) || initSegment?.sequenceNumber == segmentNumber
}

fun interface PoTokenProvider {
    fun getStreamingPoToken(videoId: String): ByteArray?
}

@OptIn(UnstableApi::class)
class SabrClient private constructor(
    private val appContext: Context,
    private val videoId: String,
    var url: String,
    private val ustreamerConfig: ByteString,
    private val poTokenProvider: PoTokenProvider?,
) {
    private var poToken: ByteString? = null
    private var fatalError: SabrError? = null
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private var audioFormat: Representation? = null
    private var videoFormat: Representation? = null
    private val initializedFormats = mutableMapOf<Int, InitializedFormat>()
    private val partialSegments = mutableMapOf<Int, Segment>()
    private val pendingSegments = mutableMapOf<Int, MutableList<Segment>>()
    private val client: OkHttpClient = OkHttpManager.instance().getClient()
    private var requestNumber = 1
    private var playbackCookie: PlaybackCookie? = null
    private var backoffTime: Int? = null
    /** Latest server-provided readahead targets for the next SABR request. */
    var nextRequestPolicy: NextRequestPolicy? = null
        private set
    private val sabrContexts = mutableMapOf<Int, SabrContext>()
    private val activeSabrContexts = mutableSetOf<Int>()
    var lastSeekMs: Long? = null
    private var liveMetadata: LiveMetadata? = null
    var serverSeekTimeMs: Long? = null; private set
    /** listener для головы эфира — чтобы плеер мог обновить таймлайн и позволить перемотку назад */
    var liveMetadataListener: ((LiveMetadata) -> Unit)? = null
    /** sequence последнего реально отданного сервером сегмента по itag — чтобы следующий запрос
     *  шёл от реальной нумерации сервера, а не от локально посчитанного индекса (нормализация по времени) */
    private val lastReturnedSequenceByItag = mutableMapOf<Int, Long>()
    /** флаг что live уже стартовал с головы — нужен чтобы отличить начальный edge от DVR rewind к началу */
    var hasStartedLive: Boolean = false
    private var lastRequestMs: Long? = null
    var lastManualFormatSelectionMs: Long? = null
    var lastActionMs: Long? = null
    /** Latest server playback-start policy, used by the player to choose readahead targets. */
    var playbackStartPolicy: PlaybackStartPolicy? = null
        private set
    private val bandwidthEstimator by lazy { DefaultBandwidthMeter.getSingletonInstance(appContext) }

    constructor(context: Context, manifest: SabrManifest, poTokenProvider: PoTokenProvider? = null) : this(
        context.applicationContext, manifest.videoId, manifest.serverAbrStreamingUri.toString(),
        ByteString.copyFrom(manifest.videoPlaybackUstreamerConfig), poTokenProvider
    )

    init { poTokenProvider?.getStreamingPoToken(videoId)?.let { poToken = ByteString.copyFrom(it) } }

    fun selectFormat(representation: Representation) {
        if (MimeTypes.isAudio(representation.format.containerMimeType)) {
            if (audioFormat?.streamInfo?.itag != representation.streamInfo.itag) {
                Log.i(TAG, "format changed: track=audio, itag=${representation.streamInfo.itag}, " +
                    "mime=${representation.format.containerMimeType}, codec=${representation.format.codecs}")
            }
            audioFormat = representation
        } else if (MimeTypes.isVideo(representation.format.containerMimeType)) {
            if (videoFormat?.streamInfo?.itag != representation.streamInfo.itag) {
                Log.i(TAG, "format changed: track=video, itag=${representation.streamInfo.itag}, " +
                    "resolution=${representation.streamInfo.width}x${representation.streamInfo.height}, " +
                    "mime=${representation.format.containerMimeType}, codec=${representation.format.codecs}")
            }
            videoFormat = representation
        }
    }

    fun getEndSegmentNumber(formatId: FormatId): Long? = initializedFormats[formatId.itag]?.endSegmentNumber

    fun getFirstAvailableSegmentNumber(formatId: FormatId): Long? =
        initializedFormats[formatId.itag]?.downloadedSegments?.keys?.minOrNull()

    fun getLiveHeadSequenceNumber(): Long? = liveMetadata?.headSequenceNumber
    fun getLiveHeadTimeMs(): Long? = liveMetadata?.headTimeMs
    fun isLive(): Boolean = liveMetadata != null
    fun hasFormatInitialized(itag: Int): Boolean = initializedFormats.containsKey(itag)

    fun getDownloadedSegmentsDebug(itag: Int): String =
        initializedFormats[itag]?.downloadedSegments?.keys?.sorted()?.toString() ?: "no format"

    /** sequence последнего отданного сегмента для itag, или null если ещё не отдавали.
     *  ChunkSource использует его, чтобы следующий live-запрос шёл от реальной нумерации сервера. */
    fun getLastReturnedSequence(itag: Int): Long? = lastReturnedSequenceByItag[itag]

    fun getMinSeekableTimeMs(): Long? = liveMetadata?.let {
        if (it.hasMinSeekableTimeTicks() && it.hasMinSeekableTimescale() && it.minSeekableTimescale != 0)
            it.minSeekableTimeTicks * 1000L / it.minSeekableTimescale else null
    }
    fun getMaxSeekableTimeMs(): Long? = liveMetadata?.let {
        if (it.hasMaxSeekableTimeTicks() && it.hasMaxSeekableTimescale() && it.maxSeekableTimescale != 0)
            it.maxSeekableTimeTicks * 1000L / it.maxSeekableTimescale else null
    }
    /** consume SABR_SEEK once so caller can apply it and we don't reuse stale value */
    fun consumeServerSeekMs(): Long? {
        val v = serverSeekTimeMs
        serverSeekTimeMs = null
        return v
    }
    fun peekServerSeekMs(): Long? = serverSeekTimeMs
    fun getLiveWindowDurationMs(): Long? {
        val head = getLiveHeadTimeMs() ?: return null
        val min = getMinSeekableTimeMs() ?: 0L
        return (head - min).takeIf { it > 0 }
    }

    /** Returns the server's current target readahead for the selected track type, if present. */
    fun getTargetReadaheadMs(format: Representation): Int? {
        val policy = nextRequestPolicy ?: return null
        val value = if (MimeTypes.isAudio(format.format.containerMimeType)) {
            if (policy.hasTargetAudioReadaheadMs()) policy.targetAudioReadaheadMs else null
        } else {
            if (policy.hasTargetVideoReadaheadMs()) policy.targetVideoReadaheadMs else null
        }
        return value?.takeIf { it >= 0 }
    }

    /** Returns the server's current minimum readahead for the selected track type, if present. */
    fun getMinReadaheadMs(format: Representation): Int? {
        val policy = nextRequestPolicy ?: return null
        val value = if (MimeTypes.isAudio(format.format.containerMimeType)) {
            if (policy.hasMinAudioReadaheadMs()) policy.minAudioReadaheadMs else null
        } else {
            if (policy.hasMinVideoReadaheadMs()) policy.minVideoReadaheadMs else null
        }
        return value?.takeIf { it >= 0 }
    }

    fun getNextSegment(playbackRequest: PlaybackRequest): Segment? {
        fatalError?.let { throw IOException("SABR error: ${it.type}") }
        val itag = playbackRequest.format.itag
        initializedFormats[itag]?.bufferedSegments?.keys?.retainAll(playbackRequest.bufferedSegments)
        Log.i(TAG, "getNextSegment: itag=$itag, requested=${playbackRequest.segment}, live=${liveMetadata != null}, initFormats=${initializedFormats.keys}, hasFormat=${initializedFormats.containsKey(itag)}")
        val result = runBlocking {
            withContext(dispatcher) {
                var format = initializedFormats[itag]
                repeat(if (liveMetadata != null) LIVE_REQUEST_RETRIES else 1) { attempt ->
                    if (format?.hasSegment(playbackRequest.segment) != true) {
                        if (attempt > 0) delay(LIVE_RETRY_DELAY_MS)
                        Log.i(TAG, "media request: attempt=$attempt, itag=$itag, segment=${playbackRequest.segment}, playerTimeMs=${playbackRequest.segmentStartTimeMs}")
                        media(playbackRequest)
                        initializedFormats.keys.retainAll { key ->
                            audioFormat?.streamInfo?.itag == key || videoFormat?.streamInfo?.itag == key
                        }
                        format = initializedFormats[itag]
                        Log.i(TAG, "after media: initFormats=${initializedFormats.keys}, hasSegment=${format?.hasSegment(playbackRequest.segment)}, downloaded=${format?.downloadedSegments?.keys?.sorted()}, pending=${pendingSegments[itag]?.size}, partial=${partialSegments.size}, endSegment=${format?.endSegmentNumber}")
                    }
                    if (format?.hasSegment(playbackRequest.segment) == true) {
                        val segment = format!!.getSegment(playbackRequest.segment)
                        if (segment != null) {
                            lastReturnedSequenceByItag[itag] = segment.sequenceNumber
                            return@withContext segment
                        }
                    }
                }
                // Live: нормализованное сопоставление сегментов ПО ВРЕМЕНИ (startMs), а не по строгому
                // равенству requestedSequence == protobuf.sequence. Сервер выбирает чанк по playerTimeMs и
                // может вернуть другой sequence, чем запросил клиент — ищем ближайший по времени чанк,
                // а следующий запрос уходит от реально отданного sequence (lastReturnedSequenceByItag).
                if (liveMetadata != null) {
                    val fallbackFormat = initializedFormats[itag]
                    if (fallbackFormat != null && fallbackFormat.downloadedSegments.isNotEmpty()) {
                        val avail = fallbackFormat.downloadedSegments.keys.sorted()
                        val headSeq = liveMetadata?.headSequenceNumber
                        val requestedSeq = playbackRequest.segment
                        val requestedTime = playbackRequest.segmentStartTimeMs
                        Log.w(TAG, "live fallback: requested $requestedSeq not found, available=$avail, headSeq=$headSeq, headTimeMs=${liveMetadata?.headTimeMs}, serverSeekMs=$serverSeekTimeMs, requestedTimeMs=$requestedTime")
                        // 1) Точный sequence (обычный согласованный случай)
                        var seg: Segment? = fallbackFormat.getSegment(requestedSeq)
                        // 2) По времени: сервер вернул другой sequence, но чанк покрывает запрошенное время
                        if (seg == null) {
                            val byTime = fallbackFormat.downloadedSegments.values
                                .minByOrNull { kotlin.math.abs(it.header.startMs - requestedTime) }
                            if (byTime != null && kotlin.math.abs(byTime.header.startMs - requestedTime) < LIVE_TIME_TOLERANCE_MS) {
                                seg = fallbackFormat.getSegment(byTime.sequenceNumber)
                                if (seg != null) {
                                    Log.i(TAG, "live fallback by time: returned seq ${seg.sequenceNumber} startMs=${seg.header.startMs} ~ requestedTime $requestedTime instead of $requestedSeq")
                                }
                            }
                        }
                        // 3) Последний шанс — ближайший к голове (live edge / reconnect в середине эфира)
                        if (seg == null && headSeq != null) {
                            val nearestHead = fallbackFormat.downloadedSegments.keys.minByOrNull { kotlin.math.abs(it - headSeq) }
                            if (nearestHead != null) {
                                seg = fallbackFormat.getSegment(nearestHead)
                                if (seg != null) {
                                    Log.i(TAG, "live nearest head fallback: returned $nearestHead for head $headSeq")
                                    hasStartedLive = true
                                }
                            }
                        }
                        if (seg != null) {
                            lastReturnedSequenceByItag[itag] = seg.sequenceNumber
                            serverSeekTimeMs = null
                            return@withContext seg
                        }
                    }
                }
                null
            }
        }
        if (result == null) {
            val f = initializedFormats[itag]
            Log.e(TAG, "no segment ${playbackRequest.segment} for itag=$itag, available=${f?.downloadedSegments?.keys?.sorted()}, endSegment=${f?.endSegmentNumber}, liveHead=${liveMetadata?.headSequenceNumber}, pending=${pendingSegments[itag]?.size}, partialIds=${partialSegments.keys}")
        }
        return result
    }

    private suspend fun media(playbackRequest: PlaybackRequest) {
        backoffTime?.let { delay(it.toLong()); backoffTime = null }
        val now = SystemClock.elapsedRealtime()
        val xtags = audioFormat?.formatId()?.xtags?.let { Xtags(it) }
        val state = ClientAbrState.newBuilder().setPlayerTimeMs(playbackRequest.segmentStartTimeMs)
            .setEnabledTrackTypesBitfield(if (videoFormat == null) 1 else 0)
            .setPlaybackRate(playbackRequest.playbackSpeed)
            .setElapsedWallTimeMs(lastRequestMs?.let { now - it } ?: 0)
            .setTimeSinceLastSeek(lastSeekMs?.let { now - it } ?: 0)
            .setTimeSinceLastManualFormatSelectionMs(lastManualFormatSelectionMs?.let { now - it } ?: 0)
            .setTimeSinceLastActionMs(lastActionMs?.let { now - it } ?: 0)
            .setAudioTrackId(audioFormat?.streamInfo?.audioTrackId ?: "")
            .setDrcEnabled(audioFormat?.streamInfo?.isDrc == true || xtags?.isDrcAudio() == true)
            .setEnableVoiceBoost(xtags?.isVoiceBoosted() ?: false).setClientViewportIsFlexible(false)
            .setBandwidthEstimate(bandwidthEstimator.bitrateEstimate)
            .setStickyResolution(max(videoFormat?.streamInfo?.height ?: 0, 360))
            .setClientViewportHeight(max(videoFormat?.streamInfo?.height ?: 0, 360))
            .setClientViewportWidth(max(videoFormat?.streamInfo?.width ?: 0, 640))
            .setLastManualSelectedResolution(max(videoFormat?.streamInfo?.height ?: 0, 360)).setVisibility(1).build()
        val abr = VideoPlaybackAbrRequest.newBuilder().setClientAbrState(state)
            .setPlayerTimeMs(playbackRequest.segmentStartTimeMs).setVideoPlaybackUstreamerConfig(ustreamerConfig)
            .addAllPreferredAudioFormatIds(listOfNotNull(audioFormat?.formatId()))
            .addAllPreferredVideoFormatIds(listOfNotNull(videoFormat?.formatId()))
            .addAllSelectedFormatIds(initializedFormats.values.map { it.id })
            .addAllBufferedRanges(initializedFormats.values.flatMap { it.buildBufferedRanges() })
            .setStreamerContext(StreamerContext.newBuilder().setPoToken(poToken ?: ByteString.empty())
                .setClientInfo(StreamerContext.ClientInfo.newBuilder().setClientName(101).setClientVersion("1.02")
                    .setDeviceMake("Apple").setDeviceModel("RealityDevice14,1").setOsName("visionOS")
                    .setOsVersion("25.6.0.23O471").build())
                .addAllSabrContexts(activeSabrContexts.mapNotNull { sabrContexts[it] })
                .addAllUnsentSabrContexts(sabrContexts.keys.filter { it !in activeSabrContexts })
                .setPlaybackCookie(playbackCookie?.toByteString() ?: ByteString.empty()).build()).build()
        val request = Request.Builder().url("$url&rn=${requestNumber++}")
            .addHeader("Content-Type", CONTENT_TYPE).addHeader("Accept-Encoding", ENCODING)
            .addHeader("Accept", ACCEPT).addHeader("Origin", YOUTUBE_FRONTEND_URL)
            .addHeader("Referer", "$YOUTUBE_FRONTEND_URL/").addHeader("User-Agent", USER_AGENT)
            .post(RequestBody.create(MediaType.parse(CONTENT_TYPE), abr.toByteArray())).build()
        lastRequestMs = SystemClock.elapsedRealtime()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP request failed: ${response.code()}")
            val body = response.body() ?: throw IOException("HTTP response has no body")
            val reader = StreamingUmpReader(body.byteStream())
            while (true) {
                val part = reader.readPart() ?: break
                processPart(part)
            }
        }
    }

    private fun processPart(part: Part) {
        when (part.type) {
            UMPPartId.MEDIA_HEADER -> {
                val header = MediaHeader.parseFrom(part.data)
                if (header.videoId != videoId) throw IOException("Header mismatch")
                val durationMs = when {
                    header.hasDurationMs() -> header.durationMs
                    header.hasTimeRange() && header.timeRange.hasDurationTicks() && header.timeRange.hasTimescale() && header.timeRange.timescale != 0 ->
                        header.timeRange.durationTicks * 1000L / header.timeRange.timescale
                    header.hasStartMs() && liveMetadata != null -> 4900L // live estimate: 5s - tolerance, как в SmartTube
                    else -> 0L
                }
                val segment = Segment(
                    header,
                    header.sequenceNumber,
                    mutableListOf(),
                    durationMs,
                )
                partialSegments[header.headerId] = segment
                Log.i(TAG, "media header: itag=${header.formatId.itag}, headerId=${header.headerId}, " +
                    "sequence=${header.sequenceNumber}, init=${header.isInitSeg}, " +
                    "startMs=${header.startMs}, durationMs=${segment.duration}")
            }
            UMPPartId.MEDIA -> {
                val parser = UmpParser(part.data)
                val id = parser.readVarint()?.toInt() ?: return
                partialSegments[id]?.data?.add(parser.data())
            }
            UMPPartId.MEDIA_END -> {
                val parser = UmpParser(part.data)
                val id = parser.readVarint()?.toInt() ?: return
                val segment = partialSegments.remove(id) ?: return
                val format = initializedFormats[segment.header.itag]
                if (format == null) {
                    pendingSegments.getOrPut(segment.header.itag) { mutableListOf() }.add(segment)
                    Log.i(TAG, "media segment pending metadata: itag=${segment.header.itag}, " +
                        "sequence=${segment.sequenceNumber}")
                    return
                }
                storeSegment(format, segment)
            }
            UMPPartId.NEXT_REQUEST_POLICY -> {
                val policy = NextRequestPolicy.parseFrom(part.data)
                nextRequestPolicy = policy
                backoffTime = if (policy.hasBackoffTimeMs()) policy.backoffTimeMs else null
                playbackCookie = if (policy.hasPlaybackCookie()) policy.playbackCookie else null
                Log.i(TAG, "next request policy: backoffMs=$backoffTime, targetAudio=${if (policy.hasTargetAudioReadaheadMs()) policy.targetAudioReadaheadMs else "null"}, targetVideo=${if (policy.hasTargetVideoReadaheadMs()) policy.targetVideoReadaheadMs else "null"}")
            }
            UMPPartId.PLAYBACK_START_POLICY -> {
                playbackStartPolicy = PlaybackStartPolicy.parseFrom(part.data)
                Log.d(TAG, "playback start policy received")
            }
            UMPPartId.FORMAT_INITIALIZATION_METADATA -> {
                val metadata = FormatInitializationMetadata.parseFrom(part.data)
                Log.i(TAG, "format init: itag=${metadata.formatId.itag}, endSegment=${metadata.endSegmentNumber}, endTimeMs=${metadata.endTimeMs}, mime=${metadata.mimeType}")
                val format = InitializedFormat(
                    metadata.formatId,
                    endSegmentNumber = metadata.endSegmentNumber,
                    duration = metadata.endTimeMs,
                )
                initializedFormats[metadata.formatId.itag] = format
                val pending = pendingSegments.remove(metadata.formatId.itag)
                if (pending != null) {
                    Log.i(TAG, "flushing ${pending.size} pending segments for itag=${metadata.formatId.itag}")
                    pending.forEach { storeSegment(format, it) }
                }
            }
            UMPPartId.LIVE_METADATA -> {
                liveMetadata = LiveMetadata.parseFrom(part.data)
                Log.i(TAG, "live metadata: headSeq=${liveMetadata?.headSequenceNumber}, headTimeMs=${liveMetadata?.headTimeMs}, broadcastId=${liveMetadata?.broadcastId}, minSeekTicks=${if (liveMetadata!!.hasMinSeekableTimeTicks()) liveMetadata!!.minSeekableTimeTicks else "null"}")
                liveMetadata?.let { liveMetadataListener?.invoke(it) }
            }
            UMPPartId.SABR_SEEK -> {
                val seek = SabrSeek.parseFrom(part.data)
                if (seek.hasSeekMediaTime() && seek.hasSeekMediaTimescale() && seek.seekMediaTimescale > 0) {
                    serverSeekTimeMs = seek.seekMediaTime * 1000 / seek.seekMediaTimescale
                    Log.i(TAG, "server seek: positionMs=$serverSeekTimeMs, " +
                        "mediaTime=${seek.seekMediaTime}, timescale=${seek.seekMediaTimescale}")
                }
            }
            UMPPartId.SABR_REDIRECT -> {
                val redirect = SabrRedirect.parseFrom(part.data)
                Log.i(TAG, "redirect: urlChanged=${url != redirect.url}")
                url = redirect.url
            }
            UMPPartId.SABR_ERROR -> {
                fatalError = SabrError.parseFrom(part.data)
                Log.e(TAG, "SABR error: type=${fatalError?.type}, code=${fatalError?.code}")
                throw IOException("SABR error: ${fatalError?.type}")
            }
            UMPPartId.REQUEST_IDENTIFIER -> Log.d(TAG, "REQUEST_IDENTIFIER part received (ignored)")
            UMPPartId.REQUEST_CANCELLATION_POLICY -> Log.d(TAG, "REQUEST_CANCELLATION_POLICY part received (ignored)")
            UMPPartId.SABR_CONTEXT_UPDATE -> {
                try {
                    val upd = SabrContextUpdate.parseFrom(part.data)
                    // Convert SabrContextUpdate -> StreamerContext.SabrContext (type+value only)
                    val ctx = SabrContext.newBuilder().setType(upd.type).setValue(upd.value).build()
                    if (upd.hasWritePolicy() && upd.writePolicy == SabrContextWritePolicy.KEEP_EXISTING && sabrContexts.containsKey(upd.type)) {
                        Log.d(TAG, "skipping KEEP_EXISTING context type=${upd.type}")
                    } else {
                        sabrContexts[upd.type] = ctx
                        if (upd.hasSendByDefault() && upd.sendByDefault) activeSabrContexts.add(upd.type)
                        Log.i(TAG, "sabr context update: type=${upd.type}, sendByDefault=${upd.sendByDefault}, writePolicy=${upd.writePolicy}")
                    }
                } catch (e: Exception) { Log.w(TAG, "failed to parse SABR_CONTEXT_UPDATE: $e") }
            }
            UMPPartId.SABR_CONTEXT_SENDING_POLICY -> {
                try {
                    val pol = SabrContextSendingPolicy.parseFrom(part.data)
                    pol.startPolicyList.forEach { activeSabrContexts.add(it) }
                    pol.stopPolicyList.forEach { activeSabrContexts.remove(it) }
                    pol.discardPolicyList.forEach { sabrContexts.remove(it); activeSabrContexts.remove(it) }
                    Log.i(TAG, "sabr context sending policy: start=${pol.startPolicyList}, stop=${pol.stopPolicyList}, discard=${pol.discardPolicyList}")
                } catch (e: Exception) { Log.w(TAG, "failed to parse SABR_CONTEXT_SENDING_POLICY: $e") }
            }
            UMPPartId.STREAM_PROTECTION_STATUS -> {
                try {
                    val s = StreamProtectionStatus.parseFrom(part.data)
                    Log.i(TAG, "stream protection: status=${s.status}")
                } catch (e: Exception) { Log.w(TAG, "failed to parse STREAM_PROTECTION_STATUS: $e") }
            }
            else -> Log.w(TAG, "Unhandled UMP part: ${part.type} size=${part.data.size}")
        }
    }

    private fun storeSegment(format: InitializedFormat, segment: Segment) {
        format.downloadedSegments[segment.sequenceNumber] = segment
        if (segment.header.isInitSeg) format.initSegment = segment
        Log.i(TAG, "media segment stored: itag=${segment.header.itag}, " +
            "sequence=${segment.sequenceNumber}, init=${segment.header.isInitSeg}, bytes=${segment.length()}")
    }

    fun generatePoToken(): ByteString? =
        poTokenProvider?.getStreamingPoToken(videoId)?.let { ByteString.copyFrom(it) }

    companion object {
        private const val TAG = "SabrStream"
        private const val CONTENT_TYPE = "application/x-protobuf"
        private const val ENCODING = "identity"
        private const val ACCEPT = "application/vnd.yt-ump"
        private const val USER_AGENT = "com.google.visionos.youtube/1.02(RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X; GB)"
        private const val YOUTUBE_FRONTEND_URL = "https://www.youtube.com"
        private const val LIVE_REQUEST_RETRIES = 3
        private const val LIVE_RETRY_DELAY_MS = 250L
        /** максимальное расхождение по времени (мс) для фолбэка «по времени» — чтобы не отдавать рандомный чанк */
        private const val LIVE_TIME_TOLERANCE_MS = 30_000L
    }
}
