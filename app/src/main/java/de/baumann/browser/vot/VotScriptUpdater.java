package de.baumann.browser.vot;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

/**
 * Phase 2.1: supply-chain updater (stub for MVP).
 * GitHub Releases channel: static assets for now.
 * Future: signed manifest (ed25519) with version downgrade protection.
 */
public class VotScriptUpdater {
    private static final String TAG = "VotUpdater";
    private static final String PREF_MANIFEST_VERSION = "vot_manifest_version";

    // For F-Droid flavor, SCRIPT_AUTOUPDATE=false would disable this entirely
    public static boolean isAutoUpdateEnabled() {
        // Controlled by BuildConfig or manifest flag; for now always true for youtube flavor
        try {
            return de.baumann.browser.BuildConfig.IS_YOUTUBE;
        } catch (Throwable t) {
            return false;
        }
    }

    public static long getSavedManifestVersion(Context ctx) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(ctx);
        return sp.getLong(PREF_MANIFEST_VERSION, 0);
    }

    public static void saveManifestVersion(Context ctx, long version) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(ctx);
        sp.edit().putLong(PREF_MANIFEST_VERSION, version).apply();
    }

    /**
     * Placeholder for signed manifest fetch.
     * In full implementation:
     * - fetch manifest.json from controlled gist/repo
     * - verify ed25519 signature with hardcoded public key
     * - check version > savedVersion (downgrade protection)
     * - download vot.user.js and skipping-rules.js, verify sha256, cache to filesdir
     * For MVP (Phase 2): just log and use assets.
     */
    public static void checkForUpdates(Context ctx) {
        if (!isAutoUpdateEnabled()) {
            Log.d(TAG, "autoUpdate disabled (full flavor or F-Droid)");
            return;
        }
        // TODO: implement network fetch + signature verification
        Log.d(TAG, "checkForUpdates stub — using bundled assets (phase0 1.11.8)");
        // Example future logic:
        // String manifestUrl = "https://raw.githubusercontent.com/sportlotto-ux/VOT-mobile-test/main/manifest.json";
        // fetch, verify, save
    }

    public static String getPublicKeyBase64() {
        // Hardcoded ed25519 public key (placeholder — generate via minisign)
        // Replace with real key before production
        return "PLACEHOLDER_PUBLIC_KEY_BASE64";
    }
}
