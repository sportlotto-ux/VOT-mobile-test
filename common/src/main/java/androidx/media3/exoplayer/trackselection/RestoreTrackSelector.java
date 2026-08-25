package androidx.media3.exoplayer.trackselection;

import android.util.Pair;
import androidx.annotation.Nullable;
import androidx.media3.exoplayer.source.TrackGroupArray;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorManager;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.MediaTrack;

/**
 * Media3 version.
 *
 * NOTE: media3 replaced the old selectVideoTrack/selectAudioTrack/selectTextTrack override points
 * with a completely different API (selectTracksForType + TrackInfo).
 * The custom track-selection fixes are temporarily disabled; manual quality selection still works
 * through SelectionOverride parameters (see TrackSelectorManager.setSelection).
 */
public class RestoreTrackSelector extends DefaultTrackSelector {
    private static final String TAG = RestoreTrackSelector.class.getSimpleName();

    public interface TrackSelectorCallback {
        Pair<ExoTrackSelection.Definition, MediaTrack> onSelectVideoTrack(TrackGroupArray groups, Parameters params);
        Pair<ExoTrackSelection.Definition, MediaTrack> onSelectAudioTrack(TrackGroupArray groups, Parameters params);
        Pair<ExoTrackSelection.Definition, MediaTrack> onSelectSubtitleTrack(TrackGroupArray groups, Parameters params);
        void updateVideoTrackSelection(TrackGroupArray groups, Parameters params, ExoTrackSelection.Definition definition);
        void updateAudioTrackSelection(TrackGroupArray groups, Parameters params, ExoTrackSelection.Definition definition);
        void updateSubtitleTrackSelection(TrackGroupArray groups, Parameters params, ExoTrackSelection.Definition definition);
    }

    public RestoreTrackSelector(ExoTrackSelection.Factory trackSelectionFactory) {
        super(trackSelectionFactory);
    }

    public void setOnTrackSelectCallback(@Nullable TrackSelectorCallback callback) {
        // TODO(media3): restore custom auto-selection fixes on top of the new selectTracksForType API
    }
}
