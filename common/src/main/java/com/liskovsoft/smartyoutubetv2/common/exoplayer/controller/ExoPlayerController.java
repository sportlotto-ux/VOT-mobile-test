package com.liskovsoft.smartyoutubetv2.common.exoplayer.controller;

import android.content.Context;
import android.os.Build;
import android.os.Build.VERSION;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.MergingMediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.BuildConfig;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.PlayerEventListener;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.ExoMediaSourceFactory;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.errors.TrackErrorFixer;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.VolumeBooster;
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

public class ExoPlayerController implements Player.EventListener {
    private static final String TAG = ExoPlayerController.class.getSimpleName();
    private final Context mContext;
    private final ExoMediaSourceFactory mMediaSourceFactory;
    private final TrackSelectorManager mTrackSelectorManager;
    private final TrackInfoFormatter2 mTrackFormatter;
    private final TrackErrorFixer mTrackErrorFixer;
    private boolean mOnSourceChanged;
    private WeakReference<Video> mVideo;
    private final PlayerEventListener mEventListener;
    private SimpleExoPlayer mPlayer;
    private PlayerView mPlayerView;
    private VolumeBooster mVolumeBooster;
    private boolean mIsEnded;
    private Runnable mOnVideoLoaded;
    private com.google.android.exoplayer2.SimpleExoPlayer mTranslationPlayer;
    private boolean mTranslationOverlayActive;
    private float mLastUserVolume = 1.0f;
    private float mVotOrigVol = 0.10f;
    private float mVotTransVol = 1.0f;
    private static java.lang.ref.WeakReference<ExoPlayerController> sActiveVotInstance;
    // adaptive ducking с мгновенным attack для поддержания разницы (vot 1.11.0 smart ducking но без задержки)
    private android.media.audiofx.Visualizer mTranslationVisualizer;
    private final android.os.Handler mDuckHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable mDuckRunnable;
    private long mLastSpeechMs = 0;
    private boolean mIsDucked = false;
    private static final int DUCK_RMS_THRESHOLD = 800;
    private static final long DUCK_RELEASE_MS = 600;
    private static final long DUCK_POLL_MS = 50;
    // точная синхронизация как vot.user.js + debounced seek для быстрого скроба
    private final android.os.Handler mSyncHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable mSyncRunnable;
    private final android.os.Handler mSeekHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable mSeekRunnable;
    private long mPendingSeekPos = -1;
    private boolean mSeekInProgress = false;
    private Player.EventListener mTransListener;

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
            // если адаптив выключен (нет visualizer) — сразу применяем фиксированный duck
            float origApplied = shouldDuck ? origVol * mLastUserVolume : mLastUserVolume;
            // если visualizer активен, его callback сам переключит, но обновляем базовый уровень для следующего ON
            if (mTranslationVisualizer == null) {
                mPlayer.setVolume(origApplied);
            } else {
                // при живом visualizer: если сейчас ducked — обновить громкость даккинга, иначе оставить userVol
                if (mIsDucked) mPlayer.setVolume(origVol * mLastUserVolume);
            }
            // translation по настройке (до 200% — пока cap 1.0, буст через LoudnessEnhancer потом по необходимости)
            float tVol = Math.min(1.0f, transVol * mLastUserVolume);
            if (mTranslationPlayer != null) mTranslationPlayer.setVolume(tVol);
            android.util.Log.e("VOT_VOL", "updateVotVolumes orig=" + origVol + " trans=" + transVol + " appliedOrig=" + origApplied + " ducked=" + mIsDucked);
        } catch (Exception e) { android.util.Log.e("VOT_VOL", "updateVotVolumes fail", e); }
    }
    public static void updateActiveVotVolumes(Context ctx) {
        try {
            ExoPlayerController inst = sActiveVotInstance != null ? sActiveVotInstance.get() : null;
            if (inst != null) inst.updateVotVolumes();
        } catch (Exception e) {}
    }
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
        com.google.android.exoplayer2.source.MediaSource videoSource = mMediaSourceFactory.fromSabrFormatInfo(formatInfo);
        com.google.android.exoplayer2.source.MediaSource audioSource = mMediaSourceFactory.fromUrlList(java.util.Collections.singletonList(translationUrl));
        openMediaSourceWithTranslation(videoSource, audioSource, pos);
    }
    public void openDashWithTranslationAudio(com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo formatInfo, String translationUrl) {
        long pos = getPositionMs();
        com.google.android.exoplayer2.source.MediaSource videoSource = mMediaSourceFactory.fromDashFormatInfo(formatInfo);
        com.google.android.exoplayer2.source.MediaSource audioSource = mMediaSourceFactory.fromUrlList(java.util.Collections.singletonList(translationUrl));
        openMediaSourceWithTranslation(videoSource, audioSource, pos);
    }
    public void attachTranslationAudio(String url) {
        long pos = getPositionMs();
        com.google.android.exoplayer2.source.MediaSource audioSource = mMediaSourceFactory.fromUrlList(java.util.Collections.singletonList(url));
        attachTranslationAudioOverlay(audioSource, pos);
    }
    public void clearTranslationAudio() {
        releaseTranslationAudioPlayer();
    }
    private void openMediaSourceWithTranslation(com.google.android.exoplayer2.source.MediaSource videoSource, com.google.android.exoplayer2.source.MediaSource audioSource, long pos) {
        // For minimal, use second player overlay instead of MergingMediaSource to avoid rebuffer
        openMediaSource(videoSource);
        if (pos >= 0) setPositionMs(pos);
        attachTranslationAudioOverlay(audioSource, pos);
    }
    private void attachTranslationAudioOverlay(com.google.android.exoplayer2.source.MediaSource audioSource, long pos) {
        releaseTranslationAudioPlayer();
        if (audioSource == null || mPlayer == null) return;
        try {
            mTranslationPlayer = com.google.android.exoplayer2.ExoPlayerFactory.newSimpleInstance(mContext, new com.google.android.exoplayer2.DefaultRenderersFactory(mContext), new com.google.android.exoplayer2.trackselection.DefaultTrackSelector());
            mTranslationOverlayActive = true;
            mLastUserVolume = mPlayer.getVolume();
            // duck original, keep translation at user volume + регулятор
            float origVol = 0.10f;
            try { origVol = com.liskovsoft.smartyoutubetv2.common.vot.VotSettings.instance(mContext).getOriginalVolumePercent() / 100.0f; } catch (Exception e) {}
            float transVol = 1.0f;
            try { transVol = com.liskovsoft.smartyoutubetv2.common.vot.VotSettings.instance(mContext).getTranslationVolumePercent() / 100.0f; } catch (Exception e) {}
            mVotOrigVol = origVol; mVotTransVol = transVol; sActiveVotInstance = new java.lang.ref.WeakReference<>(this);
            android.util.Log.e("VOT_VOL", "attach orig=" + origVol + " trans=" + transVol + " user=" + mLastUserVolume + " -> mPlayer=" + (origVol*mLastUserVolume) + " mTrans=" + (Math.min(1.0f, transVol*mLastUserVolume)));
            mPlayer.setVolume(origVol * mLastUserVolume);
            mTranslationPlayer.setVolume(Math.min(1.0f, transVol * mLastUserVolume));
            mTranslationPlayer.prepare(audioSource);
            mTranslationPlayer.seekTo(Math.max(0, pos));
            try { mTranslationPlayer.setPlaybackParameters(mPlayer.getPlaybackParameters()); } catch (Exception e) {}
            // vot.js: только события видео, без периодики - lipSync сам правит
            // фиксированная разница сразу (решает опоздание адаптива к началу фразы) + адаптивная поддержка разницы
            float origVolFixed = origVol * mLastUserVolume;
            float transVolFixed = transVol * mLastUserVolume;
            // уже сделали duck выше, теперь ставим флаг и запускаем измеритель уровня
            mIsDucked = true;
            mLastSpeechMs = System.currentTimeMillis();
            if (isVideoPlaying()) {
                mTranslationPlayer.setPlayWhenReady(true);
                android.util.Log.e("VOT_SYNC", "attach playing vPos=" + pos + " duck orig=" + origVolFixed + " trans=" + transVolFixed);
            } else if (!mPlayer.getPlayWhenReady()) {
                mTranslationPlayer.setPlayWhenReady(false);
                android.util.Log.e("VOT_SYNC", "attach paused vPos=" + pos);
            } else {
                mTranslationPlayer.setPlayWhenReady(false);
                android.util.Log.e("VOT_SYNC", "attach waiting vPos=" + pos);
            }
            startAdaptiveDucking(origVolFixed, transVolFixed);
            // listener для точного seek: ждём READY после seek, потом resume если видео играет
            mTransListener = new Player.EventListener() {
                @Override public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                    if (playbackState == Player.STATE_READY) {
                        long vPos = mPlayer != null ? mPlayer.getCurrentPosition() : -1;
                        long aPos = mTranslationPlayer != null ? mTranslationPlayer.getCurrentPosition() : -1;
                        // только диагностика: позицию диктует метка видео (seeked/periodic)
                        android.util.Log.e("VOT_SYNC", "trans READY vPos=" + vPos + " aPos=" + aPos + " diff=" + (vPos - aPos));
                    }
                }
                @Override public void onSeekProcessed() {
                    android.util.Log.e("VOT_SYNC", "trans onSeekProcessed");
                }
            };
            try { mTranslationPlayer.addListener(mTransListener); } catch (Exception e) {}
            startPeriodicSync();
        } catch (Exception e) { android.util.Log.e("VOT_VOL", "attach failed", e); releaseTranslationAudioPlayer(); }
    }
    private void releaseTranslationAudioPlayer() {
        stopAdaptiveDucking();
        stopPeriodicSync();
        if (mSeekHandler != null && mSeekRunnable != null) mSeekHandler.removeCallbacks(mSeekRunnable);
        mSeekRunnable = null; mPendingSeekPos = -1; mSeekInProgress = false;
        if (mTranslationPlayer != null) {
            try { if (mTransListener != null) mTranslationPlayer.removeListener(mTransListener); } catch (Exception e) {}
            try { mTranslationPlayer.stop(true); mTranslationPlayer.release(); } catch (Exception e) {}
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
    private void startAdaptiveDucking(float duckedOrigVol, float transVol) {
        stopAdaptiveDucking();
        try {
            int sessionId = mTranslationPlayer != null ? mTranslationPlayer.getAudioSessionId() : 0;
            if (sessionId == 0 || sessionId == -1) {
                android.util.Log.e("VOT_VOL", "adaptive duck fallback: no session, keep fixed duck " + duckedOrigVol);
                return;
            }
            mTranslationVisualizer = new android.media.audiofx.Visualizer(sessionId);
            mTranslationVisualizer.setCaptureSize(android.media.audiofx.Visualizer.getCaptureSizeRange()[1]);
            // OnDataCaptureListener избегает getWaveform (не найден в этом SDK) и даёт мгновенный attack
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
                            android.util.Log.e("VOT_VOL", "adaptive duck ON rms=" + rms + " orig=" + ducked);
                        } else if (!shouldDuck && mIsDucked) {
                            mPlayer.setVolume(mLastUserVolume);
                            mIsDucked = false;
                            android.util.Log.e("VOT_VOL", "adaptive duck OFF rms=" + rms + " orig=" + mLastUserVolume);
                        }
                    } catch (Exception e) { android.util.Log.e("VOT_VOL", "duck capture fail", e); }
                }
                @Override public void onFftDataCapture(android.media.audiofx.Visualizer v, byte[] fft, int sr) {}
            }, android.media.audiofx.Visualizer.getMaxCaptureRate() / 2, true, false);
            mTranslationVisualizer.setEnabled(true);
        } catch (Exception e) {
            android.util.Log.e("VOT_VOL", "Visualizer init failed, keep fixed duck", e);
            mTranslationVisualizer = null;
            return;
        }
        android.util.Log.e("VOT_VOL", "adaptive duck start threshold=" + DUCK_RMS_THRESHOLD + " release=" + DUCK_RELEASE_MS);
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
    // Правильная синхронизация: vot.js lipSync + debounced seek для быстрого скроба + периодическая проверка дрифта
    private void lipSync(String mode) {
        if (mTranslationPlayer == null || !mTranslationOverlayActive || mPlayer == null) return;
        try {
            long vPos = mPlayer.getCurrentPosition();
            long aPos = mTranslationPlayer.getCurrentPosition();
            PlaybackParameters vp = mPlayer.getPlaybackParameters();
            android.util.Log.e("VOT_SYNC", "VERIFY lipSync mode=" + mode + " vPos=" + vPos + " aPos=" + aPos + " diff=" + (vPos-aPos) + " playWhenReady=" + mPlayer.getPlayWhenReady() + " state=" + mPlayer.getPlaybackState() + " isPlaying=" + isVideoPlaying() + " seekInProgress=" + mSeekInProgress);
            // rate всегда синхроним как vot
            try { if (vp != null) mTranslationPlayer.setPlaybackParameters(vp); } catch (Exception ignored) {}
            if (mode == null) return;
            switch (mode) {
                case "playing":
                case "play":
                    if (isVideoPlaying()) {
                        // если видео уехало, пока перевод стоял — подтягиваем к метке видео
                        long vPos2 = mPlayer.getCurrentPosition();
                        long aPos2 = mTranslationPlayer.getCurrentPosition();
                        if (Math.abs(vPos2 - aPos2) > 1500) {
                            android.util.Log.e("VOT_SYNC", "play resync diff=" + (vPos2 - aPos2));
                            try { mTranslationPlayer.seekTo(Math.max(0, vPos2)); } catch (Exception e) {}
                        }
                        mTranslationPlayer.setPlayWhenReady(true);
                    } else if (!mPlayer.getPlayWhenReady()) {
                        mTranslationPlayer.setPlayWhenReady(false);
                    }
                    break;
                case "seeked":
                    // метка видео - единственный источник истины: прыгаем на неё, без pause/resume машинерии
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
        } catch (Exception e) { android.util.Log.e("VOT_SYNC", "lipSync fail mode=" + mode, e); }
    }
    private void scheduleTranslationSeek(long vPos) {
        mPendingSeekPos = Math.max(0, vPos);
        if (mSeekRunnable != null) mSeekHandler.removeCallbacks(mSeekRunnable);
        // дебаунс только схлопывает драг слайдера; НЕ паузим перевод - буферизация аудио сама гейтит,
        // как audio.currentTime= в vot.user.js
        mSeekRunnable = () -> {
            mSeekRunnable = null;
            try {
                // актуальная метка видео в момент коммита, не устаревшая из события
                long t = Math.max(0, mPlayer != null ? mPlayer.getCurrentPosition() : mPendingSeekPos);
                boolean mirrorPlay = isVideoPlaying() || mPlayer.getPlayWhenReady();
                android.util.Log.e("VOT_SYNC", "commitTransSeek target=" + t + " mirrorPlay=" + mirrorPlay);
                if (mTranslationPlayer != null) {
                    try { mTranslationPlayer.setPlaybackParameters(mPlayer.getPlaybackParameters()); } catch (Exception e) {}
                    mTranslationPlayer.seekTo(t);
                    mTranslationPlayer.setPlayWhenReady(mirrorPlay);
                }
            } catch (Exception e) { android.util.Log.e("VOT_SYNC", "schedule seek fail", e); }
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
                        android.util.Log.e("VOT_SYNC", "periodic drift fix diff=" + diff + " vPos=" + vPos + " aPos=" + aPos);
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
    private void syncTranslationPlayWhenReady() {
        if (mTranslationPlayer != null && mTranslationOverlayActive && mPlayer != null) {
            // fallback: delegate to lipSync for correct handling
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
            android.util.Log.e("VOT_SYNC", "openMediaSource clears previous translation overlay");
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
                android.util.Log.e("VOT_SYNC", "seek sync pos=" + positionMs);
                mTranslationPlayer.seekTo(positionMs);
            } catch (Exception e) { android.util.Log.e("VOT_SYNC", "seek fail", e); }
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
        // mirror vot's handleVideoEvent play/pause
        lipSync(play ? "play" : "pause");
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
    
    public void setPlayer(SimpleExoPlayer player) {
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
            // Enable tunneling if supported by the current media and device configuration.
            if (VERSION.SDK_INT >= 21) {
                trackSelector.setParameters(trackSelector.buildUponParameters().setTunnelingAudioSessionId(C.generateAudioSessionIdV21(mContext)));
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
    public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
        Log.d(TAG, "onTracksChanged: start: groups length: " + trackGroups.length);

        if (trackGroups.length == 0) {
            Log.i(TAG, "onTracksChanged: Hmm. Strange. Received empty groups, no selections. Why is this happens only on next/prev videos?");
            return;
        }

        notifyOnVideoLoad();

        for (TrackSelection selection : trackSelections.getAll()) {
            if (selection != null) {
                // EXO: 2.12.1
                //Format format = selection.getSelectedFormat();

                // EXO: 2.13.1
                Format format = selection.getFormat(0);

                mEventListener.onTrackChanged(ExoFormatItem.from(format));

                mTrackFormatter.setFormat(format);
            }
        }
        
        setQualityInfo(mTrackFormatter.getQualityLabel());

        // Manage audio focus. E.g. use Spotify when audio is disabled. (NOT NEEDED!!!)
        //MediaTrack audioTrack = mTrackSelectorManager.getAudioTrack();
        //ExoPlayerInitializer.enableAudioFocus(mPlayer, audioTrack != null && !audioTrack.isEmpty());
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
    public void onPlayerError(ExoPlaybackException error) {
        Log.e(TAG, "onPlayerError: " + error);

        // NOTE: Player is released at this point. So, there is no sense to restore the playback here.

        Throwable nested = error.getCause() != null ? error.getCause() : error;

        mEventListener.onEngineError(error.type, error.rendererIndex, nested);
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
    public void onPositionDiscontinuity(int reason) {
        Log.e(TAG, "onPositionDiscontinuity reason=" + reason);
        if (reason != Player.DISCONTINUITY_REASON_PERIOD_TRANSITION) {
            lipSync("seeked");
        }
        // Fix video loop on 480p with legacy codes enabled
        if (reason == Player.DISCONTINUITY_REASON_PERIOD_TRANSITION) {
            mPlayer.stop();
            mEventListener.onPlayEnd();
        }
    }

    @Override
    public void onSeekProcessed() {
        lipSync("seeked");
        mEventListener.onSeekEnd();
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
            if (mTranslationPlayer != null && mTranslationOverlayActive) {
                try { mTranslationPlayer.setPlaybackParameters(mPlayer.getPlaybackParameters()); android.util.Log.e("VOT_SYNC", "speed sync " + speed); } catch (Exception e) {}
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
            // onPlaybackParametersChanged will sync translation via lipSync
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
                android.util.Log.e("VOT_VOL", "setVolume ducked orig=" + (origVol*mLastUserVolume) + " trans=" + (transVol*mLastUserVolume) + " user=" + mLastUserVolume);
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
            mPlayer.stop(true);
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

    private void applyVolumeBoost(float volume) {
        if (mPlayer == null) {
            return;
        }

        if (mVolumeBooster != null) {
            mPlayer.removeAudioListener(mVolumeBooster);
            mVolumeBooster = null;
        }

        // 5.1 audio cannot be boosted (format isn't supported error)
        // also, other 2.0 tracks in 5.1 group is already too loud. so cancel them too.
        if (volume > 1f && !contains51Audio() && Build.VERSION.SDK_INT >= 19) {
            mVolumeBooster = new VolumeBooster(true, volume, null);
            mPlayer.addAudioListener(mVolumeBooster);
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
            mPlayer.stop(true); // Cause input lags due to high cpu load?
            mPlayer.clearVideoSurface();
            mPlayer.release();
        } catch (ArrayIndexOutOfBoundsException e) { // thrown on stop()
            e.printStackTrace();
        } finally {
            mPlayer = null;
        }
    }
}
