package com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.renderer;

import android.content.Context;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;

import android.os.Handler;

import java.nio.ByteBuffer;

import androidx.annotation.Nullable;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;

import com.liskovsoft.sharedutils.helpers.Helpers;

/**
 * Media3 version.
 *
 * Main intent: override audio delay. Also contains audio sync fix.
 */
public class DelayMediaCodecAudioRenderer extends MediaCodecAudioRenderer {
    private static final String TAG = DelayMediaCodecAudioRenderer.class.getSimpleName();

    private long mDelayUs;
    private boolean mIsAudioSyncFixEnabled;
    private boolean mIsAudioSyncFixChanged;

    public DelayMediaCodecAudioRenderer(Context context, MediaCodecSelector mediaCodecSelector,
                                        boolean enableDecoderFallback, Handler eventHandler,
                                        AudioRendererEventListener eventListener, AudioSink audioSink) {
        super(context, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, audioSink);
    }

    @Override
    public long getPositionUs() {
        return super.getPositionUs() + mDelayUs;
    }

    public void setAudioDelayMs(int delayMs) {
        mDelayUs = delayMs * 1_000;
    }

    public int getAudioDelayMs() {
        return (int) (mDelayUs / 1_000);
    }

    @Override
    protected boolean processOutputBuffer(long positionUs, long elapsedRealtimeUs, @Nullable MediaCodecAdapter codec, @Nullable ByteBuffer buffer,
                                          int bufferIndex, int bufferFlags, int sampleCount, long bufferPresentationTimeUs,
                                          boolean isDecodeOnlyBuffer, boolean isLastBuffer, Format format) throws ExoPlaybackException {
        boolean result = super.processOutputBuffer(
                positionUs, elapsedRealtimeUs, codec, buffer, bufferIndex, bufferFlags, sampleCount,
                bufferPresentationTimeUs, isDecodeOnlyBuffer, isLastBuffer, format
        );

        // Disable the use of AudioTrack.getTimestamp and force ExoPlayer to go through the legacy path of using
        // AudioTrack.getPlaybackHeadPosition instead, which might help if the first one drifts but the second one doesn't.
        if (mIsAudioSyncFixEnabled && mIsAudioSyncFixChanged) {
            try {
                Object audioSink = Helpers.getField(this, "audioSink");
                if (audioSink != null) {
                    Object audioTrackPositionTracker = Helpers.getField(audioSink, "audioTrackPositionTracker");
                    if (audioTrackPositionTracker != null) {
                        Object audioTimestampPoller = Helpers.getField(audioTrackPositionTracker, "audioTimestampPoller");
                        if (audioTimestampPoller != null) {
                            Helpers.setField(audioTimestampPoller, "audioTimestamp", null);
                            Helpers.setField(audioTimestampPoller, "state", 3);
                            mIsAudioSyncFixChanged = false;
                        }
                    }
                }
            } catch (Exception e) {
                // Media3 internals changed, fix isn't applicable anymore
                mIsAudioSyncFixChanged = false;
            }
        }

        return result;
    }

    public void enableAudioSyncFix(boolean enable) {
        if (mIsAudioSyncFixEnabled == enable) {
            return;
        }

        mIsAudioSyncFixEnabled = enable;
        mIsAudioSyncFixChanged = true;
    }

    public boolean isAudioSyncFixEnabled() {
        return mIsAudioSyncFixEnabled;
    }
}
