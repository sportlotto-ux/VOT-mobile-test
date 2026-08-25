// 
// Decompiled by Procyon v0.6.0
// 

package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.VideoActionPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SimpleMediaItem;
import io.reactivex.functions.Consumer;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.sharedutils.mylogger.Log;
import java.util.Collections;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import com.liskovsoft.sharedutils.helpers.Helpers;
import java.util.List;
import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import io.reactivex.disposables.Disposable;
import android.util.Pair;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;

public class VideoLoaderController extends BasePlayerController
{
    private static final long BUFFERING_CONTINUATION_MS = 20000L;
    private static final long BUFFERING_RECURRENCE_COUNT = 5L;
    private static final long BUFFERING_THRESHOLD_MS = 3000L;
    private static final long BUFFERING_WINDOW_MS = 60000L;
    private static final int OPEN_TYPE_DASH = 1;
    private static final int OPEN_TYPE_NONE = 0;
    private static final int OPEN_TYPE_SABR = 2;
    private static final long STREAM_END_THRESHOLD_MS = 180000L;
    private static final String TAG = "VideoLoaderController";
    private Pair<Integer, Long> mBufferingCount;
    private Disposable mFormatInfoAction;
    private int mLastErrorType;
    private MediaItemFormatInfo mLastFormatInfo;
    private int mLastOpenType;
    private final Runnable mLoadNext;
    private final Runnable mMetadataSync;
    private Disposable mMpdStreamAction;
    private final Runnable mOnApplyPlaybackMode;
    private final Runnable mOnLongBuffering;
    private Video mPendingVideo;
    private final Playlist mPlaylist;
    private final Runnable mRebootApp;
    private final Runnable mReloadVideo;
    private final Runnable mRestartEngine;
    private long mSleepTimerStartMs;
    private SuggestionsController mSuggestionsController;
    
    public VideoLoaderController() {
        this.mLastErrorType = -1;
        this.mReloadVideo = new VideoLoaderController$$ExternalSyntheticLambda3(this);
        this.mLoadNext = new VideoLoaderController$$ExternalSyntheticLambda4(this);
        this.mMetadataSync = new VideoLoaderController$$ExternalSyntheticLambda5(this);
        this.mRestartEngine = new VideoLoaderController$$ExternalSyntheticLambda6(this);
        this.mOnLongBuffering = new VideoLoaderController$$ExternalSyntheticLambda7(this);
        this.mRebootApp = new VideoLoaderController$$ExternalSyntheticLambda8(this);
        this.mOnApplyPlaybackMode = new VideoLoaderController$$ExternalSyntheticLambda9(this);
        this.mLastOpenType = 0;
        this.mPlaylist = Playlist.instance();
    }
    
    private boolean acceptAdaptiveFormats(final MediaItemFormatInfo mediaItemFormatInfo) {
        return (!this.getPlayerData().isLegacyCodecsForced() || !mediaItemFormatInfo.containsUrlFormats()) && (!this.getPlayerTweaksData().isHlsStreamsForced() || !mediaItemFormatInfo.isLive() || !mediaItemFormatInfo.containsHlsUrl()) && (!mediaItemFormatInfo.isLive() || mediaItemFormatInfo.getStartTimeMs() != 0L) && (!mediaItemFormatInfo.isLive() || !this.getPlayerTweaksData().isDashUrlStreamsForced() || !mediaItemFormatInfo.containsDashUrl()) && (!mediaItemFormatInfo.isLive() || !this.getPlayerTweaksData().isHlsStreamsForced() || !mediaItemFormatInfo.containsHlsUrl());
    }
    
    private boolean acceptDashLive(final MediaItemFormatInfo mediaItemFormatInfo) {
        final boolean hlsStreamsForced = this.getPlayerTweaksData().isHlsStreamsForced();
        final boolean b = false;
        if (hlsStreamsForced && mediaItemFormatInfo.isLive() && mediaItemFormatInfo.containsHlsUrl()) {
            return false;
        }
        boolean b2 = b;
        if (mediaItemFormatInfo.isLive()) {
            b2 = b;
            if (mediaItemFormatInfo.containsDashUrl()) {
                b2 = true;
            }
        }
        return b2;
    }
    
    private void applyAspectRatio(final MediaItemFormatInfo mediaItemFormatInfo) {
        if (this.getPlayer() == null) {
            return;
        }
        if (mediaItemFormatInfo.containsDashFormats()) {
            final List adaptiveFormats = mediaItemFormatInfo.getAdaptiveFormats();
            boolean b = false;
            final MediaFormat mediaFormat = adaptiveFormats.get(0);
            final int width = mediaFormat.getWidth();
            final int height = mediaFormat.getHeight();
            if (width < height) {
                b = true;
            }
            if (width > 0 && height > 0 && (this.getPlayerData().getAspectRatio() == 0.0f || b)) {
                this.getPlayer().setAspectRatio(width / (float)height);
            }
            else {
                this.getPlayer().setAspectRatio(this.getPlayerData().getAspectRatio());
            }
        }
    }
    
    private boolean applyEngineErrorAction(int n, int n2, final Throwable t) {
        String message;
        if (t != null) {
            message = t.getMessage();
        }
        else {
            message = null;
        }
        final String errorTitle = this.getErrorTitle(n, n2);
        final StringBuilder sb = new StringBuilder();
        sb.append(errorTitle);
        sb.append("\n");
        sb.append(message);
        final String string = sb.toString();
        final int n3 = 1;
        final int n4 = 1;
        final boolean b = false;
        boolean b2 = false;
        Label_0510: {
            if (Helpers.startsWithAny(message, new String[] { "Unable to connect to" })) {
                YouTubeServiceManager.instance().applyNoPlaybackFix();
                n2 = n4;
            }
            else if (!(t instanceof OutOfMemoryError) && (t == null || !(t.getCause() instanceof OutOfMemoryError))) {
                if (Helpers.containsAny(message, new String[] { "Exception in CronetUrlRequest", "Response code: 503" }) && !this.getPlayerTweaksData().isNetworkErrorFixingDisabled()) {
                    n = n3;
                    b2 = b;
                    if (this.getVideo() == null) {
                        break Label_0510;
                    }
                    n = n3;
                    b2 = b;
                    if (this.getVideo().isLive) {
                        break Label_0510;
                    }
                    this.getPlayerTweaksData().setPlayerDataSource(0);
                    n2 = n4;
                }
                else {
                    if (n == 0 && n2 == -1) {
                        if (Helpers.startsWithAny(message, new String[] { "Response code: 403" })) {
                            YouTubeServiceManager.instance().applyNoPlaybackFix();
                        }
                        else if (this.getPlayer() != null && !FormatItem.SUBTITLE_NONE.equals(this.getPlayer().getSubtitleFormat())) {
                            this.disableSubtitles();
                        }
                        else if (this.getPlayerTweaksData().isHighBitrateFormatsEnabled()) {
                            this.getPlayerTweaksData().setHighBitrateFormatsEnabled(false);
                        }
                        else {
                            YouTubeServiceManager.instance().applyNoPlaybackFix();
                        }
                        n = 0;
                        b2 = b;
                        break Label_0510;
                    }
                    if (n == 1 && n2 == 2) {
                        this.disableSubtitles();
                        n = n3;
                        b2 = b;
                        break Label_0510;
                    }
                    if (n == 1 && n2 == 0) {
                        this.getPlayerData().setFormat(FormatItem.VIDEO_FHD_AVC_30);
                        n = n3;
                        b2 = b;
                        if (!this.getPlayerTweaksData().isSWDecoderForced()) {
                            break Label_0510;
                        }
                        this.getPlayerTweaksData().setSWDecoderForced(false);
                        n2 = n4;
                    }
                    else {
                        if (n == 1 && n2 == 1) {
                            this.getPlayerData().setFormat(FormatItem.AUDIO_HQ_MP4A);
                            n = n3;
                            b2 = b;
                            break Label_0510;
                        }
                        n2 = n4;
                        if (n == 2) {
                            n2 = 0;
                        }
                    }
                }
            }
            else if (this.getPlayerTweaksData().getPlayerDataSource() == 1) {
                this.enableFasterDataSource();
                n2 = n4;
            }
            else {
                if (this.getPlayerData().getVideoBufferType() != 1 && this.getPlayerData().getVideoBufferType() != 2) {
                    this.getPlayerTweaksData().setSectionPlaylistEnabled(false);
                    n = n3;
                    b2 = b;
                    break Label_0510;
                }
                this.getPlayerData().setVideoBufferType(0);
                n2 = n4;
            }
            b2 = true;
            n = n2;
        }
        if (n != 0) {
            MessageHelpers.showLongMessage(this.getContext(), string);
        }
        return b2;
    }
    
    private List<String> applyFix(final List<String> list) {
        if (this.mLastErrorType == 0) {
            Collections.reverse(list);
        }
        return list;
    }
    
    private void applyPlaybackMode(int i) {
        if (this.getPlayer() == null) {
            return;
        }
        final Video video = this.getVideo();
        if (video != null) {
            if (!this.isActionsRunning()) {
                if (this.isEmbedPlayer()) {
                    i = 1;
                }
                switch (i) {
                    default: {
                        final String tag = VideoLoaderController.TAG;
                        final StringBuilder sb = new StringBuilder("Undetected repeat mode ");
                        sb.append(i);
                        Log.e(tag, (Object)sb.toString(), new Object[0]);
                        break;
                    }
                    case 5: {
                        if (!video.hasNextPlaylist() && this.mPlaylist.getNext() == null) {
                            this.stopPlayback();
                            break;
                        }
                        this.loadNext();
                        break;
                    }
                    case 3: {
                        this.getPlayer().setPositionMs(100L);
                        break;
                    }
                    case 6: {
                        if (!video.hasPlaylist() && !video.belongsToChannelUploads() && !video.belongsToChannel())
                        final VideoGroup group = video.getGroup();
                        if (group != null && group.indexOf(video) != 0) {
                            this.onPreviousClicked();
                            break;
                        }
                        break;
                    }
                    case 2:
                    case 4: {
                        this.loadNext();
                        break;
                    }
                    case 1: {
                        if (this.mPlaylist.getNext() != null) {
                            this.loadNext();
                            break;
                        }
                        final AppDialogPresenter appDialogPresenter = this.getAppDialogPresenter();
                        if (!this.getPlayer().isSuggestionsShown() && (!appDialogPresenter.isDialogShown() || appDialogPresenter.isOverlay())) {
                            appDialogPresenter.closeDialog();
                            this.getPlayer().finishReally();
                            break;
                        }
                        break;
                    }
                    case 0: {
                        if (this.mPlaylist.getNext() != null) {
                            this.loadNext();
                            break;
                        }
                        this.stopPlayback();
                        break;
                    }
                }
            }
        }
    }
    
    private void checkSleepTimer() {
        if (this.getPlayer() == null) {
            return;
        }
        if (this.getPlayerData().isSleepTimerEnabled() && System.currentTimeMillis() - this.mSleepTimerStartMs > 7200000L) {
            this.getPlayer().setPlayWhenReady(false);
            this.getPlayer().setTitle(this.getContext().getString(R.string.sleep_timer));
            this.getPlayer().showOverlay(true);
            Helpers.enableScreensaver(this.getActivity());
        }
    }
    
    private void disableSubtitles() {
        this.getPlayerData().setFormat(FormatItem.SUBTITLE_NONE);
    }
    
    private void disposeActions() {
        this.mBufferingCount = null;
        MediaServiceManager.instance().disposeActions();
        RxHelper.disposeActions(new Disposable[] { this.mFormatInfoAction, this.mMpdStreamAction });
        Utils.removeCallbacks(this.mReloadVideo, this.mLoadNext, this.mRestartEngine, this.mMetadataSync, this.mOnLongBuffering, this.mRebootApp);
    }
    
    private int effectiveOpenTypeForTranslation() {
        int mLastOpenType;
        final int n = mLastOpenType = this.mLastOpenType;
        if (n != 1) {
            if (n == 2) {
                mLastOpenType = n;
            }
            else {
                if (n == 0) {
                    final MediaItemFormatInfo mLastFormatInfo = this.mLastFormatInfo;
                    if (mLastFormatInfo != null) {
                        if (mLastFormatInfo.containsDashFormats()) {
                            return 1;
                        }
                        if (this.mLastFormatInfo.containsSabrFormats()) {
                            return 2;
                        }
                    }
                }
                mLastOpenType = 0;
            }
        }
        return mLastOpenType;
    }
    
    private void enableFasterDataSource() {
        if (this.isFasterDataSourceEnabled()) {
            return;
        }
        this.getPlayerTweaksData().setPlayerDataSource(getFasterDataSource());
    }
    
    private String getErrorTitle(int i, final int n) {
        String s;
        if (i != 0) {
            if (i != 1) {
                if (i != 2) {
                    s = this.getContext().getString(R.string.msg_player_error, new Object[] { i });
                }
                else {
                    s = this.getContext().getString(R.string.msg_player_error_unexpected);
                }
            }
            else {
                if (n != 0) {
                    if (n != 1) {
                        if (n != 2) {
                            i = R.string.unknown_renderer_error;
                        }
                        else {
                            i = R.string.msg_player_error_subtitle_renderer;
                        }
                    }
                    else {
                        i = R.string.msg_player_error_audio_renderer;
                    }
                }
                else {
                    i = R.string.msg_player_error_video_renderer;
                }
                s = this.getContext().getString(i);
            }
        }
        else {
            if (n != 0) {
                if (n != 1) {
                    if (n != 2) {
                        i = R.string.unknown_source_error;
                    }
                    else {
                        i = R.string.msg_player_error_subtitle_source;
                    }
                }
                else {
                    i = R.string.msg_player_error_audio_source;
                }
            }
            else {
                i = R.string.msg_player_error_video_source;
            }
            s = this.getContext().getString(i);
        }
        return s;
    }
    
    private static int getFasterDataSource() {
        int n;
        if (Utils.skipCronet()) {
            n = 0;
        }
        else {
            n = 2;
        }
        return n;
    }
    
    private int getNextEngine() {
        final int playerDataSource = this.getPlayerTweaksData().getPlayerDataSource();
        final boolean skipCronet = Utils.skipCronet();
        final Integer value = 1;
        final Integer value2 = 0;
        Integer[] array;
        if (skipCronet) {
            array = new Integer[] { value2, value };
        }
        else {
            array = new Integer[] { 2, value2, value };
        }
        return (int)Helpers.getNextValue((Object[])array, (Object)playerDataSource);
    }
    
    private int getPlaybackMode() {
        final int playbackMode = this.getPlayerData().getPlaybackMode();
        final Video video = this.getVideo();
        int n;
        if (video != null && video.finishOnEnded) {
            n = 1;
        }
        else {
            n = playbackMode;
            if (video != null) {
                n = playbackMode;
                if (video.belongsToShortsGroup()) {
                    n = playbackMode;
                    if (this.getPlayerTweaksData().isLoopShortsEnabled()) {
                        n = 3;
                    }
                }
            }
        }
        return n;
    }
    
    private boolean isActionsRunning() {
        return RxHelper.isAnyActionRunning(new Disposable[] { this.mFormatInfoAction, this.mMpdStreamAction });
    }
    
    private boolean isBufferingRecurrent() {
        final Pair<Integer, Long> mBufferingCount = this.mBufferingCount;
        return mBufferingCount != null && (int)mBufferingCount.first > 5L;
    }
    
    private boolean isFasterDataSourceEnabled() {
        return this.getPlayerTweaksData().getPlayerDataSource() == getFasterDataSource();
    }
    
    private void loadFormatInfo(final Video video) {
        if (this.getPlayer() == null) {
            return;
        }
        this.getPlayer().showProgressBar(true);
        this.disposeActions();
        this.mFormatInfoAction = YouTubeServiceManager.instance().getMediaItemService().getFormatInfoObserve(video.videoId).subscribe((Consumer)new VideoLoaderController$$ExternalSyntheticLambda10(this), (Consumer)new VideoLoaderController$$ExternalSyntheticLambda1(this));
    }
    
    private void loadRandomNext() {
        MediaServiceManager.instance().disposeActions();
        if (this.getPlayer() != null && this.getPlayerData() != null && this.getVideo() != null && this.getVideo().playlistInfo != null) {
            if (this.getPlayerData().getPlaybackMode() == 4) {
                if (this.getVideo().playlistInfo.getSize() != -1) {
                    final Video video = new Video();
                    video.playlistId = this.getVideo().playlistId;
                    video.playlistIndex = Utils.getRandomIndex(this.getVideo().playlistInfo.getCurrentIndex(), this.getVideo().playlistInfo.getSize());
                    MediaServiceManager.instance().loadMetadata(video, (MediaServiceManager.OnMetadata)new VideoLoaderController$$ExternalSyntheticLambda0(this));
                }
                else {
                    final VideoGroup suggestionsByIndex = this.getPlayer().getSuggestionsByIndex(0);
                    if (suggestionsByIndex != null) {
                        final int randomIndex = Utils.getRandomIndex(suggestionsByIndex.indexOf(this.getVideo()), suggestionsByIndex.getSize());
                        if (randomIndex != -1) {
                            final Video value = suggestionsByIndex.get(randomIndex);
                            this.getVideo().nextMediaItem = SimpleMediaItem.from(value);
                            this.getPlayer().setNextTitle(value);
                        }
                    }
                }
            }
        }
    }
    
    private void loadRandomNext2() {
        if (this.getPlayer() != null && this.getPlayerData() != null && this.getVideo() != null && !this.getVideo().isShuffled && this.getVideo().shuffleMediaItem != null) {
            if (this.getPlayerData().getPlaybackMode() == 4) {
                this.getVideo().isShuffled = true;
                this.getVideo().playlistParams = this.getVideo().shuffleMediaItem.getParams();
                this.getController(SuggestionsController.class).loadSuggestions(this.getVideo());
            }
        }
    }
    
    private void loadSuggestions(final Video video) {
        if (video != null) {
            this.mPlaylist.setCurrent(video);
            this.getPlayer().setVideo(video);
            this.mSuggestionsController.loadSuggestions(video);
        }
    }
    
    private void loadVideo(final Video video) {
        if (this.getPlayer() != null && video != null) {
            this.mPlaylist.setCurrent(video);
            this.getPlayer().setVideo(video);
            this.getPlayer().resetPlayerState();
            this.loadFormatInfo(video);
        }
    }
    
    private void onLongBuffering() {
        if (this.getPlayer() != null) {
            if (this.getVideo() != null) {
                if (this.getPlayerTweaksData().isHighBitrateFormatsEnabled()) {
                    this.getPlayerTweaksData().setHighBitrateFormatsEnabled(false);
                    this.reloadVideo();
                }
                else if ((!this.getVideo().isLive || this.getVideo().isLiveEnd) && this.getPlayer().getDurationMs() - this.getPlayer().getPositionMs() < 180000L) {
                    this.getMainController().onPlayEnd();
                }
                else if (!this.getVideo().isLive && !this.getVideo().isLiveEnd) {
                    this.disableSubtitles();
                    this.reloadVideo();
                }
            }
        }
    }
    
    private void openVideoInt(final Video video) {
        if (video == null) {
            return;
        }
        this.disposeActions();
        if (video.hasVideo()) {
            this.getMainController().onNewVideo(video);
        }
        else {
            VideoActionPresenter.instance(this.getContext()).apply(video);
        }
    }
    
    private void preloadNextVideoIfNeeded() {
        if (!this.isEmbedPlayer() && this.getPlayer() != null && this.getVideo() != null) {
            if (!this.getVideo().isLive) {
                if (this.getPlayer().getDurationMs() - this.getPlayer().getPositionMs() < 50000L) {
                    MediaServiceManager.instance().loadFormatInfo(this.mSuggestionsController.getNext(), (MediaServiceManager.OnFormatInfo)new VideoLoaderController$$ExternalSyntheticLambda2());
                }
            }
        }
    }
    
    private void processFormatInfo(final MediaItemFormatInfo mediaItemFormatInfo) {
        final PlaybackView player = this.getPlayer();
        if (player != null) {
            if (this.getVideo() != null) {
                this.getVideo().sync(mediaItemFormatInfo);
                this.applyAspectRatio(mediaItemFormatInfo);
                if (mediaItemFormatInfo.getPaidContentText() != null && this.getSponsorBlockData().isPaidContentNotificationEnabled()) {
                    MessageHelpers.showMessage(this.getContext(), mediaItemFormatInfo.getPaidContentText());
                }
                final boolean unplayable = mediaItemFormatInfo.isUnplayable();
                final String s = null;
                String s2;
                if (unplayable) {
                    this.mLastFormatInfo = null;
                    this.mLastOpenType = 0;
                    if (this.isEmbedPlayer()) {
                        player.finish();
                        return;
                    }
                    player.setTitle(mediaItemFormatInfo.getPlayabilityReason());
                    player.showProgressBar(false);
                    this.mSuggestionsController.loadSuggestions(this.getVideo());
                    s2 = this.getVideo().getBackgroundUrl();
                    this.scheduleNextVideoTimer(5000);
                }
                else if (this.acceptAdaptiveFormats(mediaItemFormatInfo) && mediaItemFormatInfo.containsDashFormats()) {
                    Log.d(VideoLoaderController.TAG, (Object)"Loading regular video in dash format...", new Object[0]);
                    this.mLastFormatInfo = mediaItemFormatInfo;
                    this.mLastOpenType = 1;
                    if (this.getPlayerTweaksData().isHighBitrateFormatsEnabled() && mediaItemFormatInfo.hasExtendedHlsFormats()) {
                        player.openMerged(mediaItemFormatInfo, mediaItemFormatInfo.getHlsManifestUrl());
                        this.mLastOpenType = 0;
                        s2 = s;
                    }
                    else {
                        player.openDash(mediaItemFormatInfo);
                        s2 = s;
                    }
                }
                else if (this.acceptAdaptiveFormats(mediaItemFormatInfo) && mediaItemFormatInfo.containsSabrFormats()) {
                    Log.d(VideoLoaderController.TAG, (Object)"Loading video in sabr format...", new Object[0]);
                    this.mLastFormatInfo = mediaItemFormatInfo;
                    this.mLastOpenType = 2;
                    player.openSabr(mediaItemFormatInfo);
                    s2 = s;
                }
                else if (this.acceptDashLive(mediaItemFormatInfo)) {
                    this.mLastFormatInfo = null;
                    this.mLastOpenType = 0;
                    Log.d(VideoLoaderController.TAG, (Object)"Loading live video (current or past live stream) in dash format...", new Object[0]);
                    player.openDashUrl(mediaItemFormatInfo.getDashManifestUrl());
                    s2 = s;
                }
                else if (mediaItemFormatInfo.isLive() && mediaItemFormatInfo.containsHlsUrl()) {
                    this.mLastFormatInfo = null;
                    this.mLastOpenType = 0;
                    Log.d(VideoLoaderController.TAG, (Object)"Loading live video (current or past live stream) in hls format...", new Object[0]);
                    player.openHlsUrl(mediaItemFormatInfo.getHlsManifestUrl());
                    s2 = s;
                }
                else if (mediaItemFormatInfo.containsUrlFormats()) {
                    this.mLastFormatInfo = null;
                    this.mLastOpenType = 0;
                    Log.d(VideoLoaderController.TAG, (Object)"Loading url list video. This is always LQ...", new Object[0]);
                    player.openUrlList((List)this.applyFix(mediaItemFormatInfo.createUrlList()));
                    s2 = s;
                }
                else {
                    this.mLastFormatInfo = null;
                    this.mLastOpenType = 0;
                    Log.d(VideoLoaderController.TAG, (Object)"Empty format info received. Seems future live translation. No video data to pass to the player.", new Object[0]);
                    player.setTitle(mediaItemFormatInfo.getPlayabilityReason());
                    player.showProgressBar(false);
                    this.mSuggestionsController.loadSuggestions(this.getVideo());
                    s2 = this.getVideo().getBackgroundUrl();
                    this.scheduleReloadVideoTimer(30000);
                }
                player.showBackground(s2);
            }
        }
    }
    
    private void rebootApp() {
        this.scheduleRebootAppTimer(1000);
    }
    
    private void reloadVideo() {
        this.scheduleReloadVideoTimer(1000);
    }
    
    private void restartEngine() {
        this.scheduleRestartEngineTimer(1000);
    }
    
    private void restartPlaylistIfNeeded() {
        if (this.getPlayer() != null) {
            if (this.getVideo() != null) {
                final VideoGroup group = this.getVideo().getGroup();
                if (group != null && !group.isEmpty() && this.getVideo().belongsToSamePlaylistGroup()) {
                    this.openVideoInt(group.get(0));
                }
                else {
                    Log.e(VideoLoaderController.TAG, (Object)"VideoGroup is null or empty. Can't restart playlist.", new Object[0]);
                    this.stopPlayback();
                }
            }
        }
    }
    
    private void runEngineErrorAction(final int n, final int n2, final Throwable t) {
        if (this.isEmbedPlayer() && this.getPlayer() != null && this.getPlayer().getPositionMs() == 0L) {
            this.getPlayer().finish();
            return;
        }
        if (this.getVideo() != null && this.getVideo().isLiveEnd) {
            this.getMainController().onPlayEnd();
            return;
        }
        if (this.applyEngineErrorAction(n, n2, t)) {
            this.restartEngine();
        }
        else {
            this.reloadVideo();
        }
    }
    
    private void runFormatErrorAction(final Throwable t) {
        if (this.isEmbedPlayer()) {
            if (this.getPlayer() != null) {
                this.getPlayer().finish();
            }
            return;
        }
        final String message = t.getMessage();
        final String simpleName = t.getClass().getSimpleName();
        final String format = String.format("loadFormatInfo error: %s: %s", simpleName, Utils.getStackTraceAsString(t));
        final String tag = VideoLoaderController.TAG;
        Log.e(tag, (Object)format, new Object[0]);
        if (!Helpers.containsAny(message, new String[] { "fromNullable result is null" })) {
            MessageHelpers.showLongMessage(this.getContext(), format);
        }
        if (!Helpers.containsAny(message, new String[] { "Unexpected token", "Syntax error", "invalid argument" }) && !Helpers.equalsAny(simpleName, new String[] { "PoTokenException", "BadWebViewException" })) {
            if (Helpers.containsAny(message, new String[] { "is not defined" })) {
                YouTubeServiceManager.instance().invalidateCache();
                this.reloadVideo();
            }
            else {
                Log.e(tag, (Object)"Probably no internet connection", new Object[0]);
                this.scheduleReloadVideoTimer(1000);
            }
        }
        else {
            YouTubeServiceManager.instance().applyNoPlaybackFix();
            this.reloadVideo();
        }
    }
    
    private void scheduleNextVideoTimer(final int n) {
        if (this.getPlayer() == null) {
            return;
        }
        if (this.getPlayer().isEngineInitialized()) {
            Log.d(VideoLoaderController.TAG, (Object)"Starting the next video...", new Object[0]);
            this.getPlayer().showOverlay(true);
            Utils.postDelayed(this.mLoadNext, n);
        }
    }
    
    private void scheduleRebootAppTimer(final int n) {
        if (this.getPlayer() != null) {
            Log.d(VideoLoaderController.TAG, (Object)"Rebooting the app...", new Object[0]);
            this.getPlayer().showOverlay(true);
            Utils.postDelayed(this.mRebootApp, n);
        }
    }
    
    private void scheduleReloadVideoTimer(final int n) {
        if (this.getPlayer() == null) {
            return;
        }
        if (this.getPlayer().isEngineInitialized()) {
            Log.d(VideoLoaderController.TAG, (Object)"Reloading the video...", new Object[0]);
            this.getPlayer().showOverlay(true);
            Utils.postDelayed(this.mReloadVideo, n);
        }
    }
    
    private void scheduleRestartEngineTimer(final int n) {
        if (this.getPlayer() != null) {
            Log.d(VideoLoaderController.TAG, (Object)"Restarting the engine...", new Object[0]);
            this.getPlayer().showOverlay(true);
            Utils.postDelayed(this.mRestartEngine, n);
        }
    }
    
    private void stopPlayback() {
        if (this.getPlayer() == null) {
            return;
        }
        this.getPlayer().setPositionMs(this.getPlayer().getDurationMs());
        this.getPlayer().setPlayWhenReady(false);
        this.getPlayer().showSuggestions(true);
    }
    
    private void switchNextEngine() {
        this.getPlayerTweaksData().setPlayerDataSource(this.getNextEngine());
    }
    
    private void updateBufferingCount() {
        final long currentTimeMillis = System.currentTimeMillis();
        final Pair<Integer, Long> mBufferingCount = this.mBufferingCount;
        int intValue;
        long longValue;
        if (mBufferingCount != null) {
            intValue = (int)mBufferingCount.first;
            longValue = (long)this.mBufferingCount.second;
        }
        else {
            intValue = 0;
            longValue = 0L;
        }
        int i = 1;
        if (currentTimeMillis - longValue < 60000L) {
            i = 1 + intValue;
        }
        this.mBufferingCount = (Pair<Integer, Long>)new Pair((Object)i, (Object)currentTimeMillis);
    }
    
    private void updateBufferingCountIfNeeded() {
        this.updateBufferingCount();
        if (this.isBufferingRecurrent()) {
            this.mBufferingCount = null;
            this.onLongBuffering();
        }
        else {
            Utils.postDelayed(this.mOnLongBuffering, 20000L);
        }
    }
    
    private void waitMetadataSync(final Video video, final boolean b) {
        if (video == null) {
            return;
        }
        if (video.nextMediaItem != null) {
            this.openVideoInt(Video.from(video.nextMediaItem));
        }
        else if (!video.isSynced) {
            if (b) {
                MessageHelpers.showMessageThrottled(this.getContext(), R.string.wait_data_loading);
            }
            if (this.getPlayer() != null && Math.abs(this.getPlayer().getDurationMs() - this.getPlayer().getPositionMs()) < 100L) {
                Utils.postDelayed(this.mMetadataSync, 1000L);
            }
        }
    }
    
    public boolean canReopenWithTranslation() {
        final PlaybackView player = this.getPlayer();
        return (this.mLastFormatInfo != null && this.effectiveOpenTypeForTranslation() != 0) || (player != null && player.containsMedia());
    }
    
    public MediaItemFormatInfo getLastFormatInfo() {
        return this.mLastFormatInfo;
    }
    
    public void loadNext() {
        if (this.getPlayer() != null) {
            if (this.getVideo() != null) {
                final Video next = this.mSuggestionsController.getNext();
                if (next != null) {
                    next.isShuffled = this.getVideo().isShuffled;
                    this.openVideoInt(next);
                }
                else {
                    this.waitMetadataSync(this.getVideo(), true);
                }
                if (this.getPlayerTweaksData().isPlayerUiOnNextEnabled()) {
                    this.getPlayer().showOverlay(true);
                }
            }
        }
    }
    
    public void loadPrevious() {
        if (this.getPlayer() == null) {
            return;
        }
        this.openVideoInt(this.mSuggestionsController.getPrevious());
        if (this.getPlayerTweaksData().isPlayerUiOnNextEnabled()) {
            this.getPlayer().showOverlay(true);
        }
    }
    
    @Override
    public void onBuffering() {
        Utils.postDelayed(this.mOnLongBuffering, 3000L);
    }
    
    @Override
    public void onEngineError(final int n, final int n2, final Throwable t) {
        Log.e(VideoLoaderController.TAG, (Object)"Player error occurred: %s. Trying to fix\u2026", new Object[] { n });
        this.runEngineErrorAction(this.mLastErrorType = n, n2, t);
    }
    
    @Override
    public void onEngineInitialized() {
        if (this.getPlayer() == null) {
            return;
        }
        this.loadVideo((Video)Helpers.firstNonNull((Object[])new Video[] { this.mPendingVideo, this.getVideo() }));
        this.getPlayer().setButtonState(R.id.action_repeat, this.getPlayerData().getPlaybackMode());
        this.mSleepTimerStartMs = System.currentTimeMillis();
        this.mPendingVideo = null;
    }
    
    @Override
    public void onEngineReleased() {
        this.disposeActions();
    }
    
    @Override
    public void onInit() {
        this.mSuggestionsController = this.getController(SuggestionsController.class);
        this.mSleepTimerStartMs = System.currentTimeMillis();
    }
    
    @Override
    public boolean onKeyDown(final int n) {
        if (this.getPlayer() == null) {
            return false;
        }
        this.mSleepTimerStartMs = System.currentTimeMillis();
        if (this.getPlayerData().isSleepTimerEnabled()) {
            this.getPlayer().setVideo(this.getVideo());
        }
        Utils.removeCallbacks(this.mRestartEngine, this.mRebootApp);
        return false;
    }
    
    @Override
    public void onMetadata(final MediaItemMetadata mediaItemMetadata) {
        this.loadRandomNext();
    }
    
    @Override
    public void onNewVideo(final Video mPendingVideo) {
        if (mPendingVideo == null) {
            return;
        }
        if (!mPendingVideo.fromQueue && !mPendingVideo.belongsToPlaybackQueue()) {
            this.mPlaylist.add(mPendingVideo);
        }
        else {
            mPendingVideo.fromQueue = false;
        }
        if (this.getPlayer() != null && this.getPlayer().isEngineInitialized()) {
            this.loadVideo(mPendingVideo);
        }
        else {
            this.mPendingVideo = mPendingVideo;
        }
    }
    
    @Override
    public boolean onNextClicked() {
        if (this.getGeneralData().isChildModeEnabled()) {
            this.onPlayEnd();
        }
        else {
            this.loadNext();
        }
        return true;
    }
    
    @Override
    public void onPause() {
        Utils.removeCallbacks(this.mOnLongBuffering);
    }
    
    @Override
    public void onPlay() {
        Utils.removeCallbacks(this.mOnLongBuffering);
    }
    
    @Override
    public void onPlayEnd() {
        if (this.getPlayer() == null) {
            return;
        }
        final int playbackMode = this.getPlaybackMode();
        if (this.getAppDialogPresenter().isDialogShown() && !this.getAppDialogPresenter().isOverlay() && playbackMode != 3) {
            this.getAppDialogPresenter().setOnFinish(this.mOnApplyPlaybackMode);
        }
        else {
            this.applyPlaybackMode(playbackMode);
        }
    }
    
    @Override
    public boolean onPreviousClicked() {
        this.loadPrevious();
        return true;
    }
    
    @Override
    public void onSeekEnd() {
        this.mBufferingCount = null;
    }
    
    @Override
    public void onSuggestionItemClicked(final Video video) {
        this.openVideoInt(video);
        if (this.getPlayer() != null) {
            this.getPlayer().showControls(false);
        }
    }
    
    @Override
    public void onTickle() {
        this.checkSleepTimer();
    }
    
    @Override
    public void onVideoLoaded(final Video video) {
        if (this.getPlayer() == null) {
            return;
        }
        this.mLastErrorType = -1;
        final PlaybackView player = this.getPlayer();
        final int action_repeat = R.id.action_repeat;
        int playbackMode;
        if (video.finishOnEnded) {
            playbackMode = 1;
        }
        else {
            playbackMode = this.getPlayerData().getPlaybackMode();
        }
        player.setButtonState(action_repeat, playbackMode);
    }
    
    public boolean reopenWithTranslationAudio(final String s) {
        final PlaybackView player = this.getPlayer();
        if (player != null) {
            if (s != null) {
                final int effectiveOpenTypeForTranslation = this.effectiveOpenTypeForTranslation();
                if (effectiveOpenTypeForTranslation == 1) {
                    final MediaItemFormatInfo mLastFormatInfo = this.mLastFormatInfo;
                    if (mLastFormatInfo != null) {
                        player.openDashWithTranslationAudio(mLastFormatInfo, s);
                        return true;
                    }
                }
                if (effectiveOpenTypeForTranslation == 2) {
                    final MediaItemFormatInfo mLastFormatInfo2 = this.mLastFormatInfo;
                    if (mLastFormatInfo2 != null) {
                        player.openSabrWithTranslationAudio(mLastFormatInfo2, s);
                        return true;
                    }
                }
                if (player.containsMedia()) {
                    player.attachTranslationAudio(s);
                    return true;
                }
            }
        }
        return false;
    }
    
    public boolean reopenWithoutTranslationAudio() {
        final PlaybackView player = this.getPlayer();
        if (player == null) {
            return false;
        }
        final int effectiveOpenTypeForTranslation = this.effectiveOpenTypeForTranslation();
        if (effectiveOpenTypeForTranslation == 1) {
            final MediaItemFormatInfo mLastFormatInfo = this.mLastFormatInfo;
            if (mLastFormatInfo != null) {
                player.openDash(mLastFormatInfo);
                return true;
            }
        }
        if (effectiveOpenTypeForTranslation == 2) {
            final MediaItemFormatInfo mLastFormatInfo2 = this.mLastFormatInfo;
            if (mLastFormatInfo2 != null) {
                player.openSabr(mLastFormatInfo2);
                return true;
            }
        }
        player.clearTranslationAudio();
        return true;
    }
}
