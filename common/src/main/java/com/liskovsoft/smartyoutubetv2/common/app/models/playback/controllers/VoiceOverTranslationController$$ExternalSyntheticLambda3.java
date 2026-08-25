/*
 * Decompiled with CFR 0.152.
 */
package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.VideoLoaderController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.VoiceOverTranslationController;

public final class VoiceOverTranslationController$$ExternalSyntheticLambda3
implements Runnable {
    public final /* synthetic */ VoiceOverTranslationController f$0;
    public final /* synthetic */ int f$1;
    public final /* synthetic */ String f$2;
    public final /* synthetic */ int f$3;
    public final /* synthetic */ VideoLoaderController f$4;
    public final /* synthetic */ boolean f$5;
    public final /* synthetic */ boolean f$6;

    public /* synthetic */ VoiceOverTranslationController$$ExternalSyntheticLambda3(VoiceOverTranslationController voiceOverTranslationController, int n, String string2, int n2, VideoLoaderController videoLoaderController, boolean bl, boolean bl2) {
        this.f$0 = voiceOverTranslationController;
        this.f$1 = n;
        this.f$2 = string2;
        this.f$3 = n2;
        this.f$4 = videoLoaderController;
        this.f$5 = bl;
        this.f$6 = bl2;
    }

    @Override
    public final void run() {
        this.f$0.lambda$applyResult$4$com-liskovsoft-smartyoutubetv2-common-app-models-playback-controllers-VoiceOverTranslationController(this.f$1, this.f$2, this.f$3, this.f$4, this.f$5, this.f$6);
    }
}

