package org.joan.ims;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * VoLTE admit gate: make platform VoLTE available for ANY carrier whose
 * ROM leaves it unconfigured, by default. The user opts out with the
 * stock Settings "VoLTE / Enhanced 4G LTE" toggle, which the framework
 * honors in {@code GsmCdmaPhone.isImsUseEnabled()} (platform AND user
 * setting), so a carrier that cannot use IMS has the user turn the
 * toggle off.
 *
 * <p>Turning it off is NOT a safe universal escape hatch. It yields a
 * working call only where the network still runs CS voice. On a
 * VoLTE-only carrier there is nothing to fall back to and outbound
 * calling stops entirely -- observed on T-Mobile US 2026-09-14: with
 * VoLTE made unavailable, MO dials failed outright while MT calls kept
 * arriving.
 *
 * AOSP {@code CarrierConfigManager} defaults
 * {@code KEY_CARRIER_VOLTE_AVAILABLE_BOOL} to false, and most LOS trees
 * ship no per-carrier asset for the testers' carriers. Recovery cannot
 * inject CarrierConfig assets. This uses the privileged
 * {@code overrideConfig} API (non-persistent) so uninstall + reboot
 * restores production values. Do not persist: the override XML would
 * live in {@code com.android.phone} and the uninstall zip cannot
 * delete it.
 *
 * Rules, in order:
 * <ul>
 *   <li>config not applied yet (early boot / SIM mid-scan) -> wait,
 *       retry next driver pass;</li>
 *   <li>VoLTE already available from the system -> skip the admit, but
 *       if the carrier config HIDES the user toggle, still force the
 *       toggle visible/editable so the opt-out always exists;</li>
 *   <li>otherwise -> apply the admit (non-persistent).</li>
 * </ul>
 *
 * The UI keys {@code hide_enhanced_4g_lte_bool} /
 * {@code editable_enhanced_4g_lte_bool} are not in the public SDK jar,
 * so they are addressed by string literal.
 */
final class JoanVolteCarrierGate {
    private static final String TAG = "JoanIms";

    /** Public constant; compiles against the SDK jar. */
    private static final String KEY_VOLTE =
            CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL;

    // Not exposed in the public SDK jar - string literals by design.
    private static final String KEY_APPLIED = "carrier_config_applied_bool";
    private static final String KEY_HIDE_4G = "hide_enhanced_4g_lte_bool";
    private static final String KEY_EDITABLE_4G = "editable_enhanced_4g_lte_bool";

    /** Decision of the pure gate, host-testable. */
    static final int WAIT_CONFIG = 0;
    static final int SKIP_ALREADY = 1;
    static final int APPLY_VISIBILITY = 2;
    static final int APPLY_FULL = 3;

    private static volatile String sLast = "untried";
    /** Sub whose VoLTE admit WE applied, so the row can say so. */
    private static volatile int sForcedSub = Integer.MIN_VALUE;
    /** Times the override had to be put back after it went missing. */
    private static volatile int sReapplies = 0;

    /** Set once the CARRIER_CONFIG_CHANGED watch is live. */
    private static volatile boolean sWatching = false;

    private JoanVolteCarrierGate() {}

    /**
     * Re-apply the admit whenever Telephony reloads carrier config.
     *
     * <p>The override lives in {@code com.android.phone}'s memory. If that
     * process restarts, the override is gone while this one keeps running,
     * and the driver would not notice: once registered it sleeps until the
     * REGISTER refresh is due, so {@code discover()} can be half an hour
     * away. Measured on the bench: forcing the config back to unavailable
     * left the state row unchanged for 200s with no re-apply.
     *
     * <p>{@code ACTION_CARRIER_CONFIG_CHANGED} is exactly the signal that
     * the config was rebuilt, so hook that instead of polling. Re-entry is
     * bounded: our own apply fires this broadcast, but by then the config
     * reads available and decide() returns SKIP_ALREADY, which writes
     * nothing.
     */
    static void watch(final Context app) {
        if (app == null || sWatching) {
            return;
        }
        sWatching = true;
        try {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    if (i == null) {
                        return;
                    }
                    int sub = i.getIntExtra(
                            CarrierConfigManager.EXTRA_SUBSCRIPTION_INDEX,
                            SubscriptionManager.INVALID_SUBSCRIPTION_ID);
                    if (!SubscriptionManager.isValidSubscriptionId(sub)) {
                        return;
                    }
                    /* The session-timer values were read from this same
                     * bundle. A rebuild can change them, and a cached copy
                     * would keep a call refreshing on an interval the
                     * carrier no longer asks for. */
                    JoanImsVoiceConfig.invalidate();
                    JoanCodecConfig.invalidate();
                    try {
                        TelephonyManager tm0 = app.getSystemService(
                                TelephonyManager.class);
                        if (tm0 == null) {
                            return;
                        }
                        applyIfNeeded(app, sub,
                                tm0.createForSubscriptionId(sub));
                    } catch (Throwable t) {
                        Log.w(TAG, "volte_gate watch: " + t);
                    }
                }
            };
            app.registerReceiver(r, new IntentFilter(
                    CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED));
            Log.i(TAG, "volte_gate watching carrier config changes");
        } catch (Throwable t) {
            // Not fatal: the driver still re-checks each registration pass.
            sWatching = false;
            Log.w(TAG, "volte_gate watch failed: " + t);
        }
    }

    static String last() {
        return sLast;
    }

    /**
     * Pure gate. {@code volteAvailable} is the effective
     * {@code carrier_volte_available_bool}; {@code configApplied} is
     * {@code carrier_config_applied_bool}; {@code toggleUsable} means the
     * Settings toggle is neither hidden nor locked (defaults: visible and
     * editable, which is what an unconfigured carrier gets).
     */
    static int decide(boolean volteAvailable, boolean configApplied,
            boolean toggleUsable) {
        if (!configApplied) {
            return WAIT_CONFIG;
        }
        if (volteAvailable) {
            return toggleUsable ? SKIP_ALREADY : APPLY_VISIBILITY;
        }
        return APPLY_FULL;
    }

    /**
     * Which state row a settled decision reports. Pure, host-testable.
     *
     * <p>{@code SKIP_ALREADY} is ambiguous on its own: it means the merged
     * config already says VoLTE is available, which is true both when the
     * ROM provides it and when our own override is still in place. The
     * triage value is in telling those apart.
     */
    static String kindFor(int decision, boolean weForcedIt) {
        switch (decision) {
            case SKIP_ALREADY:
                return weForcedIt ? "applied" : "skip:already-true";
            case APPLY_FULL:
                return "applied";
            case APPLY_VISIBILITY:
                return "applied-visibility";
            default:
                return "wait:config-not-applied";
        }
    }

    /** Pure state-row text. Host-testable. */
    static String stateLabel(String kind, int cid, int reapplies) {
        String s = kind + " cid=" + cid;
        if (reapplies > 0) {
            s = s + " reapplied=" + reapplies;
        }
        return s;
    }

    static String applyIfNeeded(Context ctx, int subId, TelephonyManager tm) {
        if (ctx == null || tm == null
                || !SubscriptionManager.isValidSubscriptionId(subId)) {
            sLast = "skip:no-sub";
            return sLast;
        }
        /* Deliberately no "already did this" short-circuit. The override
         * lives in com.android.phone's memory, not ours: if that process
         * restarts, the override is gone while this one keeps running, and
         * a remembered "applied" would leave VoLTE off with the state row
         * still claiming success. Re-reading a cached config once per
         * driver pass (60s+) is a few binder calls, and decide() settles
         * to SKIP_ALREADY by itself for as long as the override holds. */
        int cid = -1;
        try {
            cid = tm.getSimCarrierId();
        } catch (Throwable t) {
            cid = -1;
        }
        PersistableBundle cfg = null;
        CarrierConfigManager ccm = null;
        try {
            ccm = ctx.getSystemService(CarrierConfigManager.class);
            if (ccm != null) {
                cfg = ccm.getConfigForSubId(subId);
            }
        } catch (Throwable t) {
            cfg = null;
        }
        boolean volteAvailable = false;
        boolean configApplied = false;
        boolean toggleUsable = true;
        if (cfg != null) {
            try {
                volteAvailable = cfg.getBoolean(KEY_VOLTE, false);
                configApplied = cfg.getBoolean(KEY_APPLIED, true);
                boolean hidden = cfg.getBoolean(KEY_HIDE_4G, false);
                boolean editable = cfg.getBoolean(KEY_EDITABLE_4G, true);
                toggleUsable = !hidden && editable;
            } catch (Throwable t) {
                // keep defaults
            }
        } else {
            // No bundle at all: treat as not-applied, retry next pass.
            configApplied = false;
        }
        int decision = decide(volteAvailable, configApplied, toggleUsable);
        if (decision == WAIT_CONFIG) {
            sLast = "wait:config-not-applied cid=" + cid;
            return sLast;
        }
        if (ccm == null) {
            sLast = "fail:no-ccm";
            return sLast;
        }
        /* Did WE put this sub's admit in place? Distinguishes "the ROM
         * already allows VoLTE" from "we forced it and it is holding",
         * which look identical in the merged config. */
        boolean weForcedIt = sForcedSub == subId;

        if (decision == SKIP_ALREADY) {
            sLast = stateLabel(kindFor(SKIP_ALREADY, weForcedIt), cid,
                    weForcedIt ? sReapplies : 0);
            return sLast;
        }
        /* Reaching an apply for a sub we already forced means the override
         * went missing -- com.android.phone restarted and took its
         * in-memory overrides with it. Put it back, and count it, because
         * a climbing reapplied= is the visible symptom of a phone process
         * that keeps dying. */
        boolean reapplying = weForcedIt;

        PersistableBundle over = new PersistableBundle();
        if (decision == APPLY_FULL) {
            over.putBoolean(KEY_VOLTE, true);
        }
        // Always guarantee the opt-out exists: visible + editable toggle.
        over.putBoolean(KEY_HIDE_4G, false);
        over.putBoolean(KEY_EDITABLE_4G, true);
        String kind = kindFor(decision, true);
        Throwable firstErr;
        try {
            Method m = CarrierConfigManager.class.getMethod(
                    "overrideConfig", int.class, PersistableBundle.class,
                    boolean.class);
            m.invoke(ccm, subId, over, Boolean.FALSE);
            return recordApplied(kind, cid, subId, reapplying);
        } catch (Throwable first) {
            firstErr = first;
        }
        try {
            Method m2 = CarrierConfigManager.class.getMethod(
                    "overrideConfig", int.class, PersistableBundle.class);
            m2.invoke(ccm, subId, over);
            return recordApplied(kind, cid, subId, reapplying);
        } catch (Throwable second) {
            /* Name both. Reporting only the 3-arg failure meant a real
             * SecurityException from the fallback was displayed as
             * NoSuchMethodException -- the wrong thing to chase. */
            sLast = "fail:" + unwrap(firstErr) + "/" + unwrap(second);
            Log.w(TAG, "volte_gate " + sLast);
            return sLast;
        }
    }

    private static String recordApplied(String kind, int cid, int subId,
            boolean reapplying) {
        if (reapplying) {
            sReapplies++;
        } else {
            sReapplies = 0;
        }
        sForcedSub = subId;
        sLast = stateLabel(kind, cid, sReapplies);
        Log.i(TAG, "volte_gate " + sLast);
        return sLast;
    }

    private static String unwrap(Throwable t) {
        Throwable c = t;
        if (t instanceof java.lang.reflect.InvocationTargetException
                && t.getCause() != null) {
            c = t.getCause();
        }
        return c.getClass().getSimpleName();
    }
}
