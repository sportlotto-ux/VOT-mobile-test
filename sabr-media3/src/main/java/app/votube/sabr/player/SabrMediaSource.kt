package app.votube.sabr.player

import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.LocalConfiguration
import androidx.media3.common.Timeline
import androidx.media3.common.util.Assertions
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.BaseMediaSource
import androidx.media3.exoplayer.source.CompositeSequenceableLoaderFactory
import androidx.media3.exoplayer.source.DefaultCompositeSequenceableLoaderFactory
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.CmcdConfiguration
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import app.votube.sabr.manifest.SabrManifest
import app.votube.sabr.parser.PoTokenProvider
import app.votube.sabr.parser.SabrClient

/** A Sabr [MediaSource].  */
@UnstableApi
class SabrMediaSource(
    private var mediaItem: MediaItem,
    private val manifest: SabrManifest,
    private val sabrClient: SabrClient,
    private val chunkSourceFactory: SabrChunkSource.Factory,
    private val compositeSequenceableLoaderFactory: CompositeSequenceableLoaderFactory,
    private val cmcdConfiguration: CmcdConfiguration?,
    private val drmSessionManager: DrmSessionManager,
    private val loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
) : BaseMediaSource() {

    /** Factory for [SabrMediaSource]s.  */
    class Factory(
        private val context: Context,
        private val manifest: SabrManifest,
        private val poTokenProvider: PoTokenProvider? = null,
    ) : MediaSource.Factory {
        private var cmcdConfigurationFactory: CmcdConfiguration.Factory? = null
        private var drmSessionManagerProvider: DrmSessionManagerProvider = DefaultDrmSessionManagerProvider()
        private val compositeSequenceableLoaderFactory = DefaultCompositeSequenceableLoaderFactory()
        private var loadErrorHandlingPolicy: LoadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy()

        override fun setCmcdConfigurationFactory(cmcdConfigurationFactory: CmcdConfiguration.Factory): Factory =
            this.apply {
                this.cmcdConfigurationFactory =
                    Assertions.checkNotNull<CmcdConfiguration.Factory?>(cmcdConfigurationFactory)
            }

        override fun setDrmSessionManagerProvider(
            drmSessionManagerProvider: DrmSessionManagerProvider,
        ): Factory = this.apply { this.drmSessionManagerProvider = drmSessionManagerProvider }

        override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): Factory =
            this.apply { this.loadErrorHandlingPolicy = loadErrorHandlingPolicy }

        override fun createMediaSource(mediaItem: MediaItem): SabrMediaSource {
            Assertions.checkNotNull<LocalConfiguration>(mediaItem.localConfiguration)
            val cmcdConfiguration = cmcdConfigurationFactory?.createCmcdConfiguration(mediaItem)
            val sabrClient = SabrClient(context, manifest, poTokenProvider)
            val source = SabrMediaSource(
                mediaItem,
                manifest,
                sabrClient,
                DefaultSabrChunkSource.Factory(SabrDataSource.Factory(sabrClient)),
                compositeSequenceableLoaderFactory,
                cmcdConfiguration,
                drmSessionManagerProvider.get(mediaItem),
                loadErrorHandlingPolicy
            )
            // Слушаем голову эфира: как только сервер скажет где голова и окно DVR — обновляем таймлайн
            // чтобы плеер мог стартовать с live edge и перематывать назад к началу
            sabrClient.liveMetadataListener = { meta -> source.onLiveMetadata(meta) }
            // v28: сбрасываем монитор факта качества — старые served-значения не должны
            // переживать смену сорса (читатель в UI доверяет только свежим <30с).
            SabrQualityMonitor.reset(manifest.videoId)
            return source
        }

        override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER)
    }

    private var mediaTransferListener: TransferListener? = null

    private var elapsedRealtimeOffsetMs: Long = C.TIME_UNSET
    // Live DVR: окно от minSeek до headTime, чтобы можно было вернуться к началу
    private var liveWindowDurationUs: Long = C.TIME_UNSET
    private var liveDefaultPositionUs: Long = 0L

    /* Почему это критично: в media3 1.4.1 Window.isLive() = (liveConfiguration != null).
       Окно без liveConfiguration для плеера НЕ live: не работает
       DefaultLivePlaybackSpeedControl (плавный разгон к живому краю) и
       getCurrentLiveOffset(). Итог — после любого микрофриза/зависания загрузки
       (лог 14:48:28→14:49:00: 32с молчания UMP-чтения) позиция плеера навсегда
       оставалась на 31-32с позади головы эфира — ничто её не подтягивало.
        Теперь: targetOffset 15с (как в браузере), после ребуфера цель мягко
        поднимается до maxOffset 60с: наш темп выборки SABR ≈ темпу потребления
        (roundtrip 1.7–2.5с за 2с медиа, запаса почти нет), и кап 20с гарантировал
        seek-шторм — лог 08:40: лаг >20с → seek к defaultPos (head−15с) → убитые
        in-flight (InterruptedException) → выброшенное скачанное → перезапрос →
        снова лаг. С капом 60с transient-лаги съедает разгон до 1.2x
        (~30с за ~2.5 мин, звук тянется Sonic без изменения тона), а seek к краю
        остаётся только для настоящих stalls. Плеер сам не трогает скорость,
        если пользователь вручную выставил не-1x (условие playbackParameters.speed==1f). */
    private val liveConfiguration: MediaItem.LiveConfiguration =
        MediaItem.LiveConfiguration.Builder()
            .setTargetOffsetMs(15000)
            .setMinOffsetMs(12000)
            .setMaxOffsetMs(60000)
            .setMinPlaybackSpeed(1.0f)
            .setMaxPlaybackSpeed(1.2f)
            .build()

    @Synchronized
    override fun getMediaItem(): MediaItem {
        return mediaItem
    }

    override fun canUpdateMediaItem(mediaItem: MediaItem): Boolean {
        val existingConfiguration =
            Assertions.checkNotNull<LocalConfiguration>(this.mediaItem.localConfiguration)
        val newConfiguration = mediaItem.localConfiguration
        return newConfiguration != null && newConfiguration.uri == existingConfiguration.uri
                && newConfiguration.streamKeys == existingConfiguration.streamKeys
                && newConfiguration.drmConfiguration == existingConfiguration.drmConfiguration
                && mediaItem.liveConfiguration == this.mediaItem.liveConfiguration
    }

    @Synchronized
    override fun updateMediaItem(mediaItem: MediaItem) {
        this.mediaItem = mediaItem
    }

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        this.mediaTransferListener = mediaTransferListener
        // Каждый (ре)prepare = новый старт: если плеер начал с позиции 0 — стартуем с головы,
        // а не с середины из-за stale serverSeek=0 (pXBfmgk9lSU)
        sabrClient.hasStartedLive = false
        drmSessionManager.setPlayer(
            checkNotNull(Looper.myLooper()) { "SABR source must be prepared on a looper thread" },
            playerId
        )
        drmSessionManager.prepare()
        processManifest()
    }

    override fun maybeThrowSourceInfoRefreshError() {
        // SABR currently exposes a static timeline. Network errors are surfaced by its chunk sources.
    }

    override fun createPeriod(
        id: MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long,
    ): MediaPeriod {
        val periodIndex = id.periodUid as Int
        val periodEventDispatcher = createEventDispatcher(id)
        val drmEventDispatcher = createDrmEventDispatcher(id)
        return SabrMediaPeriod(
            manifest,
            sabrClient,
            periodIndex,
            chunkSourceFactory,
            mediaTransferListener,
            cmcdConfiguration,
            drmSessionManager,
            drmEventDispatcher,
            loadErrorHandlingPolicy,
            periodEventDispatcher,
            elapsedRealtimeOffsetMs,
            allocator,
            compositeSequenceableLoaderFactory,
            playerId
        )
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        (mediaPeriod as SabrMediaPeriod).release()
    }

    override fun releaseSourceInternal() {
        elapsedRealtimeOffsetMs = C.TIME_UNSET
        drmSessionManager.release()
    }

    private fun processManifest() {
        android.util.Log.i(
            "SabrMediaSource",
            "Preparing SABR source: videoId=${manifest.videoId}, " +
                "adaptationSets=${manifest.adaptationSets.size}, durationMs=${manifest.durationMs}"
        )
        // Для live durationMs == TIME_UNSET — делаем окно динамическим, потом обновим по LiveMetadata
        val isLive = manifest.durationMs == C.TIME_UNSET
        val windowDuration = if (isLive && liveWindowDurationUs != C.TIME_UNSET) liveWindowDurationUs else Util.msToUs(manifest.durationMs)
        val defaultPos = if (isLive && liveDefaultPositionUs != 0L) liveDefaultPositionUs else 0L
        // ДОМЕН ВРЕМЕНИ: сэмплы fMP4 несут абсолютное медиа-время эфира, поэтому период живёт в
        // абсолютном домене us, а ОКНО смещено на windowStart (positionInFirstPeriodUs).
        // Тогда позиция плеера (периодная = абсолютная) − windowStart = позиция в окне 0..windowDuration.
        val windowStartUs = if (isLive) Util.msToUs(sabrClient.getLiveWindowStartMs() ?: 0L) else 0L
        /* windowStartTimeMs = UNIX-время, соответствующее НАЧАЛУ окна (край окна = голова эфира,
           поэтому начало окна = сейчас − окно). Без него ExoPlayerImplInternal.getLiveOffsetUs
           возвращает TIME_UNSET и speed control не включается:
           offset = (currentUnixTime − windowStartTimeMs) − (позиция − windowStart).
           Между refresh'ами формула самостабильна: offset = (now−t0) + head@t0 − position,
           а каждый refresh по свежему headTimeMs пересчитывает якорь заново. */
        val windowStartTimeMs =
            if (isLive && windowDuration != C.TIME_UNSET && windowDuration > 0)
                System.currentTimeMillis() - Util.usToMs(windowDuration)
            else C.TIME_UNSET
        val timeline =
            SabrTimeline(
                C.TIME_UNSET,
                windowStartTimeMs,
                elapsedRealtimeOffsetMs,
                windowStartUs,
                windowDuration,
                defaultPos,
                liveConfiguration,
                manifest,
                mediaItem,
            )
        refreshSourceInfo(timeline)
    }

    internal fun onLiveMetadata(meta: video_streaming.LiveMetadataOuterClass.LiveMetadata) {
        // Вычисляем DVR окно: от ЗАФИКСИРОВАННОГО windowStart до headTime.
        // windowStart фиксируется SabrClient на первом LiveMetadata — домен времени чанков
        // (windowPos = time − windowStart) совпадает с доменом таймлайна и не плывёт вместе
        // со скользящим minSeekable (иначе позиции в периоде прыгают → фризы/скачки).
        // Окно РАСТЁТ от фиксированного старта — это нормально: period UID стабилен,
        // ExoPlayer сохраняет позицию при refreshSourceInfo, загрузка продолжается от очереди.
        val headTimeMs = if (meta.hasHeadTimeMs()) meta.headTimeMs else 0L
        val minSeekMs = if (meta.hasMinSeekableTimeTicks() && meta.hasMinSeekableTimescale() && meta.minSeekableTimescale != 0)
            meta.minSeekableTimeTicks * 1000L / meta.minSeekableTimescale else 0L
        val windowStartMs = sabrClient.getLiveWindowStartMs() ?: minSeekMs
        val windowMs = if (headTimeMs > windowStartMs) headTimeMs - windowStartMs else 0L
        if (windowMs <= 0) return
        val windowUs = Util.msToUs(windowMs)
        // Стабильность как в браузере — 15 сек до головы (3 сегмента), не меньше 5 сек от начала
        val defaultPosUs = (windowUs - Util.msToUs(15000)).coerceAtLeast(Util.msToUs(5000).coerceAtMost(windowUs / 2))
        // Обновляем только если окно выросло (голова движется)
        if (windowUs != liveWindowDurationUs) {
            liveWindowDurationUs = windowUs
            liveDefaultPositionUs = defaultPosUs
            android.util.Log.i("SabrMediaSource", "live timeline update: headTimeMs=$headTimeMs windowStartMs=$windowStartMs windowMs=$windowMs defaultPosMs=${Util.usToMs(defaultPosUs)}")
            // refreshSourceInfo можно звать с любого потока — BaseMediaSource сам выставит на нужный handler
            processManifest()
        }
    }

    private class SabrTimeline(
        private val presentationStartTimeMs: Long,
        private val windowStartTimeMs: Long,
        private val elapsedRealtimeEpochOffsetMs: Long,
        private val offsetInFirstPeriodUs: Long,
        private val windowDurationUs: Long,
        private val windowDefaultStartPositionUs: Long,
        private val liveConfiguration: MediaItem.LiveConfiguration?,
        private val manifest: SabrManifest,
        private val mediaItem: MediaItem?,
    ) : Timeline() {
        override fun getPeriodCount(): Int = 1

        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
            Assertions.checkIndex(periodIndex, 0, periodCount)
            val uid: Any? = if (setIds) (0 + periodIndex) else null
            return period.set(
                null,
                uid,
                0,
                Util.msToUs(manifest.durationMs),
                Util.msToUs(0) - offsetInFirstPeriodUs
            )
        }

        override fun getWindowCount(): Int = 1

        override fun getWindow(
            windowIndex: Int,
            window: Window,
            defaultPositionProjectionUs: Long,
        ): Window {
            Assertions.checkIndex(windowIndex, 0, 1)
            // positionInFirstPeriodUs = windowStart: период живёт в абсолютном домене us
            // (сэмплы fMP4 абсолютны), окно — его срез [windowStart .. windowStart+duration].
            // isDynamic=true — live-окно растёт с головой; period UID стабилен, позиция сохраняется.
            // 9-й параметр = liveConfiguration (был null): в media3 1.4.1 Window.isLive()
            // возвращает liveConfiguration != null, а ExoPlayerImplInternal кормит им
            // DefaultLivePlaybackSpeedControl (плавный разгон к живому краю после фризов).
            return window.set(
                Window.SINGLE_WINDOW_UID,
                mediaItem,
                manifest,
                presentationStartTimeMs,
                windowStartTimeMs,
                elapsedRealtimeEpochOffsetMs,
                true,
                true,
                liveConfiguration,
                windowDefaultStartPositionUs,
                windowDurationUs,
                0,
                0,
                offsetInFirstPeriodUs
            )
        }

        override fun getIndexOfPeriod(uid: Any): Int =
            if (uid !is Int || uid < 0 || uid >= periodCount) C.INDEX_UNSET else uid

        override fun getUidOfPeriod(periodIndex: Int): Any {
            Assertions.checkIndex(periodIndex, 0, periodCount)
            return 0 + periodIndex
        }
    }
}
