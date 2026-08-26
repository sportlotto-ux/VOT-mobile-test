package com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.renderer;

import android.content.Context;
import android.os.Handler;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

import com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.ExoUtils;

/**
 * Media3 version.
 *
 * Adds codec info to the debug overlay and enables setOutputSurface workaround on demand.
 */
public class DebugInfoMediaCodecVideoRenderer extends MediaCodecVideoRenderer {
    private static final String TAG = DebugInfoMediaCodecVideoRenderer.class.getSimpleName();

    private boolean mIsSetOutputSurfaceWorkaroundEnabled;

    public DebugInfoMediaCodecVideoRenderer(Context context, MediaCodecSelector mediaCodecSelector, long allowedJoiningTimeMs,
                                            boolean enableDecoderFallback, Handler eventHandler, VideoRendererEventListener eventListener,
                                            int maxDroppedFramesToNotify) {
        super(context, mediaCodecSelector, allowedJoiningTimeMs, enableDecoderFallback, eventHandler, eventListener,
                maxDroppedFramesToNotify);
    }

    @Override
    protected CodecMaxValues getCodecMaxValues(
            MediaCodecInfo codecInfo, Format format, Format[] streamFormats) {
        ExoUtils.updateVideoDecoderInfo(codecInfo);

        return super.getCodecMaxValues(codecInfo, format, streamFormats);
    }

    @Override
    protected boolean codecNeedsSetOutputSurfaceWorkaround(String name) {
        // Null surface error on Android 9 (VERSION.SDK_INT >= 28) and above (appears on background audio playback)
        return mIsSetOutputSurfaceWorkaroundEnabled || super.codecNeedsSetOutputSurfaceWorkaround(name);
    }

    /**
     * Null surface error on Android 9 (VERSION.SDK_INT >= 28) and above (appears on background audio playback)
     */
    public void enableSetOutputSurfaceWorkaround(boolean enable) {
        mIsSetOutputSurfaceWorkaroundEnabled = enable;
    }
}
