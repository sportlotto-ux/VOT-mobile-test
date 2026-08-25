package com.liskovsoft.smartyoutubetv2.common.exoplayer.controller;

import android.content.Context;
import android.os.Build;
import android.os.Build.VERSION;

import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.common.Format;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.BuildConfig;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.PlayerEventListener;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.ExoMediaSourceFactory;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.errors.TrackErrorFixer;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.ExoFormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackInfoFormatter2;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorManager;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorUtil;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.MediaTrack;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.VideoTrack;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.ExoUtils;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.List;

public class ExoPlayerController implements Player.Listener {
    private static final String TAG = ExoPlayerController.class.getSimpleName();
    private final Context mContext;
    private final ExoMediaSourceFactory mMediaSourceFactory;
    private final TrackSelectorManager mTrackSelectorManager;
    private final TrackInfoFormatter2 mTrackFormatter;
    private final TrackErrorFixer mTrackErrorFixer;
    private boolean mOnSourceChanged;
    private WeakReference<Video> mVideo;
    private final PlayerEventListener mEventListener;
    private ExoPlayer mPlayer;
    private PlayerView mPlayerView;
    private boolean mIsEnded;
    private Runnable mOnVideoLoaded;
    private androidx.media3.exoplayer.ExoPlayer mTranslationPlayer;
    private boolean mTranslationOverlayActive;
    private float mLastUserVolume = 1.0f;

    public ExoPlayerController(Context context, PlayerEventListener eventListener) {
        PlayerTweaksData playerTweaksData = PlayerTweaksData.instance(context);
        mContext = context.getApplicationContext();
        mMediaSourceFactory = new ExoMediaSourceFactory(context);
        mTrackSelectorManager = new TrackSelectorManager(context);
        mTrackFormatter = new TrackInfoFormatter2();
        mTrackFormatter.enableBitrate(PlayerTweaksData.instance(context).isQualityInfoBitrateEnabled());
        mTrackErrorFixer = new TrackErrorFixer(mTrackSelectorManager);

        mMediaSourceFactory.setTrackErrorFixer(mTrackErrorFixer);
        mEventListener = eventListener;
        
        applyShield720pFix();
        VideoTrack.sIsAltPresetsEnabled = playerTweaksData.isAltPresetsEnabled();
        MediaTrack.setAvcOverVp9Preferred(playerTweaksData.isAvcOverVp9Preferred());
    }

    private void applyShield720pFix() {
        PlayerData playerData = PlayerData.instance(mContext);
        mTrackSelectorManager.selectTrack(FormatItem.toMediaTrack(playerData.getFormat(FormatItem.TYPE_VIDEO)));
        mTrackSelectorManager.selectTrack(FormatItem.toMediaTrack(playerData.getFormat(FormatItem.TYPE_AUDIO)));
        mTrackSelectorManager.selectTrack(FormatItem.toMediaTrack(playerData.getFormat(FormatItem.TYPE_SUBTITLE)));
    }

    public void openSabr(MediaItemFormatInfo formatInfo) {
        MediaSource mediaSource = mMediaSourceFactory.fromSabrFormatInfo(formatInfo);
        openMediaSource(mediaSource);
    }

    public void openDash(MediaItemFormatInfo formatInfo) {
        MediaSource mediaSource = mMediaSourceFactory.fromDashFormatInfo(formatInfo);
        openMediaSource(mediaSource);
    }

    public void openDash(InputStream dashManifest) {
        MediaSource mediaSource = mMediaSourceFactory.fromDashManifest(dashManifest);
        openMediaSource(mediaSource);
    }

    public void openDashUrl(String dashManifestUrl) {
        MediaSource mediaSource = mMediaSourceFactory.fromDashManifestUrl(dashManifestUrl);
        openMediaSource(mediaSource);
    }

    public void openHlsUrl(String hlsPlaylistUrl) {
        MediaSource mediaSource = mMediaSourceFactory.fromHlsPlaylist(hlsPlaylistUrl);
        openMediaSource(mediaSource);
    }

    public void openUrlList(List<String> urlList) {
        MediaSource mediaSource = mMediaSourceFactory.fromUrlList(urlList);
        openMediaSource(mediaSource);
    }

    public void openMerged(MediaItemFormatInfo formatInfo, String hlsPlaylistUrl) {
        MediaSource dashMediaSource = mMediaSourceFactory.fromDashFormatInfo(formatInfo);
        MediaSource hlsMediaSource = mMediaSourceFactory.fromHlsPlaylist(hlsPlaylistUrl);
        openMediaSource(new MergingMediaSource(dashMediaSource, hlsMediaSource));
    }


    // VOT translation overlay - based on VTube logic but using base APIs
    public void openSabrWithTranslationAudio(com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo formatInfo, String translationUrl) {
        long pos = getPositionMs();
        androidx.media3.exoplayer.source.MediaSource videoSource = mMediaSourceFactory.fromSabrFormatInfo(formatInfo);
        androidx.media3.exoplayer.source.MediaSource audioSource = mMediaSourceFactory.fromUrlList(java.util.Collections.singletonList(translationUrl));
        openMediaSourceWithTranslation(videoSource, audioSource, pos);
    }
    public void openDashWithTranslationAudio(com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo formatInfo, String translationUrl) {
        long pos = getPositionMs();
        androidx.media3.exoplayer.source.MediaSource videoSource = mMediaSourceFactory.fromDashFormatInfo(formatInfo);
        androidx.media3.exoplayer.source.MediaSource audioSource = mMediaSourceFactory.fromUrlList(java.util.Collections.singletonList(translationUrl));
        openMediaSourceWithTranslation(videoSource, audioSource, pos);
    }
    public void attachTranslationAudio(String url) {
        long pos = getPositionMs();
        androidx.media3.exoplayer.source.MediaSource audioSource = mMediaSourceFactory.fromUrlList(java.util.Collections.singletonList(url));
        attachTranslationAudioOverlay(audioSource, pos);
    }
    public void clearTranslationAudio() {
        releaseTranslationAudioPlayer();
    }
    private void openMediaSourceWithTranslation(androidx.media3.exoplayer.source.MediaSource videoSource, androidx.media3.exoplayer.source.MediaSource audioSource, long pos) {
        // For minimal, use second player overlay instead of MergingMediaSource to avoid rebuffer
        openMediaSource(videoSource);
        if (pos >= 0) setPositionMs(pos);
        attachTranslationAudioOverlay(audioSource, pos);
    }
    private void attachTranslationAudioOverlay(androidx.media3.exoplayer.source.MediaSource audioSource, long pos) {
        releaseTranslationAudioPlayer();
        if (audioSource == null || mPlayer == null) return;
        try {
            mTranslationPlayer = new androidx.media3.exoplayer.ExoPlayer.Builder(mContext, new androidx.media3.exoplayer.DefaultRenderersFactory(mContext))
                    .setTrackSelector(new androidx.media3.exoplayer.trackselection.DefaultTrackSelector(mContext))
                    .build();
            mTranslationOverlayActive = true;
            mLastUserVolume = mPlayer.getVolume();
            // duck original
            float origVol = 0.15f; // 15% original when translation active
            try { origVol = com.liskovsoft.smartyoutubetv2.common.vot.VotSettings.instance(mContext).getOriginalVolumePercent() / 100.0f; } catch (Exception e) {}
            float transVol = 1.0f;
            try { transVol = com.liskovsoft.smartyoutubetv2.common.vot.VotSettings.instance(mContext).getTranslationVolumePercent() / 100.0f; } catch (Exception e) {}
            mPlayer.setVolume(origVol * mLastUserVolume);
            mTranslationPlayer.setVolume(transVol * 1.0f);
            mTranslationPlayer.prepare(audioSource);
            mTranslationPlayer.seekTo(Math.max(0, pos));
            mTranslationPlayer.setPlayWhenReady(mPlayer.getPlayWhenReady());
            // sync playback parameters
            try { mTranslationPlayer.setPlaybackParameters(mPlayer.getPlaybackParameters()); } catch (Exception e) {}
        } catch (Exception e) { releaseTranslationAudioPlayer(); }
    }
    private void releaseTranslationAudioPlayer() {
        if (mTranslationPlayer != null) {
            try { mTranslationPlayer.stop(); mTranslationPlayer.release(); } catch (Exception e) {}
            mTranslationPlayer = null;
        }
        mTranslationOverlayActive = false;
        if (mPlayer != null) {
            try { mPlayer.setVolume(mLastUserVolume); } catch (Exception e) {}
        }
    }
    private void syncTranslationPlayWhenReady() {
        if (mTranslationPlayer != null && mTranslationOverlayActive && mPlayer != null) {
            mTranslationPlayer.setPlayWhenReady(mPlayer.getPlayWhenReady());
        }
    }

    public void openMerged(InputStream dashManifest, String hlsPlaylistUrl) {
        MediaSource dashMediaSource = mMediaSourceFactory.fromDashManifest(dashManifest);
        MediaSource hlsMediaSource = mMediaSourceFactory.fromHlsPlaylist(hlsPlaylistUrl);
        openMediaSource(new MergingMediaSource(dashMediaSource, hlsMediaSource));
    }

    private void openMediaSource(MediaSource mediaSource) {
        resetPlayerState(); // fixes occasional video artifacts and problems with quality switching
        setQualityInfo("");

        mTrackSelectorManager.setMergedSource(mediaSource instanceof MergingMediaSource);
        mTrackSelectorManager.invalidate();
        mOnSourceChanged = true;
        mEventListener.onSourceChanged(getVideo());
        mPlayer.prepare(mediaSource);
    }

    public long getPositionMs() {
        if (mPlayer == null) {
            return -1;
        }

        return mPlayer.getCurrentPosition();
    }

    /**
     * NOTE: Pos gathered from content block data may slightly exceed video duration
     * (e.g. 302200 when duration is 302000).
     */
    public void setPositionMs(long positionMs) {
        // Url list videos at load stage has undefined (-1) length. So, we need to remove length check.
        if (mPlayer != null && positionMs >= 0 && positionMs <= getDurationMs()) {
            mPlayer.seekTo(positionMs);
        }
    }

    public long getDurationMs() {
        if (mPlayer == null) {
            return -1;
        }

        long duration = mPlayer.getDuration();
        return duration != C.TIME_UNSET ? duration : -1;
    }

    public void setPlayWhenReady(boolean play) {
        if (mPlayer != null) {
            mPlayer.setPlayWhenReady(play);
        }
        syncTranslationPlayWhenReady();
    }

    public boolean getPlayWhenReady() {
        if (mPlayer == null) {
            return false;
        }

        return mPlayer.getPlayWhenReady();
    }

    public boolean isPlaying() {
        return ExoUtils.isPlaying(mPlayer);
    }
    
    public boolean isLoading() {
        return ExoUtils.isLoading(mPlayer);
    }
    
    public boolean containsMedia() {
        if (mPlayer == null) {
            return false;
        }

        return mPlayer.getPlaybackState() != Player.STATE_IDLE;
    }
    
    public void release() {
        releaseTranslationAudioPlayer();
        mTrackSelectorManager.release();
        mMediaSourceFactory.release();
        releasePlayer();
        mPlayerView = null;
        // Don't destroy it (needed inside the bridge)!
        //mEventListener = null;
    }
    
    public void setPlayer(ExoPlayer player) {
        mPlayer = player;
        player.addListener(this);
    }

    //@Override
    //public void setEventListener(PlayerEventListener eventListener) {
    //    mEventListener = eventListener;
    //}
    
    public void setPlayerView(PlayerView playerView) {
        mPlayerView = playerView;
    }
    
    public void setTrackSelector(DefaultTrackSelector trackSelector) {
        mTrackSelectorManager.setTrackSelector(trackSelector);

        // TODO(media3): tunneled playback tweak (setTunnelingAudioSessionId removed in media3)
    }
    
    public void setVideo(Video video) {
        mVideo = new WeakReference<>(video);
    }
    
    public Video getVideo() {
        return mVideo != null ? mVideo.get() : null;
    }
    
    public List<FormatItem> getVideoFormats() {
        return ExoFormatItem.from(mTrackSelectorManager.getVideoTracks());
    }
    
    public List<FormatItem> getAudioFormats() {
        return ExoFormatItem.from(mTrackSelectorManager.getAudioTracks());
    }
    
    public List<FormatItem> getSubtitleFormats() {
        return ExoFormatItem.from(mTrackSelectorManager.getSubtitleTracks());
    }
    
    public void selectFormat(FormatItem formatItem) {
        if (formatItem != null && formatItem.getTrack() != null) {
            FormatItem selectedFormatItem = getSelectedFormat(formatItem.getTrack().rendererIndex);
            if (!formatItem.equals(selectedFormatItem)) {
                mTrackSelectorManager.selectTrack(formatItem.getTrack());
                mEventListener.onTrackSelected(formatItem);
            }
        }
    }

    public FormatItem getVideoFormat() {
        return getSelectedFormat(TrackSelectorManager.RENDERER_INDEX_VIDEO);
    }

    public FormatItem getAudioFormat() {
        return getSelectedFormat(TrackSelectorManager.RENDERER_INDEX_AUDIO);
    }

    public FormatItem getSubtitleFormat() {
        return getSelectedFormat(TrackSelectorManager.RENDERER_INDEX_SUBTITLE);
    }

    private FormatItem getSelectedFormat(int rendererIndex) {
        return ExoFormatItem.from(mTrackSelectorManager.getSelectedTrack(rendererIndex));
    }

    @Override
    public void onTracksChanged(Tracks tracks) {
        Log.d(TAG, "onTracksChanged: start: groups length: " + tracks.getGroups().size());

        if (tracks.getGroups().isEmpty()) {
            Log.i(TAG, "onTracksChanged: Hmm. Strange. Received empty groups, no selections. Why is this happens only on next/prev videos?");
            return;
        }

        notifyOnVideoLoad();

        for (Tracks.Group group : tracks.getGroups()) {
            int length = group.length;
            for (int i = 0; i < length; i++) {
                if (group.isTrackSelected(i)) {
                    Format format = group.getTrackFormat(i);

                    mEventListener.onTrackChanged(ExoFormatItem.from(format));

                    mTrackFormatter.setFormat(format);
                }
            }
        }

        setQualityInfo(mTrackFormatter.getQualityLabel());
    }

    private void notifyOnVideoLoad() {
        if (mOnSourceChanged) {
            mOnSourceChanged = false;

            mEventListener.onVideoLoaded(getVideo());

            if (mOnVideoLoaded != null) {
                mOnVideoLoaded.run();
            }

            // Produce thread sync problems
            // Attempt to read from field 'java.util.TreeMap$Node java.util.TreeMap$Node.left' on a null object reference
            //mTrackSelectorManager.fixTracksSelection();
        }
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        Log.e(TAG, "onPlayerError: " + error);

        // NOTE: Player is released at this point. So, there is no sense to restore the playback here.

        ExoPlaybackException exoError = error instanceof ExoPlaybackException ? (ExoPlaybackException) error : null;

        Throwable nested = error.getCause() != null ? error.getCause() : error;

        mEventListener.onEngineError(
                exoError != null ? exoError.type : PlaybackException.ERROR_CODE_UNSPECIFIED,
                exoError != null ? exoError.rendererIndex : -1,
                nested);
    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onPlayerStateChanged: " + TrackSelectorUtil.stateToString(playbackState));
        }

        boolean isPlayPressed = Player.STATE_READY == playbackState && playWhenReady;
        boolean isPausePressed = Player.STATE_READY == playbackState && !playWhenReady;
        boolean isPlaybackEnded = Player.STATE_ENDED == playbackState && playWhenReady;
        boolean isBuffering = Player.STATE_BUFFERING == playbackState && playWhenReady;

        // Fix chapters (seek and play) after playback ends
        if (isPlaybackEnded && mIsEnded) {
            return;
        }

        if (isPlayPressed) {
            mEventListener.onPlay();
        } else if (isPausePressed) {
            mEventListener.onPause();
        } else if (isPlaybackEnded) {
            mEventListener.onPlayEnd();
            mIsEnded = true;
        } else if (isBuffering) {
            mEventListener.onBuffering();
        }

        if (getPositionMs() < getDurationMs()) {
            mIsEnded = false;
        }
    }

    @Override
    public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
        Log.e(TAG, "onPositionDiscontinuity");

        // Fix video loop on 480p with legacy codes enabled
        if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
            mPlayer.stop();
            mEventListener.onPlayEnd();
        } else if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            // Replaces deprecated onSeekProcessed
            mEventListener.onSeekEnd();
        }
    }

    public float getSpeed() {
        if (mPlayer != null) {
            return mPlayer.getPlaybackParameters().speed;
        } else {
            return -1;
        }
    }

    public void setSpeed(float speed) {
        if (mPlayer != null && speed > 0) {
            if (PlayerTweaksData.instance(mContext).isAudioTimeStretchingEnabled()) {
                mPlayer.setPlaybackParameters(new PlaybackParameters(speed, mPlayer.getPlaybackParameters().pitch));
            } else {
                mPlayer.setPlaybackParameters(new PlaybackParameters(speed, speed));
            }

            mTrackFormatter.setSpeed(speed);
            setQualityInfo(mTrackFormatter.getQualityLabel());
            mEventListener.onSpeedChanged(speed);
        }
    }

    public float getPitch() {
        if (mPlayer != null) {
            return mPlayer.getPlaybackParameters().pitch;
        } else {
            return -1;
        }
    }

    public void setPitch(float pitch) {
        if (mPlayer != null && pitch > 0) {
            mPlayer.setPlaybackParameters(new PlaybackParameters(mPlayer.getPlaybackParameters().speed, pitch));
        }
    }

    public void setVolume(float volume) {
        if (mPlayer != null && volume >= 0) {
            mPlayer.setVolume(Math.min(volume, 1f));

            //applyVolumeBoost(volume);
        }
    }
    
    public float getVolume() {
        if (mPlayer != null) {
            return mPlayer.getVolume();
        } else {
            return 1;
        }
    }

    /**
     * Fixes video artifacts when switching to the next video.<br/>
     * Also could help with memory leaks(??)<br/>
     * Without this also you'll have problems with track quality switching(??).
     */
    public void resetPlayerState() {
        if (containsMedia()) {
            mPlayer.stop();
        }
    }
    
    public void setOnVideoLoaded(Runnable onVideoLoaded) {
        mOnVideoLoaded = onVideoLoaded;
    }

    private void setQualityInfo(String qualityInfoStr) {
        if (mPlayerView != null && qualityInfoStr != null) {
            mPlayerView.setQualityInfo(qualityInfoStr);
        }
    }

    private boolean contains51Audio() {
        if (mTrackSelectorManager == null || mTrackSelectorManager.getAudioTracks() == null) {
            return false;
        }

        for (MediaTrack track : mTrackSelectorManager.getAudioTracks()) {
            if (TrackSelectorUtil.is51Audio(track.format)) {
                return true;
            }
        }

        return false;
    }

    private void releasePlayer() {
        if (mPlayer == null) {
            return;
        }

        try {
            mPlayer.removeListener(this);
            mPlayer.stop(); // Cause input lags due to high cpu load?
            mPlayer.clearVideoSurface();
            mPlayer.release();
        } catch (ArrayIndexOutOfBoundsException e) { // thrown on stop()
            e.printStackTrace();
        } finally {
            mPlayer = null;
        }
    }
}
