/*
 * Decompiled with CFR 0.152.
 */
package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.VideoLoaderController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.VoiceOverTranslationController;
import com.liskovsoft.smartyoutubetv2.common.vot.VotTranslateResult;

public final class VoiceOverTranslationController$$ExternalSyntheticLambda1
implements Runnable {
    public final /* synthetic */ VoiceOverTranslationController f$0;
    public final /* synthetic */ VotTranslateResult f$1;
    public final /* synthetic */ String f$2;
    public final /* synthetic */ int f$3;
    public final /* synthetic */ VideoLoaderController f$4;
    public final /* synthetic */ boolean f$5;
    public final /* synthetic */ boolean f$6;
    public final /* synthetic */ int f$7;

    public /* synthetic */ VoiceOverTranslationController$$ExternalSyntheticLambda1(VoiceOverTranslationController voiceOverTranslationController, VotTranslateResult votTranslateResult, String string2, int n, VideoLoaderController videoLoaderController, boolean bl, boolean bl2, int n2) {
        this.f$0 = voiceOverTranslationController;
        this.f$1 = votTranslateResult;
        this.f$2 = string2;
        this.f$3 = n;
        this.f$4 = videoLoaderController;
        this.f$5 = bl;
        this.f$6 = bl2;
        this.f$7 = n2;
    }

    @Override
    public final void run() {
        this.f$0.lambda$runTranslateRequest$1(this.f$1, this.f$2, this.f$3, this.f$4, this.f$5, this.f$6, this.f$7);
    }
}

