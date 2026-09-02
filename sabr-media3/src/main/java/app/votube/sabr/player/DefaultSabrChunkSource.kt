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

    // v8: гейт по остатку буфера (предложение пользователя: <18с — «тянуть сразу»,
    // >28с — пауза деклараций). <18с — естественное состояние непрерывной загрузки
    // (следующий запрос уходит сразу после завершения предыдущего), отдельный механизм
    // не нужен. Пауза при >28с закрывает последний источник роста буфера: после v7
    // серверный target readahead (28-37с) игнорируется, но собственного потолка у
    // планировщика не было. Догон v6 (1.2x) сжигает излишек: при offset ~28с speed
    // упирается в 1.2x, остаток тает ~0.2с/с, и к таргету 15с буфер возвращается в
    // зону <18с (непрерывная загрузка). Фактический потолок = 28с + один шаг сегмента
    // (аудио ~10с) — гранулярность декларации, дальше не растёт.
    private val bufferAheadPauseMs = 28_000L
    private var bufferGatePaused = false

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
        if (trackSelection == null) throw IllegalArgumentException("SABR track selection must not be null")
        // Новый объект селекции = новая политика приложения (качество/параметры). Стартовое окно
        // гистерезиса считаем заново — иначе сразу после re-select подавление уже «прогорело».
        if (this.trackSelection !== trackSelection) lastTrackSwitchMs = 0L
        this.trackSelection = trackSelection
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

        // v8: пауза деклараций по остатку буфера. Гейт переоценивается на каждом
        // getNextChunk, а media3 при простаивающем лоадере сам пере-поллит его раз в
        // секунду (READY_MAXIMUM_INTERVAL_MS=1000) — таймер не нужен, состояние живёт
        // на уровне буфера. Остаток = loadPositionUs − playback, т.е. ДЕКЛАРИРУЕМЫЙ
        // конец очереди (включая чанки в полёте) — не даём передекларировать.
        // Init-запросы и очередь после seek не блокируются: там буфер ~0, гейт открыт.
        // VOD и загрузка файлов (isLive=false) не затронуты.
        // Голова эфира при паузе продолжает быть точной без сетевого опроса:
        // LiveMetadata приходит push с каждым ответом (при активной загрузке — каждые
        // 2-5с = фактическая свежесть «опроса 2-3с»), а между ответами offset
        // самостабилен (windowStartTime-якорь v6: эфир идёт 1:1 с настенными часами).
        if (isLive && previousChunk != null
            && bufferedDurationUs > Util.msToUs(bufferAheadPauseMs)) {
            if (!bufferGatePaused) {
                bufferGatePaused = true
                android.util.Log.i(
                    "SabrChunkSource",
                    "buffer gate: PAUSE declarations, itag=${trackSelection.selectedFormat.id}, " +
                        "aheadMs=${Util.usToMs(bufferedDurationUs)} > ${bufferAheadPauseMs}ms"
                )
            }
            return
        }
        if (bufferGatePaused) {
            bufferGatePaused = false
            android.util.Log.i(
                "SabrChunkSource",
                "buffer gate: RESUME declarations, itag=${trackSelection.selectedFormat.id}, " +
                    "aheadMs=${Util.usToMs(bufferedDurationUs)}"
            )
        }

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
        // Hysteresis for live ABR: suppress downgrade spirals and the start-at-144p slide.
        // Лог 11:35:59→11:36:29: старт на 399 (1080p AV1), за 30с сползание 398→394 (144p).
        // Причины: (1) метр пропускной способности видел SABR-раундтрип как «медленную передачу»
        // (исправлено в SabrDataSource — атрибуция реальных байтов ответа); (2) стартовое окно
        // гистерезиса не работало: queue==0 && buffered==0 => suppress=false. Теперь старт
        // (lastTrackSwitchMs==0) считается свежим переключением, подавление действует пока есть
        // хоть что-то в буфере/очереди; даунгрейд определяем и по битрейту, и по высоте (битрейт
        // в манифесте бывает неизвестен). Полная пустота буфера снимает подавление — выживание.
        val nowMs = SystemClock.elapsedRealtime()
        val timeSinceSwitchMs = nowMs - lastTrackSwitchMs
        val bufferedMs = Util.usToMs(bufferedDurationUs)
        val isRecentlySwitched = lastTrackSwitchMs == 0L || timeSinceSwitchMs < 10_000
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
        val afterFormat = trackSelection.getFormat(afterIndex)
        val bitrateDowngrade = beforeFormat.bitrate > 0 && afterFormat.bitrate > 0 &&
            afterFormat.bitrate < beforeFormat.bitrate
        val heightDowngrade = beforeFormat.height > 0 && afterFormat.height > 0 &&
            afterFormat.height < beforeFormat.height
        val isDowngrade = afterIndex != beforeIndex && (bitrateDowngrade || heightDowngrade)
        val hasBufferOrQueue = bufferedMs > 500 || queue.isNotEmpty()
        val shouldSuppressDowngrade = isLive && isDowngrade && isRecentlySwitched && hasBufferOrQueue
        if (shouldSuppressDowngrade) {
            android.util.Log.i(
                "SabrChunkSource",
                "ABR hysteresis: suppress downgrade $beforeIndex -> $afterIndex " +
                    "(recent ${timeSinceSwitchMs}ms ago, buf=${bufferedMs}ms, queue=${queue.size})"
            )
            // Для текущего чанка используем старый holder (высокое качество), селектор остаётся на низком:
            // следующий getNextChunk снова предложит даунгрейд и снова подавит — окно 10с от последнего свитча.
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
            // Поднят на уровень if/else: выставляется в sequential-ветке, читается в jumpedForSyncLocal ниже.
            var jumpedForSync = false
            if (isLive) {
                val downloaded = sabrClient.getDownloadedSegmentsDebug(representationHolder.representation.streamInfo.itag)
                val headSeq = sabrClient.getLiveHeadSequenceNumber()
                val headTimeMs = sabrClient.getLiveHeadTimeMs()
                val minSeekMs = sabrClient.getMinSeekableTimeMs()
                // ДОМЕН ВРЕМЕНИ: сэмплы fMP4 несут АБСОЛЮТНОЕ медиа-время эфира (tfdt ≈ 1.13e10 мс),
                // поэтому период/очередь/загрузка живут в абсолютном домене us. Таймлайн-окно
                // смещено на windowStart (positionInFirstPeriodUs) — см. SabrMediaSource.
                // loadPosition может быть ещё в относительном домене (0..windowDuration) до первой
                // пересборки окна — нормализуем: относительное всегда << windowStart.
                val windowStartMs0 = sabrClient.getLiveWindowStartMs()
                val loadMsRaw = Util.usToMs(loadPositionUs)
                val loadAbsMs = if (windowStartMs0 != null && loadMsRaw < windowStartMs0) windowStartMs0 + loadMsRaw else loadMsRaw
                val loadRelMs = windowStartMs0?.let { loadAbsMs - it } ?: loadMsRaw
                // Seek/DVR remap НАЗАД: позиция очереди ушла от якоря больше чем на 5с — якорь
                // описывает уже не ту точку эфира, последовательные запросы продолжат из прошлого
                // (лог 13:31:41: якорь в районе seq=721 при очереди на 728 → повторная отдача
                // старых сегментов = микрофриз/луп). Сбрасываем ДО выбора ветки (покрывает и
                // initial после seek, и sequential) — планировщик продолжит от реальной позиции.
                representationHolder.representation.streamInfo.itag.let { currentItag ->
                    sabrClient.getLastReturnedTimeMs(currentItag)?.let { anchorMs ->
                        if (loadAbsMs < anchorMs - 5_000) {
                            sabrClient.resetTimeAnchors(currentItag)
                            android.util.Log.w("SabrChunkSource", "live re-anchor: itag=$currentItag queue $loadAbsMs is ${anchorMs - loadAbsMs}ms behind served anchor $anchorMs -> reset time anchors")
                        }
                    }
                }
                // Слушаем голову эфира: для live edge берём headSeq, для DVR rewind — проверяем пройденное время
                // SABR_SEEK обрабатываем только на seek/initial (когда queue пуст), иначе последовательные сегменты идут по порядку
                if (previousChunk == null) {
                    // Без LiveMetadata сервер отдаст «слепую» позицию 0 — ждём метаданные
                    // (приходят с ответом на init-запрос вместе с префетчем первых сегментов).
                    if (headSeq == null) {
                        android.util.Log.i("SabrChunkSource", "live pre-metadata: itag=${representationHolder.representation.streamInfo.itag} — wait for LiveMetadata before media request")
                        return
                    }
                    val rawSeek = sabrClient.peekServerSeekMs()
                    val targetMs = loadRelMs
                    val headIsBig = (headTimeMs ?: 0L) > 60_000
                    // Нулевой SABR_SEEK в середине большого live — мусор (init-позиция сервера), а не реальная
                    // команда перемотки. Иначе он перебивает реальную позицию (запуск/переключение трека/реконнект)
                    // и мы тянем середину или начало вместо нужного места. Ненулевой seek — реальная команда.
                    val serverSeekMs = rawSeek?.takeIf { it != 0L || !headIsBig }
                    // initial edge — любой первый запрос трека (previousChunk==null) с маленьким target и большой головой
                    // должен идти к head-15с. Глобальный hasStartedLive ломал второй трек (аудио/видео): первый трек уже выставлял
                    // hasStartedLive=true, второй с previousChunk==null попадал в DVR mapping 0 → segment 0/7417 вместо головы.
                    val isInitialEdge = headSeq != null && headSeq > 100 && loadRelMs < 1000 && headIsBig
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
                        // DVR/continuation: loadPosition УЖЕ в абсолютном домене периода (сэмплы fMP4
                        // абсолютны, окно смещено на windowStart) — просто клампим в окно.
                        // Нормализация выше переводит ранний относительный период в абсолютный,
                        // поэтому windowStart+target НЕ нужен (был бы двойной сдвиг).
                        // A/V-подгонку по sequence УБРАЛИ: серии sequence у аудио/видео разные (в логе
                        // одно и то же время = video 4567, audio 4570), прыжок к «чужому» номеру давал
                        // requestedTime на 60с в прошлом (4511166 вместо 4571166) и вечный fallback.
                        if (headSeq != null && headTimeMs != null) {
                            val windowStartMs = windowStartMs0 ?: (minSeekMs ?: 0L)
                            // Проверяем что абсолютное время внутри окна
                            val clampedAbsolute = loadAbsMs.coerceIn(windowStartMs, headTimeMs)
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
                    // Декларируемый конец очереди (loadPosition живёт по нему). Нужен ВМЕСТЕ с якорем:
                    // после flush/reposition очередь может быть перестроена на новую позицию плеера,
                    // а якорь lastReturned* остаётся в старом месте (лог 12:36:44→59: loadPosition
                    // 15734600 против якоря 15725600 → запросы тянут уже проигранные seq 7863-7868
                    // = зацикливание). Оба времени используем в catch-up ниже.
                    val declaredEndMs = Util.usToMs(previousChunk.endTimeUs)
                    requestedTimeMs = when {
                        sameFormatContinuing && sabrClient.getLastReturnedEndTimeMs(currentItag) != null ->
                            sabrClient.getLastReturnedEndTimeMs(currentItag)!!
                        sameFormatContinuing && sabrClient.getLastReturnedTimeMs(currentItag) != null ->
                            sabrClient.getLastReturnedTimeMs(currentItag)!! + estDurationMs
                        else -> {
                            // Шов после ABR-переключения/реконнекта: продолжаем от времени очереди
                            // (абсолютный домен) — сервер отдаст сегмент нового формата, покрывающий это время
                            declaredEndMs
                        }
                    }
                    if (headTimeMs != null && requestedTimeMs > headTimeMs) requestedTimeMs = headTimeMs
                    // Аварийный catch-up: ТОЛЬКО реальное отставание от ПЛЕЕРА — по ЯКОРЮ
                    // (requestedTime) или по ДЕКЛАРИРУЕМОМУ концу очереди. Тянуть прошлое при
                    // живом плеере = буфер-минус → фриз и луп.
                    // v7: критерий «requested позади конца очереди на 6с» (behindQueue) УДАЛЁН.
                    // Очередь, убежавшая вперёд якоря — это НОРМАЛЬНЫЙ конвейер префетча: пока
                    // спекулятивный чанк грузится (long-poll сервера), его декларируемое окно
                    // заведомо впереди якоря на 1-2 шага сегмента (для аудио с шагом ~10с это
                    // стабильно > 6с). Лог 15:37:55-15:38:43: за 48с — 14 «catch-up -> jump» на
                    // ЗДОРОВОМ префетче, каждый jump переалиасил окно назад, media3 выкидывал
                    // уже декларированные чанки (discardUpstreamMediaChunksFromIndex) — пила
                    // буфера 29с→19с, дубли/повторы сэмплов и видимые перескоки позиции.
                    // Расхождение якоря и очереди теперь не накапливается само: декларация
                    // стартует от РЕАЛЬНОГО старта сегмента (peek префетча) или от конца якоря
                    // (см. startTime ниже), поэтому зазор ограничен 1-2 шагами и не триггерит.
                    // Реальные затыки ловит behindPlayback (якорь/конец позади плеера на 4с+),
                    // рассинхрон окон после flush/seek — jumpedForSyncLocal ниже, seek назад —
                    // live re-anchor выше.
                    val playerTimeMs = Util.usToMs(loadingInfo.playbackPositionUs)
                    val behindPlayback = requestedTimeMs < playerTimeMs - 4000 || declaredEndMs < playerTimeMs - 4000
                    if (behindPlayback) {
                        val refTimeMs = maxOf(playerTimeMs, loadAbsMs)
                        val catchUpMs = maxOf(refTimeMs, sabrClient.getMaxLastReturnedTimeMs()?.takeIf { it > refTimeMs } ?: refTimeMs)
                        requestedTimeMs = headTimeMs?.let { minOf(catchUpMs, it) } ?: catchUpMs
                        if (headSeq != null && headTimeMs != null) {
                            val offsetMs = (headTimeMs - requestedTimeMs).coerceAtLeast(0)
                            segmentNum = maxOf(0L, headSeq - offsetMs / maxOf(estDurationMs, 1))
                        }
                        jumpedForSync = true
                        android.util.Log.w("SabrChunkSource", "live catch-up: itag=$currentItag requested $requestedTimeMs was behind playback $playerTimeMs/queue $loadAbsMs (declaredEnd=$declaredEndMs) -> jump (segmentNum=$segmentNum)")
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
                val declaredEndMsLocal = Util.usToMs(previousChunk.endTimeUs)
                val currentItag = representationHolder.representation.streamInfo.itag
                val myNextMs = if ((previousChunk.dataSpec.customData as? PlaybackRequest)?.format?.itag == currentItag)
                    sabrClient.getLastReturnedEndTimeMs(currentItag) ?: declaredEndMsLocal
                else declaredEndMsLocal
                // Ре-алиасим декларацию к реально отданному старту (peekServedStartMs), если:
                // (a) был catch-up прыжок якоря, (б) якорь/очередь позади плеера (flush/seek/шов).
                // Иначе декларируем диапазон из старого места — очередь врёт плееру, повторная
                // отдача уже проигранных сегментов = зацикливание (лог 12:36:44→59).
                jumpedForSync || myNextMs < playerMs - 4000 || declaredEndMsLocal < playerMs - 4000
            } else false
            val startTimeUs = if (isLive) {
                // Живём в АБСОЛЮТНОМ домене (сэмплы fMP4 = медиа-время эфира): начало чанка =
                // реально отданный startMs (peek — чтобы декларируемое время очереди совпало с
                // сэмплами, иначе buffered/readahead уплывает на величину клампа сервера),
                // для initial/прыжка — запрошенное время.
                // v7: для последовательной декларации НЕ берём больше previousChunk.endTimeUs —
                // это ДЕКЛАРИРУЕМЫЙ конец предыдущего чанка, а при спекулятивной декларации
                // (сегмент ещё не префетчен) он фиктивен. Каждый такой чанк двигал окно
                // очереди на шаг вперёд РЕАЛЬНОГО контента (лог 15:37:53-55: три декларации
                // за 1.1с из кэша унесли окно на 2151100 при контенте 2140200) — расхождение
                // окно/сэмплы давало повторы и перескоки позиции. Вместо этого:
                // 1) РЕАЛЬНЫЙ старт сегмента этого чанка из префетча (peekSegmentStartMs —
                //    только точный seq, без тайм-матча: forward-матч мог бы дать окно
                //    следующего сегмента при запросе текущего);
                // 2) иначе — конец якоря (requestedTimeMs): лучший прогноз старта следующего
                //    сегмента, ограничен одним шагом от уже отданного контента;
                // 3) пол по старту якоря: повторная отдача старого seq не откатывает окно.
                if (previousChunk != null && !jumpedForSyncLocal) {
                    val seqItag = representationHolder.representation.streamInfo.itag
                    val anchorStartMs = sabrClient.getLastReturnedTimeMs(seqItag)
                    val realStartMs = sabrClient.peekSegmentStartMs(seqItag, segmentNum + 1)
                        ?.let { if (anchorStartMs != null) maxOf(it, anchorStartMs) else it }
                        ?: requestedTimeMs
                    Util.msToUs(realStartMs)
                } else {
                    val declaredStartMs = sabrClient.peekServedStartMs(
                        representationHolder.representation.streamInfo.itag,
                        segmentNum + 1,
                        requestedTimeMs,
                    ) ?: requestedTimeMs
                    Util.msToUs(declaredStartMs)
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
            // Конец чанка = РЕАЛЬНОЕ время старта следующего сегмента (если уже лежит в
            // префетче): декларируемая durationMs у части эфиров расходится с реальным ритмом
            // (лог 13:32-13:34: 3-7с декларируемых при реальном шаге 2с) → очередь убегала от
            // сэмплов fMP4 → плеер считал буфер «виртуальным», семплы кончались → микрофриз.
            val nextSegmentStartUs = if (isLive) {
                sabrClient.peekSegmentStartMs(
                    representationHolder.representation.streamInfo.itag,
                    segmentNum + 2, // текущий чанк = сегмент segmentNum+1, следующий = +2
                )?.takeIf { it > Util.usToMs(startTimeUs) }?.let { Util.msToUs(it) }
            } else null
            val endTimeUs = when {
                nextSegmentStartUs != null -> nextSegmentStartUs
                representationHolder.lastSegmentDurationUs > 0 -> startTimeUs + representationHolder.lastSegmentDurationUs
                else ->
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
            // Приоритет — наблюдаемый реальный шаг стартов эфира; декларируемая durationMs
            // (protobuf, приходит через SabrDataSource) у части эфиров расходится с реальным
            // ритмом (лог 13:32-13:34: 3-7с против реальных 2с) → раздутая очередь → микрофризы.
            val reqItag = (chunk.dataSpec.customData as? PlaybackRequest)?.format?.itag
            val realStepUs = reqItag?.let { sabrClient.getLastRealStepMs(it) }
                ?.takeIf { it > 0 }?.let { Util.msToUs(it) }
            val durationUs = realStepUs ?: (dataSource as SabrDataSource).lastSegmentDurationUs
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
