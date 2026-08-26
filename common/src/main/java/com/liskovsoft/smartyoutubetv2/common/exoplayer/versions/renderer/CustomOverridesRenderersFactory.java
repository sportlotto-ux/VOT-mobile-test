package com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.renderer;

import android.content.Context;
import android.os.Handler;

import androidx.annotation.Nullable;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;

import com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.selector.BlacklistMediaCodecSelector;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.util.AmazonQuirks;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;

import java.util.ArrayList;

/**
 * Media3 version.
 *
 * Main intent: override audio delay, apply frame drop fixes (Amazon/Sony/Amlogic), debug info overlay.
 */
public class CustomOverridesRenderersFactory extends CustomRenderersFactoryBase {
    private static final String TAG = CustomOverridesRenderersFactory.class.getSimpleName();

    private final PlayerData mPlayerData;
    private final PlayerTweaksData mPlayerTweaksData;

    public CustomOverridesRenderersFactory(Context activity) {
        super(activity);

        mPlayerData = PlayerData.instance(activity);
        mPlayerTweaksData = PlayerTweaksData.instance(activity);

        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON);

        if (mPlayerTweaksData.isSWDecoderForced()) {
            setMediaCodecSelector(new BlacklistMediaCodecSelector());
        }

        AmazonQuirks.disableSnappingToVsync(mPlayerTweaksData.isSnappingToVsyncDisabled());
        AmazonQuirks.skipProfileLevelCheck(mPlayerTweaksData.isProfileLevelCheckSkipped());
    }

    @Override
    protected void buildAudioRenderers(Context context, @ExtensionRendererMode int extensionRendererMode,
                                       MediaCodecSelector mediaCodecSelector, boolean enableDecoderFallback, AudioSink audioSink,
                                       Handler eventHandler, AudioRendererEventListener eventListener, ArrayList<Renderer> out) {
        super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink,
                eventHandler, eventListener, out);

        if ((mPlayerData.getAudioDelayMs() == 0 || !mPlayerData.isAudioDelayEnabled()) && !mPlayerTweaksData.isAudioSyncFixEnabled()) {
            // Improve performance a bit by eliminating calculations presented in custom renderer.
            return;
        }

        DelayMediaCodecAudioRenderer audioRenderer =
                new DelayMediaCodecAudioRenderer(context, mediaCodecSelector, enableDecoderFallback, eventHandler,
                        eventListener, audioSink);

        audioRenderer.setAudioDelayMs(mPlayerData.isAudioDelayEnabled() ? mPlayerData.getAudioDelayMs() : 0);
        audioRenderer.enableAudioSyncFix(mPlayerTweaksData.isAudioSyncFixEnabled());

        replaceAudioRenderer(out, audioRenderer);
    }

    @Override
    protected void buildVideoRenderers(Context context, @ExtensionRendererMode int extensionRendererMode,
                                       MediaCodecSelector mediaCodecSelector, boolean enableDecoderFallback, Handler eventHandler,
                                       VideoRendererEventListener eventListener, long allowedVideoJoiningTimeMs,
                                       ArrayList<Renderer> out) {
        super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, eventHandler,
                eventListener, allowedVideoJoiningTimeMs, out);

        if (!mPlayerTweaksData.isAmazonFrameDropFixEnabled() && !mPlayerTweaksData.isSonyFrameDropFixEnabled() && !mPlayerTweaksData.isAmlogicFixEnabled()) {
            // Improve performance a bit by eliminating some if conditions presented in tweaks.
            DebugInfoMediaCodecVideoRenderer videoRenderer =
                    new DebugInfoMediaCodecVideoRenderer(context, mediaCodecSelector, allowedVideoJoiningTimeMs,
                            enableDecoderFallback, eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY);

            videoRenderer.enableSetOutputSurfaceWorkaround(true); // Force enable?

            replaceVideoRenderer(out, videoRenderer);

            return;
        }

        TweaksMediaCodecVideoRenderer videoRenderer =
                new TweaksMediaCodecVideoRenderer(context, mediaCodecSelector, allowedVideoJoiningTimeMs,
                        enableDecoderFallback, eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY);

        videoRenderer.enableFrameDropFix(mPlayerTweaksData.isAmazonFrameDropFixEnabled());
        videoRenderer.enableFrameDropSonyFix(mPlayerTweaksData.isSonyFrameDropFixEnabled());
        videoRenderer.enableAmlogicFix(mPlayerTweaksData.isAmlogicFixEnabled());
        videoRenderer.enableSetOutputSurfaceWorkaround(true); // Force enable?

        replaceVideoRenderer(out, videoRenderer);
    }
}
