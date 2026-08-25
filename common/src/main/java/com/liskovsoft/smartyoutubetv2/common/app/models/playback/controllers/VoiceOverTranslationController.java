/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  android.app.Activity
 *  android.content.Context
 *  android.os.Handler
 *  android.os.Looper
 *  com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
 *  com.liskovsoft.sharedutils.helpers.MessageHelpers
 */
package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.analytics.AnalyticsEventReporter;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.VideoLoaderController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.VoiceOverTranslationController$$ExternalSyntheticLambda0;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.VoiceOverTranslationController$$ExternalSyntheticLambda1;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.VoiceOverTranslationController$$ExternalSyntheticLambda2;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.VoiceOverTranslationController$$ExternalSyntheticLambda4;
import com.liskovsoft.smartyoutubetv2.common.vot.VotApiService;
import com.liskovsoft.smartyoutubetv2.common.vot.VotApiServiceImpl;
import com.liskovsoft.smartyoutubetv2.common.vot.VotSettings;
import com.liskovsoft.smartyoutubetv2.common.vot.VotTranslateResult;

/*
 * Illegal identifiers - consider using --renameillegalidents true
 */
public class VoiceOverTranslationController
extends BasePlayerController {
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
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private Runnable mPendingRetryTask;
    private String mPendingStartForVideoId;
    private String mPendingTranslationAudioUrl;
    private volatile boolean mRequestInProgress;
    private volatile int mRequestToken;
    private boolean mRestorePending;
    private VotSettings mSettings;
    private final Runnable mStatusTicker = new Runnable(){

        @Override
        public void run() {
            VoiceOverTranslationController.access$002(VoiceOverTranslationController.this, false);
            VoiceOverTranslationController.this.tryStartPendingRequest();
            if (VoiceOverTranslationController.this.mPendingStartForVideoId != null && !VoiceOverTranslationController.this.mRequestInProgress && !VoiceOverTranslationController.this.isWaiting()) {
                VoiceOverTranslationController.access$202(VoiceOverTranslationController.this, null);
                VoiceOverTranslationController.access$502(VoiceOverTranslationController.this, null);
                VoiceOverTranslationController.access$602(VoiceOverTranslationController.this, 0L);
                VoiceOverTranslationController.access$702(VoiceOverTranslationController.this, 0L);
                VoiceOverTranslationController.access$802(VoiceOverTranslationController.this, 3);
                Context context = VoiceOverTranslationController.this.getContext();
                if (context != null) {
                    MessageHelpers.showMessage((Context)context, (int)R.string.voice_over_translate_not_available);
                }
            }
            VoiceOverTranslationController.this.updateUiState();
            if (VoiceOverTranslationController.this.shouldShowWaitingStatus()) {
                VoiceOverTranslationController.this.startStatusTicker();
            }
        }
    };
    private boolean mStatusTickerRunning;
    private String mTransientStatusText;
    private long mTransientStatusUntilMs;
    private String mTranslationAudioUrl;
    private String mTranslationEnabledForVideoId;
    private volatile long mWaitEndTimeMs;
    private volatile int mWaitLimitMinutes = 3;
    private volatile long mWaitStartTimeMs;

    static /* synthetic */ boolean access$002(VoiceOverTranslationController voiceOverTranslationController, boolean bl) {
        voiceOverTranslationController.mStatusTickerRunning = bl;
        return bl;
    }

    static /* synthetic */ String access$202(VoiceOverTranslationController voiceOverTranslationController, String string2) {
        voiceOverTranslationController.mPendingStartForVideoId = string2;
        return string2;
    }

    static /* synthetic */ String access$502(VoiceOverTranslationController voiceOverTranslationController, String string2) {
        voiceOverTranslationController.mPendingTranslationAudioUrl = string2;
        return string2;
    }

    static /* synthetic */ long access$602(VoiceOverTranslationController voiceOverTranslationController, long l) {
        voiceOverTranslationController.mWaitStartTimeMs = l;
        return l;
    }

    static /* synthetic */ long access$702(VoiceOverTranslationController voiceOverTranslationController, long l) {
        voiceOverTranslationController.mWaitEndTimeMs = l;
        return l;
    }

    static /* synthetic */ int access$802(VoiceOverTranslationController voiceOverTranslationController, int n) {
        voiceOverTranslationController.mWaitLimitMinutes = n;
        return n;
    }

    /*
     * Exception decompiling
     */
    private void applyResult(VotTranslateResult var1_1, String var2_2, int var3_3, VideoLoaderController var4_4, boolean var5_5, boolean var6_6, int var7_7) {
        /*
         * This method has failed to decompile.  When submitting a bug report, please provide this stack trace, and (if you hold appropriate legal rights) the relevant class file.
         * 
         * org.benf.cfr.reader.util.ConfusedCFRException: Statement already marked as first in another block
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.Op03SimpleStatement.markFirstStatementInBlock(Op03SimpleStatement.java:461)
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.op3rewriters.Misc.markWholeBlock(Misc.java:251)
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.op3rewriters.ConditionalRewriter.considerAsSimpleIf(ConditionalRewriter.java:673)
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.op3rewriters.ConditionalRewriter.identifyNonjumpingConditionals(ConditionalRewriter.java:56)
         *     at org.benf.cfr.reader.bytecode.CodeAnalyser.getAnalysisInner(CodeAnalyser.java:722)
         *     at org.benf.cfr.reader.bytecode.CodeAnalyser.getAnalysisOrWrapFail(CodeAnalyser.java:278)
         *     at org.benf.cfr.reader.bytecode.CodeAnalyser.getAnalysis(CodeAnalyser.java:201)
         *     at org.benf.cfr.reader.entities.attributes.AttributeCode.analyse(AttributeCode.java:94)
         *     at org.benf.cfr.reader.entities.Method.analyse(Method.java:531)
         *     at org.benf.cfr.reader.entities.ClassFile.analyseMid(ClassFile.java:1055)
         *     at org.benf.cfr.reader.entities.ClassFile.analyseTop(ClassFile.java:942)
         *     at org.benf.cfr.reader.Driver.doJarVersionTypes(Driver.java:257)
         *     at org.benf.cfr.reader.Driver.doJar(Driver.java:139)
         *     at org.benf.cfr.reader.CfrDriverImpl.analyse(CfrDriverImpl.java:76)
         *     at org.benf.cfr.reader.Main.main(Main.java:54)
         */
        throw new IllegalStateException("Decompilation failed");
    }

    private void applyTranslationVolume() {
        if (this.getPlayer() == null) {
            return;
        }
        this.getPlayer().setVolume(this.getPlayer().getVolume());
    }

    private static String buildTranslationErrorStatus(String string2) {
        return VoiceOverTranslationController.buildTranslationErrorStatus(string2, null);
    }

    private static String buildTranslationErrorStatus(String charSequence, String charSequence2) {
        if (((String)(charSequence = charSequence != null ? ((String)charSequence).replace('\n', ' ').replace('\r', ' ').trim() : "")).isEmpty()) {
            charSequence = VoiceOverTranslationController.summarizeTranslationStage((String)charSequence2);
            if (charSequence != null) {
                charSequence2 = new StringBuilder("\u041e\u0448\u0438\u0431\u043a\u0430 \u043f\u0435\u0440\u0435\u0432\u043e\u0434\u0430: ");
                ((StringBuilder)charSequence2).append((String)charSequence);
                charSequence = ((StringBuilder)charSequence2).toString();
            } else {
                charSequence = "\u041e\u0448\u0438\u0431\u043a\u0430 \u043f\u0435\u0440\u0435\u0432\u043e\u0434\u0430";
            }
            return charSequence;
        }
        String string2 = ((String)charSequence).toLowerCase();
        if (string2.contains("timeout")) {
            if ((charSequence2 = VoiceOverTranslationController.summarizeTranslationStage((String)charSequence2)) != null) {
                charSequence = new StringBuilder("\u041e\u0448\u0438\u0431\u043a\u0430 \u043f\u0435\u0440\u0435\u0432\u043e\u0434\u0430: timeout, ");
                ((StringBuilder)charSequence).append((String)charSequence2);
                charSequence = ((StringBuilder)charSequence).toString();
            } else {
                charSequence = "\u041e\u0448\u0438\u0431\u043a\u0430 \u043f\u0435\u0440\u0435\u0432\u043e\u0434\u0430: timeout";
            }
            return charSequence;
        }
        if (!string2.contains("session_required") && !string2.contains("session required")) {
            if (string2.contains("http 400")) {
                if ((charSequence2 = VoiceOverTranslationController.summarizeTranslationStage((String)charSequence2)) != null) {
                    charSequence = new StringBuilder("\u041e\u0448\u0438\u0431\u043a\u0430 \u043f\u0435\u0440\u0435\u0432\u043e\u0434\u0430: HTTP 400, ");
                    ((StringBuilder)charSequence).append((String)charSequence2);
                    charSequence = ((StringBuilder)charSequence).toString();
                } else {
                    charSequence = "\u041e\u0448\u0438\u0431\u043a\u0430 \u043f\u0435\u0440\u0435\u0432\u043e\u0434\u0430: HTTP 400";
                }
                return charSequence;
            }
            if (string2.contains("http 401")) {
                return "\u041e\u0448\u0438\u0431\u043a\u0430 \u043f\u0435\u0440\u0435\u0432\u043e\u0434\u0430: HTTP 401";
            }
            if (string2.contains("http 403")) {
                return "\u041e\u0448\u0438\u0431\u043a\u0430 \u043f\u0435\u0440\u0435\u0432\u043e\u0434\u0430: HTTP 403";
            }
            charSequence2 = charSequence;
            if (((String)charSequence).length() > 96) {
                charSequence2 = ((String)charSequence).substring(0, 96);
            }
            charSequence = new StringBuilder("\u041e\u0448\u0438\u0431\u043a\u0430 \u043f\u0435\u0440\u0435\u0432\u043e\u0434\u0430: ");
            ((StringBuilder)charSequence).append((String)charSequence2);
            return ((StringBuilder)charSequence).toString();
        }
        return "\u041e\u0448\u0438\u0431\u043a\u0430 \u043f\u0435\u0440\u0435\u0432\u043e\u0434\u0430: \u043d\u0443\u0436\u0435\u043d \u043d\u043e\u0432\u044b\u0439 session";
    }

    private String buildWaitingStatusText() {
        Context context = this.getContext();
        if (context == null) {
            return "";
        }
        int n = this.mWaitStartTimeMs > 0L ? Math.max(0, (int)((System.currentTimeMillis() - this.mWaitStartTimeMs) / 1000L)) : 0;
        int n2 = n / 60;
        return context.getString(R.string.voice_over_translate_waiting_elapsed, new Object[]{this.mWaitLimitMinutes, n2, n % 60});
    }

    private static int calculateRetryDelayMs(int n) {
        if (n <= 0) {
            return 5000;
        }
        return (int)Math.max(5000L, Math.min(60000L, (long)n * 1000L + 2000L));
    }

    private int calculateWaitLimitMinutes(Video video) {
        if (video != null && video.getDurationMs() > 0L) {
            double d = video.getDurationMs();
            Double.isNaN(d);
            return Math.max(1, (int)Math.round(d / 60000.0 / 10.0));
        }
        return 3;
    }

    private void cancelPendingRetry() {
        Runnable runnable = this.mPendingRetryTask;
        if (runnable != null) {
            this.mMainHandler.removeCallbacks(runnable);
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

    private String detectSourceLanguage(MediaItemFormatInfo object) {
        if (object == null) {
            return null;
        }
        if (object.getAdaptiveFormats() != null) {
            for (Object object2 : object.getAdaptiveFormats()) {
                if (object2 == null || object2.getMimeType() == null || !object2.getMimeType().startsWith("audio") || (object2 = this.normalizeLang(object2.getLanguage())) == null) continue;
                return object2;
            }
        }
        if (object.getSubtitles() != null) {
            for (Object object3 : object.getSubtitles()) {
                if (object3 == null || (object3 = this.normalizeLang(object3.getLanguageCode())) == null) continue;
                return object3;
            }
        }
        return null;
    }

    private void finishTranslationAnalytics(boolean bl, String string2) {
        block4: {
            Video video;
            Object object;
            block6: {
                block5: {
                    if (this.mAnalyticsTranslationSessionId == null || this.mAnalyticsTranslationVideoId == null) break block4;
                    object = this.getVideo();
                    if (object == null || ((Video)object).videoId == null) break block5;
                    video = object;
                    if (this.mAnalyticsTranslationVideoId.equals(((Video)object).videoId)) break block6;
                }
                video = Video.from(this.mAnalyticsTranslationVideoId);
            }
            long l = System.currentTimeMillis();
            long l2 = this.mAnalyticsTranslationStartedAtMs;
            if (l2 <= 0L) {
                l2 = l;
            }
            long l3 = Math.max(0L, l - l2);
            Context context = this.getContext();
            String string3 = this.mAnalyticsTranslationSessionId;
            String string4 = this.mAnalyticsSourceLanguage;
            object = bl ? "completed" : "failed";
            AnalyticsEventReporter.reportTranslation(context, video, string3, string4, "ru", l2, l, l3, bl, string2, "end", (String)object);
            this.clearTranslationAnalytics();
        }
    }

    private String getRequestLang(MediaItemFormatInfo object) {
        VotSettings votSettings = this.getSettings();
        if (votSettings != null && votSettings.isUseLivelyVoice()) {
            return "en";
        }
        if ((object = this.detectSourceLanguage((MediaItemFormatInfo)object)) == null) {
            object = REQUEST_LANG_FALLBACK;
        }
        return object;
    }

    private VotSettings getSettings() {
        Context context;
        if (this.mSettings == null && (context = this.getContext()) != null) {
            this.mSettings = VotSettings.instance(context);
        }
        return this.mSettings;
    }

    private boolean hasTransientStatus() {
        String string2 = this.mTransientStatusText;
        if (string2 != null && !string2.isEmpty()) {
            if (System.currentTimeMillis() > this.mTransientStatusUntilMs) {
                this.clearTransientStatus();
                return false;
            }
            return true;
        }
        return false;
    }

    private boolean isWaiting() {
        boolean bl = this.mWaitEndTimeMs > System.currentTimeMillis();
        return bl;
    }

    private static boolean isWaitingVotStatus(String string2) {
        boolean bl = false;
        if (string2 == null) {
            return false;
        }
        if ("WAITING".equalsIgnoreCase(string2) || "LONG_WAITING".equalsIgnoreCase(string2)) {
            bl = true;
        }
        return bl;
    }

    static /* synthetic */ void lambda$runTranslateRequest$0(String[] stringArray, String string2) {
        if (string2 != null && !string2.isEmpty()) {
            stringArray[0] = string2;
        }
    }

    private long minutesToMs(int n) {
        return (long)Math.max(1, n) * 60000L;
    }

    private String normalizeLang(String string2) {
        int n;
        if (string2 == null) {
            return null;
        }
        String string3 = string2.trim().toLowerCase();
        if (string3.isEmpty()) {
            return null;
        }
        int n2 = n = string3.indexOf(45);
        if (n == -1) {
            n2 = string3.indexOf(95);
        }
        string2 = string3;
        if (n2 != -1) {
            string2 = string3.substring(0, n2);
        }
        return string2;
    }

    private static void postToUiThread(Activity activity, Runnable runnable) {
        if (activity != null && !activity.isFinishing()) {
            activity.runOnUiThread(runnable);
        } else {
            new Handler(Looper.getMainLooper()).post(runnable);
        }
    }

    private String proxifyAudioUrl(String object) {
        Object object2 = this.getSettings();
        if (object2 == null) {
            return object;
        }
        if (!((VotSettings)object2).isUseLivelyVoice()) {
            return object;
        }
        if ((object2 = ((VotSettings)object2).proxifyAudioUrl((String)object)) != null) {
            object = object2;
        }
        return object;
    }

    private void requestTranslationInternal(boolean bl, boolean bl2) {
        Video video = this.getVideo();
        Context context = this.getContext();
        if (video == null) {
            this.setDebugStatus("no_video");
            if (!bl2 && context != null) {
                MessageHelpers.showMessage((Context)context, (int)R.string.voice_over_translate_no_video);
            }
            return;
        }
        if (video.isLive) {
            this.setDebugStatus("live_not_supported");
            if (!bl2 && context != null) {
                MessageHelpers.showMessage((Context)context, (int)R.string.voice_over_translate_no_live);
            }
            return;
        }
        if (video.videoId != null && !video.videoId.isEmpty()) {
            this.clearTransientStatus();
            VideoLoaderController videoLoaderController = this.getController(VideoLoaderController.class);
            CharSequence charSequence = videoLoaderController != null ? this.detectSourceLanguage(videoLoaderController.getLastFormatInfo()) : null;
            if (!(video.videoId.equals(this.mTranslationEnabledForVideoId) || this.mRequestInProgress || this.isWaiting())) {
                if (videoLoaderController == null) {
                    this.startTranslationAnalytics(video, (String)charSequence);
                    this.finishTranslationAnalytics(false, "loader_missing");
                    this.setDebugStatus("loader_missing");
                    if (!bl2 && context != null) {
                        MessageHelpers.showMessage((Context)context, (int)R.string.voice_over_translate_not_available);
                    }
                    return;
                }
                if (!videoLoaderController.canReopenWithTranslation()) {
                    this.startTranslationAnalytics(video, (String)charSequence);
                    this.mPendingStartForVideoId = video.videoId;
                    this.mPendingTranslationAudioUrl = null;
                    this.mWaitLimitMinutes = this.calculateWaitLimitMinutes(video);
                    this.mWaitStartTimeMs = System.currentTimeMillis();
                    this.mWaitEndTimeMs = this.mWaitStartTimeMs + this.minutesToMs(this.mWaitLimitMinutes);
                    this.setDebugStatus("waiting_loader_reopen");
                    this.updateUiState();
                    if (!bl2 && context != null) {
                        MessageHelpers.showMessage((Context)context, (int)R.string.voice_over_translate_please_wait);
                    }
                    return;
                }
                this.getSettings().resetMixToDefaults();
                this.startTranslationAnalytics(video, (String)charSequence);
                this.mPendingStartForVideoId = null;
                this.mDebugRetryCount = 0;
                charSequence = new StringBuilder("request_start lang=");
                ((StringBuilder)charSequence).append(this.getRequestLang(videoLoaderController.getLastFormatInfo()));
                this.setDebugStatus(((StringBuilder)charSequence).toString());
                this.runTranslateRequest(video, videoLoaderController, bl, bl2, 0);
                return;
            }
            this.setDebugStatus("toggle_off");
            this.turnOffTranslation(videoLoaderController);
            return;
        }
        this.setDebugStatus("empty_video_id");
        if (!bl2 && context != null) {
            MessageHelpers.showMessage((Context)context, (int)R.string.voice_over_translate_no_video);
        }
    }

    private void restorePlayerVolumeIfNeeded() {
    }

    private void runTranslateRequest(Video video, VideoLoaderController videoLoaderController, boolean bl, boolean bl2, int n) {
        int n2;
        String[] stringArray = this.getContext();
        if (stringArray == null) {
            return;
        }
        Activity activity = this.getActivity();
        CharSequence charSequence = new StringBuilder(VIDEO_URL_PREFIX);
        charSequence.append(video.videoId);
        String string2 = charSequence.toString();
        long l = video.getDurationMs();
        int n3 = l > 0L ? (int)(l / 1000L) : 300;
        charSequence = video.videoId;
        int n4 = n2 = this.mRequestToken;
        if (n == 0) {
            this.mRequestToken = n4 = n2 + 1;
        }
        VotApiServiceImpl votApiServiceImpl = new VotApiServiceImpl((Context)stringArray);
        stringArray = new String[1];
        this.mPendingStartForVideoId = null;
        this.mPendingTranslationAudioUrl = null;
        this.mRequestInProgress = true;
        this.mDebugRetryCount = n;
        if (n == 0) {
            this.mWaitLimitMinutes = this.calculateWaitLimitMinutes(video);
            this.mWaitStartTimeMs = System.currentTimeMillis();
            this.mWaitEndTimeMs = System.currentTimeMillis() + this.minutesToMs(this.mWaitLimitMinutes);
        } else if (this.mWaitStartTimeMs <= 0L) {
            this.mWaitStartTimeMs = System.currentTimeMillis();
        }
        StringBuilder stringBuilder = new StringBuilder("poll ");
        stringBuilder.append(n + 1);
        stringBuilder.append(" request_sent");
        this.setDebugStatus(stringBuilder.toString());
        this.updateUiState();
        if (bl && this.getPlayer() != null) {
            this.getPlayer().showProgressBar(true);
        }
        new Thread(new VoiceOverTranslationController$$ExternalSyntheticLambda4(this, votApiServiceImpl, video, string2, n3, videoLoaderController, stringArray, activity, (String)charSequence, n4, bl, bl2, n)).start();
    }

    private void setDebugStatus(String string2) {
        this.mDebugStatus = string2;
    }

    private void setTransientStatus(String string2) {
        this.mTransientStatusText = string2;
        long l = string2 != null ? System.currentTimeMillis() + 15000L : 0L;
        this.mTransientStatusUntilMs = l;
    }

    private boolean shouldRestoreAfterEngineInit() {
        Object object = this.getVideo();
        boolean bl = object != null && ((Video)object).videoId != null && ((Video)object).videoId.equals(this.mTranslationEnabledForVideoId) && (object = this.mTranslationAudioUrl) != null && !((String)object).isEmpty();
        return bl;
    }

    private boolean shouldShowWaitingStatus() {
        boolean bl = this.mWaitStartTimeMs > 0L && (this.mRequestInProgress || this.isWaiting());
        return bl;
    }

    private void startStatusTicker() {
        if (this.mStatusTickerRunning) {
            return;
        }
        this.mStatusTickerRunning = true;
        this.mMainHandler.postDelayed(this.mStatusTicker, 1000L);
    }

    private void startTranslationAnalytics(Video video, String string2) {
        if (!(video == null || video.videoId == null || video.videoId.isEmpty() || this.mAnalyticsTranslationSessionId != null && video.videoId.equals(this.mAnalyticsTranslationVideoId))) {
            this.mAnalyticsTranslationSessionId = AnalyticsEventReporter.newSessionId();
            this.mAnalyticsTranslationStartedAtMs = System.currentTimeMillis();
            this.mAnalyticsTranslationVideoId = video.videoId;
            if (string2 == null) {
                string2 = this.getController(VideoLoaderController.class) != null ? this.getController(VideoLoaderController.class).getLastFormatInfo() : null;
                string2 = AnalyticsEventReporter.detectVideoLanguage((MediaItemFormatInfo)string2);
            }
            this.mAnalyticsSourceLanguage = string2;
            Context context = this.getContext();
            String string3 = this.mAnalyticsTranslationSessionId;
            string2 = this.mAnalyticsSourceLanguage;
            long l = this.mAnalyticsTranslationStartedAtMs;
            AnalyticsEventReporter.reportTranslation(context, video, string3, string2, "ru", l, l, 0L, false, null, "start", "started");
        }
    }

    private void stopStatusTicker() {
        if (!this.mStatusTickerRunning) {
            return;
        }
        this.mMainHandler.removeCallbacks(this.mStatusTicker);
        this.mStatusTickerRunning = false;
    }

    private static String summarizeTranslationStage(String string2) {
        if (string2 == null) {
            return null;
        }
        String string3 = string2.replace('\n', ' ').replace('\r', ' ').trim().toLowerCase();
        if (string3.isEmpty()) {
            return null;
        }
        if (!string3.contains("audio_try_failed") && !string3.contains("audio_upload_failed")) {
            if (!(string3.contains("audio_chunk") || string3.contains("audio_stream") || string3.contains("audio_try"))) {
                if (string3.contains("audio_requested")) {
                    return "\u0441\u0435\u0440\u0432\u0435\u0440 \u0437\u0430\u043f\u0440\u043e\u0441\u0438\u043b \u0438\u0441\u0445\u043e\u0434\u043d\u043e\u0435 \u0430\u0443\u0434\u0438\u043e";
                }
                if (!string3.contains("session_required") && !string3.contains("failed_session_required")) {
                    if (string3.contains("translate_http_error")) {
                        return "\u043e\u0448\u0438\u0431\u043a\u0430 API \u043f\u0435\u0440\u0435\u0432\u043e\u0434\u0430";
                    }
                    if (!string3.contains("waiting") && !string3.contains("long_waiting")) {
                        if (!(string3.contains("request_start") || string3.contains("request_sent") || string3.contains("start from="))) {
                            string3 = string2;
                            if (string2.length() > 48) {
                                string3 = string2.substring(0, 48).trim();
                            }
                            return string3;
                        }
                        return "\u043e\u0448\u0438\u0431\u043a\u0430 \u0437\u0430\u043f\u0443\u0441\u043a\u0430 \u043f\u0435\u0440\u0435\u0432\u043e\u0434\u0430";
                    }
                    return "\u0442\u0430\u0439\u043c\u0430\u0443\u0442 \u043e\u0436\u0438\u0434\u0430\u043d\u0438\u044f \u0440\u0435\u0437\u0443\u043b\u044c\u0442\u0430\u0442\u0430";
                }
                return "\u0441\u0435\u0440\u0432\u0435\u0440 \u0441\u0431\u0440\u043e\u0441\u0438\u043b session";
            }
            return "\u0442\u0430\u0439\u043c\u0430\u0443\u0442 \u043f\u0440\u0438 \u043e\u0442\u043f\u0440\u0430\u0432\u043a\u0435 \u0438\u0441\u0445\u043e\u0434\u043d\u043e\u0433\u043e \u0430\u0443\u0434\u0438\u043e";
        }
        return "\u0441\u0431\u043e\u0439 \u0437\u0430\u0433\u0440\u0443\u0437\u043a\u0438 \u0438\u0441\u0445\u043e\u0434\u043d\u043e\u0433\u043e \u0430\u0443\u0434\u0438\u043e";
    }

    private void tryRestoreAfterEngineInit() {
        if (!this.mRestorePending) {
            return;
        }
        VideoLoaderController videoLoaderController = this.getController(VideoLoaderController.class);
        if (videoLoaderController != null && videoLoaderController.canReopenWithTranslation()) {
            this.mRestorePending = false;
            videoLoaderController.reopenWithTranslationAudio(this.mTranslationAudioUrl);
            this.applyTranslationVolume();
        }
    }

    private void tryStartPendingRequest() {
        Video video;
        if (this.mPendingStartForVideoId != null && !this.mRequestInProgress && !this.isTranslationEnabledForCurrentVideo() && (video = this.getVideo()) != null && video.videoId != null) {
            if (!video.videoId.equals(this.mPendingStartForVideoId)) {
                this.mPendingStartForVideoId = null;
                this.mPendingTranslationAudioUrl = null;
                return;
            }
            VideoLoaderController videoLoaderController = this.getController(VideoLoaderController.class);
            if (videoLoaderController != null && videoLoaderController.canReopenWithTranslation()) {
                String string2 = this.mPendingTranslationAudioUrl;
                if (string2 != null) {
                    if (videoLoaderController.reopenWithTranslationAudio(string2)) {
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
                        videoLoaderController = this.getContext();
                        if (videoLoaderController != null) {
                            MessageHelpers.showMessage((Context)videoLoaderController, (int)R.string.voice_over_translate_enabled);
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

    private void turnOffTranslation(VideoLoaderController videoLoaderController) {
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
        videoLoaderController = this.getContext();
        if (videoLoaderController != null) {
            MessageHelpers.showMessage((Context)videoLoaderController, (int)R.string.voice_over_translate_disabled);
        }
    }

    private void updateUiState() {
        if (this.getPlayer() == null) {
            this.stopStatusTicker();
            return;
        }
        boolean bl = this.isTranslationEnabledForCurrentVideo();
        int n = bl ? 1 : (this.shouldShowWaitingStatus() ? 2 : 0);
        this.getPlayer().setButtonState(R.id.action_voice_over_translate, n);
        if (bl) {
            this.getPlayer().setVoiceOverTranslateStatus(null);
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
        } else {
            this.getPlayer().setVoiceOverTranslateStatus(null);
            this.stopStatusTicker();
        }
    }

    public int getRemainingWaitSec() {
        long l = this.mWaitEndTimeMs;
        if (l <= 0L) {
            return 0;
        }
        return Math.max(0, (int)((l - System.currentTimeMillis()) / 1000L));
    }

    public boolean isRequestInProgress() {
        return this.mRequestInProgress;
    }

    public boolean isTranslationEnabledForCurrentVideo() {
        Video video = this.getVideo();
        boolean bl = video != null && video.videoId != null && video.videoId.equals(this.mTranslationEnabledForVideoId);
        return bl;
    }

    /* synthetic */ void lambda$applyResult$4(int n, String charSequence, int n2, VideoLoaderController videoLoaderController, boolean bl, boolean bl2) {
        this.mPendingRetryTask = null;
        Video video = this.getVideo();
        if (n == this.mRequestToken && video != null && ((String)charSequence).equals(video.videoId) && this.mTranslationEnabledForVideoId == null && !this.mRequestInProgress) {
            charSequence = new StringBuilder("retry_after_wait ");
            ((StringBuilder)charSequence).append(n2 + 2);
            this.setDebugStatus(((StringBuilder)charSequence).toString());
            this.runTranslateRequest(video, videoLoaderController, bl, bl2, n2 + 1);
        }
    }

    /* synthetic */ void lambda$runTranslateRequest$1(VotTranslateResult votTranslateResult, String string2, int n, VideoLoaderController videoLoaderController, boolean bl, boolean bl2, int n2) {
        this.applyResult(votTranslateResult, string2, n, videoLoaderController, bl, bl2, n2);
    }

    /* synthetic */ void lambda$runTranslateRequest$2(int n, String[] object, Exception exception, boolean bl, boolean bl2) {
        if (n != this.mRequestToken) {
            return;
        }
        this.mLastTranslationProgress = object[0];
        this.mRequestInProgress = false;
        this.mWaitEndTimeMs = 0L;
        this.mWaitStartTimeMs = 0L;
        this.mWaitLimitMinutes = 3;
        this.finishTranslationAnalytics(false, "request_exception");
        object = new StringBuilder("request_exception ");
        ((StringBuilder)object).append(exception.getClass().getSimpleName());
        this.setDebugStatus(((StringBuilder)object).toString());
        this.setTransientStatus(VoiceOverTranslationController.buildTranslationErrorStatus(exception.getMessage(), this.mLastTranslationProgress));
        if (bl && this.getPlayer() != null) {
            this.getPlayer().showProgressBar(false);
        }
        this.stopStatusTicker();
        this.updateUiState();
        if (!bl2 && this.getContext() != null) {
            object = exception.getMessage();
            if (object != null && !((String)object).isEmpty()) {
                MessageHelpers.showMessage((Context)this.getContext(), (String)object);
            } else {
                MessageHelpers.showMessage((Context)this.getContext(), (int)R.string.voice_over_translate_error);
            }
        }
    }

    /*
     * WARNING - void declaration
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    /* synthetic */ void lambda$runTranslateRequest$3(VotApiService object, Video object2, String string2, int n, VideoLoaderController videoLoaderController, String[] stringArray, Activity activity, String string3, int n2, boolean bl, boolean bl2, int n3) {
        Exception var1_5 = null;
        block6: {
            String string4;
            String string5;
            MediaItemFormatInfo mediaItemFormatInfo;
            String string6;
            try {
                string6 = ((Video)object2).videoId;
                mediaItemFormatInfo = videoLoaderController.getLastFormatInfo();
            }
            catch (Exception exception) {
                var1_5 = exception;
                break block6;
            }
            try {
                string5 = this.getRequestLang(mediaItemFormatInfo);
                mediaItemFormatInfo = videoLoaderController.getLastFormatInfo();
                string4 = ((Video)object2).getTitle();
            }
            catch (Exception exception) {
                break block6;
            }
            try {
                object2 = new VoiceOverTranslationController$$ExternalSyntheticLambda0(stringArray);
                object = object.translate(string6, string2, n, string5, "ru", mediaItemFormatInfo, string4, (VotApiService.ProgressListener)object2);
                object2 = new VoiceOverTranslationController$$ExternalSyntheticLambda1(this, (VotTranslateResult)object, string3, n2, videoLoaderController, bl, bl2, n3);
                VoiceOverTranslationController.postToUiThread(activity, (Runnable)object2);
                return;
            }
            catch (Exception exception) {
                var1_5 = exception;
            }
        }
        VoiceOverTranslationController.postToUiThread(activity, new VoiceOverTranslationController$$ExternalSyntheticLambda2(this, n2, stringArray, (Exception)var1_5, bl, bl2));
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
    public void onNewVideo(Video video) {
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
    public void onSourceChanged(Video video) {
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

