/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  android.app.Activity
 */
package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import android.app.Activity;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.VideoLoaderController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.VoiceOverTranslationController;
import com.liskovsoft.smartyoutubetv2.common.vot.VotApiService;

public final class VoiceOverTranslationController$$ExternalSyntheticLambda4
implements Runnable {
    public final /* synthetic */ VoiceOverTranslationController f$0;
    public final /* synthetic */ VotApiService f$1;
    public final /* synthetic */ boolean f$10;
    public final /* synthetic */ boolean f$11;
    public final /* synthetic */ int f$12;
    public final /* synthetic */ Video f$2;
    public final /* synthetic */ String f$3;
    public final /* synthetic */ int f$4;
    public final /* synthetic */ VideoLoaderController f$5;
    public final /* synthetic */ String[] f$6;
    public final /* synthetic */ Activity f$7;
    public final /* synthetic */ String f$8;
    public final /* synthetic */ int f$9;

    public /* synthetic */ VoiceOverTranslationController$$ExternalSyntheticLambda4(VoiceOverTranslationController voiceOverTranslationController, VotApiService votApiService, Video video, String string2, int n, VideoLoaderController videoLoaderController, String[] stringArray, Activity activity, String string3, int n2, boolean bl, boolean bl2, int n3) {
        this.f$0 = voiceOverTranslationController;
        this.f$1 = votApiService;
        this.f$2 = video;
        this.f$3 = string2;
        this.f$4 = n;
        this.f$5 = videoLoaderController;
        this.f$6 = stringArray;
        this.f$7 = activity;
        this.f$8 = string3;
        this.f$9 = n2;
        this.f$10 = bl;
        this.f$11 = bl2;
        this.f$12 = n3;
    }

    @Override
    public final void run() {
        this.f$0.lambda$runTranslateRequest$3$com-liskovsoft-smartyoutubetv2-common-app-models-playback-controllers-VoiceOverTranslationController(this.f$1, this.f$2, this.f$3, this.f$4, this.f$5, this.f$6, this.f$7, this.f$8, this.f$9, this.f$10, this.f$11, this.f$12);
    }
}

