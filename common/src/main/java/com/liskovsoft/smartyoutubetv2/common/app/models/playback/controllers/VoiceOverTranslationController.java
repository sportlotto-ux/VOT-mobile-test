// 
// Decompiled by Procyon v0.6.0
// 

package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import com.liskovsoft.smartyoutubetv2.common.vot.VotApiService;
import com.liskovsoft.smartyoutubetv2.common.vot.VotApiServiceImpl;
import android.app.Activity;
import com.liskovsoft.smartyoutubetv2.common.analytics.AnalyticsEventReporter;
import java.util.Iterator;
import com.liskovsoft.mediaserviceinterfaces.data.MediaSubtitle;
import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.vot.VotTranslateResult;
import android.content.Context;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import android.os.Looper;
import com.liskovsoft.smartyoutubetv2.common.vot.VotSettings;
import android.os.Handler;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;

public class VoiceOverTranslationController extends BasePlayerController
{
    private static final String ANALYTICS_TARGET_LANGUAGE = "ru";
    private static final int DEFAULT_WAIT_LIMIT_MINUTES = 3;
    private static final long ERROR_STATUS_DURATION_MS = 15000L;
    private static final int MAX_RETRY_DELAY_MS = 60000;
    private static final int MAX_UI_RETRIES = 180;
    private static final int MIN_RETRY_DELAY_MS = 5000;
    private static final String REQUEST_LANG_FALLBACK = "auto";
    private static final String RESPONSE_LANG = "ru";
    private static final int RETRY_BUFFER_MS = 2000;
    private static final int STATUS_TICK_MS = 1000;
    private static final String VIDEO_URL_PREFIX = "https://www.youtube.com/watch?v=";
    private String mAnalyticsSourceLanguage;
    private String mAnalyticsTranslationSessionId;
    private long mAnalyticsTranslationStartedAtMs;
    private String mAnalyticsTranslationVideoId;
    private int mDebugRetryCount;
    private String mDebugStatus;
    private String mLastTranslationProgress;
    private final Handler mMainHandler;
    private Runnable mPendingRetryTask;
    private String mPendingStartForVideoId;
    private String mPendingTranslationAudioUrl;
    private volatile boolean mRequestInProgress;
    private volatile int mRequestToken;
    private boolean mRestorePending;
    private VotSettings mSettings;
    private final Runnable mStatusTicker;
    private boolean mStatusTickerRunning;
    private String mTransientStatusText;
    private long mTransientStatusUntilMs;
    private String mTranslationAudioUrl;
    private String mTranslationEnabledForVideoId;
    private volatile long mWaitEndTimeMs;
    private volatile int mWaitLimitMinutes;
    private volatile long mWaitStartTimeMs;
    
    public VoiceOverTranslationController() {
        this.mWaitLimitMinutes = 3;
        this.mMainHandler = new Handler(Looper.getMainLooper());
        this.mStatusTicker = new Runnable() {
            @Override
            public void run() {
                VoiceOverTranslationController.this.mStatusTickerRunning = false;
                VoiceOverTranslationController.this.tryStartPendingRequest();
                if (VoiceOverTranslationController.this.mPendingStartForVideoId != null && !VoiceOverTranslationController.this.mRequestInProgress && !VoiceOverTranslationController.this.isWaiting()) {
                    VoiceOverTranslationController.this.mPendingStartForVideoId = null;
                    VoiceOverTranslationController.this.mPendingTranslationAudioUrl = null;
                    VoiceOverTranslationController.this.mWaitStartTimeMs = 0L;
                    VoiceOverTranslationController.this.mWaitEndTimeMs = 0L;
                    VoiceOverTranslationController.this.mWaitLimitMinutes = 3;
                    final Context context = VoiceOverTranslationController.this.getContext();
                    if (context != null) {
                        MessageHelpers.showMessage(context, R.string.voice_over_translate_not_available);
                    }
                }
                VoiceOverTranslationController.this.updateUiState();
                if (VoiceOverTranslationController.this.shouldShowWaitingStatus()) {
                    VoiceOverTranslationController.this.startStatusTicker();
                }
            }
        };
    }
    
    private void applyResult(final VotTranslateResult votTranslateResult, String s, int n, final VideoLoaderController videoLoaderController, final boolean b, final boolean b2, final int n2) {
        if (n != this.mRequestToken) {
            return;
        }
        final Video video = this.getVideo();
        if (video == null || !s.equals(video.videoId)) {
            this.mRequestInProgress = false;
            this.mWaitEndTimeMs = 0L;
            this.mWaitStartTimeMs = 0L;
            this.mWaitLimitMinutes = 3;
            this.finishTranslationAnalytics(false, "video_changed");
            if (b && this.getPlayer() != null) {
                this.getPlayer().showProgressBar(false);
            }
            this.stopStatusTicker();
            this.setDebugStatus("video_changed");
            this.updateUiState();
            return;
        }
        if (votTranslateResult == null) {
            this.mRequestInProgress = false;
            this.mWaitEndTimeMs = 0L;
            this.mWaitStartTimeMs = 0L;
            this.mWaitLimitMinutes = 3;
            this.finishTranslationAnalytics(false, "null_result");
            if (b && this.getPlayer() != null) {
                this.getPlayer().showProgressBar(false);
            }
            this.stopStatusTicker();
            this.setDebugStatus("null_result");
            this.updateUiState();
            if (!b2 && this.getContext() != null) {
                MessageHelpers.showMessage(this.getContext(), R.string.voice_over_translate_error);
            }
            return;
        }
        this.mLastTranslationProgress = votTranslateResult.debug;
        String debugStatus;
        if (votTranslateResult.debug != null) {
            debugStatus = votTranslateResult.debug;
        }
        else {
            final StringBuilder sb = new StringBuilder("result ");
            sb.append(votTranslateResult.status);
            debugStatus = sb.toString();
        }
        this.setDebugStatus(debugStatus);
        final boolean ready = votTranslateResult.isReady();
        final boolean b3 = true;
        if (ready && votTranslateResult.url != null) {
            this.cancelPendingRetry();
            this.mRequestInProgress = false;
            if (b && this.getPlayer() != null) {
                this.getPlayer().showProgressBar(false);
            }
            final String proxifyAudioUrl = this.proxifyAudioUrl(votTranslateResult.url);
            if (videoLoaderController != null && videoLoaderController.reopenWithTranslationAudio(proxifyAudioUrl)) {
                n = 1;
            }
            else {
                n = 0;
            }
            if (n != 0) {
                this.mWaitEndTimeMs = 0L;
                this.mWaitStartTimeMs = 0L;
                this.mWaitLimitMinutes = 3;
                this.mTranslationEnabledForVideoId = s;
                this.mTranslationAudioUrl = proxifyAudioUrl;
                this.mRestorePending = false;
                this.clearTransientStatus();
                this.applyTranslationVolume();
                this.finishTranslationAnalytics(true, null);
                this.stopStatusTicker();
                final StringBuilder sb2 = new StringBuilder("enabled ");
                String string;
                if (votTranslateResult.translationId != null) {
                    final StringBuilder sb3 = new StringBuilder("id=");
                    sb3.append(votTranslateResult.translationId);
                    string = sb3.toString();
                }
                else {
                    string = "";
                }
                sb2.append(string);
                this.setDebugStatus(sb2.toString());
                this.updateUiState();
                if (this.getContext() != null) {
                    MessageHelpers.showMessage(this.getContext(), R.string.voice_over_translate_enabled);
                }
            }
            else {
                this.mPendingStartForVideoId = s;
                this.mPendingTranslationAudioUrl = proxifyAudioUrl;
                this.mWaitLimitMinutes = this.calculateWaitLimitMinutes(video);
                long mWaitStartTimeMs;
                if (this.mWaitStartTimeMs > 0L) {
                    mWaitStartTimeMs = this.mWaitStartTimeMs;
                }
                else {
                    mWaitStartTimeMs = System.currentTimeMillis();
                }
                this.mWaitStartTimeMs = mWaitStartTimeMs;
                this.mWaitEndTimeMs = System.currentTimeMillis() + this.minutesToMs(this.mWaitLimitMinutes);
                this.setDebugStatus("url_ready loader_reopen_pending");
                this.updateUiState();
                if (!b2 && this.getContext() != null) {
                    MessageHelpers.showMessage(this.getContext(), R.string.voice_over_translate_please_wait);
                }
            }
            return;
        }
        int n3 = 0;
        Label_0560: {
            if (n2 < 180 && videoLoaderController != null && videoLoaderController.canReopenWithTranslation()) {
                n3 = (b3 ? 1 : 0);
                if (votTranslateResult.remainingTime > 0) {
                    break Label_0560;
                }
                if (isWaitingVotStatus(votTranslateResult.status)) {
                    n3 = (b3 ? 1 : 0);
                    break Label_0560;
                }
            }
            n3 = 0;
        }
        if (n3 != 0) {
            this.cancelPendingRetry();
            final int calculateRetryDelayMs = calculateRetryDelayMs(votTranslateResult.remainingTime);
            final long currentTimeMillis = System.currentTimeMillis();
            final long n4 = calculateRetryDelayMs;
            this.mWaitEndTimeMs = currentTimeMillis + n4;
            this.mRequestInProgress = false;
            final VoiceOverTranslationController$$ExternalSyntheticLambda3 mPendingRetryTask = new VoiceOverTranslationController$$ExternalSyntheticLambda3(this, n, s, n2, videoLoaderController, b, b2);
            this.mPendingRetryTask = mPendingRetryTask;
            this.mMainHandler.postDelayed((Runnable)mPendingRetryTask, n4);
            final StringBuilder sb4 = new StringBuilder();
            if (votTranslateResult.debug != null) {
                s = votTranslateResult.debug;
            }
            else {
                s = "waiting";
            }
            sb4.append(s);
            sb4.append(" retry_in=");
            sb4.append(calculateRetryDelayMs / 1000);
            sb4.append("s");
            this.setDebugStatus(sb4.toString());
            this.updateUiState();
            if (!b2 && this.getContext() != null) {
                final Context context = this.getContext();
                String s2;
                if (votTranslateResult.message != null && !votTranslateResult.message.isEmpty()) {
                    s2 = votTranslateResult.message;
                }
                else {
                    s2 = this.getContext().getString(R.string.voice_over_translate_in_progress);
                }
                MessageHelpers.showMessage(context, s2);
            }
            return;
        }
        this.cancelPendingRetry();
        this.mRequestInProgress = false;
        this.mWaitEndTimeMs = 0L;
        this.mWaitStartTimeMs = 0L;
        this.mWaitLimitMinutes = 3;
        if (votTranslateResult.status != null) {
            s = votTranslateResult.status;
        }
        else {
            s = "not_ready";
        }
        this.finishTranslationAnalytics(false, s);
        if (b && this.getPlayer() != null) {
            this.getPlayer().showProgressBar(false);
        }
        this.stopStatusTicker();
        final StringBuilder sb5 = new StringBuilder();
        if (votTranslateResult.debug != null) {
            s = votTranslateResult.debug;
        }
        else {
            s = "failed";
        }
        sb5.append(s);
        sb5.append(" done");
        this.setDebugStatus(sb5.toString());
        if (votTranslateResult.remainingTime <= 0 && !votTranslateResult.isAudioRequested()) {
            this.setTransientStatus(buildTranslationErrorStatus(votTranslateResult.message, votTranslateResult.debug));
        }
        this.updateUiState();
        if (votTranslateResult.remainingTime <= 0 && !votTranslateResult.isAudioRequested()) {
            if (votTranslateResult.message != null && !votTranslateResult.message.isEmpty()) {
                if (!b2 && this.getContext() != null) {
                    MessageHelpers.showMessage(this.getContext(), votTranslateResult.message);
                }
            }
            else if (!b2 && this.getContext() != null) {
                MessageHelpers.showMessage(this.getContext(), R.string.voice_over_translate_error);
            }
        }
        else if (!b2 && this.getContext() != null) {
            MessageHelpers.showMessage(this.getContext(), R.string.voice_over_translate_in_progress);
        }
    }
    
    private void applyTranslationVolume() {
        if (this.getPlayer() == null) {
            return;
        }
        this.getPlayer().setVolume(this.getPlayer().getVolume());
    }
    
    private static String buildTranslationErrorStatus(final String s) {
        return buildTranslationErrorStatus(s, null);
    }
    
    private static String buildTranslationErrorStatus(String str, String str2) {
        if (str != null) {
            str = str.replace('\n', ' ').replace('\r', ' ').trim();
        }
        else {
            str = "";
        }
        if (str.isEmpty()) {
            str = summarizeTranslationStage(str2);
            if (str != null) {
                final StringBuilder sb = new StringBuilder("\u041e\u0448\u0438\u0431\u043a\u0430 \u043f\u0435\u0440\u0435\u0432\u043e\u0434\u0430: ");
                sb.append(str);
                str = sb.toString();
            }
            else {
                str = "\u041e\u0448\u0438\u0431\u043a\u0430 \u043f\u0435\u0440\u0435\u0432\u043e\u0434\u0430";
            }
            return str;
        }
        final String lowerCase = str.toLowerCase();
        if (lowerCase.contains("timeout")) {
            str2 = summarizeTranslationStage(str2);
            if (str2 != null) {
                final StringBuilder sb2 = new StringBuilder("\u041e\u0448\u0438\u0431\u043a\u0430 \u043f\u0435\u0440\u0435\u0432\u043e\u0434\u0430: timeout, ");
                sb2.append(str2);
                str = sb2.toString();
            }
            else {
                str = "\u041e\u0448\u0438\u0431\u043a\u0430 \u043f\u0435\u0440\u0435\u0432\u043e\u0434\u0430: timeout";
            }
            return str;
        }
        if (lowerCase.contains("session_required") || lowerCase.contains("session required")) {
            return "\u041e\u0448\u0438\u0431\u043a\u0430 \u043f\u0435\u0440\u0435\u0432\u043e\u0434\u0430: \u043d\u0443\u0436\u0435\u043d \u043d\u043e\u0432\u044b\u0439 session";
        }
        if (lowerCase.contains("http 400")) {
            str2 = summarizeTranslationStage(str2);
            if (str2 != null) {
                final StringBuilder sb3 = new StringBuilder("\u041e\u0448\u0438\u0431\u043a\u0430 \u043f\u0435\u0440\u0435\u0432\u043e\u0434\u0430: HTTP 400, ");
                sb3.append(str2);
                str = sb3.toString();
            }
            else {
                str = "\u041e\u0448\u0438\u0431\u043a\u0430 \u043f\u0435\u0440\u0435\u0432\u043e\u0434\u0430: HTTP 400";
            }
            return str;
        }
        if (lowerCase.contains("http 401")) {
            return "\u041e\u0448\u0438\u0431\u043a\u0430 \u043f\u0435\u0440\u0435\u0432\u043e\u0434\u0430: HTTP 401";
        }
        if (lowerCase.contains("http 403")) {
            return "\u041e\u0448\u0438\u0431\u043a\u0430 \u043f\u0435\u0440\u0435\u0432\u043e\u0434\u0430: HTTP 403";
        }
        str2 = str;
        if (str.length() > 96) {
            str2 = str.substring(0, 96);
        }
        final StringBuilder sb4 = new StringBuilder("\u041e\u0448\u0438\u0431\u043a\u0430 \u043f\u0435\u0440\u0435\u0432\u043e\u0434\u0430: ");
        sb4.append(str2);
        return sb4.toString();
    }
    
    private String buildWaitingStatusText() {
        final Context context = this.getContext();
        if (context == null) {
            return "";
        }
        int max;
        if (this.mWaitStartTimeMs > 0L) {
            max = Math.max(0, (int)((System.currentTimeMillis() - this.mWaitStartTimeMs) / 1000L));
        }
        else {
            max = 0;
        }
        return context.getString(R.string.voice_over_translate_waiting_elapsed, new Object[] { this.mWaitLimitMinutes, max / 60, max % 60 });
    }
    
    private static int calculateRetryDelayMs(final int n) {
        if (n <= 0) {
            return 5000;
        }
        return (int)Math.max(5000L, Math.min(60000L, n * 1000L + 2000L));
    }
    
    private int calculateWaitLimitMinutes(final Video video) {
        if (video != null && video.getDurationMs() > 0L) {
            final double v = (double)video.getDurationMs();
            Double.isNaN(v);
            return Math.max(1, (int)Math.round(v / 60000.0 / 10.0));
        }
        return 3;
    }
    
    private void cancelPendingRetry() {
        final Runnable mPendingRetryTask = this.mPendingRetryTask;
        if (mPendingRetryTask != null) {
            this.mMainHandler.removeCallbacks(mPendingRetryTask);
            this.mPendingRetryTask = null;
        }
    }
    
    private void clearTransientStatus() {
        this.mTransientStatusText = null;
        this.mTransientStatusUntilMs = 0L;
    }
    
    private void clearTranslationAnalytics() {
        this.mAnalyticsTranslationSessionId = null;
        this.mAnalyticsTranslationStartedAtMs = 0L;
        this.mAnalyticsTranslationVideoId = null;
        this.mAnalyticsSourceLanguage = null;
    }
    
    private String detectSourceLanguage(final MediaItemFormatInfo mediaItemFormatInfo) {
        if (mediaItemFormatInfo == null) {
            return null;
        }
        if (mediaItemFormatInfo.getAdaptiveFormats() != null) {
            for (final MediaFormat mediaFormat : mediaItemFormatInfo.getAdaptiveFormats()) {
                if (mediaFormat != null && mediaFormat.getMimeType() != null) {
                    if (!mediaFormat.getMimeType().startsWith("audio")) {
                        continue;
                    }
                    final String normalizeLang = this.normalizeLang(mediaFormat.getLanguage());
                    if (normalizeLang != null) {
                        return normalizeLang;
                    }
                    continue;
                }
            }
        }
        if (mediaItemFormatInfo.getSubtitles() != null) {
            for (final MediaSubtitle mediaSubtitle : mediaItemFormatInfo.getSubtitles()) {
                if (mediaSubtitle == null) {
                    continue;
                }
                final String normalizeLang2 = this.normalizeLang(mediaSubtitle.getLanguageCode());
                if (normalizeLang2 != null) {
                    return normalizeLang2;
                }
            }
        }
        return null;
    }
    
    private void finishTranslationAnalytics(final boolean b, final String s) {
        if (this.mAnalyticsTranslationSessionId != null) {
            if (this.mAnalyticsTranslationVideoId != null) {
                final Video video = this.getVideo();
                Video from = null;
                Label_0059: {
                    if (video != null && video.videoId != null) {
                        from = video;
                        if (this.mAnalyticsTranslationVideoId.equals(video.videoId)) {
                            break Label_0059;
                        }
                    }
                    from = Video.from(this.mAnalyticsTranslationVideoId);
                }
                final long currentTimeMillis = System.currentTimeMillis();
                long mAnalyticsTranslationStartedAtMs = this.mAnalyticsTranslationStartedAtMs;
                if (mAnalyticsTranslationStartedAtMs <= 0L) {
                    mAnalyticsTranslationStartedAtMs = currentTimeMillis;
                }
                final long max = Math.max(0L, currentTimeMillis - mAnalyticsTranslationStartedAtMs);
                final Context context = this.getContext();
                final String mAnalyticsTranslationSessionId = this.mAnalyticsTranslationSessionId;
                final String mAnalyticsSourceLanguage = this.mAnalyticsSourceLanguage;
                String s2;
                if (b) {
                    s2 = "completed";
                }
                else {
                    s2 = "failed";
                }
                AnalyticsEventReporter.reportTranslation(context, from, mAnalyticsTranslationSessionId, mAnalyticsSourceLanguage, "ru", mAnalyticsTranslationStartedAtMs, currentTimeMillis, max, b, s, "end", s2);
                this.clearTranslationAnalytics();
            }
        }
    }
    
    private String getRequestLang(final MediaItemFormatInfo mediaItemFormatInfo) {
        final VotSettings settings = this.getSettings();
        if (settings != null && settings.isUseLivelyVoice()) {
            return "en";
        }
        String detectSourceLanguage = this.detectSourceLanguage(mediaItemFormatInfo);
        if (detectSourceLanguage == null) {
            detectSourceLanguage = "auto";
        }
        return detectSourceLanguage;
    }
    
    private VotSettings getSettings() {
        if (this.mSettings == null) {
            final Context context = this.getContext();
            if (context != null) {
                this.mSettings = VotSettings.instance(context);
            }
        }
        return this.mSettings;
    }
    
    private boolean hasTransientStatus() {
        final String mTransientStatusText = this.mTransientStatusText;
        if (mTransientStatusText == null || mTransientStatusText.isEmpty()) {
            return false;
        }
        if (System.currentTimeMillis() > this.mTransientStatusUntilMs) {
            this.clearTransientStatus();
            return false;
        }
        return true;
    }
    
    private boolean isWaiting() {
        return this.mWaitEndTimeMs > System.currentTimeMillis();
    }
    
    private static boolean isWaitingVotStatus(final String s) {
        boolean b = false;
        if (s == null) {
            return false;
        }
        if ("WAITING".equalsIgnoreCase(s) || "LONG_WAITING".equalsIgnoreCase(s)) {
            b = true;
        }
        return b;
    }
    
    private long minutesToMs(final int b) {
        return Math.max(1, b) * 60000L;
    }
    
    private String normalizeLang(String substring) {
        if (substring == null) {
            return null;
        }
        final String lowerCase = substring.trim().toLowerCase();
        if (lowerCase.isEmpty()) {
            return null;
        }
        int endIndex;
        if ((endIndex = lowerCase.indexOf(45)) == -1) {
            endIndex = lowerCase.indexOf(95);
        }
        substring = lowerCase;
        if (endIndex != -1) {
            substring = lowerCase.substring(0, endIndex);
        }
        return substring;
    }
    
    private static void postToUiThread(final Activity activity, final Runnable runnable) {
        if (activity != null && !activity.isFinishing()) {
            activity.runOnUiThread(runnable);
        }
        else {
            new Handler(Looper.getMainLooper()).post(runnable);
        }
    }
    
    private String proxifyAudioUrl(String s) {
        final VotSettings settings = this.getSettings();
        if (settings == null) {
            return s;
        }
        if (!settings.isUseLivelyVoice()) {
            return s;
        }
        final String proxifyAudioUrl = settings.proxifyAudioUrl(s);
        if (proxifyAudioUrl != null) {
            s = proxifyAudioUrl;
        }
        return s;
    }
    
    private void requestTranslationInternal(final boolean b, final boolean b2) {
        final Video video = this.getVideo();
        final Context context = this.getContext();
        if (video == null) {
            this.setDebugStatus("no_video");
            if (!b2 && context != null) {
                MessageHelpers.showMessage(context, R.string.voice_over_translate_no_video);
            }
            return;
        }
        if (video.isLive) {
            this.setDebugStatus("live_not_supported");
            if (!b2 && context != null) {
                MessageHelpers.showMessage(context, R.string.voice_over_translate_no_live);
            }
            return;
        }
        if (video.videoId == null || video.videoId.isEmpty()) {
            this.setDebugStatus("empty_video_id");
            if (!b2 && context != null) {
                MessageHelpers.showMessage(context, R.string.voice_over_translate_no_video);
            }
            return;
        }
        this.clearTransientStatus();
        final VideoLoaderController videoLoaderController = this.getController(VideoLoaderController.class);
        String detectSourceLanguage;
        if (videoLoaderController != null) {
            detectSourceLanguage = this.detectSourceLanguage(videoLoaderController.getLastFormatInfo());
        }
        else {
            detectSourceLanguage = null;
        }
        if (video.videoId.equals(this.mTranslationEnabledForVideoId) || this.mRequestInProgress || this.isWaiting()) {
            this.setDebugStatus("toggle_off");
            this.turnOffTranslation(videoLoaderController);
            return;
        }
        if (videoLoaderController == null) {
            this.startTranslationAnalytics(video, detectSourceLanguage);
            this.finishTranslationAnalytics(false, "loader_missing");
            this.setDebugStatus("loader_missing");
            if (!b2 && context != null) {
                MessageHelpers.showMessage(context, R.string.voice_over_translate_not_available);
            }
            return;
        }
        if (!videoLoaderController.canReopenWithTranslation()) {
            this.startTranslationAnalytics(video, detectSourceLanguage);
            this.mPendingStartForVideoId = video.videoId;
            this.mPendingTranslationAudioUrl = null;
            this.mWaitLimitMinutes = this.calculateWaitLimitMinutes(video);
            this.mWaitStartTimeMs = System.currentTimeMillis();
            this.mWaitEndTimeMs = this.mWaitStartTimeMs + this.minutesToMs(this.mWaitLimitMinutes);
            this.setDebugStatus("waiting_loader_reopen");
            this.updateUiState();
            if (!b2 && context != null) {
                MessageHelpers.showMessage(context, R.string.voice_over_translate_please_wait);
            }
            return;
        }
        this.getSettings().resetMixToDefaults();
        this.startTranslationAnalytics(video, detectSourceLanguage);
        this.mPendingStartForVideoId = null;
        this.mDebugRetryCount = 0;
        final StringBuilder sb = new StringBuilder("request_start lang=");
        sb.append(this.getRequestLang(videoLoaderController.getLastFormatInfo()));
        this.setDebugStatus(sb.toString());
        this.runTranslateRequest(video, videoLoaderController, b, b2, 0);
    }
    
    private void restorePlayerVolumeIfNeeded() {
    }
    
    private void runTranslateRequest(final Video video, final VideoLoaderController videoLoaderController, final boolean b, final boolean b2, final int mDebugRetryCount) {
        final Context context = this.getContext();
        if (context == null) {
            return;
        }
        final Activity activity = this.getActivity();
        final StringBuilder sb = new StringBuilder("https://www.youtube.com/watch?v=");
        sb.append(video.videoId);
        final String string = sb.toString();
        final long durationMs = video.getDurationMs();
        int n;
        if (durationMs > 0L) {
            n = (int)(durationMs / 1000L);
        }
        else {
            n = 300;
        }
        final String videoId = video.videoId;
        int mRequestToken;
        final int n2 = mRequestToken = this.mRequestToken;
        if (mDebugRetryCount == 0) {
            mRequestToken = n2 + 1;
            this.mRequestToken = mRequestToken;
        }
        final VotApiServiceImpl votApiServiceImpl = new VotApiServiceImpl(context);
        final String[] array = { null };
        this.mPendingStartForVideoId = null;
        this.mPendingTranslationAudioUrl = null;
        this.mRequestInProgress = true;
        if ((this.mDebugRetryCount = mDebugRetryCount) == 0) {
            this.mWaitLimitMinutes = this.calculateWaitLimitMinutes(video);
            this.mWaitStartTimeMs = System.currentTimeMillis();
            this.mWaitEndTimeMs = System.currentTimeMillis() + this.minutesToMs(this.mWaitLimitMinutes);
        }
        else if (this.mWaitStartTimeMs <= 0L) {
            this.mWaitStartTimeMs = System.currentTimeMillis();
        }
        final StringBuilder sb2 = new StringBuilder("poll ");
        sb2.append(mDebugRetryCount + 1);
        sb2.append(" request_sent");
        this.setDebugStatus(sb2.toString());
        this.updateUiState();
        if (b && this.getPlayer() != null) {
            this.getPlayer().showProgressBar(true);
        }
        new Thread(new VoiceOverTranslationController$$ExternalSyntheticLambda4(this, votApiServiceImpl, video, string, n, videoLoaderController, array, activity, videoId, mRequestToken, b, b2, mDebugRetryCount)).start();
    }
    
    private void setDebugStatus(final String mDebugStatus) {
        this.mDebugStatus = mDebugStatus;
    }
    
    private void setTransientStatus(final String mTransientStatusText) {
        this.mTransientStatusText = mTransientStatusText;
        long mTransientStatusUntilMs;
        if (mTransientStatusText != null) {
            mTransientStatusUntilMs = System.currentTimeMillis() + 15000L;
        }
        else {
            mTransientStatusUntilMs = 0L;
        }
        this.mTransientStatusUntilMs = mTransientStatusUntilMs;
    }
    
    private boolean shouldRestoreAfterEngineInit() {
        final Video video = this.getVideo();
        if (video != null && video.videoId != null && video.videoId.equals(this.mTranslationEnabledForVideoId)) {
            final String mTranslationAudioUrl = this.mTranslationAudioUrl;
            if (mTranslationAudioUrl != null && !mTranslationAudioUrl.isEmpty()) {
                return true;
            }
        }
        return false;
    }
    
    private boolean shouldShowWaitingStatus() {
        return this.mWaitStartTimeMs > 0L && (this.mRequestInProgress || this.isWaiting());
    }
    
    private void startStatusTicker() {
        if (this.mStatusTickerRunning) {
            return;
        }
        this.mStatusTickerRunning = true;
        this.mMainHandler.postDelayed(this.mStatusTicker, 1000L);
    }
    
    private void startTranslationAnalytics(final Video video, String mAnalyticsSourceLanguage) {
        if (video != null && video.videoId != null) {
            if (!video.videoId.isEmpty()) {
                if (this.mAnalyticsTranslationSessionId == null || !video.videoId.equals(this.mAnalyticsTranslationVideoId)) {
                    this.mAnalyticsTranslationSessionId = AnalyticsEventReporter.newSessionId();
                    this.mAnalyticsTranslationStartedAtMs = System.currentTimeMillis();
                    this.mAnalyticsTranslationVideoId = video.videoId;
                    if (mAnalyticsSourceLanguage == null) {
                        MediaItemFormatInfo lastFormatInfo;
                        if (this.getController(VideoLoaderController.class) != null) {
                            lastFormatInfo = this.getController(VideoLoaderController.class).getLastFormatInfo();
                        }
                        else {
                            lastFormatInfo = null;
                        }
                        mAnalyticsSourceLanguage = AnalyticsEventReporter.detectVideoLanguage(lastFormatInfo);
                    }
                    this.mAnalyticsSourceLanguage = mAnalyticsSourceLanguage;
                    final Context context = this.getContext();
                    final String mAnalyticsTranslationSessionId = this.mAnalyticsTranslationSessionId;
                    mAnalyticsSourceLanguage = this.mAnalyticsSourceLanguage;
                    final long mAnalyticsTranslationStartedAtMs = this.mAnalyticsTranslationStartedAtMs;
                    AnalyticsEventReporter.reportTranslation(context, video, mAnalyticsTranslationSessionId, mAnalyticsSourceLanguage, "ru", mAnalyticsTranslationStartedAtMs, mAnalyticsTranslationStartedAtMs, 0L, false, null, "start", "started");
                }
            }
        }
    }
    
    private void stopStatusTicker() {
        if (!this.mStatusTickerRunning) {
            return;
        }
        this.mMainHandler.removeCallbacks(this.mStatusTicker);
        this.mStatusTickerRunning = false;
    }
    
    private static String summarizeTranslationStage(final String s) {
        if (s == null) {
            return null;
        }
        final String lowerCase = s.replace('\n', ' ').replace('\r', ' ').trim().toLowerCase();
        if (lowerCase.isEmpty()) {
            return null;
        }
        if (lowerCase.contains("audio_try_failed") || lowerCase.contains("audio_upload_failed")) {
            return "\u0441\u0431\u043e\u0439 \u0437\u0430\u0433\u0440\u0443\u0437\u043a\u0438 \u0438\u0441\u0445\u043e\u0434\u043d\u043e\u0433\u043e \u0430\u0443\u0434\u0438\u043e";
        }
        if (lowerCase.contains("audio_chunk") || lowerCase.contains("audio_stream") || lowerCase.contains("audio_try")) {
            return "\u0442\u0430\u0439\u043c\u0430\u0443\u0442 \u043f\u0440\u0438 \u043e\u0442\u043f\u0440\u0430\u0432\u043a\u0435 \u0438\u0441\u0445\u043e\u0434\u043d\u043e\u0433\u043e \u0430\u0443\u0434\u0438\u043e";
        }
        if (lowerCase.contains("audio_requested")) {
            return "\u0441\u0435\u0440\u0432\u0435\u0440 \u0437\u0430\u043f\u0440\u043e\u0441\u0438\u043b \u0438\u0441\u0445\u043e\u0434\u043d\u043e\u0435 \u0430\u0443\u0434\u0438\u043e";
        }
        if (lowerCase.contains("session_required") || lowerCase.contains("failed_session_required")) {
            return "\u0441\u0435\u0440\u0432\u0435\u0440 \u0441\u0431\u0440\u043e\u0441\u0438\u043b session";
        }
        if (lowerCase.contains("translate_http_error")) {
            return "\u043e\u0448\u0438\u0431\u043a\u0430 API \u043f\u0435\u0440\u0435\u0432\u043e\u0434\u0430";
        }
        if (lowerCase.contains("waiting") || lowerCase.contains("long_waiting")) {
            return "\u0442\u0430\u0439\u043c\u0430\u0443\u0442 \u043e\u0436\u0438\u0434\u0430\u043d\u0438\u044f \u0440\u0435\u0437\u0443\u043b\u044c\u0442\u0430\u0442\u0430";
        }
        if (!lowerCase.contains("request_start") && !lowerCase.contains("request_sent") && !lowerCase.contains("start from=")) {
            String trim = s;
            if (s.length() > 48) {
                trim = s.substring(0, 48).trim();
            }
            return trim;
        }
        return "\u043e\u0448\u0438\u0431\u043a\u0430 \u0437\u0430\u043f\u0443\u0441\u043a\u0430 \u043f\u0435\u0440\u0435\u0432\u043e\u0434\u0430";
    }
    
    private void tryRestoreAfterEngineInit() {
        if (!this.mRestorePending) {
            return;
        }
        final VideoLoaderController videoLoaderController = this.getController(VideoLoaderController.class);
        if (videoLoaderController != null) {
            if (videoLoaderController.canReopenWithTranslation()) {
                this.mRestorePending = false;
                videoLoaderController.reopenWithTranslationAudio(this.mTranslationAudioUrl);
                this.applyTranslationVolume();
            }
        }
    }
    
    private void tryStartPendingRequest() {
        if (this.mPendingStartForVideoId != null && !this.mRequestInProgress) {
            if (!this.isTranslationEnabledForCurrentVideo()) {
                final Video video = this.getVideo();
                if (video != null) {
                    if (video.videoId != null) {
                        if (!video.videoId.equals(this.mPendingStartForVideoId)) {
                            this.mPendingStartForVideoId = null;
                            this.mPendingTranslationAudioUrl = null;
                            return;
                        }
                        final VideoLoaderController videoLoaderController = this.getController(VideoLoaderController.class);
                        if (videoLoaderController != null) {
                            if (videoLoaderController.canReopenWithTranslation()) {
                                final String mPendingTranslationAudioUrl = this.mPendingTranslationAudioUrl;
                                if (mPendingTranslationAudioUrl != null) {
                                    if (videoLoaderController.reopenWithTranslationAudio(mPendingTranslationAudioUrl)) {
                                        this.mTranslationEnabledForVideoId = video.videoId;
                                        this.mTranslationAudioUrl = this.mPendingTranslationAudioUrl;
                                        this.mPendingStartForVideoId = null;
                                        this.mPendingTranslationAudioUrl = null;
                                        this.mWaitStartTimeMs = 0L;
                                        this.mWaitEndTimeMs = 0L;
                                        this.mRestorePending = false;
                                        this.clearTransientStatus();
                                        this.applyTranslationVolume();
                                        this.finishTranslationAnalytics(true, null);
                                        this.stopStatusTicker();
                                        this.setDebugStatus("enabled_from_pending_url");
                                        this.updateUiState();
                                        final Context context = this.getContext();
                                        if (context != null) {
                                            MessageHelpers.showMessage(context, R.string.voice_over_translate_enabled);
                                        }
                                    }
                                    return;
                                }
                                this.mPendingStartForVideoId = null;
                                this.setDebugStatus("pending_request_start");
                                this.runTranslateRequest(video, videoLoaderController, true, true, 0);
                            }
                        }
                    }
                }
            }
        }
    }
    
    private void turnOffTranslation(final VideoLoaderController videoLoaderController) {
        this.cancelPendingRetry();
        ++this.mRequestToken;
        this.mRequestInProgress = false;
        this.mWaitEndTimeMs = 0L;
        this.mWaitStartTimeMs = 0L;
        this.mWaitLimitMinutes = 3;
        this.mRestorePending = false;
        this.mPendingStartForVideoId = null;
        this.mPendingTranslationAudioUrl = null;
        this.clearTransientStatus();
        this.mLastTranslationProgress = null;
        if (this.getPlayer() != null) {
            this.getPlayer().showProgressBar(false);
        }
        if (videoLoaderController != null) {
            videoLoaderController.reopenWithoutTranslationAudio();
        }
        this.restorePlayerVolumeIfNeeded();
        this.mTranslationEnabledForVideoId = null;
        this.mTranslationAudioUrl = null;
        this.finishTranslationAnalytics(false, "cancelled");
        this.clearTranslationAnalytics();
        this.stopStatusTicker();
        this.setDebugStatus(null);
        this.updateUiState();
        final Context context = this.getContext();
        if (context != null) {
            MessageHelpers.showMessage(context, R.string.voice_over_translate_disabled);
        }
    }
    
    private void updateUiState() {
        if (this.getPlayer() == null) {
            this.stopStatusTicker();
            return;
        }
        final boolean translationEnabledForCurrentVideo = this.isTranslationEnabledForCurrentVideo();
        int n;
        if (translationEnabledForCurrentVideo) {
            n = 1;
        }
        else if (this.shouldShowWaitingStatus()) {
            n = 2;
        }
        else {
            n = 0;
        }
        this.getPlayer().setButtonState(R.id.action_voice_over_translate, n);
        if (translationEnabledForCurrentVideo) {
            this.getPlayer().setVoiceOverTranslateStatus((String)null);
            this.stopStatusTicker();
            return;
        }
        if (this.shouldShowWaitingStatus()) {
            this.getPlayer().setVoiceOverTranslateStatus(this.buildWaitingStatusText());
            this.startStatusTicker();
            return;
        }
        if (this.hasTransientStatus()) {
            this.getPlayer().setVoiceOverTranslateStatus(this.mTransientStatusText);
            this.stopStatusTicker();
        }
        else {
            this.getPlayer().setVoiceOverTranslateStatus((String)null);
            this.stopStatusTicker();
        }
    }
    
    public int getRemainingWaitSec() {
        final long mWaitEndTimeMs = this.mWaitEndTimeMs;
        if (mWaitEndTimeMs <= 0L) {
            return 0;
        }
        return Math.max(0, (int)((mWaitEndTimeMs - System.currentTimeMillis()) / 1000L));
    }
    
    public boolean isRequestInProgress() {
        return this.mRequestInProgress;
    }
    
    public boolean isTranslationEnabledForCurrentVideo() {
        final Video video = this.getVideo();
        return video != null && video.videoId != null && video.videoId.equals(this.mTranslationEnabledForVideoId);
    }
    
    @Override
    public void onEngineInitialized() {
        this.mRestorePending = this.shouldRestoreAfterEngineInit();
        this.tryRestoreAfterEngineInit();
        this.tryStartPendingRequest();
        this.updateUiState();
    }
    
    @Override
    public void onEngineReleased() {
        this.restorePlayerVolumeIfNeeded();
    }
    
    @Override
    public void onInit() {
        this.updateUiState();
    }
    
    @Override
    public void onNewVideo(final Video video) {
        this.restorePlayerVolumeIfNeeded();
        this.getSettings().resetMixToDefaults();
        if (this.mAnalyticsTranslationSessionId != null) {
            this.finishTranslationAnalytics(false, "video_changed");
        }
        this.cancelPendingRetry();
        ++this.mRequestToken;
        this.mRequestInProgress = false;
        this.mWaitEndTimeMs = 0L;
        this.mWaitStartTimeMs = 0L;
        this.mWaitLimitMinutes = 3;
        this.mRestorePending = false;
        this.mTranslationEnabledForVideoId = null;
        this.mTranslationAudioUrl = null;
        this.mPendingStartForVideoId = null;
        this.mPendingTranslationAudioUrl = null;
        this.clearTransientStatus();
        this.clearTranslationAnalytics();
        this.stopStatusTicker();
        this.updateUiState();
    }
    
    @Override
    public void onSourceChanged(final Video video) {
        this.tryRestoreAfterEngineInit();
        this.tryStartPendingRequest();
        this.updateUiState();
    }
    
    @Override
    public void onTickle() {
        this.tryStartPendingRequest();
        this.updateUiState();
    }
    
    public void requestTranslation() {
        this.requestTranslationInternal(true, false);
    }
}
