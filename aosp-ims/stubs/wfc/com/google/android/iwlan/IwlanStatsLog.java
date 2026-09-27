package com.google.android.iwlan;

/**
 * Stand-in for the class stats-log-api-gen writes (IwlanStatsLog). Outside
 * the Android tree there is no generator, and these atoms only feed
 * Google's metrics, so writing them is a no-op.
 */
public final class IwlanStatsLog {
    public static final int IWLAN_UNDERLYING_NETWORK_VALIDATION_RESULT_REPORTED = 0;
    public static final int IWLAN_SETUP_DATA_CALL_RESULT_REPORTED = 0;
    public static final int IWLAN_PDN_DISCONNECTED_REASON_REPORTED = 0;

    private IwlanStatsLog() {}

    public static void write(int code, Object... fields) {}
}
