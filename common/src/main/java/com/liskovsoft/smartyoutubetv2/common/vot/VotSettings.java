// 
// Decompiled by Procyon v0.6.0
// 

package com.liskovsoft.smartyoutubetv2.common.vot;

import android.content.SharedPreferences;
import android.content.Context;
import android.content.SharedPreferences;

public final class VotSettings
{
    private static final String AUDIO_PROXY_PATH_PREFIX = "/video-translation/audio-proxy/";
    private static final String AUDIO_SOURCE_PREFIX = "https://vtrans.s3-private.mds.yandex.net/tts/prod/";
    private static final String DEFAULT_AUDIO_PROXY_HOST = "vot-worker.kload.workers.dev";
    private static final int DEFAULT_ORIGINAL_VOLUME_PERCENT = 10;
    private static final int DEFAULT_TRANSLATION_VOLUME_PERCENT = 100;
    private static final String KEY_AUDIO_PROXY_ENABLED = "audio_proxy_enabled";
    private static final String KEY_AUDIO_PROXY_HOST = "audio_proxy_host";
    private static final String KEY_LIVELY_DEFAULT_OFF_20260326 = "lively_default_off_20260326";
    private static final String KEY_ORIGINAL_VOLUME_PERCENT = "original_volume_percent";
    private static final String KEY_TRANSLATION_VOLUME_PERCENT = "translation_volume_percent";
    private static final String KEY_USE_LIVELY_VOICE = "use_lively_voice";
    private static final String KEY_YANDEX_OAUTH_TOKEN = "yandex_oauth_token";
    private static final String LEGACY_AUDIO_PROXY_HOST = "vot-worker.toil.cc";
    private static final String LEGACY_AUDIO_PROXY_HOST_MODE1 = "vot-new.toil-dump.workers.dev";
    private static final String PREF_NAME = "vot_settings";
    private final SharedPreferences mPrefs;
    
    private VotSettings(final Context context) {
        this.mPrefs = context.getApplicationContext().getSharedPreferences("vot_settings", 0);
        this.migrateDefaultsIfNeeded();
    }
    
    public static VotSettings instance(final Context context) {
        return new VotSettings(context);
    }
    
    private void migrateDefaultsIfNeeded() {
        final SharedPreferences.Editor edit = this.mPrefs.edit();
        final SharedPreferences mPrefs = this.mPrefs;
        int n = 0;
        final boolean boolean1 = mPrefs.getBoolean("lively_default_off_20260326", false);
        final int n2 = 1;
        if (!boolean1) {
            edit.putBoolean("use_lively_voice", false);
            edit.putBoolean("lively_default_off_20260326", true);
            n = 1;
        }
        if (!this.mPrefs.contains("original_volume_percent")) {
            edit.putInt("original_volume_percent", 10);
            n = 1;
        }
        if (!this.mPrefs.contains("audio_proxy_enabled")) {
            edit.putBoolean("audio_proxy_enabled", true);
            n = 1;
        }
        if ("vot-worker.toil.cc".equalsIgnoreCase(sanitizeProxyHost(this.mPrefs.getString("audio_proxy_host", "vot-worker.kload.workers.dev")))) {
            edit.putString("audio_proxy_host", "vot-worker.kload.workers.dev");
            n = n2;
        }
        if (n != 0) {
            edit.apply();
        }
    }
    
    private static String sanitizeProxyHost(String s) {
        String trim;
        if (s != null) {
            trim = s.trim();
        }
        else {
            trim = "";
        }
        if (trim.isEmpty()) {
            return "vot-worker.kload.workers.dev";
        }
        if (trim.startsWith("http://")) {
            s = trim.substring(7);
        }
        else {
            s = trim;
            if (trim.startsWith("https://")) {
                s = trim.substring(8);
            }
        }
        final int index = s.indexOf(47);
        String substring = s;
        if (index >= 0) {
            substring = s.substring(0, index);
        }
        if (substring.isEmpty()) {
            return "vot-worker.kload.workers.dev";
        }
        if (!"vot-worker.toil.cc".equalsIgnoreCase(substring) && !"vot-new.toil-dump.workers.dev".equalsIgnoreCase(substring)) {
            return substring;
        }
        return "vot-worker.kload.workers.dev";
    }
    
    public void clearYandexOauthToken() {
        this.mPrefs.edit().remove("yandex_oauth_token").apply();
    }
    
    public String getAudioProxyHost() {
        return sanitizeProxyHost(this.mPrefs.getString("audio_proxy_host", "vot-worker.kload.workers.dev"));
    }
    
    public int getOriginalVolumePercent() {
        return Math.max(0, Math.min(50, this.mPrefs.getInt("original_volume_percent", 10)));
    }
    
    public int getTranslationVolumePercent() {
        return Math.max(50, Math.min(100, this.mPrefs.getInt("translation_volume_percent", 100)));
    }
    
    public String getYandexOauthToken() {
        final SharedPreferences mPrefs = this.mPrefs;
        String s = null;
        final String string = mPrefs.getString("yandex_oauth_token", (String)null);
        if (string == null) {
            return null;
        }
        final String trim = string.trim();
        if (!trim.isEmpty()) {
            s = trim;
        }
        return s;
    }
    
    public boolean hasYandexOauthToken() {
        return this.getYandexOauthToken() != null;
    }
    
    public boolean isAudioProxyEnabled() {
        return this.mPrefs.getBoolean("audio_proxy_enabled", true);
    }
    
    public boolean isUseLivelyVoice() {
        return this.mPrefs.getBoolean("use_lively_voice", false);
    }
    
    public String proxifyAudioUrl(final String s) {
        return s;
    }
    
    public void resetMixToDefaults() {
        this.mPrefs.edit().putInt("translation_volume_percent", 100).putInt("original_volume_percent", 10).apply();
    }
    
    public void setAudioProxyEnabled(final boolean b) {
        this.mPrefs.edit().putBoolean("audio_proxy_enabled", b).apply();
    }
    
    public void setAudioProxyHost(final String s) {
        this.mPrefs.edit().putString("audio_proxy_host", sanitizeProxyHost(s)).apply();
    }
    
    public void setOriginalVolumePercent(int max) {
        max = Math.max(0, Math.min(50, max));
        this.mPrefs.edit().putInt("original_volume_percent", max).apply();
    }
    
    public void setTranslationVolumePercent(int max) {
        max = Math.max(50, Math.min(100, max));
        this.mPrefs.edit().putInt("translation_volume_percent", max).apply();
    }
    
    public void setUseLivelyVoice(final boolean b) {
        this.mPrefs.edit().putBoolean("use_lively_voice", b).apply();
    }
    
    public void setYandexOauthToken(String trim) {
        if (trim != null) {
            trim = trim.trim();
        }
        else {
            trim = null;
        }
        if (trim != null && !trim.isEmpty()) {
            this.mPrefs.edit().putString("yandex_oauth_token", trim).apply();
            return;
        }
        this.mPrefs.edit().remove("yandex_oauth_token").apply();
    }
}
