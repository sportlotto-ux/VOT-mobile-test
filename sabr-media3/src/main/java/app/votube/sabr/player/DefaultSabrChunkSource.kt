package app.votube.sabr.player

import androidx.media3.common.C
import androidx.media3.common.C.TrackType
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.chunk.BaseMediaChunkIterator
import androidx.media3.exoplayer.source.chunk.BundledChunkExtractor
import androidx.media3.exoplayer.source.chunk.Chunk
import androidx.media3.exoplayer.source.chunk.ChunkExtractor
import androidx.media3.exoplayer.source.chunk.ChunkHolder
import androidx.media3.exoplayer.source.chunk.ContainerMediaChunk
import androidx.media3.exoplayer.source.chunk.InitializationChunk
import androidx.media3.exoplayer.source.chunk.MediaChunk
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.CmcdConfiguration
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.FallbackOptions
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import androidx.media3.extractor.ChunkIndex
import app.votube.sabr.manifest.Representation
import app.votube.sabr.manifest.SabrManifest
import app.votube.sabr.parser.PlaybackRequest
import app.votube.sabr.parser.SabrClient
import android.os.SystemClock

/** A default [SabrChunkSource] implementation.  */
@UnstableApi
class DefaultSabrChunkSource(
    chunkExtractorFactory: ChunkExtractor.Factory,
    private val manifest: SabrManifest,
    private val sabrClient: SabrClient,
    private val adaptationSetIndices: IntArray,
    private var trackSelection: ExoTrackSelection,
    private val trackType: @TrackType Int,
    private val dataSource: DataSource,
    private val playerId: PlayerId,
) : SabrChunkSource {

    /** [SabrChunkSource.Factory] for [DefaultSabrChunkSource] instances.  */
    class Factory(
        private val dataSourceFactory: DataSource.Factory,
    ) : SabrChunkSource.Factory {
        private val chunkExtractorFactory = BundledChunkExtractor.Factory()

        override fun createSabrChunkSource(
            manifest: SabrManifest,
            sabrClient: SabrClient,
            adaptationSetIndices: IntArray,
            trackSelection: ExoTrackSelection,
            trackType: @TrackType Int,
            elapsedRealtimeOffsetMs: Long,
            transferListener: TransferListener?,
            playerId: PlayerId,
            cmcdConfiguration: CmcdConfiguration?,
        ): SabrChunkSource {
            val dataSource = dataSourceFactory.createDataSource()
            transferListener?.let { dataSource.addTransferListener(it) }
            return DefaultSabrChunkSource(
                chunkExtractorFactory,
                manifest,
                sabrClient,
                adaptationSetIndices,
                trackSelection,
                trackType,
                dataSource,
                playerId,
            )
        }

        override fun getOutputTextFormat(sourceFormat: Format): Format {
            return chunkExtractorFactory.getOutputTextFormat(sourceFormat)
        }
    }

    private val representationHolders: MutableList<RepresentationHolder>

    private var fatalError: Exception? = null
    private var missingLastSegment = false
    private val isLive = manifest.durationMs == C.TIME_UNSET
    private var lastTrackSwitchMs: Long = 0L
    private var lastSelectedTrackIndex: Int = C.INDEX_UNSET

    init {
        val representations =
            adaptationSetIndices.flatMap { manifest.adaptationSets[it].representations }
                .filterNotNull().toList()
        representationHolders = (0 until trackSelection.length()).map {
            val representation = representations[trackSelection.getIndexInTrackGroup(it)]
            RepresentationHolder(
                Util.msToUs(representation.streamInfo.durationMs ?: manifest.durationMs),
                representation,
                chunkExtractorFactory.createProgressiveMediaExtractor(
                    trackType,
                    representation.format,
                    false,
                    emptyList(),
                    null,
                    playerId
                ),
            )
        }.toMutableList()
    }

    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long {
        // inform the server when we last sought to a new position
        sabrClient.lastSeekMs = SystemClock.elapsedRealtime()

        // Segments are aligned across representations, so any segment index will do.
        for (representationHolder in representationHolders) {
            if (representationHolder.chunkIndex != null) {
                val segmentCount = representationHolder.segmentCount
                if (segmentCount == 0L) {
                    continue
                }
                val segmentNum = representationHolder.getSegmentNum(positionUs)
                val firstSyncUs = representationHolder.getSegmentStartTimeUs(segmentNum)
                val secondSyncUs = if (firstSyncUs < positionUs && (segmentNum < segmentCount - 1))
                    representationHolder.getSegmentStartTimeUs(segmentNum + 1)
                else firstSyncUs
                return seekParameters.resolveSeekPositionUs(positionUs, firstSyncUs, secondSyncUs)
            }
        }
        // We don't have a segment index to adjust the seek position with yet.
        return positionUs
    }

    override fun updateTrackSelection(trackSelection: ExoTrackSelection?) {
        this.trackSelection = trackSelection
            ?: throw IllegalArgumentException("SABR track selection must not be null")
    }

    override fun maybeThrowError() {
        if (fatalError != null) {
            throw fatalError!!
        }
    }

    override fun getPreferredQueueSize(
        playbackPositionUs: Long,
        queue: MutableList<out MediaChunk>,
    ): Int {
        if (fatalError != null || trackSelection.length() < 2) {
            return queue.size
        }
        return trackSelection.evaluateQueueSize(playbackPositionUs, queue)
    }

    override fun shouldCancelLoad(
        playbackPositionUs: Long, loadingChunk: Chunk, queue: MutableList<out MediaChunk>,
    ): Boolean {
        if (fatalError != null) {
            return false
        }
        return trackSelection.shouldCancelChunkLoad(playbackPositionUs, loadingChunk, queue)
    }

    override fun getNextChunk(
        loadingInfo: LoadingInfo,
        loadPositionUs: Long,
        queue: List<MediaChunk>,
        out: ChunkHolder,
    ) {
        if (fatalError != null) {
            return
        }

        android.util.Log.i(
            "SabrChunkSource",
            "getNextChunk: queue=${queue.size}, loadPositionUs=$loadPositionUs, " +
                "selectedIndex=${trackSelection.selectedIndex}, " +
                "itag=${trackSelection.selectedFormat.id}, " +
                "resolution=${trackSelection.selectedFormat.width}x${trackSelection.selectedFormat.height}"
        )
        val playbackPositionUs = loadingInfo.playbackPositionUs
        val bufferedDurationUs = loadPositionUs - playbackPositionUs
        logReadaheadPolicy(bufferedDurationUs)

        val previousChunk = queue.lastOrNull()

        val chunkIterators = representationHolders.map {
            if (it.chunkIndex == null) MediaChunkIterator.EMPTY
            else {
                val lastAvailableSegmentNum = it.getLastAvailableSegmentNum()

                val segmentNum = previousChunk?.nextChunkIndex ?: Util.constrainValue(
                    it.getSegmentNum(loadPositionUs),
                    0,
                    lastAvailableSegmentNum
                )

                RepresentationSegmentIterator(
                    it, segmentNum, lastAvailableSegmentNum
                )
            }
        }.toTypedArray()

        // Adaptive track selection may change the selected format when `updateSelectedTrack` is called.
        // This can lead to playback errors if only one stream changes. We artificially delay this by only
        // changing the format on the next selection, ensuring there is some data already buffered.
        var representationHolder = representationHolders[trackSelection.selectedIndex]
        sabrClient.selectFormat(representationHolder.representation)
        // Hysteresis for live ABR: avoid spiral downgrades and start-at-144p.
        // Было: suppress только если !critical && queue>=2 — на старте buffer 0 < min (2000) => critical true,
        // поэтому сразу даунгрейд к 144p. Теперь подавляем даунгрейд 10с даже при critical, если буфер >500мс
        // и очередь не пуста — держим высокое качество первые секунды, даём буферу набраться.
        val nowMs = SystemClock.elapsedRealtime()
        val timeSinceSwitchMs = nowMs - lastTrackSwitchMs
        val bufferedMs = Util.usToMs(bufferedDurationUs)
        val isRecentlySwitched = lastTrackSwitchMs != 0L && timeSinceSwitchMs < 10_000
        val beforeIndex = trackSelection.selectedIndex
        val beforeFormat = trackSelection.selectedFormat
        trackSelection.updateSelectedTrack(
            playbackPositionUs,
            bufferedDurationUs,
            C.TIME_UNSET,
            queue,
            chunkIterators,
        )
        val afterIndex = trackSelection.selectedIndex
        val isDowngrade = afterIndex != beforeIndex && trackSelection.getFormat(afterIndex).bitrate < beforeFormat.bitrate
        val shouldSuppressDowngrade = isLive && isRecentlySwitched && isDowngrade && queue.size >= 1 && bufferedMs > 500
        if (shouldSuppressDowngrade) {
            android.util.Log.i(
                "SabrChunkSource",
                "ABR hysteresis: suppress downgrade $beforeIndex -> $afterIndex (recent ${timeSinceSwitchMs}ms ago, buf=${bufferedMs}ms, queue=${queue.size})"
            )
            // Для текущего чанка используем старый holder (высокое качество), селектор остаётся на низком
            // но следующий getNextChunk снова предложит даунгрейд и снова подавит — держим 10с
            representationHolder = representationHolders[beforeIndex]
        } else {
            if (afterIndex != beforeIndex) {
                lastTrackSwitchMs = nowMs
                lastSelectedTrackIndex = afterIndex
                android.util.Log.i(
                    "SabrChunkSource",
                    "ABR switch: $beforeIndex -> $afterIndex (buf=${bufferedMs}ms, downgrade=$isDowngrade)"
                )
            }
            // обычный путь — holder уже соответствует afterIndex, но если был before, нужно обновить
            representationHolder = representationHolders[trackSelection.selectedIndex]
            sabrClient.selectFormat(representationHolder.representation)
        }

        if (representationHolder.chunkIndex == null) {
            // SABR is server-driven: the container index is only built once media fragments
            // (moof/mdat) have been parsed, so it may be absent until the first media chunk
            // loads. Never signal EOS here and never re-request initialization.
            if (!representationHolder.initializationRequested && queue.isEmpty()) {
                // when we request a new format, it should start with an initialization chunk
                val dataSpec = DataSpec.Builder()
                    // must be non-null, but is unused
                    .setUri(manifest.serverAbrStreamingUri)
                    .setCustomData(
                        PlaybackRequest.initRequest(
                            representationHolder.representation.formatId(),
                            Util.usToMs(playbackPositionUs),
                            loadingInfo.playbackSpeed,
                        )
                    )
                    .build()

                android.util.Log.i(
                    "SabrChunkSource",
                    "requesting initialization: itag=${representationHolder.representation.streamInfo.itag}"
                )
                out.chunk = InitializationChunk(
                    dataSource,
                    dataSpec,
                    trackSelection.selectedFormat,
                    trackSelection.selectionReason,
                    trackSelection.selectionData,
                    representationHolder.chunkExtractor
                        ?: throw IllegalStateException("SABR chunk extractor is unavailable")
                )
                return
            }

            if (!representationHolder.initializationRequested) {
                // SABR мультиплексирует: один media-ответ может принести FormatInitializationMetadata
                // для обоих треков (аудио+видео). Если новый itag уже инициализирован сервером
                // (пришёл с другим треком), не ждём InitializationChunk — он никогда не придёт,
                // т.к. queue не пуста (старые чанки предыдущего itag). Считаем инициализацию done.
                if (sabrClient.hasFormatInitialized(representationHolder.representation.streamInfo.itag)) {
                    representationHolder.initializationRequested = true
                } else {
                    // Initialization is still loading; there is nothing to request yet.
                    return
                }
            }

            // Initialization completed but the extractor has not produced an index yet.
            // Request the next media segment using server metadata (endSegmentNumber)
            // instead of a client-built ChunkIndex.
            var segmentNum = previousChunk?.nextChunkIndex ?: 0
            // Для live идём от РЕАЛЬНО отданного сервером sequence (нормализация): сервер может
            // вернуть другой номер, чем наш счётчик, поэтому следующий запрос должен продолжать
            // именно от серверной нумерации, иначе уйдём в петлю no segment.
            if (isLive && previousChunk != null) {
                sabrClient.getLastReturnedSequence(representationHolder.representation.streamInfo.itag)?.let {
                    segmentNum = it
                }
            }
            var requestedTimeMs = Util.usToMs(previousChunk?.endTimeUs ?: loadPositionUs)
            if (isLive) {
                val downloaded = sabrClient.getDownloadedSegmentsDebug(representationHolder.representation.streamInfo.itag)
                val headSeq = sabrClient.getLiveHeadSequenceNumber()
                val headTimeMs = sabrClient.getLiveHeadTimeMs()
                val minSeekMs = sabrClient.getMinSeekableTimeMs()
                // Слушаем голову эфира: для live edge берём headSeq, для DVR rewind — проверяем пройденное время
                // SABR_SEEK обрабатываем только на seek/initial (когда queue пуст), иначе последовательные сегменты идут по порядку
                if (previousChunk == null) {
                    val rawSeek = sabrClient.peekServerSeekMs()
                    val targetMs = Util.usToMs(loadPositionUs)
                    val headIsBig = (headTimeMs ?: 0L) > 60_000
                    // Нулевой SABR_SEEK в середине большого live — мусор (init-позиция сервера), а не реальная
                    // команда перемотки. Иначе он перебивает реальную позицию (запуск/переключение трека/реконнект)
                    // и мы тянем середину или начало вместо нужного места. Ненулевой seek — реальная команда.
                    val serverSeekMs = rawSeek?.takeIf { it != 0L || !headIsBig }
                    // initial edge — любой первый запрос трека (previousChunk==null) с маленьким target и большой головой
                    // должен идти к head-15с. Глобальный hasStartedLive ломал второй трек (аудио/видео): первый трек уже выставлял
                    // hasStartedLive=true, второй с previousChunk==null попадал в DVR mapping 0 → segment 0/7417 вместо головы.
                    val isInitialEdge = headSeq != null && headSeq > 100 && targetMs < 1000 && headIsBig
                    // Причина проблемы pXBfmgk9lSU: сервер после init шлёт seek=0 (начало), а голова 1153 (2300с) —
                    // если слушаем seek=0, просим середину (692) вместо края, получаем 1,2,3 и петлю no segment 693.
                    // Для live edge игнорируем seek=0 и берём голову.
                    if (isInitialEdge) {
                        sabrClient.consumeServerSeekMs() // чистим stale seek 0
                        segmentNum = headSeq!!
                        val rawRequested = (headTimeMs ?: 0L) - 15000 // 15с до головы — стабильно как в браузере
                        // Не улетаем за окно DVR (window 10с → head-15000 вне окна) — clamp к [minSeek, head],
                        // иначе startTime станет 0, а плеер на defaultPos 5с → readahead -5с и ABR падает в 144p
                        requestedTimeMs = if (minSeekMs != null && headTimeMs != null) rawRequested.coerceIn(minSeekMs, headTimeMs) else rawRequested
                        sabrClient.hasStartedLive = true
                        android.util.Log.i("SabrChunkSource", "live initial head (ignore seek): headSeq=$headSeq headTimeMs=$headTimeMs seekMs=$rawSeek -> segmentNum=$segmentNum requestedTimeMs=$requestedTimeMs")
                    } else if (serverSeekMs != null) {
                        sabrClient.consumeServerSeekMs()
                        // Сервер сказал куда мотать (ненулевой seek) — берём отрезок по времени seek
                        requestedTimeMs = serverSeekMs
                        val estDurationMs = if (representationHolder.lastSegmentDurationUs > 0) representationHolder.lastSegmentDurationUs / 1000 else 5000L
                        if (headSeq != null && headTimeMs != null) {
                            val offsetMs = headTimeMs - serverSeekMs
                            val segmentsAgo = if (offsetMs > 0) offsetMs / estDurationMs else 0L
                            segmentNum = maxOf(0L, headSeq - segmentsAgo)
                        }
                        android.util.Log.i("SabrChunkSource", "live serverSeek (initial/seek): seekMs=$serverSeekMs headSeq=$headSeq headTimeMs=$headTimeMs -> segmentNum=$segmentNum requestedTimeMs=$requestedTimeMs")
                    } else {
                        sabrClient.consumeServerSeekMs() // чистим мусорный seek 0, идём по реальной позиции
                        // DVR/continuation: целевое время = windowStart + loadPosition, где windowStart
                        // зафиксирован на первом LiveMetadata (не скользит вместе с окном — иначе
                        // startTime старых и новых чанков разъедутся).
                        // A/V-подгонку по sequence УБРАЛИ: серии sequence у аудио/видео разные (в логе
                        // одно и то же время = video 4567, audio 4570), прыжок к «чужому» номеру давал
                        // requestedTime на 60с в прошлом (4511166 вместо 4571166) и вечный fallback.
                        if (headSeq != null && headTimeMs != null) {
                            val windowStartMs = sabrClient.getLiveWindowStartMs() ?: (minSeekMs ?: 0L)
                            val absoluteTimeMs = windowStartMs + targetMs
                            // Проверяем что абсолютное время внутри окна
                            val clampedAbsolute = absoluteTimeMs.coerceIn(windowStartMs, headTimeMs)
                            requestedTimeMs = clampedAbsolute
                            val estDurationMs = if (representationHolder.lastSegmentDurationUs > 0) representationHolder.lastSegmentDurationUs / 1000 else 5000L
                            val offsetFromHeadMs = headTimeMs - clampedAbsolute
                            val segmentsAgo = if (offsetFromHeadMs > 0) offsetFromHeadMs / maxOf(estDurationMs, 1) else 0L
                            segmentNum = maxOf(0L, headSeq - segmentsAgo)
                            android.util.Log.i("SabrChunkSource", "live DVR mapping: targetMs=$targetMs windowStart=$windowStartMs absolute=$clampedAbsolute headSeq=$headSeq -> segmentNum=$segmentNum")
                        } else if (headSeq != null && headSeq > 100 && segmentNum < 10) {
                            // Fallback: если голова далеко, а просим 0/1 — берём голову
                            segmentNum = headSeq
                            requestedTimeMs = headTimeMs ?: requestedTimeMs
                            android.util.Log.i("SabrChunkSource", "live head fallback: headSeq=$headSeq -> segmentNum=$segmentNum")
                        }
                    }
                } else {
                    // sequential live: segmentNum — от РЕАЛЬНОГО sequence последнего отданного сегмента
                    // (серия сервера для этого формата), requestedTimeMs — от РЕАЛЬНОГО времени последнего
                    // сегмента (start+duration), а НЕ headTime − (headSeq − seq)·est: серии sequence у
                    // аудио/видео и у LiveMetadata разные, эта формула уводила время назад на ~5с/с
                    // (лог: 4568166 → 4440166 за 18с) → вечный fallback и фризы.
                    // Не улетаем за голову — сервер ещё не сгенерил head+1, clamp к head.
                    if (headSeq != null && segmentNum > headSeq) {
                        android.util.Log.w("SabrChunkSource", "live sequential clamp: segmentNum $segmentNum > headSeq $headSeq -> $headSeq")
                        segmentNum = headSeq
                    }
                    val estDurationMs = if (representationHolder.lastSegmentDurationUs > 0) representationHolder.lastSegmentDurationUs / 1000 else 5000L
                    val currentItag = representationHolder.representation.streamInfo.itag
                    val prevRequestItag = (previousChunk.dataSpec.customData as? PlaybackRequest)?.format?.itag
                    val sameFormatContinuing = prevRequestItag == currentItag
                    requestedTimeMs = when {
                        sameFormatContinuing && sabrClient.getLastReturnedEndTimeMs(currentItag) != null ->
                            sabrClient.getLastReturnedEndTimeMs(currentItag)!!
                        sameFormatContinuing && sabrClient.getLastReturnedTimeMs(currentItag) != null ->
                            sabrClient.getLastReturnedTimeMs(currentItag)!! + estDurationMs
                        else -> {
                            // Шов после ABR-переключения/реконнекта: продолжаем от времени очереди
                            // (абсолютный домен) — сервер отдаст сегмент нового формата, покрывающий это время
                            Util.usToMs(previousChunk.endTimeUs)
                        }
                    }
                    if (headTimeMs != null && requestedTimeMs > headTimeMs) requestedTimeMs = headTimeMs
                    // Аварийный catch-up ТОЛЬКО когда загрузка реально отстала от плеера (requestedTime
                    // позади playback на >4с): тянуть прошлое при живом плеере = буфер-минус → фриз.
                    // Обычное отставание от ПРЕФЕТЧА другого трека не трогаем — догоняется естественно.
                    var jumpedForSync = false
                    val playerTimeMs = Util.usToMs(loadingInfo.playbackPositionUs)
                    if (requestedTimeMs < playerTimeMs - 4000) {
                        val catchUpMs = maxOf(playerTimeMs, sabrClient.getMaxLastReturnedTimeMs()?.takeIf { it > playerTimeMs } ?: playerTimeMs)
                        requestedTimeMs = headTimeMs?.let { minOf(catchUpMs, it) } ?: catchUpMs
                        if (headSeq != null && headTimeMs != null) {
                            val offsetMs = (headTimeMs - requestedTimeMs).coerceAtLeast(0)
                            segmentNum = maxOf(0L, headSeq - offsetMs / maxOf(estDurationMs, 1))
                        }
                        jumpedForSync = true
                        android.util.Log.w("SabrChunkSource", "live catch-up: itag=$currentItag requested $requestedTimeMs was behind playback $playerTimeMs -> jump (segmentNum=$segmentNum)")
                    }
                    android.util.Log.i("SabrChunkSource", "live sequential: segmentNum=$segmentNum headSeq=$headSeq headTimeMs=$headTimeMs -> requestedTimeMs=$requestedTimeMs itag=${representationHolder.representation.streamInfo.itag} jumped=$jumpedForSync")
                }
                android.util.Log.i(
                    "SabrChunkSource",
                    "live segment request: itag=${representationHolder.representation.streamInfo.itag} index=$segmentNum, loadPositionMs=${Util.usToMs(loadPositionUs)}, requestedTimeMs=$requestedTimeMs, headSeq=$headSeq, headTimeMs=$headTimeMs, minSeekMs=$minSeekMs, downloaded=$downloaded"
                )
            }
            val endSegmentNumber = sabrClient.getEndSegmentNumber(
                representationHolder.representation.formatId()
            )
            if (!isLive && endSegmentNumber != null && endSegmentNumber > 0) {
                val lastAvailableSegmentNum = endSegmentNumber - 1
                if (segmentNum > lastAvailableSegmentNum
                    || (missingLastSegment && segmentNum >= lastAvailableSegmentNum)) {
                    // The segment is beyond the end of the stream (VOD).
                    out.endOfStream = true
                    return
                }
            }

            // Live: start должен соответствовать window-позиции запрошенного времени для initial/прыжка,
            // иначе readahead уходит в минус. Для sequential берём previousChunk.endTimeUs чтобы очередь
            // была континуальна, иначе аудио и видео разъедутся по startTime (gap) и loadPosition застрянет.
            // jumpedForSyncLocal — если загрузка отстала от плеера (loader lag), startTime берём из
            // windowPos(requestedTime), а не из времени предыдущего чанка.
            val jumpedForSyncLocal = if (isLive && previousChunk != null) {
                val playerMs = Util.usToMs(loadingInfo.playbackPositionUs)
                val currentItag = representationHolder.representation.streamInfo.itag
                val myNextMs = if ((previousChunk.dataSpec.customData as? PlaybackRequest)?.format?.itag == currentItag)
                    sabrClient.getLastReturnedEndTimeMs(currentItag) ?: Util.usToMs(previousChunk.endTimeUs)
                else Util.usToMs(previousChunk.endTimeUs)
                myNextMs < playerMs - 4000
            } else false
            val startTimeUs = if (isLive) {
                if (previousChunk != null && !jumpedForSyncLocal) previousChunk.endTimeUs
                else {
                    val windowStartMs = sabrClient.getLiveWindowStartMs() ?: (sabrClient.getMinSeekableTimeMs() ?: 0L)
                    val windowDurationMs = sabrClient.getLiveWindowDurationMs()
                    val rawWindowPosMs = requestedTimeMs - windowStartMs
                    val windowPosMs = if (windowDurationMs != null && windowDurationMs > 0) rawWindowPosMs.coerceIn(0L, windowDurationMs) else rawWindowPosMs.coerceAtLeast(0L)
                    Util.msToUs(windowPosMs)
                }
            } else previousChunk?.endTimeUs ?: loadPositionUs
            if (!isLive && startTimeUs >= representationHolder.periodDurationUs) {
                // The period duration clips the period to a position before the segment.
                out.endOfStream = true
                return
            }
            // For live streams the manifest duration is TIME_UNSET. A zero duration is
            // still valid for the first request; media3 will use the next request to
            // advance the live queue.
            val endTimeUs = if (representationHolder.lastSegmentDurationUs > 0) {
                startTimeUs + representationHolder.lastSegmentDurationUs
            } else {
                // Media3 requires a finite end time for ContainerMediaChunk. The
                // server-segment duration is learned from SabrDataSource after the
                // first response, so use one second only for the initial live chunk.
                startTimeUs + 1_000_000L
            }
            val seekTimeUs = if (queue.isEmpty()) loadPositionUs else C.TIME_UNSET

            // use the queue to build the buffered segments
            // each queue media chunk corresponds to 1 segment
            val bufferedSegments = queue.mapNotNull { (it.dataSpec.customData as PlaybackRequest?)?.segment }
            // Для live используем запрошенное время с учётом головы/DVR, иначе startTimeUs
            val effectiveTimeMs = if (isLive) requestedTimeMs else Util.usToMs(startTimeUs)
            val dataSpec = DataSpec.Builder()
                // must be non-null, but is unused
                .setUri(manifest.serverAbrStreamingUri)
                .setCustomData(PlaybackRequest(
                    representationHolder.representation.formatId(),
                    Util.usToMs(playbackPositionUs),
                    loadingInfo.playbackSpeed,
                    // SABR sequence numbers count the index segment as 0
                    segmentNum + 1,
                    effectiveTimeMs,
                    bufferedSegments,
                ))
                .build()

            android.util.Log.i(
                "SabrChunkSource",
                "requesting segment (pre-index): live=$isLive, index=$segmentNum, " +
                    "requestedSequence=${segmentNum + 1}, endSegmentNumber=$endSegmentNumber"
            )
            out.chunk = ContainerMediaChunk(
                dataSource,
                dataSpec,
                trackSelection.selectedFormat,
                trackSelection.selectionReason,
                trackSelection.selectionData,
                startTimeUs,
                endTimeUs,
                seekTimeUs,
                representationHolder.periodDurationUs,
                segmentNum,
                1,
                0,
                representationHolder.chunkExtractor
                    ?: throw IllegalStateException("SABR chunk extractor is unavailable")
            )
            return
        }

        if (representationHolder.segmentCount == 0L) {
            // The index doesn't define any segments.
            out.endOfStream = true
            return
        }

        val lastAvailableSegmentNum = representationHolder.getLastAvailableSegmentNum()
        val segmentNum = previousChunk?.nextChunkIndex ?: Util.constrainValue(
            representationHolder.getSegmentNum(loadPositionUs),
            0,
            lastAvailableSegmentNum
        )

        if (!isLive && (segmentNum > lastAvailableSegmentNum
            || (missingLastSegment && segmentNum >= lastAvailableSegmentNum))) {
            // The segment is beyond the end of the period.
            out.endOfStream = true
            return
        }

        if (!isLive && representationHolder.getSegmentStartTimeUs(segmentNum) >= representationHolder.periodDurationUs) {
            // The period duration clips the period to a position before the segment.
            out.endOfStream = true
            return
        }

        val seekTimeUs = if (queue.isEmpty()) loadPositionUs else C.TIME_UNSET
        val startTimeUs = representationHolder.getSegmentStartTimeUs(segmentNum)

        // use the queue to build the buffered segments
        // each queue media chunk corresponds to 1 segment
        val bufferedSegments = queue.mapNotNull { (it.dataSpec.customData as PlaybackRequest?)?.segment }
        val dataSpec = DataSpec.Builder()
            // must be non-null, but is unused
            .setUri(manifest.serverAbrStreamingUri)
            .setCustomData(PlaybackRequest(
                representationHolder.representation.formatId(),
                Util.usToMs(playbackPositionUs),
                loadingInfo.playbackSpeed,
                // the chunk index doesn't count the index segment as segment 0
                segmentNum + 1,
                Util.usToMs(startTimeUs),
                bufferedSegments,
            ))
            .build()

        android.util.Log.i(
            "SabrChunkSource",
            "requesting segment: index=$segmentNum, sabrSegment=${segmentNum + 1}, " +
                "lastAvailable=$lastAvailableSegmentNum"
        )
        out.chunk = ContainerMediaChunk(
            dataSource,
            dataSpec,
            trackSelection.selectedFormat,
            trackSelection.selectionReason,
            trackSelection.selectionData,
            startTimeUs,
            representationHolder.getSegmentEndTimeUs(segmentNum),
            seekTimeUs,
            representationHolder.periodDurationUs,
            segmentNum,
            1,
            0,
            representationHolder.chunkExtractor
                ?: throw IllegalStateException("SABR chunk extractor is unavailable")
        )
    }

    private fun logReadaheadPolicy(bufferedDurationUs: Long) {
        val targetMs = sabrClient.getTargetReadaheadMs(
            representationHolders[trackSelection.selectedIndex].representation
        )
        val minMs = sabrClient.getMinReadaheadMs(
            representationHolders[trackSelection.selectedIndex].representation
        )
        if (targetMs != null || minMs != null) {
            android.util.Log.i(
                "SabrChunkSource",
                "readahead: currentMs=${Util.usToMs(bufferedDurationUs)}, minMs=$minMs, targetMs=$targetMs"
            )
        }
    }

    override fun onChunkLoadCompleted(chunk: Chunk) {
        val trackIndex = trackSelection.indexOf(chunk.trackFormat)
        if (trackIndex == C.INDEX_UNSET || trackIndex !in representationHolders.indices) {
            return
        }
        val representationHolder = representationHolders[trackIndex]
        if (chunk is InitializationChunk) {
            representationHolder.initializationRequested = true
        }
        // The extractor builds the index from media fragments, so it may only become
        // available after a media chunk (not the init chunk) has been parsed.
        representationHolder.chunkExtractor?.chunkIndex?.let { chunkIndex ->
            if (representationHolder.chunkIndex !== chunkIndex) {
                representationHolder.chunkIndex = chunkIndex
                android.util.Log.i(
                    "SabrChunkSource",
                    "index available: track=$trackIndex, segments=${chunkIndex.length}"
                )
            }
        }
        // Keep the segment duration accumulator up to date so media chunks can be
        // scheduled with correct end times while no container index is available.
        // NOTE: Chunk.dataSource is protected in media3 1.4.1 — all chunks here are
        // created by this source against `this.dataSource`, so check that instead.
        if (chunk is MediaChunk && dataSource is SabrDataSource) {
            val durationUs = (dataSource as SabrDataSource).lastSegmentDurationUs
            if (durationUs > 0) {
                representationHolder.lastSegmentDurationUs = durationUs
            }
        }
    }

    override fun onChunkLoadError(
        chunk: Chunk,
        cancelable: Boolean,
        loadErrorInfo: LoadErrorInfo,
        loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
    ): Boolean {
        if (!cancelable) {
            return false
        }
        // Workaround for missing segment at the end of the period
        if (chunk is MediaChunk
            && loadErrorInfo.exception is InvalidResponseCodeException
            && (loadErrorInfo.exception as InvalidResponseCodeException).responseCode == 404
        ) {
            val representationHolder =
                representationHolders[trackSelection.indexOf(chunk.trackFormat)]
            val segmentCount = representationHolder.segmentCount
            if (segmentCount != 0L) {
                val lastAvailableSegmentNum = segmentCount - 1
                // Media3 chunk indices are zero-based, while SABR requests use segmentNum + 1.
                // A 404 for the first unavailable segment means the period has ended.
                if (chunk.nextChunkIndex >= lastAvailableSegmentNum) {
                    missingLastSegment = true
                    return true
                }
            }
        }

        val fallbackOptions = createFallbackOptions(trackSelection)
        if (!fallbackOptions.isFallbackAvailable(LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK)
            && !fallbackOptions.isFallbackAvailable(LoadErrorHandlingPolicy.FALLBACK_TYPE_LOCATION)
        ) {
            return false
        }
        val fallbackSelection =
            loadErrorHandlingPolicy.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
        if (fallbackSelection == null || !fallbackOptions.isFallbackAvailable(fallbackSelection.type)) {
            // Policy indicated to not use any fallback or a fallback type that is not available.
            return false
        }

        var cancelLoad = false
        if (fallbackSelection.type == LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK) {
            cancelLoad =
                trackSelection.excludeTrack(
                    trackSelection.indexOf(chunk.trackFormat), fallbackSelection.exclusionDurationMs
                )
        }
        return cancelLoad
    }

    override fun release() {
        for (representationHolder in representationHolders) {
            representationHolder.chunkExtractor?.release()
        }
    }

    private fun createFallbackOptions(trackSelection: ExoTrackSelection): FallbackOptions {
        val nowMs = SystemClock.elapsedRealtime()
        val numberOfTracks = trackSelection.length()
        var numberOfExcludedTracks = 0
        for (i in 0 until numberOfTracks) {
            if (trackSelection.isTrackExcluded(i, nowMs)) {
                numberOfExcludedTracks++
            }
        }
        return FallbackOptions(
            0,
            0,
            numberOfTracks,
            numberOfExcludedTracks
        )
    }

    /** [MediaChunkIterator] wrapping a [RepresentationHolder].  */
    class RepresentationSegmentIterator(
        private val representationHolder: RepresentationHolder,
        firstAvailableSegmentNum: Long,
        lastAvailableSegmentNum: Long,
    ) : BaseMediaChunkIterator(firstAvailableSegmentNum, lastAvailableSegmentNum) {
        override fun getDataSpec(): DataSpec {
            checkInBounds()
            return DataSpec.Builder()
                // must be non-null, but is unused
                .setUri("sabr://unused")
                .build()
        }

        override fun getChunkStartTimeUs(): Long {
            checkInBounds()
            return representationHolder.getSegmentStartTimeUs(currentIndex)
        }

        override fun getChunkEndTimeUs(): Long {
            checkInBounds()
            return representationHolder.getSegmentEndTimeUs(currentIndex)
        }
    }

    /** Holds information about a snapshot of a single [Representation].  */
    data class RepresentationHolder(
        val periodDurationUs: Long,
        val representation: Representation,
        val chunkExtractor: ChunkExtractor?,
    ) {
        var chunkIndex: ChunkIndex? = null
        var initializationRequested = false
        var lastSegmentDurationUs = 0L

        val segmentCount: Long
            get() = chunkIndex?.length?.toLong() ?: 0

        fun getSegmentStartTimeUs(segmentNum: Long): Long =
            chunkIndex?.timesUs?.getOrNull(segmentNum.toInt())
                ?: throw IllegalStateException("SABR segment index is unavailable: $segmentNum")

        fun getSegmentEndTimeUs(segmentNum: Long): Long {
            val index = chunkIndex
                ?: throw IllegalStateException("SABR segment index is unavailable: $segmentNum")
            val durationUs = index.durationsUs.getOrNull(segmentNum.toInt())
                ?: throw IllegalStateException("SABR segment duration is unavailable: $segmentNum")
            return getSegmentStartTimeUs(segmentNum) + durationUs
        }

        fun getSegmentNum(positionUs: Long): Long =
            (chunkIndex ?: throw IllegalStateException("SABR segment index is unavailable"))
                .getChunkIndex(positionUs).toLong()

        fun getLastAvailableSegmentNum(): Long =
            (chunkIndex ?: throw IllegalStateException("SABR segment index is unavailable"))
                .length.toLong() - 1
    }
}
