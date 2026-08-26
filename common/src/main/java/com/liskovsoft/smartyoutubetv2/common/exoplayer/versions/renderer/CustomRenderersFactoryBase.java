package com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.renderer;

import android.content.Context;
import android.os.Handler;
import androidx.annotation.Nullable;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;

import java.util.ArrayList;

/**
 * Media3 version of the base factory that swaps stock renderers with tweaked ones.
 */
public abstract class CustomRenderersFactoryBase extends androidx.media3.exoplayer.DefaultRenderersFactory {
    public CustomRenderersFactoryBase(Context context) {
        super(context);
    }

    protected void replaceVideoRenderer(ArrayList<Renderer> renderers, MediaCodecVideoRenderer videoRenderer) {
        if (renderers != null && videoRenderer != null) {
            Renderer originMediaCodecVideoRenderer = null;
            int index = 0;

            for (Renderer renderer : renderers) {
                if (renderer instanceof MediaCodecVideoRenderer) {
                    originMediaCodecVideoRenderer = renderer;
                    break;
                }
                index++;
            }

            if (originMediaCodecVideoRenderer != null) {
                // replace origin with custom
                renderers.remove(originMediaCodecVideoRenderer);
                renderers.add(index, videoRenderer);
            }
        }
    }

    protected void replaceAudioRenderer(ArrayList<Renderer> renderers, MediaCodecAudioRenderer audioRenderer) {
        if (renderers != null && audioRenderer != null) {
            Renderer originMediaCodecAudioRenderer = null;
            int index = 0;

            for (Renderer renderer : renderers) {
                if (renderer instanceof MediaCodecAudioRenderer) {
                    originMediaCodecAudioRenderer = renderer;
                    break;
                }
                index++;
            }

            if (originMediaCodecAudioRenderer != null) {
                // replace origin with custom
                renderers.remove(originMediaCodecAudioRenderer);
                renderers.add(index, audioRenderer);
            }
        }
    }
}
