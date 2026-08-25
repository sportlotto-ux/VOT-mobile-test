/*
 * Decompiled with CFR 0.152.
 */
package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.VoiceOverTranslationController;

public final class VoiceOverTranslationController$$ExternalSyntheticLambda2
implements Runnable {
    public final /* synthetic */ VoiceOverTranslationController f$0;
    public final /* synthetic */ int f$1;
    public final /* synthetic */ String[] f$2;
    public final /* synthetic */ Exception f$3;
    public final /* synthetic */ boolean f$4;
    public final /* synthetic */ boolean f$5;

    public /* synthetic */ VoiceOverTranslationController$$ExternalSyntheticLambda2(VoiceOverTranslationController voiceOverTranslationController, int n, String[] stringArray, Exception exception, boolean bl, boolean bl2) {
        this.f$0 = voiceOverTranslationController;
        this.f$1 = n;
        this.f$2 = stringArray;
        this.f$3 = exception;
        this.f$4 = bl;
        this.f$5 = bl2;
    }

    @Override
    public final void run() {
        this.f$0.lambda$runTranslateRequest$2(this.f$1, this.f$2, this.f$3, this.f$4, this.f$5);
    }
}

