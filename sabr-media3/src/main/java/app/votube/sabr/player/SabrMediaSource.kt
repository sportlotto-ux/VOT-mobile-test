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

            return SabrMediaSource(
                mediaItem,
                manifest,
                sabrClient,
                DefaultSabrChunkSource.Factory(SabrDataSource.Factory(sabrClient)),
                compositeSequenceableLoaderFactory,
                cmcdConfiguration,
                drmSessionManagerProvider.get(mediaItem),
                loadErrorHandlingPolicy
            )
        }

        override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER)
    }

    private var mediaTransferListener: TransferListener? = null

    private var elapsedRealtimeOffsetMs: Long = C.TIME_UNSET

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
        val timeline =
            SabrTimeline(
                C.TIME_UNSET,
                C.TIME_UNSET,
                elapsedRealtimeOffsetMs,
                0,
                Util.msToUs(manifest.durationMs),
                0,
                manifest,
                mediaItem,
            )
        refreshSourceInfo(timeline)
    }

    private class SabrTimeline(
        private val presentationStartTimeMs: Long,
        private val windowStartTimeMs: Long,
        private val elapsedRealtimeEpochOffsetMs: Long,
        private val offsetInFirstPeriodUs: Long,
        private val windowDurationUs: Long,
        private val windowDefaultStartPositionUs: Long,
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
            return window.set(
                Window.SINGLE_WINDOW_UID,
                mediaItem,
                manifest,
                presentationStartTimeMs,
                windowStartTimeMs,
                elapsedRealtimeEpochOffsetMs,
                true,
                false,
                null,
                windowDefaultStartPositionUs,
                windowDurationUs,
                0,
                periodCount - 1,
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
