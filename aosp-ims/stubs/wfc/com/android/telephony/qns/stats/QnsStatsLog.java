package com.android.telephony.qns.stats;

/**
 * Stand-in for the class stats-log-api-gen writes (QnsStatsLog): atom ids
 * only. Outside the Android tree there is no generator, and these atoms
 * only feed metrics, so their ids are placeholders.
 */
public final class QnsStatsLog {
    public static final int QUALIFIED_RAT_LIST_CHANGED = 0;
    public static final int QNS_IMS_CALL_DROP_STATS = 0;
    public static final int QNS_FALLBACK_RESTRICTION_CHANGED = 0;
    public static final int QNS_RAT_PREFERENCE_MISMATCH_INFO = 0;
    public static final int QNS_HANDOVER_TIME_MILLIS = 0;
    public static final int QNS_HANDOVER_PINGPONG = 0;

    private QnsStatsLog() {}
}
