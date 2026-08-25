package com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.renderer;

import android.content.Context;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.selector.BlacklistMediaCodecSelector;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;

/**
 * Media3 version.
 *
 * NOTE: custom MediaCodecVideoRenderer / MediaCodecAudioRenderer overrides are disabled
 * during migration (their constructors changed drastically in media3).
 * Temporarily lost tweaks: audio delay, frame drop fixes (Amazon/Sony/Amlogic), debug info overlay.
 */
public class CustomOverridesRenderersFactory extends DefaultRenderersFactory {
    private static final String TAG = CustomOverridesRenderersFactory.class.getSimpleName();

    public CustomOverridesRenderersFactory(Context activity) {
        super(activity);

        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON);

        if (PlayerTweaksData.instance(activity).isSWDecoderForced()) {
            setMediaCodecSelector(new BlacklistMediaCodecSelector());
        }
    }
}
