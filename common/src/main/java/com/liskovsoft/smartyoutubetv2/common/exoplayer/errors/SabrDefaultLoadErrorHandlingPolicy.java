package com.liskovsoft.smartyoutubetv2.common.exoplayer.errors;

import com.liskovsoft.sharedutils.helpers.Helpers;

import java.io.IOException;

public class SabrDefaultLoadErrorHandlingPolicy extends DashDefaultLoadErrorHandlingPolicy {
    @Override
    public long getBlacklistDurationMsFor(LoadErrorInfo loadErrorInfo) {
        return super.getBlacklistDurationMsFor(dataType, loadDurationMs, exception, errorCount);
    }
    
    @Override
    public long getRetryDelayMsFor(LoadErrorInfo loadErrorInfo) {
        if (Helpers.contains(exception.getMessage(), "Wait 5 sec")) {
            return 5_000;
        }

        return super.getRetryDelayMsFor(dataType, loadDurationMs, exception, errorCount);
    }
}
