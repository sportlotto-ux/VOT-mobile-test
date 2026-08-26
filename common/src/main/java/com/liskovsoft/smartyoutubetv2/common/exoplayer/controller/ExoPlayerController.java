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
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.VolumeBooster;
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
    private VolumeBooster mTranslationBooster;
    private float mAppliedTransBoost = -1f;
    private boolean mTranslationOverlayActive;
    private float mLastUserVolume = 1.0f;
    // --- VOT sync machinery (ported from unified bc780a9/e565caf) ---
    private float mVotOrigVol = 0.10f;
    private float mVotTransVol = 1.0f;
    private static java.lang.ref.WeakReference<ExoPlayerController> sActiveVotInstance;
    private android.media.audiofx.Visualizer mTranslationVisualizer;
    private final android.os.Handler mDuckHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable mDuckRunnable;
    private long mLastSpeechMs = 0;
    private boolean mIsDucked = false;
    private static final int DUCK_RMS_THRESHOLD = 800;
    private static final long DUCK_RELEASE_MS = 600;
    private static final long DUCK_POLL_MS = 50;
    private final android.os.Handler mSyncHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable mSyncRunnable;
    private final android.os.Handler mSeekHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable mSeekRunnable;
    private long mPendingSeekPos = -1;
    private boolean mSeekInProgress = false;
    private Player.Listener mTransListener;

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
            float origVol = 0.10f;
            try { origVol = com.liskovsoft.smartyoutubetv2.common.vot.VotSettings.instance(mContext).getOriginalVolumePercent() / 100.0f; } catch (Exception e) {}
            float transVol = 1.0f;
            try { transVol = com.liskovsoft.smartyoutubetv2.common.vot.VotSettings.instance(mContext).getTranslationVolumePercent() / 100.0f; } catch (Exception e) {}
            mVotOrigVol = origVol; mVotTransVol = transVol; sActiveVotInstance = new java.lang.ref.WeakReference<>(this);
            mPlayer.setVolume(origVol * mLastUserVolume);
            mTranslationPlayer.setVolume(Math.min(1.0f, transVol * mLastUserVolume));
            mTranslationPlayer.prepare(audioSource);
            mTranslationPlayer.seekTo(Math.max(0, pos));
            applyTranslationBoost(transVol);
            try { mTranslationPlayer.setPlaybackParameters(mPlayer.getPlaybackParameters()); } catch (Exception e) {}
            mIsDucked = true;
            mLastSpeechMs = System.currentTimeMillis();
            if (isVideoPlaying()) {
                mTranslationPlayer.setPlayWhenReady(true);
            } else if (!mPlayer.getPlayWhenReady()) {
                mTranslationPlayer.setPlayWhenReady(false);
            } else {
                mTranslationPlayer.setPlayWhenReady(false);
            }
            startAdaptiveDucking(origVol * mLastUserVolume, transVol * mLastUserVolume);
            mTransListener = new Player.Listener() {
                @Override public void onPlaybackStateChanged(int playbackState) {
                    if (playbackState == Player.STATE_READY) {
                        long vPos = mPlayer != null ? mPlayer.getCurrentPosition() : -1;
                        long aPos = mTranslationPlayer != null ? mTranslationPlayer.getCurrentPosition() : -1;
                        Log.e("VOT_SYNC", "trans READY vPos=" + vPos + " aPos=" + aPos + " diff=" + (vPos - aPos));
                    }
                }
            };
            try { mTranslationPlayer.addListener(mTransListener); } catch (Exception e) {}
            startPeriodicSync();
        } catch (Exception e) { Log.e("VOT_VOL", "attach failed", e); releaseTranslationAudioPlayer(); }
    }
    private void releaseTranslationAudioPlayer() {
        stopAdaptiveDucking();
        stopPeriodicSync();
        if (mSeekHandler != null && mSeekRunnable != null) mSeekHandler.removeCallbacks(mSeekRunnable);
        mSeekRunnable = null; mPendingSeekPos = -1; mSeekInProgress = false;
        if (mTranslationBooster != null) {
            try { if (mTranslationPlayer != null) mTranslationPlayer.removeAnalyticsListener(mTranslationBooster); } catch (Exception e) {}
            try { mTranslationBooster.release(); } catch (Exception e) {}
            mTranslationBooster = null;
        }
        mAppliedTransBoost = -1f;
        if (mTranslationPlayer != null) {
            try { if (mTransListener != null) mTranslationPlayer.removeListener(mTransListener); } catch (Exception e) {}
            try { mTranslationPlayer.stop(); mTranslationPlayer.release(); } catch (Exception e) {}
            mTranslationPlayer = null;
        }
        mTransListener = null;
        mTranslationOverlayActive = false;
        mIsDucked = false;
        if (sActiveVotInstance != null && sActiveVotInstance.get() == this) sActiveVotInstance = null;
        if (mPlayer != null) {
            try { mPlayer.setVolume(mLastUserVolume); } catch (Exception e) {}
        }
    }
    // Applies >100% gain (LoudnessEnhancer) to the translation player when the
    // user sets translation volume above 100%. ExoPlayer volume itself caps at 1.0.
    private void applyTranslationBoost(float transVol) {
        if (mTranslationPlayer == null) return;
        if (mAppliedTransBoost == transVol) return;
        mAppliedTransBoost = transVol;
        if (mTranslationBooster != null) {
            try { mTranslationPlayer.removeAnalyticsListener(mTranslationBooster); } catch (Exception e) {}
            try { mTranslationBooster.release(); } catch (Exception e) {}
            mTranslationBooster = null;
        }
        if (transVol > 1f) {
            mTranslationBooster = new VolumeBooster(true, transVol, mTranslationPlayer);
            try {
                mTranslationPlayer.addAnalyticsListener(mTranslationBooster);
                int sessionId = mTranslationPlayer.getAudioSessionId();
                if (sessionId != 0 && sessionId != -1) mTranslationBooster.boostSession(sessionId);
            } catch (Exception e) { mTranslationBooster = null; }
        }
    }
    private void startAdaptiveDucking(float duckedOrigVol, float transVol) {
        stopAdaptiveDucking();
        try {
            int sessionId = mTranslationPlayer != null ? mTranslationPlayer.getAudioSessionId() : 0;
            if (sessionId == 0 || sessionId == -1) {
                return;
            }
            mTranslationVisualizer = new android.media.audiofx.Visualizer(sessionId);
            mTranslationVisualizer.setCaptureSize(android.media.audiofx.Visualizer.getCaptureSizeRange()[1]);
            mTranslationVisualizer.setDataCaptureListener(new android.media.audiofx.Visualizer.OnDataCaptureListener() {
                @Override public void onWaveFormDataCapture(android.media.audiofx.Visualizer v, byte[] waveform, int sr) {
                    if (mTranslationPlayer == null || !mTranslationOverlayActive || mPlayer == null) return;
                    try {
                        long sum = 0;
                        for (byte b : waveform) { int d = (b & 0xFF) - 128; sum += d * d; }
                        int rms = (int) Math.sqrt(sum / (double) waveform.length);
                        boolean hasSpeech = rms > DUCK_RMS_THRESHOLD;
                        long now = System.currentTimeMillis();
                        if (hasSpeech) mLastSpeechMs = now;
                        boolean shouldDuck = hasSpeech || (now - mLastSpeechMs) < DUCK_RELEASE_MS;
                        float ducked = mVotOrigVol * mLastUserVolume;
                        if (shouldDuck && !mIsDucked) {
                            mPlayer.setVolume(ducked);
                            mIsDucked = true;
                        } else if (!shouldDuck && mIsDucked) {
                            mPlayer.setVolume(mLastUserVolume);
                            mIsDucked = false;
                        }
                    } catch (Exception e) { Log.e("VOT_VOL", "duck capture fail", e); }
                }
                @Override public void onFftDataCapture(android.media.audiofx.Visualizer v, byte[] fft, int sr) {}
            }, android.media.audiofx.Visualizer.getMaxCaptureRate() / 2, true, false);
            mTranslationVisualizer.setEnabled(true);
        } catch (Exception e) {
            mTranslationVisualizer = null;
            return;
        }
    }
    private void stopAdaptiveDucking() {
        if (mDuckHandler != null && mDuckRunnable != null) mDuckHandler.removeCallbacks(mDuckRunnable);
        mDuckRunnable = null;
        if (mTranslationVisualizer != null) {
            try { mTranslationVisualizer.setDataCaptureListener(null, 0, false, false); } catch (Exception e) {}
            try { mTranslationVisualizer.setEnabled(false); mTranslationVisualizer.release(); } catch (Exception e) {}
            mTranslationVisualizer = null;
        }
    }
    private boolean isVideoPlaying() {
        return ExoUtils.isPlaying(mPlayer);
    }
    private void lipSync(String mode) {
        if (mTranslationPlayer == null || !mTranslationOverlayActive || mPlayer == null) return;
        try {
            long vPos = mPlayer.getCurrentPosition();
            long aPos = mTranslationPlayer.getCurrentPosition();
            PlaybackParameters vp = mPlayer.getPlaybackParameters();
            // rate всегда синхроним как vot
            try { if (vp != null) mTranslationPlayer.setPlaybackParameters(vp); } catch (Exception ignored) {}
            if (mode == null) return;
            switch (mode) {
                case "playing":
                case "play":
                    if (isVideoPlaying()) {
                        long vPos2 = mPlayer.getCurrentPosition();
                        long aPos2 = mTranslationPlayer.getCurrentPosition();
                        if (Math.abs(vPos2 - aPos2) > 1500) {
                            try { mTranslationPlayer.seekTo(Math.max(0, vPos2)); } catch (Exception e) {}
                        }
                        mTranslationPlayer.setPlayWhenReady(true);
                    } else if (!mPlayer.getPlayWhenReady()) {
                        mTranslationPlayer.setPlayWhenReady(false);
                    }
                    break;
                case "seeked":
                    scheduleTranslationSeek(vPos);
                    break;
                case "pause":
                case "waiting":
                case "ended":
                    mTranslationPlayer.setPlayWhenReady(false);
                    break;
                case "ratechange":
                    break;
                default: break;
            }
        } catch (Exception e) { Log.e("VOT_SYNC", "lipSync fail mode=" + mode, e); }
    }
    private void scheduleTranslationSeek(long vPos) {
        mPendingSeekPos = Math.max(0, vPos);
        if (mSeekRunnable != null) mSeekHandler.removeCallbacks(mSeekRunnable);
        mSeekInProgress = true;
        mSeekRunnable = () -> {
            mSeekRunnable = null;
            try {
                long t = Math.max(0, mPlayer != null ? mPlayer.getCurrentPosition() : mPendingSeekPos);
                boolean mirrorPlay = isVideoPlaying() || mPlayer.getPlayWhenReady();
                if (mTranslationPlayer != null) {
                    try { mTranslationPlayer.setPlaybackParameters(mPlayer.getPlaybackParameters()); } catch (Exception e) {}
                    mTranslationPlayer.seekTo(t);
                    mTranslationPlayer.setPlayWhenReady(mirrorPlay);
                }
            } catch (Exception e) { Log.e("VOT_SYNC", "schedule seek fail", e); }
            finally {
                mSeekInProgress = false;
            }
        };
        mSeekHandler.postDelayed(mSeekRunnable, 60);
    }
    private void startPeriodicSync() {
        stopPeriodicSync();
        mSyncRunnable = new Runnable() {
            @Override public void run() {
                if (mTranslationOverlayActive && mTranslationPlayer != null && mPlayer != null && !mSeekInProgress && isVideoPlaying()) {
                    long vPos = mPlayer.getCurrentPosition();
                    long aPos = mTranslationPlayer.getCurrentPosition();
                    long diff = vPos - aPos;
                    long adiff = Math.abs(diff);
                    if (adiff > 300) {
                        try { mTranslationPlayer.seekTo(Math.max(0, vPos)); } catch (Exception e) {}
                    }
                }
                mSyncHandler.postDelayed(this, 600);
            }
        };
        mSyncHandler.postDelayed(mSyncRunnable, 600);
    }
    private void stopPeriodicSync() {
        if (mSyncHandler != null && mSyncRunnable != null) mSyncHandler.removeCallbacks(mSyncRunnable);
        mSyncRunnable = null;
    }
    public void updateVotVolumes() {
        if (!mTranslationOverlayActive || mPlayer == null) return;
        try {
            float origVol = 0.10f;
            try { origVol = com.liskovsoft.smartyoutubetv2.common.vot.VotSettings.instance(mContext).getOriginalVolumePercent() / 100.0f; } catch (Exception e) {}
            float transVol = 1.0f;
            try { transVol = com.liskovsoft.smartyoutubetv2.common.vot.VotSettings.instance(mContext).getTranslationVolumePercent() / 100.0f; } catch (Exception e) {}
            mVotOrigVol = origVol;
            mVotTransVol = transVol;
            boolean shouldDuck = mIsDucked || (System.currentTimeMillis() - mLastSpeechMs) < DUCK_RELEASE_MS;
            float origApplied = shouldDuck ? origVol * mLastUserVolume : mLastUserVolume;
            if (mTranslationVisualizer == null) {
                mPlayer.setVolume(origApplied);
            } else {
                if (mIsDucked) mPlayer.setVolume(origVol * mLastUserVolume);
            }
            float tVol = Math.min(1.0f, transVol * mLastUserVolume);
            if (mTranslationPlayer != null) {
                mTranslationPlayer.setVolume(tVol);
                applyTranslationBoost(transVol);
            }
        } catch (Exception e) { Log.e("VOT_VOL", "updateVotVolumes fail", e); }
    }
    public static void updateActiveVotVolumes(Context ctx) {
        try {
            ExoPlayerController inst = sActiveVotInstance != null ? sActiveVotInstance.get() : null;
            if (inst != null) inst.updateVotVolumes();
        } catch (Exception e) {}
    }
    private void syncTranslationPlayWhenReady() {
        if (mTranslationPlayer != null && mTranslationOverlayActive && mPlayer != null) {
            if (mPlayer.getPlayWhenReady() && isVideoPlaying()) lipSync("play");
            else if (!mPlayer.getPlayWhenReady()) lipSync("pause");
            else lipSync(null);
        }
    }

    public void openMerged(InputStream dashManifest, String hlsPlaylistUrl) {
        MediaSource dashMediaSource = mMediaSourceFactory.fromDashManifest(dashManifest);
        MediaSource hlsMediaSource = mMediaSourceFactory.fromHlsPlaylist(hlsPlaylistUrl);
        openMediaSource(new MergingMediaSource(dashMediaSource, hlsMediaSource));
    }

    private void openMediaSource(MediaSource mediaSource) {
        // ensure previous translation overlay doesn't leak to next video
        if (mTranslationOverlayActive) {
            releaseTranslationAudioPlayer();
        }
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
        if (mPlayer != null && positionMs >= 0) {
            long dur = getDurationMs();
            if (dur == -1 || positionMs <= dur) {
                mPlayer.seekTo(positionMs);
            } else {
                // clamp if exceed
                mPlayer.seekTo(dur);
            }
        }
        if (mTranslationPlayer != null && mTranslationOverlayActive && positionMs >= 0) {
            try {
                mTranslationPlayer.seekTo(positionMs);
            } catch (Exception e) { Log.e("VOT_SYNC", "seek fail", e); }
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
        lipSync(play ? "play" : "pause");
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

        if (mContext != null && trackSelector != null && PlayerTweaksData.instance(mContext).isTunneledPlaybackEnabled()) {
            // Media3 handles tunneling automatically when enabled and supported by the device/format.
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                trackSelector.setParameters(trackSelector.buildUponParameters().setTunnelingEnabled(true));
            }
        }
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

        // --- VOT lipSync mirror ---
        if (isPlayPressed) lipSync("playing");
        else if (isPausePressed) lipSync("pause");
        else if (isPlaybackEnded) lipSync("ended");
        else if (isBuffering) lipSync("waiting");

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
        // NOTE(media3): end-of-video is already handled via STATE_ENDED above.
        // The old exoplayer 'auto transition -> stop' hack causes an endless
        // restart loop on media3, so it's removed here.
        if (reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
            lipSync("seeked");
        }
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            // Replaces deprecated onSeekProcessed
            mEventListener.onSeekEnd();
        }
    }

    @Override
    public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {
        lipSync("ratechange");
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
            mLastUserVolume = Math.min(volume, 1f);
            if (mTranslationOverlayActive) {
                float origVol = 0.10f;
                try { origVol = com.liskovsoft.smartyoutubetv2.common.vot.VotSettings.instance(mContext).getOriginalVolumePercent() / 100.0f; } catch (Exception e) {}
                float transVol = 1.0f;
                try { transVol = com.liskovsoft.smartyoutubetv2.common.vot.VotSettings.instance(mContext).getTranslationVolumePercent() / 100.0f; } catch (Exception e) {}
                mPlayer.setVolume(origVol * mLastUserVolume);
                if (mTranslationPlayer != null) mTranslationPlayer.setVolume(transVol * mLastUserVolume);
                Log.e("VOT_VOL", "setVolume ducked orig=" + (origVol*mLastUserVolume) + " trans=" + (transVol*mLastUserVolume) + " user=" + mLastUserVolume);
            } else {
                mPlayer.setVolume(mLastUserVolume);
            }
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
