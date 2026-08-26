package com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.renderer;

import android.content.Context;
import android.os.Handler;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.util.Log;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

/**
 * Media3 version.
 *
 * Frame drop fixes (Amazon SurfaceView, Sony Bravia), Amlogic max values fix.
 */
public class TweaksMediaCodecVideoRenderer extends DebugInfoMediaCodecVideoRenderer {
    private static final String TAG = TweaksMediaCodecVideoRenderer.class.getSimpleName();

    private boolean mIsFrameDropFixEnabled;
    private boolean mIsFrameDropSonyFixEnabled;
    private boolean mIsAmlogicFixEnabled;

    public TweaksMediaCodecVideoRenderer(Context context, MediaCodecSelector mediaCodecSelector, long allowedJoiningTimeMs,
                                         boolean enableDecoderFallback, Handler eventHandler, VideoRendererEventListener eventListener,
                                         int maxDroppedFramesToNotify) {
        super(context, mediaCodecSelector, allowedJoiningTimeMs, enableDecoderFallback, eventHandler, eventListener,
                maxDroppedFramesToNotify);
    }

    // Fix frame drops on SurfaceView
    // https://github.com/google/ExoPlayer/issues/6348
    @Override
    protected void renderOutputBufferV21(
            MediaCodecAdapter codec, int index, long presentationTimeUs, long releaseTimeNs) {
        super.renderOutputBufferV21(codec, index, presentationTimeUs, mIsFrameDropFixEnabled ? 0 : releaseTimeNs);
    }

    @Override
    protected CodecMaxValues getCodecMaxValues(
            MediaCodecInfo codecInfo, Format format, Format[] streamFormats) {
        CodecMaxValues maxValues =
                super.getCodecMaxValues(codecInfo, format, streamFormats);

        if (mIsAmlogicFixEnabled) {
            if (maxValues.width < 1920 || maxValues.height < 1089) {
                Log.d(TAG, "Applying Amlogic fix...");
                return new CodecMaxValues(
                        Math.max(maxValues.width, 1920),
                        Math.max(maxValues.height, 1089),
                        maxValues.inputSize);
            }
        }

        return maxValues;
    }

    /**
     * Frame drop fixes on Sony Bravia<br/>
     * https://github.com/google/ExoPlayer/issues/6348#issuecomment-718986083
     */
    @Override
    protected boolean shouldDropOutputBuffer(long earlyUs, long elapsedRealtimeUs, boolean isLastBuffer) {
        if (mIsFrameDropSonyFixEnabled) {
            return earlyUs < -1000000 && !isLastBuffer;
        }

        return super.shouldDropOutputBuffer(earlyUs, elapsedRealtimeUs, isLastBuffer);
    }

    /**
     * Frame drop fixes on Sony Bravia<br/>
     * https://github.com/google/ExoPlayer/issues/6348#issuecomment-718986083
     */
    @Override
    protected boolean shouldDropBuffersToKeyframe(long earlyUs, long elapsedRealtimeUs, boolean isLastBuffer) {
        if (mIsFrameDropSonyFixEnabled) {
            return earlyUs < -1500000 && !isLastBuffer;
        }

        return super.shouldDropBuffersToKeyframe(earlyUs, elapsedRealtimeUs, isLastBuffer);
    }

    public void enableFrameDropFix(boolean enabled) {
        mIsFrameDropFixEnabled = enabled;
    }

    public void enableFrameDropSonyFix(boolean enabled) {
        mIsFrameDropSonyFixEnabled = enabled;
    }

    public void enableAmlogicFix(boolean enabled) {
        mIsAmlogicFixEnabled = enabled;
    }
}
