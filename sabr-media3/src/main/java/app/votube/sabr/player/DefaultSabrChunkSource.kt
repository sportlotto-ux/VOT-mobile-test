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

        val playbackPositionUs = loadingInfo.playbackPositionUs
        val bufferedDurationUs = loadPositionUs - playbackPositionUs

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
        val representationHolder = representationHolders[trackSelection.selectedIndex]
        sabrClient.selectFormat(representationHolder.representation)
        trackSelection.updateSelectedTrack(
            playbackPositionUs,
            bufferedDurationUs,
            C.TIME_UNSET,
            queue,
            chunkIterators,
        )

        if (representationHolder.chunkIndex == null) {
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

        if (segmentNum > lastAvailableSegmentNum
            || (missingLastSegment && segmentNum >= lastAvailableSegmentNum)) {
            // The segment is beyond the end of the period.
            out.endOfStream = true
            return
        }

        if (representationHolder.getSegmentStartTimeUs(segmentNum) >= representationHolder.periodDurationUs) {
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

    override fun onChunkLoadCompleted(chunk: Chunk) {
        if (chunk is InitializationChunk) {
            val trackIndex = trackSelection.indexOf(chunk.trackFormat)
            val representationHolder = representationHolders[trackIndex]
            if (representationHolder.chunkIndex == null) {
                representationHolder.chunkExtractor?.chunkIndex?.let {
                    representationHolders[trackIndex].chunkIndex = it
                }
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
