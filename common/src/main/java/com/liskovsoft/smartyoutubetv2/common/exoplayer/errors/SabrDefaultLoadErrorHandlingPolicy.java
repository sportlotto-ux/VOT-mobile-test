package com.liskovsoft.smartyoutubetv2.common.exoplayer.errors;

import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo;
import com.liskovsoft.sharedutils.helpers.Helpers;

import androidx.media3.common.C;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.datasource.HttpDataSource;

import java.io.IOException;

public class SabrDefaultLoadErrorHandlingPolicy extends DashDefaultLoadErrorHandlingPolicy {
    @Override
    public long getRetryDelayMsFor(LoadErrorInfo loadErrorInfo) {
        IOException exception = loadErrorInfo.exception;
        if (exception != null && Helpers.contains(exception.getMessage(), "Wait 5 sec")) {
            return 5_000;
        }

        return super.getRetryDelayMsFor(loadErrorInfo);
    }
}
