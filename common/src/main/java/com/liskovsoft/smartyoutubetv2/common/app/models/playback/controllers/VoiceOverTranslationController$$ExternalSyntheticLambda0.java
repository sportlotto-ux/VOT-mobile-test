/*
 * Decompiled with CFR 0.152.
 */
package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.VoiceOverTranslationController;
import com.liskovsoft.smartyoutubetv2.common.vot.VotApiService;

public final class VoiceOverTranslationController$$ExternalSyntheticLambda0
implements VotApiService.ProgressListener {
    public final /* synthetic */ String[] f$0;

    public /* synthetic */ VoiceOverTranslationController$$ExternalSyntheticLambda0(String[] stringArray) {
        this.f$0 = stringArray;
    }

    @Override
    public final void onProgress(String string2) {
        VoiceOverTranslationController.lambda$runTranslateRequest$0(this.f$0, string2);
    }
}

