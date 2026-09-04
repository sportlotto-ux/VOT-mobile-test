package app.votube.sabr.parser

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.FilterInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
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
import video_streaming.ReloadPlayerResponse.ReloadPlaybackContext
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
    // var: broadcast переприсылает init на каждый ответ — метаданные освежаем,
    // сегменты НЕ вайпаем (иначе вечный downloaded=[] и рефетч).
    var endSegmentNumber: Long,
    var initSegment: Segment? = null,
    var duration: Long,
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
    /* КЛОН общего клиента с readTimeout: одна UMP-загрузка идёт через общий канал
       limitedParallelism(1), и зависшее чтение блокирует ВСЕ A/V запросы.
       Лог 14:48:28→14:49:00: чтение сегмента молча висело 32с — буфер кончился,
       playback встал, и латентность навсегда осталась 31-32с позади эфира.
       readTimeout ограничивает ТОЛЬКО паузу между байтами (не весь стрим):
       легитимный long-poll с типовым потоком данных проходит, мёртвое соединение
       обрывается через 20с → Media3 перезапрашивает чанк по своей политике ретраев. */
    private val client: OkHttpClient = OkHttpManager.instance().getClient()
        .newBuilder()
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
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
    /** Наблюдаемый реальный ритм эфира (мс): шаг стартов соседних отданных сегментов одного формата.
     *  Декларируемая durationMs (protobuf) у части эфиров не совпадает с реальным шагом
     *  (лог 13:32-13:34: durationMs 3-7с при реальном шаге 2с) → декларируемая очередь убегала
     *  от сэмплов fMP4 → буфер «виртуально» есть, семплы кончились → микрофриз + DVR remap. */
    private val realStepMsByItag = mutableMapOf<Int, Long>()
    /** время (startMs/endMs) последнего реально отданного сегмента по itag. Время — ОБЩИЙ домен
     *  для аудио и видео, в отличие от sequence: у каждого формата своя серия номеров (в логе одно
     *  и то же время = video seq 4567, audio seq 4570), поэтому синхронизация A/V и планирование
     *  живут по времени, а sequence — только для bookkeeping запросов. */
    private val lastReturnedTimeMsByItag = mutableMapOf<Int, Long>()
    private val lastReturnedEndTimeMsByItag = mutableMapOf<Int, Long>()
    /** minSeekable на момент первого LiveMetadata — фиксируем. Домен времени чанков
     *  (windowPos = time − windowStart) не должен плыть вместе со скользящим minSeekable
     *  в течение периода, иначе startTime старых и новых чанков разъедутся (фризы/скачки). */
    private var liveWindowStartMs: Long? = null
    /** Lock разделяемого состояния (initializedFormats/lastReturned/liveMetadata/partialSegments):
     *  мутации идут и с loader-потока (getNextSegment), и с dispatcher-корутины (processPart). */
    private val stateLock = Any()
    /** Доступ к разделяемому состоянию под локом. */
    private fun <T> withState(block: () -> T): T = synchronized(stateLock, block)
    /** last use timestamp per itag — to avoid churn when ABR oscillates (keep recent formats) */
    private val lastFormatUseMs = mutableMapOf<Int, Long>()
    /** Кумулятивная история отданного по itag: seq -> (startMs, durationMs).
     *  v15 (sabr-dash-poc findings): серверу докладываем ВСЁ потреблённое за сессию
     *  (merge: extend range ending at seq-1, else new — seek-гэпы сохраняются),
     *  а не дельту с прошлого запроса — иначе "буфер не сходится" и сервер молча
     *  перестаёт слать медиа (наши 164Б-спирали). Init не занимает время — не пишем. */
    private val consumedSegsByItag = mutableMapOf<Int, MutableMap<Long, Pair<Long, Long>>>()
    /** флаг что live уже стартовал с головы — нужен чтобы отличить начальный edge от DVR rewind к началу */
    var hasStartedLive: Boolean = false
    private var lastRequestMs: Long? = null
    /** Момент последнего пустого media-ответа (0 новых сегментов): следующие запросы
     *  тормозим EMPTY_BACKOFF_MS, иначе спин 169Б по 8 req/s (лог 22:02:23, 22:04:57). */
    private var lastMediaEmptyMs: Long = 0L
    /** Счётчик новых сегментов в текущем media() — сбрасывается в начале вызова. */
    private var mediaStoredCounter: Int = 0
    var lastManualFormatSelectionMs: Long? = null
    var lastActionMs: Long? = null
    /** Latest server playback-start policy, used by the player to choose readahead targets. */
    var playbackStartPolicy: PlaybackStartPolicy? = null
        private set
    private val bandwidthEstimator by lazy { DefaultBandwidthMeter.getSingletonInstance(appContext) }
    /** Монотонный максимум высоты выбранного видео за сессию — защита sticky-разрешения от «запирания»:
    *   однажды сползший в 144p выбор не должен стать для сервера «пользовательским решением» навсегда. */
    private var stickyResolutionMax: Int = 0
    /** Суммарные байты, полученные по сети SABR-раундтрипами. SabrDataSource атрибутирует их
    *   метру пропускной способности: окно замера включает сетевое ожидание, но без этих байтов
    *   сэмпл = «один сегмент за раундтрип» → оценка канала рушится → ABR сползает в 144p. */
    private val networkBytesCounter = AtomicLong(0)

    /** Снапшот сетевого счётчика для дельты вокруг блокирующего getNextSegment. */
    fun networkBytesSnapshot(): Long = networkBytesCounter.get()

    constructor(context: Context, manifest: SabrManifest, poTokenProvider: PoTokenProvider? = null) : this(
        context.applicationContext, manifest.videoId, manifest.serverAbrStreamingUri.toString(),
        ByteString.copyFrom(manifest.videoPlaybackUstreamerConfig), poTokenProvider
    )

    init { poTokenProvider?.getStreamingPoToken(videoId)?.let { poToken = ByteString.copyFrom(it) } }

    fun selectFormat(representation: Representation) = withState {
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
            val height = representation.streamInfo.height ?: 0
            if (height > stickyResolutionMax) stickyResolutionMax = height
        }
        lastFormatUseMs[representation.streamInfo.itag] = SystemClock.elapsedRealtime()
    }

    fun getEndSegmentNumber(formatId: FormatId): Long? =
        withState { initializedFormats[formatId.itag]?.endSegmentNumber }

    fun getFirstAvailableSegmentNumber(formatId: FormatId): Long? = withState {
        initializedFormats[formatId.itag]?.downloadedSegments?.keys?.minOrNull()
    }

    fun getLiveHeadSequenceNumber(): Long? = withState { liveMetadata?.headSequenceNumber }
    fun getLiveHeadTimeMs(): Long? = withState { liveMetadata?.headTimeMs }
    fun isLive(): Boolean = withState { liveMetadata != null }
    fun hasFormatInitialized(itag: Int): Boolean = withState { initializedFormats.containsKey(itag) }

    fun getDownloadedSegmentsDebug(itag: Int): String = withState {
        initializedFormats[itag]?.downloadedSegments?.keys?.sorted()?.toString() ?: "no format"
    }

    /** sequence последнего отданного сегмента для itag, или null если ещё не отдавали.
     *  ChunkSource использует его, чтобы следующий live-запрос шёл от реальной нумерации сервера. */
    fun getLastReturnedSequence(itag: Int): Long? = withState { lastReturnedSequenceByItag[itag] }
    fun getMaxLastReturnedSequence(): Long? = withState { lastReturnedSequenceByItag.values.maxOrNull() }

    /** Время (startMs) последнего отданного сегмента для itag — база для планирования следующего. */
    fun getLastReturnedTimeMs(itag: Int): Long? = withState { lastReturnedTimeMsByItag[itag] }
    /** Конец (startMs+duration) последнего отданного сегмента для itag — requestedTime следующего запроса. */
    fun getLastReturnedEndTimeMs(itag: Int): Long? = withState { lastReturnedEndTimeMsByItag[itag] }
    /** Максимальное время последнего отданного сегмента по всем форматам. Время — общий домен
     *  для аудио/видео, в отличие от sequence (серии номеров у форматов разные и несравнимы). */
    fun getMaxLastReturnedTimeMs(): Long? = withState { lastReturnedTimeMsByItag.values.maxOrNull() }

    /** Наблюдаемый реальный шаг стартов (мс) для itag — оценка длительности сегмента эфира,
     *  устойчивая к расхождению декларируемой durationMs с реальным ритмом. */
    fun getLastRealStepMs(itag: Int): Long? = withState { realStepMsByItag[itag] }

    /** Реальное время старта сегмента [sequenceNumber], если он уже лежит в префетче/буфере
     *  формата. ChunkSource декларирует конец чанка по нему — очередь совпадает с сэмплами. */
    fun peekSegmentStartMs(itag: Int, sequenceNumber: Long): Long? = withState {
        val f = initializedFormats[itag] ?: return@withState null
        (f.downloadedSegments[sequenceNumber] ?: f.bufferedSegments[sequenceNumber])?.header?.startMs
    }

    /** Сброс временных якорей формата: легитимный seek/DVR remap назад — планировщик
     *  переориентируется на позицию очереди, а не на старый якорь (иначе после перемотки
     *  монотонный якорь тянет запросы вперёд, мимо точки перемотки). */
    fun resetTimeAnchors(itag: Int) = withState {
        lastReturnedTimeMsByItag.remove(itag)
        lastReturnedEndTimeMsByItag.remove(itag)
    }

    /** Начало DVR-окна, зафиксированное на первом LiveMetadata (не скользит в течение периода). */
    fun getLiveWindowStartMs(): Long? = withState { liveWindowStartMs }

    fun getMinSeekableTimeMs(): Long? = withState {
        liveMetadata?.let {
            if (it.hasMinSeekableTimeTicks() && it.hasMinSeekableTimescale() && it.minSeekableTimescale != 0)
                ticksToMs(it.minSeekableTimeTicks, it.minSeekableTimescale) else null
        }
    }
    fun getMaxSeekableTimeMs(): Long? = withState {
        liveMetadata?.let {
            if (it.hasMaxSeekableTimeTicks() && it.hasMaxSeekableTimescale() && it.maxSeekableTimescale != 0)
                ticksToMs(it.maxSeekableTimeTicks, it.maxSeekableTimescale) else null
        }
    }
    /** consume SABR_SEEK once so caller can apply it and we don't reuse stale value */
    fun consumeServerSeekMs(): Long? = withState {
        val v = serverSeekTimeMs
        serverSeekTimeMs = null
        v
    }
    fun peekServerSeekMs(): Long? = withState { serverSeekTimeMs }
    fun getLiveWindowDurationMs(): Long? = withState {
        val head = liveMetadata?.headTimeMs
        if (head == null) return@withState null
        val start = liveWindowStartMs ?: liveMetadata?.let {
            if (it.hasMinSeekableTimeTicks() && it.hasMinSeekableTimescale() && it.minSeekableTimescale != 0)
                ticksToMs(it.minSeekableTimeTicks, it.minSeekableTimescale) else 0L
        } ?: 0L
        (head - start).takeIf { it > 0 }
    }

    /** Запомнить реально отданный сегмент: sequence (серия сервера для этого формата) и время
     *  (общий домен аудио/видео) — на этом строятся следующие запросы и A/V синхронизация.
     *  Конец якоря = РЕАЛЬНОЕ время следующего сегмента (если уже лежит в префетче/буфере),
     *  иначе наблюдаемый шаг стартов, и лишь в крайнем случае декларируемая seg.duration:
     *  декларируемая длительность у части эфиров расходится с реальным ритмом, и очередь
     *  начинала врать плееру (виртуальный буфер при закончившихся семплах → микрофриз). */
    private fun noteServed(itag: Int, seg: Segment) {
        withState {
            val prevStartMs = lastReturnedTimeMsByItag[itag]
            lastReturnedSequenceByItag[itag] = seg.sequenceNumber
            // Время якоря не двигаем назад: повторная/частичная отдача старого сегмента
            // (лог 13:31:41: частичный seq=721 durationMs=13 откатил якорь с 3615006 на
            // 3605014 → запросы ушли в прошлое → петля). Легитимный seek назад сбрасывает
            // якоря через resetTimeAnchors() из ChunkSource.
            lastReturnedTimeMsByItag[itag] = maxOf(prevStartMs ?: seg.header.startMs, seg.header.startMs)
            val stepMs = prevStartMs?.let { seg.header.startMs - it }
            if (stepMs != null && stepMs in 500L..60_000L) realStepMsByItag[itag] = stepMs
            val f = initializedFormats[itag]
            val nextStartMs = (f?.downloadedSegments?.get(seg.sequenceNumber + 1)
                ?: f?.bufferedSegments?.get(seg.sequenceNumber + 1))?.header?.startMs
                ?.takeIf { it > seg.header.startMs }
            val endMs = nextStartMs
                ?: seg.header.startMs + (realStepMsByItag[itag] ?: seg.duration)
            lastReturnedEndTimeMsByItag[itag] = maxOf(lastReturnedEndTimeMsByItag[itag] ?: endMs, endMs)
            // Кумулятивная история для bufferedRanges (findings): init времени не занимает.
            if (!seg.header.isInitSeg) {
                consumedSegsByItag.getOrPut(itag) { mutableMapOf() }[seg.sequenceNumber] =
                    seg.header.startMs to seg.duration
            }
        }
    }

    /** Ближайший ПО ВРЕМЕНИ ВПЕРЁД сегмент из кэша формата или null.
     *  Разрешены: тот же момент (сервер перенумеровал чанк, |startMs − requested| ≤ SAME_TIME_EPS_MS)
     *  и вперёд до LIVE_TIME_TOLERANCE_MS (следующий сегмент из префетча). Назад дальше eps — НЕЛЬЗЯ:
     *  повтор старого чанка зацикливает загрузку (лог 10:32: re-serve seq 2258110/2258112/2258113
     *  на 5с позади запроса каждые ~1с → фриз + звук в цикл). Уже отданный сегмент для этого itag
     *  исключается — он не должен быть отдан дважды. */
    private fun findTimeMatch(format: InitializedFormat?, requestedTimeMs: Long, itag: Int): Segment? {
        val cached = format?.downloadedSegments?.values ?: return null
        val lastServedSeq = lastReturnedSequenceByItag[itag]
        return cached.filter {
            it.sequenceNumber != lastServedSeq &&
                it.header.startMs >= requestedTimeMs - SAME_TIME_EPS_MS &&
                it.header.startMs <= requestedTimeMs + LIVE_TIME_TOLERANCE_MS
        }.minByOrNull { it.header.startMs }
    }

    /** Скип вперёд при дырке в серии (не прыжок к голове): ближайший доступный сегмент
     *  с startMs в [requested − EPS, requested + cap], берём САМЫЙ РАННИЙ (минимальный скип).
     *  Назад дальше eps — НЕЛЬЗЯ (тот же зацикливание-фриз, что в findTimeMatch).
     *  Уже отданный сегмент исключается. */
    private fun findSkipMatch(format: InitializedFormat?, requestedTimeMs: Long, itag: Int, capMs: Long): Segment? {
        val cached = format?.downloadedSegments?.values ?: return null
        val lastServedSeq = lastReturnedSequenceByItag[itag]
        return cached.filter {
            it.sequenceNumber != lastServedSeq &&
                it.header.startMs >= requestedTimeMs - SAME_TIME_EPS_MS &&
                it.header.startMs <= requestedTimeMs + capMs
        }.minByOrNull { it.header.startMs }
    }

    /** Какой сегмент будет отдан для (itag, segment, requestedTimeMs): точный по sequence или
     *  forward тайм-матч. ChunkSource использует startMs как ДЕКЛАРИРОВАННОЕ время чанка, чтобы
     *  очередь совпадала с реальными сэмплами fMP4 (абсолютное медиа-время). */
    fun peekServedStartMs(itag: Int, segment: Long, requestedTimeMs: Long): Long? = withState {
        val f = initializedFormats[itag]
        (f?.getSegment(segment) ?: findTimeMatch(f, requestedTimeMs, itag))?.header?.startMs
    }

    /** Returns the server's current target readahead for the selected track type, if present. */
    fun getTargetReadaheadMs(format: Representation): Int? = withState {
        val policy = nextRequestPolicy ?: return@withState null
        val value = if (MimeTypes.isAudio(format.format.containerMimeType)) {
            if (policy.hasTargetAudioReadaheadMs()) policy.targetAudioReadaheadMs else null
        } else {
            if (policy.hasTargetVideoReadaheadMs()) policy.targetVideoReadaheadMs else null
        }
        value?.takeIf { it >= 0 }
    }

    /** Returns the server's current minimum readahead for the selected track type, if present. */
    fun getMinReadaheadMs(format: Representation): Int? = withState {
        val policy = nextRequestPolicy ?: return@withState null
        val value = if (MimeTypes.isAudio(format.format.containerMimeType)) {
            if (policy.hasMinAudioReadaheadMs()) policy.minAudioReadaheadMs else null
        } else {
            if (policy.hasMinVideoReadaheadMs()) policy.minVideoReadaheadMs else null
        }
        value?.takeIf { it >= 0 }
    }

    /** Только текущие треки (как у референса googlevideo/SabrStreamingAdapter).
     *  Отвалившиеся itag серверу не предлагаем — иначе он досыпает за них пачками
     *  (лог: newSegs=6 по 4.4МБ) и рвёт временные якоря. До первой инициализации —
     *  пусто, только preferred (как у референса). Вызывать под локом. */
    private fun currentSelectedIds(): List<FormatId> =
        if (initializedFormats.isNotEmpty()) {
            listOfNotNull(audioFormat?.formatId(), videoFormat?.formatId())
        } else emptyList()

    /** Кумулятивные ranges: потреблённое за сессию + held-префетч, merge по правилу
     *  findings (extend при seq==prev+1, иначе новый range — гэпы seek'ов целы).
     *  Вызывать под локом. */
    private fun buildCumulativeBufferedRanges(): List<BufferedRange> {
        val currentItags = listOfNotNull(audioFormat?.streamInfo?.itag, videoFormat?.streamInfo?.itag)
        return currentItags.flatMap { itag ->
            val formatId = initializedFormats[itag]?.id ?: return@flatMap emptyList()
            val points = mutableMapOf<Long, Pair<Long, Long>>()
            consumedSegsByItag[itag]?.let { points.putAll(it) }
            initializedFormats[itag]?.downloadedSegments?.values?.forEach { seg ->
                if (!seg.header.isInitSeg) {
                    points.putIfAbsent(seg.sequenceNumber, seg.header.startMs to seg.duration)
                }
            }
            if (points.isEmpty()) return@flatMap emptyList()
            val sorted = points.entries.sortedBy { it.key }
            val ranges = mutableListOf<BufferedRange>()
            var runStart = sorted[0].key
            var runStartMs = sorted[0].value.first
            var runDur = sorted[0].value.second
            var prev = sorted[0].key
            fun flush() {
                ranges.add(
                    BufferedRange.newBuilder().setFormatId(formatId).setStartTimeMs(runStartMs)
                        .setDurationMs(runDur)
                        .setStartSegmentIndex(runStart.toInt())
                        .setEndSegmentIndex(prev.toInt()).build()
                )
            }
            for ((seq, td) in sorted.drop(1)) {
                if (seq == prev + 1) {
                    runDur += td.second
                    prev = seq
                } else {
                    flush()
                    runStart = seq
                    runStartMs = td.first
                    runDur = td.second
                    prev = seq
                }
            }
            flush()
            ranges
        }
    }

    fun getNextSegment(playbackRequest: PlaybackRequest): Segment? {
        fatalError?.let { throw IOException("SABR error: ${it.type}") }
        val itag = playbackRequest.format.itag
        withState {
            lastFormatUseMs[itag] = SystemClock.elapsedRealtime()
            initializedFormats[itag]?.bufferedSegments?.keys?.retainAll(playbackRequest.bufferedSegments)
        }
        Log.i(TAG, "getNextSegment: itag=$itag, requested=${playbackRequest.segment}, live=${isLive()}, initFormats=${withState { initializedFormats.keys }}, hasFormat=${hasFormatInitialized(itag)}")
        val result = runBlocking {
            withContext(dispatcher) {
                var format = withState { initializedFormats[itag] }
                // Быстрый путь (live): точного sequence нет, но запрошенное время уже покрыто
                // префетчем прошлого ответа — отдаём без HTTP-запроса. Сервер выбирает сегмент по
                // playerTimeMs, поэтому тайм-матч корректен; гонять за точным номером — лишние
                // раундтрипы, каждый из которых крадёт полосу и даёт фризы (см. лог 20:20:2x:
                // каждый сегмент шёл через 2-3 запроса «requested N not found»).
                if (liveMetadata != null && format?.hasSegment(playbackRequest.segment) != true) {
                    val cached = withState { findTimeMatch(format, playbackRequest.segmentStartTimeMs, itag) }
                    if (cached != null) {
                        val served = withState { format!!.getSegment(cached.sequenceNumber) }
                        if (served != null) {
                            Log.i(TAG, "live time-match (cache): seq=${served.sequenceNumber} startMs=${served.header.startMs} ~ requested ${playbackRequest.segmentStartTimeMs} instead of ${playbackRequest.segment} (no request)")
                            noteServed(itag, served)
                            return@withContext served
                        }
                    }
                }
                repeat(if (liveMetadata != null) LIVE_REQUEST_RETRIES else 1) { attempt ->
                    if (format?.hasSegment(playbackRequest.segment) != true) {
                        if (attempt > 0) {
                            // После пустого ответа ждём дольше: следующий кусок ещё не готов.
                            val sinceEmpty = SystemClock.elapsedRealtime() - lastMediaEmptyMs
                            if (lastMediaEmptyMs > 0 && sinceEmpty < EMPTY_BACKOFF_MS) {
                                delay(EMPTY_BACKOFF_MS - sinceEmpty)
                            } else {
                                delay(LIVE_RETRY_DELAY_MS)
                            }
                        }
                        Log.i(TAG, "media request: attempt=$attempt, itag=$itag, segment=${playbackRequest.segment}, playerTimeMs=${playbackRequest.segmentStartTimeMs}")
                        media(playbackRequest)
                        // Retain selected + any format that still holds data or was used recently (30s)
                        // — avoids churn when ABR oscillates and lets multiplexed responses reuse
                        // already-downloaded segments for the other track without re-fetch.
                        withState {
                            val nowMs = SystemClock.elapsedRealtime()
                            initializedFormats.keys.retainAll { key ->
                                key == audioFormat?.streamInfo?.itag || key == videoFormat?.streamInfo?.itag ||
                                    initializedFormats[key]?.let { it.downloadedSegments.isNotEmpty() || it.bufferedSegments.isNotEmpty() } == true ||
                                    pendingSegments.containsKey(key) ||
                                    (lastFormatUseMs[key]?.let { nowMs - it < 30_000 } == true)
                            }
                        }
                        format = withState { initializedFormats[itag] }
                        Log.i(TAG, "after media: initFormats=${withState { initializedFormats.keys }}, hasSegment=${format?.hasSegment(playbackRequest.segment)}, downloaded=${format?.downloadedSegments?.keys?.sorted()}, pending=${withState { pendingSegments[itag]?.size }}, partial=${withState { partialSegments.size }}, endSegment=${format?.endSegmentNumber}")
                    }
                    if (format?.hasSegment(playbackRequest.segment) == true) {
                        val segment = withState { format!!.getSegment(playbackRequest.segment) }
                        if (segment != null) {
                            noteServed(itag, segment)
                            return@withContext segment
                        }
                    }
                    // Тайм-матч сразу после ответа: сервер уже вернул сегмент, покрывающий запрошенное
                    // время — не делаем ещё 2 попытки в поисках точного sequence (каждая — новый HTTP-запрос).
                    if (liveMetadata != null) {
                        val timeMatch = withState { findTimeMatch(format, playbackRequest.segmentStartTimeMs, itag) }
                        if (timeMatch != null) {
                            val served = withState { format!!.getSegment(timeMatch.sequenceNumber) }
                            if (served != null) {
                                Log.i(TAG, "live time-match (attempt=$attempt): seq=${served.sequenceNumber} startMs=${served.header.startMs} ~ requested ${playbackRequest.segmentStartTimeMs} instead of ${playbackRequest.segment}")
                                noteServed(itag, served)
                                return@withContext served
                            }
                        }
                    }
                }
                // Live: нормализованное сопоставление сегментов ПО ВРЕМЕНИ (startMs), а не по строгому
                // равенству requestedSequence == protobuf.sequence. Сервер выбирает чанк по playerTimeMs и
                // может вернуть другой sequence, чем запросил клиент — ищем ближайший по времени чанк,
                // а следующий запрос уходит от реально отданных sequence/времени (noteServed).
                if (liveMetadata != null) {
                    withState {
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
                                val byTime = findTimeMatch(fallbackFormat, requestedTime, itag)
                                if (byTime != null) {
                                    seg = fallbackFormat.getSegment(byTime.sequenceNumber)
                                    if (seg != null) {
                                        Log.i(TAG, "live fallback by time: returned seq ${seg.sequenceNumber} startMs=${seg.header.startMs} ~ requestedTime $requestedTime instead of $requestedSeq")
                                    }
                                }
                            }
                            // 2b) v17: ограниченный скип вперёд (не прыжок к голове!). Очередь отливила
                            // от фронта на 4–10с (медленный 299-й: roundtrip 3–5с за 2с медиа) — прыжок
                            // к голове рвал декларируемые времена, loadPosition улетал за конец окна
                            // таймлайна → seek к defaultPos → убитые in-flight → рестарт со старого →
                            // снова фолбэк (seek-шторм раз в 1–2 мин, лог 09:00–09:01). Скип ≤10с держит
                            // очередь внутри окна: следующий запрос бьёт в кэш, лок восстанавливается.
                            if (seg == null) {
                                val skip = findSkipMatch(fallbackFormat, requestedTime, itag, LIVE_SKIP_CAP_MS)
                                if (skip != null) {
                                    seg = fallbackFormat.getSegment(skip.sequenceNumber)
                                    if (seg != null) {
                                        Log.i(TAG, "live fallback skip: +${seg.header.startMs - requestedTime}ms seq=${seg.sequenceNumber} startMs=${seg.header.startMs} ~ requestedTime $requestedTime instead of $requestedSeq")
                                    }
                                }
                            }
                            // 3) Последний шанс — ближайший к голове. Только настоящий реконнект
                            //    (дыра > LIVE_SKIP_CAP_MS): в обычном режиме сюда не доходим, иначе
                            //    прыжок к голове запускает цикл «прыжок → loadPosition вне окна →
                            //    seek → рестарт со старого» (см. 2b).
                            //    Уже отданный sequence исключаем — иначе отдадим тот же чанк повторно.
                            if (seg == null && headSeq != null) {
                                val lastServedSeq = lastReturnedSequenceByItag[itag]
                                val nearestHead = fallbackFormat.downloadedSegments.keys
                                    .filter { it != lastServedSeq }
                                    .minByOrNull { kotlin.math.abs(it - headSeq) }
                                if (nearestHead != null) {
                                    seg = fallbackFormat.getSegment(nearestHead)
                                    if (seg != null) {
                                        Log.i(TAG, "live nearest head fallback: returned $nearestHead for head $headSeq")
                                        hasStartedLive = true
                                    }
                                }
                            }
                            if (seg != null) {
                                noteServed(itag, seg)
                                serverSeekTimeMs = null
                                return@withState seg
                            }
                        }
                        null
                    }?.let { return@withContext it }
                }
                // v14: дырка в серии itag (лог 08:09:28: audio 516 нет, available=[515],
                // head=517 — и дальше 16с спама 164Б + error-backoff лоадера до смены трека).
                // Вместо фатала — один прыжок якорей к голове и повтор: пропущенный seq
                // считаем скипом сервера. Лоадер всё время занят (дедлока v13 нет), спам
                // ограничен парой пустых ответов, а не 16с.
                if (liveMetadata != null) {
                    val headSeqNow = withState { liveMetadata?.headSequenceNumber }
                    val headTimeNow = withState { liveMetadata?.headTimeMs }
                    if (headSeqNow != null && headSeqNow > playbackRequest.segment) {
                        Log.w(TAG, "live hole skip: itag=$itag seq=${playbackRequest.segment} missing, head=$headSeqNow — re-anchor and retry once")
                        val stepMs = withState { getLastRealStepMs(itag) } ?: 2000L
                        withState {
                            lastReturnedSequenceByItag[itag] = headSeqNow - 1
                            if (headTimeNow != null) {
                                lastReturnedTimeMsByItag[itag] = headTimeNow - stepMs
                                lastReturnedEndTimeMsByItag[itag] = headTimeNow
                            } else {
                                lastReturnedTimeMsByItag.remove(itag)
                                lastReturnedEndTimeMsByItag.remove(itag)
                            }
                        }
                        val skipReq = PlaybackRequest(
                            playbackRequest.format,
                            playbackRequest.playerPosition,
                            playbackRequest.playbackSpeed,
                            headSeqNow,
                            headTimeNow ?: playbackRequest.segmentStartTimeMs,
                            playbackRequest.bufferedSegments,
                        )
                        media(skipReq)
                        val skipServed = withState {
                            val f = initializedFormats[itag] ?: return@withState null
                            val exact = if (f.hasSegment(headSeqNow)) f.getSegment(headSeqNow) else null
                            exact ?: findTimeMatch(f, skipReq.segmentStartTimeMs, itag)
                                ?.let { f.getSegment(it.sequenceNumber) }
                        }
                        if (skipServed != null) {
                            Log.i(TAG, "live hole skip served: itag=$itag seq=${skipServed.sequenceNumber} startMs=${skipServed.header.startMs} (was stuck at ${playbackRequest.segment})")
                            noteServed(itag, skipServed)
                            return@withContext skipServed
                        }
                    }
                }
                null
            }
        }
        if (result == null) {
            val f = withState { initializedFormats[itag] }
            // Для live с пустым кэшем не спамим E — это transient (голова ещё не догнала, или трек только что инициализирован)
            if (liveMetadata != null && f?.downloadedSegments?.isEmpty() == true) {
                Log.w(TAG, "live no segment yet ${playbackRequest.segment} for itag=$itag, head=${withState { liveMetadata?.headSequenceNumber }}, pending=${withState { pendingSegments[itag]?.size }} — will retry at head")
            } else {
                Log.e(TAG, "no segment ${playbackRequest.segment} for itag=$itag, available=${f?.downloadedSegments?.keys?.sorted()}, endSegment=${f?.endSegmentNumber}, liveHead=${withState { liveMetadata?.headSequenceNumber }}, pending=${withState { pendingSegments[itag]?.size }}, partialIds=${withState { partialSegments.keys }}")
            }
        }
        return result
    }

    private suspend fun media(playbackRequest: PlaybackRequest) {
        backoffTime?.let { delay(it.toLong()); backoffTime = null }
        // Межвызовный троттл после пустого ответа: два трека (аудио+видео) идут через один
        // dispatcher, без паузы они долбят сервер по очереди каждые ~100мс.
        val sinceEmpty0 = SystemClock.elapsedRealtime() - lastMediaEmptyMs
        if (lastMediaEmptyMs > 0 && sinceEmpty0 < EMPTY_BACKOFF_MS) {
            delay(EMPTY_BACKOFF_MS - sinceEmpty0)
        }
        mediaStoredCounter = 0
        val now = SystemClock.elapsedRealtime()
        // Снимок состояния под локом: формат/контексты мутируются из других loader-потоков
        val abr = withState {
            val xtags = audioFormat?.formatId()?.xtags?.let { Xtags(it) }
            // sticky/manual — максимум высоты за сессию: сервер не должен «запирать» качество
            // на упавшем разрешении, если клиентский ABR на миг сполз вниз.
            val stickyResolutionHeight = max(videoFormat?.streamInfo?.height ?: 0, max(stickyResolutionMax, 360))
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
                // Пол 1 Мбит/с на оценке, докладываемой серверу: после сбойной сессии синглтон-метр
                // (живёт весь процесс) мог остаться на кбит/с — тогда сервер запирает качество на
                // минимуме и СЛЕДУЮЩИЙ эфир стартует сразу в 144p. Реальный канал поднимет выше.
                .setBandwidthEstimate(maxOf(bandwidthEstimator.bitrateEstimate, MIN_REPORTED_BANDWIDTH_BPS))
                .setStickyResolution(stickyResolutionHeight)
                .setClientViewportHeight(max(videoFormat?.streamInfo?.height ?: 0, 360))
                .setClientViewportWidth(max(videoFormat?.streamInfo?.width ?: 0, 640))
                .setLastManualSelectedResolution(stickyResolutionHeight).setVisibility(1).build()
            VideoPlaybackAbrRequest.newBuilder().setClientAbrState(state)
                .setPlayerTimeMs(playbackRequest.segmentStartTimeMs).setVideoPlaybackUstreamerConfig(ustreamerConfig)
                .addAllPreferredAudioFormatIds(listOfNotNull(audioFormat?.formatId()))
                .addAllPreferredVideoFormatIds(listOfNotNull(videoFormat?.formatId()))
                .addAllSelectedFormatIds(currentSelectedIds())
                .addAllBufferedRanges(buildCumulativeBufferedRanges())
                .setStreamerContext(StreamerContext.newBuilder().setPoToken(poToken ?: ByteString.empty())
                    .setClientInfo(StreamerContext.ClientInfo.newBuilder().setClientName(101).setClientVersion("1.02")
                        .setDeviceMake("Apple").setDeviceModel("RealityDevice14,1").setOsName("visionOS")
                        .setOsVersion("25.6.0.23O471").build())
                    .addAllSabrContexts(activeSabrContexts.mapNotNull { sabrContexts[it] })
                    .addAllUnsentSabrContexts(sabrContexts.keys.filter { it !in activeSabrContexts })
                    .setPlaybackCookie(playbackCookie?.toByteString() ?: ByteString.empty()).build()).build()
        }
        val request = Request.Builder().url("$url&rn=${requestNumber++}")
            .addHeader("Content-Type", CONTENT_TYPE).addHeader("Accept-Encoding", ENCODING)
            .addHeader("Accept", ACCEPT).addHeader("Origin", YOUTUBE_FRONTEND_URL)
            .addHeader("Referer", "$YOUTUBE_FRONTEND_URL/").addHeader("User-Agent", USER_AGENT)
            .post(RequestBody.create(MediaType.parse(CONTENT_TYPE), abr.toByteArray())).build()
        lastRequestMs = SystemClock.elapsedRealtime()
        val roundtripStartMs = SystemClock.elapsedRealtime()
        val networkBytesBefore = networkBytesCounter.get()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP request failed: ${response.code()}")
            val body = response.body() ?: throw IOException("HTTP response has no body")
            // Считаем реальные байты ответа (identity — размер на проводе): SabrDataSource
            // атрибутирует их метру пропускной способности, чтобы сэмпл отражал фактическую
            // скорость канала, а не «один сегмент за полный раундтрип».
            val countingStream = object : FilterInputStream(body.byteStream()) {
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, len).also { if (it > 0) networkBytesCounter.addAndGet(it.toLong()) }
                override fun read(): Int =
                    super.read().also { if (it >= 0) networkBytesCounter.incrementAndGet() }
            }
            val reader = StreamingUmpReader(countingStream)
            while (true) {
                val part = reader.readPart() ?: break
                processPart(part)
            }
        }
        Log.i(TAG, "media roundtrip: bytes=${networkBytesCounter.get() - networkBytesBefore}, ms=${SystemClock.elapsedRealtime() - roundtripStartMs}, newSegs=$mediaStoredCounter")
        if (mediaStoredCounter == 0) {
            lastMediaEmptyMs = SystemClock.elapsedRealtime()
        }
    }

    private fun processPart(part: Part) {
        var liveMeta: LiveMetadata? = null
        withState {
            when (part.type) {
                UMPPartId.MEDIA_HEADER -> {
                    val header = MediaHeader.parseFrom(part.data)
                    if (header.videoId != videoId) throw IOException("Header mismatch")
                    val durationMs = when {
                        header.hasDurationMs() -> header.durationMs
                        header.hasTimeRange() && header.timeRange.hasDurationTicks() && header.timeRange.hasTimescale() && header.timeRange.timescale != 0 ->
                            ticksToMs(header.timeRange.durationTicks, header.timeRange.timescale)
                        header.hasStartMs() && liveMetadata != null -> 2000L // live estimate = реальный шаг эфира 2с (v9: 4900→2000, иначе очередь 5с vs шаг 2с → virtual buffer)
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
                    val id = parser.readVarint()?.toInt() ?: return@withState
                    partialSegments[id]?.data?.add(parser.data())
                }
                UMPPartId.MEDIA_END -> {
                    val parser = UmpParser(part.data)
                    val id = parser.readVarint()?.toInt() ?: return@withState
                    val segment = partialSegments.remove(id) ?: return@withState
                    // Валидация как у референса googlevideo: оборванный long-poll даёт кусок
                    // короче заявленного content_length — такой в экстрактор нельзя (фриз),
                    // дропаем, доберём следующим запросом с бэкоффом.
                    if (segment.header.hasContentLength() && segment.header.contentLength > 0 &&
                        segment.length().toLong() != segment.header.contentLength) {
                        Log.w(TAG, "media segment content-length mismatch: itag=${segment.header.itag}, " +
                            "sequence=${segment.sequenceNumber}, expected=${segment.header.contentLength}, " +
                            "got=${segment.length()} — dropped")
                        return@withState
                    }
                    val format = initializedFormats[segment.header.itag]
                    if (format == null) {
                        pendingSegments.getOrPut(segment.header.itag) { mutableListOf() }.add(segment)
                        Log.i(TAG, "media segment pending metadata: itag=${segment.header.itag}, " +
                            "sequence=${segment.sequenceNumber}")
                        return@withState
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
                    val existing = initializedFormats[metadata.formatId.itag]
                    if (existing != null) {
                        // v15 (sabr-dash-poc findings): broadcast шлёт init на каждый ответ.
                        // Только освежаем метаданные — кэш сегментов НЕ трогаем, иначе каждый
                        // ответ вайпает downloaded и клиент вечно рефетчит (downloaded=[]).
                        existing.endSegmentNumber = metadata.endSegmentNumber
                        existing.duration = metadata.endTimeMs
                    } else {
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
                    lastFormatUseMs[metadata.formatId.itag] = SystemClock.elapsedRealtime()
                }
                UMPPartId.LIVE_METADATA -> {
                    val meta = LiveMetadata.parseFrom(part.data)
                    liveMetadata = meta
                    // Фиксируем начало DVR-окна ПЕРВЫМ значением minSeekable — домен времени чанков
                    // (windowPos = time − windowStart) не должен плыть вместе со скользящим окном.
                    if (liveWindowStartMs == null) {
                        liveWindowStartMs = meta.takeIf {
                            it.hasMinSeekableTimeTicks() && it.hasMinSeekableTimescale() && it.minSeekableTimescale != 0
                        }?.let { ticksToMs(it.minSeekableTimeTicks, it.minSeekableTimescale) } ?: 0L
                    }
                    Log.i(TAG, "live metadata: headSeq=${meta.headSequenceNumber}, headTimeMs=${meta.headTimeMs}, broadcastId=${meta.broadcastId}, minSeekTicks=${if (meta.hasMinSeekableTimeTicks()) meta.minSeekableTimeTicks else "null"}, windowStartMs=$liveWindowStartMs")
                    liveMeta = meta
                }
                UMPPartId.SABR_SEEK -> {
                    val seek = SabrSeek.parseFrom(part.data)
                    if (seek.hasSeekMediaTime() && seek.hasSeekMediaTimescale() && seek.seekMediaTimescale > 0) {
                        // v20: ticks*1000 переполняет даже Long при ns-timescale
                        // (1.1e16*1000=1.1e19 > 9.2e18 → positionMs уходил в минус, лог 09:48:43).
                        serverSeekTimeMs = ticksToMs(seek.seekMediaTime, seek.seekMediaTimescale)
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
                UMPPartId.RELOAD_PLAYER_RESPONSE -> {
                    // Как у референса googlevideo — сервер инвалидировал сессию: сбрасываем
                    // handshake (cookie/контексты), медиакэш оставляем. Иначе молотим в мёртвую
                    // сессию и получаем пустые ответы → фриз.
                    try {
                        val reload = ReloadPlaybackContext.parseFrom(part.data)
                        val token = reload.takeIf { it.hasReloadPlaybackParams() }
                            ?.reloadPlaybackParams?.takeIf { it.hasToken() }?.token
                        playbackCookie = null
                        sabrContexts.clear()
                        activeSabrContexts.clear()
                        Log.e(TAG, "reload player response: session reset, token=${token ?: "none"}")
                    } catch (e: Exception) { Log.w(TAG, "failed to parse RELOAD_PLAYER_RESPONSE: $e") }
                }
                UMPPartId.SNACKBAR_MESSAGE -> {
                    Log.i(TAG, "snackbar message received, size=${part.data.size}")
                }
                else -> Log.w(TAG, "Unhandled UMP part: ${part.type} size=${part.data.size}")
            }
        }
        // Уведомляем слушателя ВНЕ лока: он обновляет таймлайн MediaSource (refreshSourceInfo)
        liveMeta?.let { liveMetadataListener?.invoke(it) }
    }

    private fun storeSegment(format: InitializedFormat, segment: Segment) {
        format.downloadedSegments[segment.sequenceNumber] = segment
        mediaStoredCounter++
        if (segment.header.isInitSeg) format.initSegment = segment
        lastFormatUseMs[segment.header.itag] = SystemClock.elapsedRealtime()
        evictStaleSegments(format)
        Log.i(TAG, "media segment stored: itag=${segment.header.itag}, " +
            "sequence=${segment.sequenceNumber}, init=${segment.header.isInitSeg}, bytes=${segment.length()}")
    }

    /** v16: пропущенные префетчи (очередь перепрыгнула после seek'а к краю) иначе лежат
     *  в downloaded вечно: лог 08:40 — seq=15303 в downloaded спустя минуты, fallback
     *  available=[15303, …] врёт, память растёт (v15 убрал вайп, эвикции не было).
     *  Держим ~4 мин DVR назад от головы + жёсткий кап; consumed-историю — ~20 мин. */
    private fun evictStaleSegments(format: InitializedFormat) = withState {
        val head = liveMetadata?.headSequenceNumber ?: return@withState
        val floor = head - STALE_KEEP_SEGS
        if (format.downloadedSegments.size > STALE_EVICT_THRESHOLD) {
            format.downloadedSegments.keys.removeAll { it < floor }
        }
        if (format.bufferedSegments.size > STALE_EVICT_THRESHOLD) {
            format.bufferedSegments.keys.removeAll { it < floor }
        }
        consumedSegsByItag[format.id.itag]?.let { hist ->
            if (hist.size > CONSUMED_HIST_CAP) hist.keys.removeAll { it < head - CONSUMED_HIST_KEEP }
        }
    }

    fun generatePoToken(): ByteString? =
        poTokenProvider?.getStreamingPoToken(videoId)?.let { ByteString.copyFrom(it) }

    /** Точный перевод ticks→ms без переполнения: сначала деление, остаток — отдельно.
     *  Прямое ticks*1000 вылетает за Long при ns-timescale (лог 09:48:43). */
    private fun ticksToMs(ticks: Long, timescale: Long): Long =
        if (timescale <= 0) 0L else ticks / timescale * 1000 + (ticks % timescale) * 1000 / timescale

    companion object {
        private const val TAG = "SabrStream"
        private const val CONTENT_TYPE = "application/x-protobuf"
        private const val ENCODING = "identity"
        private const val ACCEPT = "application/vnd.yt-ump"
        private const val USER_AGENT = "com.google.visionos.youtube/1.02(RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X; GB)"
        private const val YOUTUBE_FRONTEND_URL = "https://www.youtube.com"
        private const val LIVE_REQUEST_RETRIES = 3
        private const val LIVE_RETRY_DELAY_MS = 250L
        /** Пауза после пустого ответа (без новых сегментов): сервер ещё не сгенерил
         *  следующий 2с-кусок, долбить его каждые 250мс бессмысленно — ждём ~половину
         *  ритма эфира. См. шторм 169Б в логе 22:04:57 (10 запросов за 1.3с). */
        private const val EMPTY_BACKOFF_MS = 900L
        /** v16: окно эвикции пропущенных префетчей (~4 мин DVR от головы) и порог срабатывания. */
        private const val STALE_KEEP_SEGS = 120L
        private const val STALE_EVICT_THRESHOLD = 120
        /** v16: кап consumed-истории для cumulative bufferedRanges (~20 мин). */
        private const val CONSUMED_HIST_KEEP = 600L
        private const val CONSUMED_HIST_CAP = 1500
        /** v17: кап скипа вперёд в фолбэке (мс). Дыры 4–10с — обычный режим отстающей
         *  очереди, скипаем минимально; больше — только реконнект прыжком к голове. */
        private const val LIVE_SKIP_CAP_MS = 10_000L
        /** максимальное расхождение по времени (мс) для тайм-матча вперёд — ~1.5 live-сегмента.
         *  Было 30_000: фолбэк молча отдавал чанк до 30с не от запрошенного места → рассинхрон.
         *  Было 12_500: кэш-матч подхватывал сегмент на 11с вперёд от запрошенного (лог 12:36:56:
         *  seq=7875 startMs=15749607 для requested 15738509) → пропуск контента и разрыв очереди.
         *  Было 6_000: отдавал на 5с вперёд (лог 22:24:04: seq=1002 startMs=5008400 для
         *  requested 5003400) → видимый скачок. 3с покрывает следующий префетч-сегмент
         *  (ритм эфира 2с + джиттер) и не даёт больших прыжков. */
        private const val LIVE_TIME_TOLERANCE_MS = 3_000L
        /** допустимый сдвиг НАЗАД от запрошенного времени (мс) — только дрожание границ при
         *  перенумерации сервера. Всё, что старее — повтор уже проигранного чанка (запрещено). */
        private const val SAME_TIME_EPS_MS = 1_500L
        /** Пол оценки канала, докладываемой серверу (бит/с): не даём обрушившемуся сэмплу
         *  метра «запереть» серверный ABR на минимуме между сессиями. */
        private const val MIN_REPORTED_BANDWIDTH_BPS = 1_000_000L
    }
}
