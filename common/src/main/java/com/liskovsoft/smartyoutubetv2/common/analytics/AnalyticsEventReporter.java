// 
// Decompiled by Procyon v0.6.0
// 

package com.liskovsoft.smartyoutubetv2.common.analytics;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import org.json.JSONObject;
import android.net.NetworkCapabilities;
import android.net.Network;
import android.net.ConnectivityManager;
import android.content.SharedPreferences;
import java.util.UUID;
import android.provider.Settings$Secure;
import android.os.Build;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager$PackageInfoFlags;
import android.os.Build$VERSION;
import android.content.Context;
import java.util.Date;
import java.util.Iterator;
import com.liskovsoft.mediaserviceinterfaces.data.MediaSubtitle;
import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import java.util.TimeZone;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.text.SimpleDateFormat;

public final class AnalyticsEventReporter
{
    private static final int CONNECT_TIMEOUT_MS = 2500;
    private static final SimpleDateFormat DATE_FORMAT;
    private static final String ENDPOINT_URL = "https://videos.ru/vtube/update/";
    private static final ExecutorService EXECUTOR;
    private static final String PREF_NAME = "analytics_reporter";
    private static final String PREF_USER_KEY = "user_key";
    private static final int READ_TIMEOUT_MS = 3500;
    private static final String TAG = "AnalyticsEventReporter";
    
    static {
        EXECUTOR = Executors.newSingleThreadExecutor();
        (DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)).setTimeZone(TimeZone.getTimeZone("UTC"));
    }
    
    private AnalyticsEventReporter() {
    }
    
    public static String detectVideoLanguage(final MediaItemFormatInfo mediaItemFormatInfo) {
        if (mediaItemFormatInfo == null) {
            return "auto";
        }
        if (mediaItemFormatInfo.getAdaptiveFormats() != null) {
            for (final MediaFormat mediaFormat : mediaItemFormatInfo.getAdaptiveFormats()) {
                if (mediaFormat != null && mediaFormat.getMimeType() != null) {
                    if (!mediaFormat.getMimeType().startsWith("audio")) {
                        continue;
                    }
                    final String normalizeLanguage = normalizeLanguage(mediaFormat.getLanguage());
                    if (!"auto".equals(normalizeLanguage)) {
                        return normalizeLanguage;
                    }
                    continue;
                }
            }
        }
        if (mediaItemFormatInfo.getSubtitles() != null) {
            for (final MediaSubtitle mediaSubtitle : mediaItemFormatInfo.getSubtitles()) {
                if (mediaSubtitle == null) {
                    continue;
                }
                final String normalizeLanguage2 = normalizeLanguage(mediaSubtitle.getLanguageCode());
                if (!"auto".equals(normalizeLanguage2)) {
                    return normalizeLanguage2;
                }
            }
        }
        return "auto";
    }
    
    private static String formatUtc(long currentTimeMillis) {
        if (currentTimeMillis <= 0L) {
            currentTimeMillis = System.currentTimeMillis();
        }
        final SimpleDateFormat date_FORMAT = AnalyticsEventReporter.DATE_FORMAT;
        synchronized (date_FORMAT) {
            return date_FORMAT.format(new Date(currentTimeMillis));
        }
    }
    
    private static String getAppVersion(final Context context) {
        final String s = "";
        if (context == null) {
            return "";
        }
        try {
            final PackageManager packageManager = context.getPackageManager();
            if (packageManager == null) {
                return "";
            }
            PackageInfo packageInfo;
            if (Build$VERSION.SDK_INT >= 33) {
                packageInfo = packageManager.getPackageInfo(context.getPackageName(), PackageManager$PackageInfoFlags.of(0L));
            }
            else {
                packageInfo = packageManager.getPackageInfo(context.getPackageName(), 0);
            }
            String versionName = s;
            if (packageInfo != null) {
                versionName = s;
                if (packageInfo.versionName != null) {
                    versionName = packageInfo.versionName;
                }
            }
            return versionName;
        }
        finally {
            return s;
        }
    }
    
    private static String getClientCountry() {
        final String s = "";
        try {
            final String country = Locale.getDefault().getCountry();
            String upperCase = s;
            if (country != null) {
                upperCase = country.toUpperCase(Locale.US);
            }
            return upperCase;
        }
        finally {
            return s;
        }
    }
    
    private static String getClientDevice() {
        final String trim = safe(Build.MANUFACTURER).trim();
        final String trim2 = safe(Build.MODEL).trim();
        final String trim3 = safe(Build$VERSION.RELEASE).trim();
        String string;
        if (trim3.isEmpty()) {
            string = "android";
        }
        else {
            final StringBuilder sb = new StringBuilder("android ");
            sb.append(trim3);
            string = sb.toString();
        }
        final StringBuilder sb2 = new StringBuilder();
        sb2.append(trim);
        sb2.append(" ");
        sb2.append(trim2);
        final String trim4 = sb2.toString().trim();
        if (trim4.isEmpty()) {
            return string;
        }
        final StringBuilder sb3 = new StringBuilder();
        sb3.append(trim4);
        sb3.append(" (");
        sb3.append(string);
        sb3.append(")");
        return sb3.toString();
    }
    
    private static String getUserKey(final Context context) {
        if (context == null) {
            return "anon:unknown";
        }
        try {
            final SharedPreferences sharedPreferences = context.getSharedPreferences("analytics_reporter", 0);
            final String string = sharedPreferences.getString("user_key", (String)null);
            if (string != null && !string.isEmpty()) {
                return string;
            }
            final String string2 = Settings$Secure.getString(context.getContentResolver(), "android_id");
            String s;
            if (string2 != null && !string2.isEmpty()) {
                final StringBuilder sb = new StringBuilder("anon:");
                sb.append(string2);
                s = sb.toString();
            }
            else {
                final StringBuilder sb2 = new StringBuilder("anon:");
                sb2.append(UUID.randomUUID());
                s = sb2.toString();
            }
            sharedPreferences.edit().putString("user_key", s).apply();
            return s;
        }
        finally {
            return "anon:fallback";
        }
    }
    
    private static boolean isVpnUsed(final Context context) {
        final boolean b = false;
        if (context == null) {
            return false;
        }
        if (Build$VERSION.SDK_INT < 23) {
            return false;
        }
        try {
            final ConnectivityManager connectivityManager = (ConnectivityManager)context.getSystemService("connectivity");
            if (connectivityManager == null) {
                return false;
            }
            final Network activeNetwork = connectivityManager.getActiveNetwork();
            if (activeNetwork == null) {
                return false;
            }
            final NetworkCapabilities networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
            boolean b2 = b;
            if (networkCapabilities != null) {
                final boolean hasTransport = networkCapabilities.hasTransport(4);
                b2 = b;
                if (hasTransport) {
                    b2 = true;
                }
            }
            return b2;
        }
        finally {
            return b;
        }
    }
    
    private static int msToSec(final long n) {
        if (n <= 0L) {
            return 0;
        }
        return (int)(n / 1000L);
    }
    
    public static String newSessionId() {
        return UUID.randomUUID().toString();
    }
    
    private static String normalizeLanguage(String substring) {
        if (substring != null && !substring.trim().isEmpty()) {
            final String lowerCase = substring.trim().toLowerCase(Locale.US);
            int index = lowerCase.indexOf(45);
            final int index2 = lowerCase.indexOf(95);
            if (index < 0) {
                index = index2;
            }
            substring = lowerCase;
            if (index > 0) {
                substring = lowerCase.substring(0, index);
            }
            return substring;
        }
        return "auto";
    }
    
    public static void reportInterest(final Context context, final Video video, final String s, final long n, final long n2, final long n3, final long n4, final long n5, final boolean b, final int b2, final String s2, final String s3, final String s4) {
        if (video == null || video.videoId == null) {
            return;
        }
        if (video.videoId.isEmpty()) {
            return;
        }
        try {
            final JSONObject jsonObject = new JSONObject();
            jsonObject.put("event_type", (Object)"interest");
            jsonObject.put("session_id", (Object)s);
            jsonObject.put("external_video_id", (Object)video.videoId);
            jsonObject.put("title", (Object)safe(video.getTitleFull()));
            final StringBuilder sb = new StringBuilder("https://www.youtube.com/watch?v=");
            sb.append(video.videoId);
            jsonObject.put("direct_url", (Object)sb.toString());
            jsonObject.put("video_language", (Object)normalizeLanguage(s4));
            jsonObject.put("user_key", (Object)getUserKey(context));
            jsonObject.put("watched_raw_sec", msToSec(n3));
            jsonObject.put("watched_unique_sec", msToSec(n4));
            jsonObject.put("duration_sec", msToSec(n5));
            jsonObject.put("completed", b);
            jsonObject.put("replay_count", Math.max(0, b2));
            jsonObject.put("record_stage", (Object)safe(s2));
            jsonObject.put("playback_status", (Object)safe(s3));
            jsonObject.put("client_device", (Object)getClientDevice());
            jsonObject.put("client_country", (Object)getClientCountry());
            jsonObject.put("vpn_used", isVpnUsed(context));
            jsonObject.put("app_version", (Object)getAppVersion(context));
            jsonObject.put("started_at", (Object)formatUtc(n));
            jsonObject.put("ended_at", (Object)formatUtc(n2));
            sendAsync(jsonObject);
        }
        finally {}
    }
    
    public static void reportTranslation(final Context context, final Video video, final String s, final String s2, final String s3, final long n, final long n2, final long b, final boolean b2, final String s4, final String s5, final String s6) {
        if (video == null || video.videoId == null) {
            return;
        }
        if (video.videoId.isEmpty()) {
            return;
        }
        try {
            final JSONObject jsonObject = new JSONObject();
            jsonObject.put("event_type", (Object)"translation");
            jsonObject.put("session_id", (Object)s);
            jsonObject.put("external_video_id", (Object)video.videoId);
            jsonObject.put("title", (Object)safe(video.getTitleFull()));
            final StringBuilder sb = new StringBuilder("https://www.youtube.com/watch?v=");
            sb.append(video.videoId);
            jsonObject.put("direct_url", (Object)sb.toString());
            jsonObject.put("video_language", (Object)normalizeLanguage(s2));
            jsonObject.put("user_key", (Object)getUserKey(context));
            jsonObject.put("source_language", (Object)normalizeLanguage(s2));
            jsonObject.put("target_language", (Object)normalizeLanguage(s3));
            jsonObject.put("translation_enabled", true);
            jsonObject.put("wait_ms", Math.max(0L, b));
            jsonObject.put("translation_success", b2);
            jsonObject.put("record_stage", (Object)safe(s5));
            jsonObject.put("translation_status", (Object)safe(s6));
            jsonObject.put("app_version", (Object)getAppVersion(context));
            if (!b2 && s4 != null && !s4.isEmpty()) {
                jsonObject.put("error_code", (Object)s4);
            }
            jsonObject.put("started_at", (Object)formatUtc(n));
            jsonObject.put("completed_at", (Object)formatUtc(n2));
            sendAsync(jsonObject);
        }
        finally {}
    }
    
    private static String safe(String s) {
        if (s == null) {
            s = "";
        }
        return s;
    }
    
    private static void sendAsync(final JSONObject jsonObject) {
        AnalyticsEventReporter.EXECUTOR.execute(new AnalyticsEventReporter$$ExternalSyntheticLambda0(jsonObject));
    }
}
