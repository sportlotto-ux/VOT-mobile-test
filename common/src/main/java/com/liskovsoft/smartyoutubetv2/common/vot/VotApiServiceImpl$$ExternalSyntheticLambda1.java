/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.liskovsoft.mediaserviceinterfaces.data.MediaFormat
 */
package com.liskovsoft.smartyoutubetv2.common.vot;

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.smartyoutubetv2.common.vot.VotApiServiceImpl;
import java.util.function.ToIntFunction;

public final class VotApiServiceImpl$$ExternalSyntheticLambda1
implements ToIntFunction {
    public final /* synthetic */ VotApiServiceImpl f$0;
    public final /* synthetic */ String f$1;

    public /* synthetic */ VotApiServiceImpl$$ExternalSyntheticLambda1(VotApiServiceImpl votApiServiceImpl, String string2) {
        this.f$0 = votApiServiceImpl;
        this.f$1 = string2;
    }

    public final int applyAsInt(Object object) {
        return this.f$0.lambda$findAudioFormatsForUpload$0VotApiServiceImpl(this.f$1, (MediaFormat)object);
    }
}

