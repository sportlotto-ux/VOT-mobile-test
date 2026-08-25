package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerUI;
import com.liskovsoft.smartyoutubetv2.common.vot.VotApiServiceImpl;
import com.liskovsoft.smartyoutubetv2.common.vot.VotSettings;
import com.liskovsoft.smartyoutubetv2.common.vot.VotTranslateResult;

public class VoiceOverTranslationController extends BasePlayerController {
    private String mTranslationEnabledForVideoId;
    private String mTranslationAudioUrl;
    private volatile boolean mRequestInProgress;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onNewVideo(Video item) {
        // keep translation state per video, reset UI if video changed
        updateUiState();
    }

    @Override
    public void onVideoLoaded(Video item) {
        updateUiState();
    }

    public void requestTranslation() {
        Video video = getVideo();
        Context ctx = getContext();
        Log.e("VOT_UI", "requestTranslation video=" + (video != null ? video.videoId : "null"));
        if (video == null || video.videoId == null || video.videoId.isEmpty()) {
            if (ctx != null) MessageHelpers.showMessage(ctx, R.string.voice_over_translate_no_video);
            return;
        }
        if (video.isLive) {
            if (ctx != null) MessageHelpers.showMessage(ctx, R.string.voice_over_translate_no_live);
            return;
        }
        if (mRequestInProgress) {
            Log.e("VOT_UI", "already in progress, ignore");
            return;
        }

        // toggle off if already enabled for this video
        if (video.videoId.equals(mTranslationEnabledForVideoId)) {
            VideoLoaderController loader = getController(VideoLoaderController.class);
            if (loader != null) loader.reopenWithoutTranslationAudio();
            mTranslationEnabledForVideoId = null;
            mTranslationAudioUrl = null;
            updateUiState();
            if (ctx != null) MessageHelpers.showMessage(ctx, R.string.voice_over_translate_disabled);
            return;
        }

        VideoLoaderController loader = getController(VideoLoaderController.class);
        MediaItemFormatInfo formatInfo = loader != null ? loader.getLastFormatInfo() : null;
        if (loader != null && !loader.canReopenWithTranslation()) {
            if (ctx != null) MessageHelpers.showMessage(ctx, R.string.voice_over_translate_not_available);
            return;
        }

        mRequestInProgress = true;
        updateUiState();
        if (ctx != null) MessageHelpers.showMessage(ctx, R.string.voice_over_translate_in_progress);

        String videoUrl = "https://www.youtube.com/watch?v=" + video.videoId;
        String title = video.getTitle() != null ? video.getTitle() : "";
        int durationSec = video.getDurationMs() > 0 ? (int)(video.getDurationMs()/1000) : 300;

        new Thread(() -> {
            try {
                Context c = getContext();
                if (c == null) return;
                VotApiServiceImpl service = new VotApiServiceImpl(c);
                String requestLang = "auto";
                Log.e("VOT_UI", "calling translate url=" + videoUrl);
                VotTranslateResult result = service.translate(videoUrl, title, durationSec, requestLang, "ru", formatInfo, video.videoId, progress -> {
                    Log.e("VOT_UI", "progress " + progress);
                    mHandler.post(() -> {
                    });
                });
                Log.e("VOT_UI", "translate result ready=" + (result != null ? result.isReady() : "null") + " url=" + (result != null ? result.url : "null") + " status=" + (result != null ? result.status : "null") + " msg=" + (result != null ? result.message : "null") + " debug=" + (result != null ? result.debug : "null"));
                mHandler.post(() -> {
                    mRequestInProgress = false;
                    if (result != null && result.isReady() && result.url != null) {
                        String proxied = VotSettings.instance(c).proxifyAudioUrl(result.url);
                        Log.e("VOT_UI", "reopenWithTranslationAudio proxied=" + proxied);
                        boolean ok = false;
                        if (loader != null) ok = loader.reopenWithTranslationAudio(proxied);
                        Log.e("VOT_UI", "reopen ok=" + ok);
                        if (ok) {
                            mTranslationEnabledForVideoId = video.videoId;
                            mTranslationAudioUrl = proxied;
                            MessageHelpers.showMessage(c, R.string.voice_over_translate_enabled);
                        } else {
                            MessageHelpers.showMessage(c, R.string.voice_over_translate_error);
                        }
                    } else {
                        String msg = result != null && result.message != null ? result.message : c.getString(R.string.voice_over_translate_error);
                        Log.e("VOT_UI", "translate failed msg=" + msg);
                        MessageHelpers.showMessage(c, msg);
                    }
                    updateUiState();
                });
            } catch (Exception e) {
                Log.e("VOT_UI", "translate exception", e);
                mHandler.post(() -> {
                    mRequestInProgress = false;
                    updateUiState();
                    Context cc = getContext();
                    if (cc != null) MessageHelpers.showMessage(cc, R.string.voice_over_translate_error);
                });
            }
        }).start();
    }

    public boolean isTranslationEnabledForCurrentVideo() {
        Video v = getVideo();
        return v != null && v.videoId != null && v.videoId.equals(mTranslationEnabledForVideoId);
    }

    private void updateUiState() {
        if (getPlayer() == null) return;
        boolean enabled = isTranslationEnabledForCurrentVideo();
        int state;
        if (enabled) state = PlayerUI.BUTTON_ON;
        else if (mRequestInProgress) state = PlayerUI.BUTTON_WAITING;
        else state = PlayerUI.BUTTON_OFF;
        getPlayer().setButtonState(R.id.action_voice_over_translate, state);
        if (mRequestInProgress) {
            getPlayer().setVoiceOverTranslateStatus(getContext() != null ? getContext().getString(R.string.voice_over_translate_in_progress) : null);
        } else {
            getPlayer().setVoiceOverTranslateStatus(null);
        }
    }
}
