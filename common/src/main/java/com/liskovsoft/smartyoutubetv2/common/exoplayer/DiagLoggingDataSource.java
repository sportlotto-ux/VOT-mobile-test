package com.liskovsoft.smartyoutubetv2.common.exoplayer;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;

import com.liskovsoft.sharedutils.mylogger.Log;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * v26-diag (ВРЕМЕННО): логирует URL запросов, упавших с HTTP 4xx/5xx в DASH-ветке,
 * чтобы отличить 403 на sq/-сегментах от 403 на refresh манифеста.
 * Значения sig/lsig/spc/xpc затираются, остаются host, itag, sq/N, expire.
 */
public class DiagLoggingDataSource implements DataSource {
    private static final String TAG = DiagLoggingDataSource.class.getSimpleName();
    private final DataSource mDelegate;

    public DiagLoggingDataSource(DataSource delegate) {
        mDelegate = delegate;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        mDelegate.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        try {
            return mDelegate.open(dataSpec);
        } catch (HttpDataSource.InvalidResponseCodeException e) {
            if (e.responseCode >= 400) {
                Log.w(TAG, "diagDASH HTTP %s url=%s", e.responseCode, redact(dataSpec.uri));
            }
            throw e;
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        return mDelegate.read(buffer, offset, length);
    }

    @Override
    @Nullable
    public Uri getUri() {
        return mDelegate.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return mDelegate.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        mDelegate.close();
    }

    private static String redact(Uri uri) {
        if (uri == null) {
            return "null";
        }
        String s = uri.toString();
        s = s.replaceAll("/sig/[^/]+", "/sig/…");
        s = s.replaceAll("/lsig/[^/]+", "/lsig/…");
        s = s.replaceAll("/spc/[^/]+", "/spc/…");
        s = s.replaceAll("/xpc/[^/=]+", "/xpc/…");
        if (s.length() > 420) {
            s = s.substring(0, 420) + "…";
        }
        return s;
    }

    public static class Factory implements DataSource.Factory {
        private final DataSource.Factory mDelegate;

        public Factory(DataSource.Factory delegate) {
            mDelegate = delegate;
        }

        @Override
        public DataSource createDataSource() {
            return new DiagLoggingDataSource(mDelegate.createDataSource());
        }
    }
}
