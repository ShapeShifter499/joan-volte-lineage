package org.joan.ims;

import android.content.Context;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;

/**
 * The carrier's IMS voice knobs, read from CarrierConfigManager.
 *
 * <p>These are configuration, not constants. ImsStack's
 * {@code core/config/CarrierConfig.java} reads the same five keys for
 * session timers, and picking numbers here instead would mean a carrier
 * that asks for a 600-second session gets 1800 and a refresh that arrives
 * after the core has already dropped the dialog.
 *
 * <p>Defaults match AOSP's own (CarrierConfigManager's ImsVoice block:
 * supported=true, 1800 s, Min-SE 90 s, refresher=uac, UPDATE preferred),
 * so an unconfigured carrier behaves the way the rest of Android would
 * rather than the way this file happened to be written.
 */
final class JoanImsVoiceConfig {

    final boolean timerSupported;
    final int sessionExpiresSec;
    final int minSeSec;
    final int refresherType;
    final int refreshMethod;
    /** Where the values came from, for the trace. */
    final String source;

    private JoanImsVoiceConfig(boolean timerSupported, int sessionExpiresSec,
                               int minSeSec, int refresherType,
                               int refreshMethod, String source) {
        this.timerSupported = timerSupported;
        this.sessionExpiresSec = sessionExpiresSec;
        this.minSeSec = minSeSec;
        this.refresherType = refresherType;
        this.refreshMethod = refreshMethod;
        this.source = source;
    }

    static JoanImsVoiceConfig defaults(String why) {
        return new JoanImsVoiceConfig(true,
                JoanSessionTimer.DEFAULT_EXPIRES_SEC,
                JoanSessionTimer.DEFAULT_MIN_SE_SEC,
                JoanSessionTimer.REFRESHER_UAC,
                JoanSessionTimer.METHOD_UPDATE_PREFERRED,
                why);
    }

    private static volatile JoanImsVoiceConfig sCached;
    private static volatile int sCachedSub = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

    /** Drop the cache; carrier config was rebuilt. */
    static void invalidate() {
        sCached = null;
        sCachedSub = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    static JoanImsVoiceConfig forSub(Context ctx, int subId) {
        JoanImsVoiceConfig hit = sCached;
        if (hit != null && sCachedSub == subId) {
            return hit;
        }
        JoanImsVoiceConfig p = load(ctx, subId);
        sCached = p;
        sCachedSub = subId;
        return p;
    }

    private static JoanImsVoiceConfig load(Context ctx, int subId) {
        if (ctx == null || !SubscriptionManager.isValidSubscriptionId(subId)) {
            return defaults("no-sub");
        }
        PersistableBundle cfg;
        try {
            CarrierConfigManager ccm =
                    ctx.getSystemService(CarrierConfigManager.class);
            if (ccm == null) {
                return defaults("no-service");
            }
            cfg = ccm.getConfigForSubId(subId);
        } catch (Throwable t) {
            return defaults("read-failed:" + t.getClass().getSimpleName());
        }
        if (cfg == null || cfg.isEmpty()) {
            return defaults("empty");
        }
        JoanImsVoiceConfig d = defaults("mixed");
        boolean supported = cfg.getBoolean(
                CarrierConfigManager.ImsVoice.KEY_SESSION_TIMER_SUPPORTED_BOOL,
                d.timerSupported);
        int expires = cfg.getInt(
                CarrierConfigManager.ImsVoice.KEY_SESSION_EXPIRES_TIMER_SEC_INT,
                d.sessionExpiresSec);
        int minSe = cfg.getInt(
                CarrierConfigManager.ImsVoice
                        .KEY_MINIMUM_SESSION_EXPIRES_TIMER_SEC_INT,
                d.minSeSec);
        int refresher = cfg.getInt(
                CarrierConfigManager.ImsVoice.KEY_SESSION_REFRESHER_TYPE_INT,
                d.refresherType);
        int method = cfg.getInt(
                CarrierConfigManager.ImsVoice.KEY_SESSION_REFRESH_METHOD_INT,
                d.refreshMethod);
        /* A bundle that answers every key with the fallback is one the
         * platform has not populated yet, not a carrier that chose these
         * values. Saying so in the trace is the difference between
         * "carrier wants 1800" and "nobody told us anything". */
        boolean anySet = cfg.containsKey(CarrierConfigManager.ImsVoice
                        .KEY_SESSION_EXPIRES_TIMER_SEC_INT)
                || cfg.containsKey(CarrierConfigManager.ImsVoice
                        .KEY_SESSION_TIMER_SUPPORTED_BOOL);
        return new JoanImsVoiceConfig(supported,
                JoanSessionTimer.offerExpires(expires, minSe),
                JoanSessionTimer.minSe(minSe),
                refresher, method,
                anySet ? "carrier-config" : "carrier-config-unset");
    }

    String summary() {
        return "timer=" + timerSupported
                + " se=" + sessionExpiresSec
                + " min_se=" + minSeSec
                + " refresher=" + (refresherType == JoanSessionTimer.REFRESHER_UAC
                        ? "uac" : refresherType == JoanSessionTimer.REFRESHER_UAS
                                ? "uas" : "unknown")
                + " method=" + (refreshMethod
                        == JoanSessionTimer.METHOD_UPDATE_PREFERRED
                        ? "update" : "invite")
                + " src=" + source;
    }
}
