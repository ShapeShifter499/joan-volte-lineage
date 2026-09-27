/*
 * Copyright (C) 2026 The joan-volte-lineage authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.imsstack.joan;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/*
 * Ported from joan's org.joan.ims.JoanVolteCarrierGate for the AOSP stack,
 * which has no equivalent: upstream expects the ROM to ship carrier config
 * for its carriers, and LineageOS on joan ships none for most of them.
 *
 * Wi-Fi calling is added on top, more narrowly than VoLTE: it is admitted
 * only for carriers whose LG profile shipped VoWiFi (assets/joan/
 * wfc-profiles.json, from LG's Ims6 configs), because a toggle for a carrier
 * without an ePDG would only fail. A SIM is resolved to an LG profile the
 * way joan does, by Android carrier id and then PLMN (joan's maps). For the
 * three US carriers whose ePDG is not the 3GPP default name, the address
 * is supplied too. Nothing is overridden that the ROM already sets.
 */
public final class CarrierImsGate {
    private static final String TAG = "ImsStackGate";

    /** Public constant; compiles against the SDK jar. */
    private static final String KEY_VOLTE =
            CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL;

    private static final String KEY_WFC =
            CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL;
    // CarrierConfigManager.Iwlan keys and values (Android 15 API).
    private static final String KEY_EPDG_STATIC = "iwlan.epdg_static_address_string";
    private static final String KEY_EPDG_PRIORITY = "iwlan.epdg_address_priority_int_array";
    private static final int EPDG_ADDRESS_STATIC = 0;
    private static final int EPDG_ADDRESS_PLMN = 1;
    /** ePDGs that are not epdg.epc.mncXXX.mccYYY.pub.3gppnetwork.org, by LG operator. */
    private static final Map<String, String> EPDG_BY_OPERATOR = Map.of(
            "TMO.US", "ss.epdg.epc.mnc260.mcc310.pub.3gppnetwork.org",
            "ATT.US", "epdg.epc.att.net",
            "VZW.US", "wo.vzwwo.com");

    private static volatile Map<String, String> sProfileByCarrierId;
    private static volatile Map<String, String> sProfileByPlmn;
    private static volatile Set<String> sWfcProfiles;

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

    private CarrierImsGate() {}

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
            // A protected broadcast, sent only by Telephony.
            app.registerReceiver(r, new IntentFilter(
                    CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED),
                    Context.RECEIVER_EXPORTED);
            Log.i(TAG, "volte_gate watching carrier config changes");
        } catch (Throwable t) {
            // Not fatal: the driver still re-checks each registration pass.
            sWatching = false;
            Log.w(TAG, "volte_gate watch failed: " + t);
        }
    }

    /**
     * Admit VoLTE for every active subscription now, and again whenever
     * Telephony rebuilds carrier config. Called once from the ImsStack
     * process of the single-APK build.
     */
    public static void start(Context app) {
        watch(app);
        try {
            SubscriptionManager sm = app.getSystemService(SubscriptionManager.class);
            TelephonyManager tm = app.getSystemService(TelephonyManager.class);
            if (sm == null || tm == null) {
                return;
            }
            java.util.List<android.telephony.SubscriptionInfo> subs =
                    sm.getActiveSubscriptionInfoList();
            if (subs == null) {
                return;
            }
            for (android.telephony.SubscriptionInfo si : subs) {
                int sub = si.getSubscriptionId();
                applyIfNeeded(app, sub, tm.createForSubscriptionId(sub));
            }
        } catch (Throwable t) {
            Log.w(TAG, "volte_gate start: " + t);
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
        boolean wfcAvailable = false;
        boolean epdgSet = false;
        if (cfg != null) {
            try {
                volteAvailable = cfg.getBoolean(KEY_VOLTE, false);
                configApplied = cfg.getBoolean(KEY_APPLIED, true);
                boolean hidden = cfg.getBoolean(KEY_HIDE_4G, false);
                boolean editable = cfg.getBoolean(KEY_EDITABLE_4G, true);
                toggleUsable = !hidden && editable;
                wfcAvailable = cfg.getBoolean(KEY_WFC, false);
                String epdg = cfg.getString(KEY_EPDG_STATIC, "");
                epdgSet = epdg != null && !epdg.isEmpty();
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

        String profile = profileFor(ctx, tm, cid);
        boolean wfcWanted = !wfcAvailable && isWfcProfile(ctx, profile);
        String epdg = epdgFor(profile);
        boolean epdgWanted = !epdgSet && epdg != null;

        if (decision == SKIP_ALREADY && !wfcWanted && !epdgWanted) {
            sLast = stateLabel(kindFor(SKIP_ALREADY, weForcedIt), cid,
                    weForcedIt ? sReapplies : 0) + wfcLabel(profile, wfcAvailable);
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
        if (wfcWanted) {
            over.putBoolean(KEY_WFC, true);
        }
        if (epdgWanted) {
            over.putString(KEY_EPDG_STATIC, epdg);
            over.putIntArray(KEY_EPDG_PRIORITY, new int[] {EPDG_ADDRESS_STATIC, EPDG_ADDRESS_PLMN});
        }
        String kind = kindFor(decision == SKIP_ALREADY ? APPLY_VISIBILITY : decision, true)
                + (wfcWanted ? "+wfc" : "") + (epdgWanted ? "+epdg" : "");
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

    /** LG profile for this SIM: Android carrier id first, then PLMN, as joan resolves it. */
    static String profileFor(Context ctx, TelephonyManager tm, int cid) {
        loadMaps(ctx);
        String p = (cid >= 0 && sProfileByCarrierId != null)
                ? sProfileByCarrierId.get(Integer.toString(cid)) : null;
        if (p == null && sProfileByPlmn != null) {
            try {
                p = sProfileByPlmn.get(tm.getSimOperator());
            } catch (Throwable t) {
                p = null;
            }
        }
        return p;
    }

    /** "OP.CC" of an LG profile key such as TMO.US.NAO. */
    static String operatorOf(String profile) {
        String[] parts = profile.split("\\.");
        return parts.length >= 2 ? parts[0] + "." + parts[1] : profile;
    }

    static boolean isWfcProfile(Context ctx, String profile) {
        if (profile == null) {
            return false;
        }
        loadMaps(ctx);
        Set<String> wfc = sWfcProfiles;
        return wfc != null && (wfc.contains(profile) || wfc.contains(operatorOf(profile)));
    }

    static String epdgFor(String profile) {
        return profile == null ? null : EPDG_BY_OPERATOR.get(operatorOf(profile));
    }

    private static String wfcLabel(String profile, boolean wfcAvailable) {
        return " wfc=" + (wfcAvailable ? "on" : "off") + " lg=" + profile;
    }

    private static synchronized void loadMaps(Context ctx) {
        if (sWfcProfiles != null) {
            return;
        }
        try {
            sProfileByCarrierId = readMap(ctx, "joan/carrier-id-map.json");
            sProfileByPlmn = readMap(ctx, "joan/carrier-plmn-map.json");
            JSONArray arr = new JSONObject(readAsset(ctx, "joan/wfc-profiles.json"))
                    .getJSONArray("profiles");
            Set<String> wfc = new HashSet<>();
            for (int i = 0; i < arr.length(); i++) {
                wfc.add(arr.getString(i));
            }
            sWfcProfiles = wfc;
        } catch (Throwable t) {
            Log.w(TAG, "carrier maps: " + t);
            sWfcProfiles = new HashSet<>();
        }
    }

    private static Map<String, String> readMap(Context ctx, String asset) throws Exception {
        JSONObject o = new JSONObject(readAsset(ctx, asset));
        Map<String, String> m = new HashMap<>();
        for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            m.put(k, o.getString(k));
        }
        return m;
    }

    private static String readAsset(Context ctx, String name) throws Exception {
        try (InputStream in = ctx.getAssets().open(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
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
