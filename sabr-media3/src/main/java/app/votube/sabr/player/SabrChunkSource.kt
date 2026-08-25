package app.votube.sabr.player

import androidx.media3.common.C.TrackType
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.chunk.ChunkSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.CmcdConfiguration
import app.votube.sabr.manifest.SabrManifest
import app.votube.sabr.parser.SabrClient

/** A [ChunkSource] for Sabr streams.  */
@UnstableApi
interface SabrChunkSource : ChunkSource {
    /** Factory for [SabrChunkSource]s.  */
    interface Factory {
        fun createSabrChunkSource(
            manifest: SabrManifest,
            sabrClient: SabrClient,
            adaptationSetIndices: IntArray,
            trackSelection: ExoTrackSelection,
            trackType: @TrackType Int,
            elapsedRealtimeOffsetMs: Long,
            transferListener: TransferListener?,
            playerId: PlayerId,
            cmcdConfiguration: CmcdConfiguration?
        ): SabrChunkSource?

        /**
         * Returns the output [Format] of emitted text samples which were originally in `sourceFormat`.
         */
        fun getOutputTextFormat(sourceFormat: Format): Format? {
            return sourceFormat
        }
    }

    /**
     * Updates the track selection.
     *
     * @param trackSelection The new track selection instance. Must be equivalent to the previous one.
     */
    fun updateTrackSelection(trackSelection: ExoTrackSelection?)
}
