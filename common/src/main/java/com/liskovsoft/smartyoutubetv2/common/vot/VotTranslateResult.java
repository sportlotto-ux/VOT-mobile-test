// 
// Decompiled by Procyon v0.6.0
// 

package com.liskovsoft.smartyoutubetv2.common.vot;

public class VotTranslateResult
{
    public final String debug;
    public final String message;
    public final int remainingTime;
    public final String status;
    public final boolean translated;
    public final String translationId;
    public final String url;
    
    public VotTranslateResult(final boolean translated, final int remainingTime, final String url, final String message, final String status, final String debug, final String translationId) {
        this.translated = translated;
        this.remainingTime = remainingTime;
        this.url = url;
        this.message = message;
        this.status = status;
        this.debug = debug;
        this.translationId = translationId;
    }
    
    public boolean isAudioRequested() {
        return "AUDIO_REQUESTED".equalsIgnoreCase(this.status);
    }
    
    public boolean isReady() {
        if (this.translated) {
            final String url = this.url;
            if (url != null && !url.isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
