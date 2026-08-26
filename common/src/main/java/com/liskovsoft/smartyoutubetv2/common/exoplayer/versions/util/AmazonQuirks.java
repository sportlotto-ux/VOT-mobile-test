package com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.util;

import android.os.Build;

/**
 * Standalone port of Amazon device quirks (originally from the Amazon ExoPlayer port).
 *
 * NOTE(media3): deep integration points (vsync snapping, profile level check inside MediaCodecUtil)
 * don't exist in media3 anymore. The flags are kept so the settings stay wired; currently only
 * device detection is functional.
 */
public final class AmazonQuirks {
    private static final String TAG = AmazonQuirks.class.getSimpleName();

    private static final String FIRETV_GEN1_DEVICE_MODEL = "AFTB";
    private static final String FIRETV_GEN2_DEVICE_MODEL = "AFTS";
    private static final String FIRETV_STICK_DEVICE_MODEL = "AFTM";
    private static final String FIRETV_STICK_GEN2_DEVICE_MODEL = "AFTT";
    private static final String KINDLE_TABLET_DEVICE_MODEL = "KF";
    private static final String FIRE_PHONE_DEVICE_MODEL = "SD";
    private static final String AMAZON = "Amazon";

    private static final String DEVICEMODEL = Build.MODEL;
    private static final String MANUFACTURER = Build.MANUFACTURER;

    private static final boolean isAmazonDevice;
    private static final boolean isFireTVGen1;
    private static final boolean isFireTVStick;
    private static final boolean isFireTVGen2;

    private static boolean isSnappingToVsyncDisabled;
    private static boolean skipProfileLevelCheck;

    static {
        isAmazonDevice = MANUFACTURER.equalsIgnoreCase(AMAZON);
        isFireTVGen1 = isAmazonDevice && DEVICEMODEL.equalsIgnoreCase(FIRETV_GEN1_DEVICE_MODEL);
        isFireTVGen2 = isAmazonDevice && DEVICEMODEL.equalsIgnoreCase(FIRETV_GEN2_DEVICE_MODEL);
        isFireTVStick = isAmazonDevice && DEVICEMODEL.equalsIgnoreCase(FIRETV_STICK_DEVICE_MODEL)
                || isAmazonDevice && DEVICEMODEL.equalsIgnoreCase(FIRETV_STICK_GEN2_DEVICE_MODEL);
    }

    private AmazonQuirks() {
    }

    public static boolean isAmazonDevice() {
        return isAmazonDevice;
    }

    public static boolean isFireTVGen1Family() {
        return isFireTVGen1 || isFireTVStick;
    }

    public static boolean isFireTVGen2() {
        return isFireTVGen2;
    }

    /**
     * To disable snapping the frame release times to VSYNC call this function with true
     */
    public static void disableSnappingToVsync(boolean disable) {
        isSnappingToVsyncDisabled = disable;
    }

    public static boolean isSnappingToVsyncDisabled() {
        return isSnappingToVsyncDisabled;
    }

    /**
     * Skip codec profile level checks (device under-reports capabilities).
     */
    public static void skipProfileLevelCheck(boolean skip) {
        skipProfileLevelCheck = skip;
    }

    public static boolean shouldSkipProfileLevelCheck() {
        return skipProfileLevelCheck;
    }
}
