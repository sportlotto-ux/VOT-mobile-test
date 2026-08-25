// 
// Decompiled by Procyon v0.6.0
// 

package com.liskovsoft.smartyoutubetv2.common.vot;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;

public interface VotApiService
{
    VotTranslateResult translate(final String p0, final String p1, final int p2, final String p3, final String p4, final MediaItemFormatInfo p5, final String p6, final ProgressListener p7);
    
    public interface ProgressListener
    {
        void onProgress(final String p0);
    }
}
