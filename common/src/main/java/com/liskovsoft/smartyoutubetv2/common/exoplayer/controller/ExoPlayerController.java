// 
// Decompiled by Procyon v0.6.0
// 

package com.liskovsoft.smartyoutubetv2.common.exoplayer.controller;

import com.google.android.exoplayer2.C;
import com.liskovsoft.sharedutils.helpers.Helpers;
import java.io.InputStream;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.ExoPlayer;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.ExoUtils;
import java.util.List;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.ExoFormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.google.android.exoplayer2.source.MergingMediaSource;
import com.liskovsoft.smartyoutubetv2.common.vot.VotSettings;
import java.util.Iterator;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorUtil;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.PlaybackParameters;

import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.RenderersFactory;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.source.MediaSource;
import android.os.Build;
import com.google.android.exoplayer2.audio.AudioListener;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.MediaTrack;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.VideoTrack;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import java.lang.ref.WeakReference;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.VolumeBooster;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorManager;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackInfoFormatter2;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.errors.TrackErrorFixer;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.ExoMediaSourceFactory;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.PlayerEventListener;
import android.content.Context;
import com.google.android.exoplayer2.Player;

public class ExoPlayerController implements Player.EventListener
{
    private static final String TAG = "ExoPlayerController";
    private static final float TRANSLATION_LOUDNESS_COMPENSATION = 1.8f;
    private final Context mContext;
    private final PlayerEventListener mEventListener;
    private boolean mIsEnded;
    private float mLastUserVolume;
    private final ExoMediaSourceFactory mMediaSourceFactory;
    private boolean mOnSourceChanged;
    private Runnable mOnVideoLoaded;
    private SimpleExoPlayer mPlayer;
    private PlayerView mPlayerView;
    private final TrackErrorFixer mTrackErrorFixer;
    private final TrackInfoFormatter2 mTrackFormatter;
    private final TrackSelectorManager mTrackSelectorManager;
    private boolean mTranslationOverlayActive;
    private SimpleExoPlayer mTranslationPlayer;
    private VolumeBooster mTranslationVolumeBooster;
    private WeakReference<Video> mVideo;
    private VolumeBooster mVolumeBooster;
    
    public ExoPlayerController(final Context context, final PlayerEventListener mEventListener) {
        this.mLastUserVolume = 1.0f;
        final PlayerTweaksData instance = PlayerTweaksData.instance(context);
        this.mContext = context.getApplicationContext();
        final ExoMediaSourceFactory mMediaSourceFactory = new ExoMediaSourceFactory(context);
        this.mMediaSourceFactory = mMediaSourceFactory;
        final TrackSelectorManager mTrackSelectorManager = new TrackSelectorManager(context);
        this.mTrackSelectorManager = mTrackSelectorManager;
        (this.mTrackFormatter = new TrackInfoFormatter2()).enableBitrate(PlayerTweaksData.instance(context).isQualityInfoBitrateEnabled());
        mMediaSourceFactory.setTrackErrorFixer(this.mTrackErrorFixer = new TrackErrorFixer(mTrackSelectorManager));
        this.mEventListener = mEventListener;
        this.applyShield720pFix();
        VideoTrack.sIsAltPresetsEnabled = instance.isAltPresetsEnabled();
        MediaTrack.setAvcOverVp9Preferred(instance.isAvcOverVp9Preferred());
    }
    
    private void applyOverlayVolumesFromMaster() {
        if (this.mTranslationOverlayActive) {
            if (this.mPlayer != null) {
                final float max = Math.max(0.0f, Math.min(1.0f, this.mLastUserVolume));
                this.mPlayer.setVolume(Math.max(0.0f, Math.min(1.0f, this.getOriginalVolumeFactor() * max)));
                if (this.mTranslationPlayer == null) {
                    return;
                }
                final float b = max * this.getTranslationVolumeFactor() * 1.8f;
                this.mTranslationPlayer.setVolume(Math.max(0.0f, Math.min(1.0f, b)));
                this.applyTranslationBoost(b);
            }
        }
    }
    
    private void applyShield720pFix() {
        final PlayerData instance = PlayerData.instance(this.mContext);
        this.mTrackSelectorManager.selectTrack(FormatItem.toMediaTrack(instance.getFormat(0)));
        this.mTrackSelectorManager.selectTrack(FormatItem.toMediaTrack(instance.getFormat(1)));
        this.mTrackSelectorManager.selectTrack(FormatItem.toMediaTrack(instance.getFormat(2)));
    }
    
    private void applyTranslationBoost(final float n) {
        final SimpleExoPlayer mTranslationPlayer = this.mTranslationPlayer;
        if (mTranslationPlayer == null) {
            return;
        }
        final VolumeBooster mTranslationVolumeBooster = this.mTranslationVolumeBooster;
        if (mTranslationVolumeBooster != null) {
            mTranslationPlayer.removeAudioListener((AudioListener)mTranslationVolumeBooster);
            this.mTranslationVolumeBooster = null;
        }
        if (n <= 1.0f) {
            return;
        }
        final VolumeBooster mTranslationVolumeBooster2 = new VolumeBooster(true, n, this.mTranslationPlayer);
        this.mTranslationVolumeBooster = mTranslationVolumeBooster2;
        this.mTranslationPlayer.addAudioListener((AudioListener)mTranslationVolumeBooster2);
    }
    
    private void applyVolumeBoost(final float n) {
        final SimpleExoPlayer mPlayer = this.mPlayer;
        if (mPlayer == null) {
            return;
        }
        final VolumeBooster mVolumeBooster = this.mVolumeBooster;
        if (mVolumeBooster != null) {
            mPlayer.removeAudioListener((AudioListener)mVolumeBooster);
            this.mVolumeBooster = null;
        }
        if (n > 1.0f && !this.contains51Audio() && Build.VERSION.SDK_INT >= 19) {
            final VolumeBooster mVolumeBooster2 = new VolumeBooster(true, n, null);
            this.mVolumeBooster = mVolumeBooster2;
            this.mPlayer.addAudioListener((AudioListener)mVolumeBooster2);
        }
    }
    
    private void attachTranslationAudioOverlay(final MediaSource mediaSource, long max) {
        this.releaseTranslationAudioPlayer();
        if (mediaSource != null) {
            if (this.mPlayer != null) {
                final SimpleExoPlayer simpleInstance = ExoPlayerFactory.newSimpleInstance(this.mContext, (RenderersFactory)new DefaultRenderersFactory(this.mContext), (TrackSelector)new DefaultTrackSelector());
                this.mTranslationPlayer = simpleInstance;
                boolean playWhenReady = true;
                this.mTranslationOverlayActive = true;
                simpleInstance.addListener((Player$EventListener)new Player$EventListener() {
                    public void onPlayerStateChanged(final boolean b, final int n) {
                        if (n == 3) {
                            ExoPlayerController.this.syncTranslationSeekToMain();
                        }
                    }
                });
                final SimpleExoPlayer mPlayer = this.mPlayer;
                if (mPlayer != null) {
                    this.mLastUserVolume = mPlayer.getVolume();
                }
                this.applyOverlayVolumesFromMaster();
                this.mTranslationPlayer.prepare(mediaSource);
                max = Math.max(0L, max);
                this.mTranslationPlayer.seekTo(max);
                final SimpleExoPlayer mTranslationPlayer = this.mTranslationPlayer;
                final SimpleExoPlayer mPlayer2 = this.mPlayer;
                if (mPlayer2 == null || !mPlayer2.getPlayWhenReady()) {
                    playWhenReady = false;
                }
                mTranslationPlayer.setPlayWhenReady(playWhenReady);
                this.syncTranslationPlaybackParameters();
            }
        }
    }
    
    private boolean contains51Audio() {
        final TrackSelectorManager mTrackSelectorManager = this.mTrackSelectorManager;
        if (mTrackSelectorManager != null) {
            if (mTrackSelectorManager.getAudioTracks() != null) {
                final Iterator<MediaTrack> iterator = this.mTrackSelectorManager.getAudioTracks().iterator();
                while (iterator.hasNext()) {
                    if (TrackSelectorUtil.is51Audio(iterator.next().format)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    
    private float getOriginalVolumeFactor() {
        return VotSettings.instance(this.mContext).getOriginalVolumePercent() / 100.0f;
    }
    
    private float getTranslationVolumeFactor() {
        return VotSettings.instance(this.mContext).getTranslationVolumePercent() / 100.0f;
    }
    
    private void notifyOnVideoLoad() {
        if (this.mOnSourceChanged) {
            this.mOnSourceChanged = false;
            this.mEventListener.onVideoLoaded(this.getVideo());
            final Runnable mOnVideoLoaded = this.mOnVideoLoaded;
            if (mOnVideoLoaded != null) {
                mOnVideoLoaded.run();
            }
        }
    }
    
    private void openMediaSource(final MediaSource mediaSource) {
        this.openMediaSource(mediaSource, -1L);
    }
    
    private void openMediaSource(final MediaSource mediaSource, final long b) {
        this.releaseTranslationAudioPlayer();
        this.resetPlayerState();
        this.setQualityInfo("");
        this.mTrackSelectorManager.setMergedSource(mediaSource instanceof MergingMediaSource);
        this.mTrackSelectorManager.invalidate();
        this.mOnSourceChanged = true;
        this.mEventListener.onSourceChanged(this.getVideo());
        this.mPlayer.prepare(mediaSource);
        if (b >= 0L) {
            this.mPlayer.seekTo(Math.max(0L, b));
        }
    }
    
    private void releasePlayer() {
        this.releaseTranslationAudioPlayer();
        final SimpleExoPlayer mPlayer = this.mPlayer;
        if (mPlayer == null) {
            return;
        }
        try {
            mPlayer.removeListener((Player$EventListener)this);
            this.mPlayer.stop(true);
            this.mPlayer.clearVideoSurface();
            this.mPlayer.release();
            this.mPlayer = null;
        }
        catch (final ArrayIndexOutOfBoundsException ex) {
            ex.printStackTrace();
        }
    }
    
    private void releaseTranslationAudioPlayer() {
        final SimpleExoPlayer mTranslationPlayer = this.mTranslationPlayer;
        if (mTranslationPlayer == null) {
            return;
        }
        try {
            final VolumeBooster mTranslationVolumeBooster = this.mTranslationVolumeBooster;
            if (mTranslationVolumeBooster != null) {
                mTranslationPlayer.removeAudioListener((AudioListener)mTranslationVolumeBooster);
                this.mTranslationVolumeBooster = null;
            }
            this.mTranslationPlayer.stop(true);
            this.mTranslationPlayer.release();
        }
        catch (final Exception ex) {
            Log.e(ExoPlayerController.TAG, (Object)"releaseTranslationAudioPlayer", (Throwable)ex);
        }
        this.mTranslationPlayer = null;
        this.mTranslationOverlayActive = false;
        final SimpleExoPlayer mPlayer = this.mPlayer;
        if (mPlayer != null) {
            mPlayer.setVolume(this.mLastUserVolume);
        }
    }
    
    private void setQualityInfo(final String qualityInfo) {
        final PlayerView mPlayerView = this.mPlayerView;
        if (mPlayerView != null && qualityInfo != null) {
            mPlayerView.setQualityInfo(qualityInfo);
        }
    }
    
    private void syncTranslationPlayWhenReady() {
        final SimpleExoPlayer mTranslationPlayer = this.mTranslationPlayer;
        if (mTranslationPlayer != null && this.mTranslationOverlayActive) {
            final SimpleExoPlayer mPlayer = this.mPlayer;
            if (mPlayer != null) {
                mTranslationPlayer.setPlayWhenReady(mPlayer.getPlayWhenReady());
            }
        }
    }
    
    private void syncTranslationPlaybackParameters() {
        final SimpleExoPlayer mTranslationPlayer = this.mTranslationPlayer;
        if (mTranslationPlayer != null && this.mTranslationOverlayActive) {
            final SimpleExoPlayer mPlayer = this.mPlayer;
            if (mPlayer != null) {
                mTranslationPlayer.setPlaybackParameters(mPlayer.getPlaybackParameters());
            }
        }
    }
    
    private void syncTranslationSeekToMain() {
        final SimpleExoPlayer mTranslationPlayer = this.mTranslationPlayer;
        if (mTranslationPlayer != null && this.mTranslationOverlayActive) {
            final SimpleExoPlayer mPlayer = this.mPlayer;
            if (mPlayer != null) {
                mTranslationPlayer.seekTo(mPlayer.getCurrentPosition());
            }
        }
    }
    
    public void attachTranslationAudio(final String s) {
        final MediaSource fromAudioUrl = this.mMediaSourceFactory.fromAudioUrl(s);
        if (fromAudioUrl == null) {
            this.releaseTranslationAudioPlayer();
            return;
        }
        long positionMs = this.getPositionMs();
        if (positionMs < 0L) {
            positionMs = 0L;
        }
        this.attachTranslationAudioOverlay(fromAudioUrl, positionMs);
    }
    
    public void clearTranslationAudio() {
        this.releaseTranslationAudioPlayer();
    }
    
    public boolean containsMedia() {
        final SimpleExoPlayer mPlayer = this.mPlayer;
        boolean b = false;
        if (mPlayer == null) {
            return false;
        }
        if (mPlayer.getPlaybackState() != 1) {
            b = true;
        }
        return b;
    }
    
    public FormatItem getAudioFormat() {
        return ExoFormatItem.from(this.mTrackSelectorManager.getAudioTrack());
    }
    
    public List<FormatItem> getAudioFormats() {
        return ExoFormatItem.from(this.mTrackSelectorManager.getAudioTracks());
    }
    
    public long getDurationMs() {
        final SimpleExoPlayer mPlayer = this.mPlayer;
        long n = -1L;
        if (mPlayer == null) {
            return -1L;
        }
        final long duration = mPlayer.getDuration();
        if (duration != -9223372036854775807L) {
            n = duration;
        }
        return n;
    }
    
    public float getPitch() {
        final SimpleExoPlayer mPlayer = this.mPlayer;
        if (mPlayer != null) {
            return mPlayer.getPlaybackParameters().pitch;
        }
        return -1.0f;
    }
    
    public boolean getPlayWhenReady() {
        final SimpleExoPlayer mPlayer = this.mPlayer;
        return mPlayer != null && mPlayer.getPlayWhenReady();
    }
    
    public long getPositionMs() {
        final SimpleExoPlayer mPlayer = this.mPlayer;
        if (mPlayer == null) {
            return -1L;
        }
        return mPlayer.getCurrentPosition();
    }
    
    public float getSpeed() {
        final SimpleExoPlayer mPlayer = this.mPlayer;
        if (mPlayer != null) {
            return mPlayer.getPlaybackParameters().speed;
        }
        return -1.0f;
    }
    
    public FormatItem getSubtitleFormat() {
        return ExoFormatItem.from(this.mTrackSelectorManager.getSubtitleTrack());
    }
    
    public List<FormatItem> getSubtitleFormats() {
        return ExoFormatItem.from(this.mTrackSelectorManager.getSubtitleTracks());
    }
    
    public Video getVideo() {
        final WeakReference<Video> mVideo = this.mVideo;
        Video video;
        if (mVideo != null) {
            video = mVideo.get();
        }
        else {
            video = null;
        }
        return video;
    }
    
    public FormatItem getVideoFormat() {
        return ExoFormatItem.from(this.mTrackSelectorManager.getVideoTrack());
    }
    
    public List<FormatItem> getVideoFormats() {
        return ExoFormatItem.from(this.mTrackSelectorManager.getVideoTracks());
    }
    
    public float getVolume() {
        final SimpleExoPlayer mPlayer = this.mPlayer;
        if (mPlayer == null) {
            return 1.0f;
        }
        if (this.mTranslationOverlayActive) {
            return this.mLastUserVolume;
        }
        return mPlayer.getVolume();
    }
    
    public boolean isLoading() {
        return ExoUtils.isLoading((ExoPlayer)this.mPlayer);
    }
    
    public boolean isPlaying() {
        return ExoUtils.isPlaying((ExoPlayer)this.mPlayer);
    }
    
    public void onPlayerError(final ExoPlaybackException obj) {
        final String tag = ExoPlayerController.TAG;
        final StringBuilder sb = new StringBuilder("onPlayerError: ");
        sb.append(obj);
        Log.e(tag, (Object)sb.toString(), new Object[0]);
        Object cause;
        if (obj.getCause() != null) {
            cause = obj.getCause();
        }
        else {
            cause = obj;
        }
        this.mEventListener.onEngineError(obj.type, obj.rendererIndex, (Throwable)cause);
    }
    
    public void onPlayerStateChanged(final boolean b, int n) {
        final boolean b2 = 3 == n && b;
        final boolean b3 = 3 == n && !b;
        final boolean b4 = 4 == n && b;
        if (2 == n && b) {
            n = 1;
        }
        else {
            n = 0;
        }
        if (b4 && this.mIsEnded) {
            return;
        }
        if (b2) {
            this.mEventListener.onPlay();
        }
        else if (b3) {
            this.mEventListener.onPause();
        }
        else if (b4) {
            this.mEventListener.onPlayEnd();
            this.mIsEnded = true;
        }
        else if (n != 0) {
            this.mEventListener.onBuffering();
        }
        if (this.getPositionMs() < this.getDurationMs()) {
            this.mIsEnded = false;
        }
        this.syncTranslationPlayWhenReady();
    }
    
    public void onPositionDiscontinuity(final int n) {
        Log.e(ExoPlayerController.TAG, (Object)"onPositionDiscontinuity", new Object[0]);
        if (n == 0) {
            this.mPlayer.stop();
            this.mEventListener.onPlayEnd();
        }
    }
    
    public void onSeekProcessed() {
        this.syncTranslationSeekToMain();
        this.mEventListener.onSeekEnd();
    }
    
    public void onTracksChanged(final TrackGroupArray trackGroupArray, final TrackSelectionArray trackSelectionArray) {
        final String tag = ExoPlayerController.TAG;
        final StringBuilder sb = new StringBuilder("onTracksChanged: start: groups length: ");
        sb.append(trackGroupArray.length);
        Log.d(tag, (Object)sb.toString(), new Object[0]);
        if (trackGroupArray.length == 0) {
            Log.i(tag, (Object)"onTracksChanged: Hmm. Strange. Received empty groups, no selections. Why is this happens only on next/prev videos?", new Object[0]);
            return;
        }
        this.notifyOnVideoLoad();
        for (final TrackSelection trackSelection : trackSelectionArray.getAll()) {
            if (trackSelection != null) {
                final Format format = trackSelection.getFormat(0);
                this.mEventListener.onTrackChanged(ExoFormatItem.from(format));
                this.mTrackFormatter.setFormat(format);
            }
        }
        this.setQualityInfo(this.mTrackFormatter.getQualityLabel());
    }
    
    public void openDash(final MediaItemFormatInfo mediaItemFormatInfo) {
        this.openMediaSource(this.mMediaSourceFactory.fromDashFormatInfo(mediaItemFormatInfo));
    }
    
    public void openDash(final InputStream inputStream) {
        this.openMediaSource(this.mMediaSourceFactory.fromDashManifest(inputStream));
    }
    
    public void openDashUrl(final String s) {
        this.openMediaSource(this.mMediaSourceFactory.fromDashManifestUrl(s));
    }
    
    public void openDashWithTranslationAudio(final MediaItemFormatInfo mediaItemFormatInfo, final String s) {
        final MediaSource fromDashFormatInfo = this.mMediaSourceFactory.fromDashFormatInfo(mediaItemFormatInfo);
        final MediaSource fromAudioUrl = this.mMediaSourceFactory.fromAudioUrl(s);
        if (fromAudioUrl == null) {
            this.openMediaSource(fromDashFormatInfo);
            return;
        }
        long positionMs = this.getPositionMs();
        long n;
        if (positionMs >= 0L) {
            n = positionMs;
        }
        else {
            n = -1L;
        }
        this.openMediaSource(fromDashFormatInfo, n);
        if (positionMs < 0L) {
            positionMs = 0L;
        }
        this.attachTranslationAudioOverlay(fromAudioUrl, positionMs);
    }
    
    public void openHlsUrl(final String s) {
        this.openMediaSource(this.mMediaSourceFactory.fromHlsPlaylist(s));
    }
    
    public void openMerged(final MediaItemFormatInfo mediaItemFormatInfo, final String s) {
        this.openMediaSource((MediaSource)new MergingMediaSource(new MediaSource[] { this.mMediaSourceFactory.fromDashFormatInfo(mediaItemFormatInfo), this.mMediaSourceFactory.fromHlsPlaylist(s) }));
    }
    
    public void openMerged(final InputStream inputStream, final String s) {
        this.openMediaSource((MediaSource)new MergingMediaSource(new MediaSource[] { this.mMediaSourceFactory.fromDashManifest(inputStream), this.mMediaSourceFactory.fromHlsPlaylist(s) }));
    }
    
    public void openSabr(final MediaItemFormatInfo mediaItemFormatInfo) {
        this.openMediaSource(this.mMediaSourceFactory.fromSabrFormatInfo(mediaItemFormatInfo));
    }
    
    public void openSabrWithTranslationAudio(final MediaItemFormatInfo mediaItemFormatInfo, final String s) {
        final MediaSource fromSabrFormatInfo = this.mMediaSourceFactory.fromSabrFormatInfo(mediaItemFormatInfo);
        final MediaSource fromAudioUrl = this.mMediaSourceFactory.fromAudioUrl(s);
        if (fromAudioUrl == null) {
            this.openMediaSource(fromSabrFormatInfo);
            return;
        }
        long positionMs = this.getPositionMs();
        long n;
        if (positionMs >= 0L) {
            n = positionMs;
        }
        else {
            n = -1L;
        }
        this.openMediaSource(fromSabrFormatInfo, n);
        if (positionMs < 0L) {
            positionMs = 0L;
        }
        this.attachTranslationAudioOverlay(fromAudioUrl, positionMs);
    }
    
    public void openUrlList(final List<String> list) {
        this.openMediaSource(this.mMediaSourceFactory.fromUrlList(list));
    }
    
    public void release() {
        this.mTrackSelectorManager.release();
        this.mMediaSourceFactory.release();
        this.releasePlayer();
        this.mPlayerView = null;
    }
    
    public void resetPlayerState() {
        if (this.containsMedia()) {
            this.mPlayer.stop(true);
        }
    }
    
    public void selectFormat(final FormatItem formatItem) {
        if (formatItem != null) {
            this.mEventListener.onTrackSelected(formatItem);
            this.mTrackSelectorManager.selectTrack(FormatItem$_CC.toMediaTrack(formatItem));
        }
    }
    
    public void setOnVideoLoaded(final Runnable mOnVideoLoaded) {
        this.mOnVideoLoaded = mOnVideoLoaded;
    }
    
    public void setPitch(final float n) {
        if (this.mPlayer != null && n > 0.0f && !Helpers.floatEquals(n, this.getPitch())) {
            this.mPlayer.setPlaybackParameters(new PlaybackParameters(this.mPlayer.getPlaybackParameters().speed, n));
        }
        this.syncTranslationPlaybackParameters();
    }
    
    public void setPlayWhenReady(final boolean playWhenReady) {
        final SimpleExoPlayer mPlayer = this.mPlayer;
        if (mPlayer != null) {
            mPlayer.setPlayWhenReady(playWhenReady);
        }
        this.syncTranslationPlayWhenReady();
    }
    
    public void setPlayer(final SimpleExoPlayer mPlayer) {
        (this.mPlayer = mPlayer).addListener((Player$EventListener)this);
    }
    
    public void setPlayerView(final PlayerView mPlayerView) {
        this.mPlayerView = mPlayerView;
    }
    
    public void setPositionMs(final long n) {
        if (this.mPlayer != null && n >= 0L && n <= this.getDurationMs()) {
            this.mPlayer.seekTo(n);
            final SimpleExoPlayer mTranslationPlayer = this.mTranslationPlayer;
            if (mTranslationPlayer != null && this.mTranslationOverlayActive) {
                mTranslationPlayer.seekTo(n);
            }
        }
    }
    
    public void setSpeed(final float speed) {
        if (this.mPlayer != null && speed > 0.0f && !Helpers.floatEquals(speed, this.getSpeed())) {
            if (PlayerTweaksData.instance(this.mContext).isAudioTimeStretchingEnabled()) {
                this.mPlayer.setPlaybackParameters(new PlaybackParameters(speed, this.mPlayer.getPlaybackParameters().pitch));
            }
            else {
                this.mPlayer.setPlaybackParameters(new PlaybackParameters(speed, speed));
            }
            this.mTrackFormatter.setSpeed(speed);
            this.setQualityInfo(this.mTrackFormatter.getQualityLabel());
            this.mEventListener.onSpeedChanged(speed);
        }
        this.syncTranslationPlaybackParameters();
    }
    
    public void setTrackSelector(final DefaultTrackSelector trackSelector) {
        this.mTrackSelectorManager.setTrackSelector(trackSelector);
        final Context mContext = this.mContext;
        if (mContext != null && trackSelector != null && PlayerTweaksData.instance(mContext).isTunneledPlaybackEnabled() && Build.VERSION.SDK_INT >= 21) {
            trackSelector.setParameters(trackSelector.buildUponParameters().setTunnelingAudioSessionId(C.generateAudioSessionIdV21(this.mContext)));
        }
    }
    
    public void setVideo(final Video referent) {
        this.mVideo = new WeakReference<Video>(referent);
    }
    
    public void setVolume(final float a) {
        if (a >= 0.0f) {
            this.mLastUserVolume = Math.min(a, 1.0f);
        }
        final SimpleExoPlayer mPlayer = this.mPlayer;
        if (mPlayer != null && a >= 0.0f) {
            if (this.mTranslationOverlayActive) {
                this.applyOverlayVolumesFromMaster();
            }
            else {
                mPlayer.setVolume(this.mLastUserVolume);
            }
        }
    }
}
