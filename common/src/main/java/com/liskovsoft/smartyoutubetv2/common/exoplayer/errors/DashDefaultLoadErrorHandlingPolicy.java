package com.liskovsoft.smartyoutubetv2.common.exoplayer.errors;

import androidx.media3.common.C;
import androidx.media3.exoplayer.ParserException;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException;
import androidx.media3.exoplayer.upstream.Loader.UnexpectedLoaderException;

import java.io.FileNotFoundException;
import java.io.IOException;

public class DashDefaultLoadErrorHandlingPolicy extends DefaultLoadErrorHandlingPolicy {
    /**
     * Copied from the parent class!
     */
    @Override
    public long getBlacklistDurationMsFor(int dataType, long loadDurationMs, IOException exception, int errorCount) {
        if (exception instanceof InvalidResponseCodeException) {
            int responseCode = ((InvalidResponseCodeException) exception).responseCode;
            return responseCode == 404 // HTTP 404 Not Found.
                    || responseCode == 410 // HTTP 410 Gone.
                    ? DEFAULT_TRACK_BLACKLIST_MS
                    : C.TIME_UNSET;
        }
        return C.TIME_UNSET;
    }

    /**
     * Copied from the parent class!
     */
    @Override
    public long getRetryDelayMsFor(int dataType, long loadDurationMs, IOException exception, int errorCount) {
        return exception instanceof ParserException
                || exception instanceof FileNotFoundException
                || exception instanceof UnexpectedLoaderException
                ? C.TIME_UNSET
                : Math.min((errorCount - 1) * 1000, 5000);
    }
}
