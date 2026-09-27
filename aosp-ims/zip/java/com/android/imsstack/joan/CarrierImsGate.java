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
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.Xml;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

/*
 * Ported from joan's org.joan.ims.JoanVolteCarrierGate for the AOSP stack,
 * which has no equivalent: upstream expects the ROM to ship carrier config
 * for its carriers, and LineageOS on joan ships none for most of them.
 *
 * Wi-Fi calling is offered for every SIM too, as VoLTE is. Where the
 * carrier runs an ePDG under the 3GPP default name, or one this build knows
 * (below, and the imported per-carrier data), it works; elsewhere the
 * tunnel is simply not built and IMS stays on LTE. The
 * VoLTE toggle is always left visible, editable and able to turn IMS off.
 *
 * It also applies the per-carrier IMS config LineageOS converts from Pixel
 * carrier settings (SIP, SMS over IMS, Ut, emergency, video, RTT, ePDG,
 * QNS), from assets/joan/carrier/<mcc><mnc>.xml: the blocks matching this
 * SIM, in order, with CarrierConfig's own filter rules. At run time and on
 * top of whatever the ROM ships, so a LineageOS-based ROM keeps its own
 * carrier config; a source build puts the same blocks in its vendor.xml.
 *
 * The IMS, XCAP and emergency APNs a SIM lacks come from ImsApnGate, run
 * at the same moments.
 *
 * A SIM is resolved to an LG profile the way joan does, by Android carrier
 * id and then PLMN (joan's maps), for the ePDG table. Nothing is
 * overridden that the config already sets, except by the imported data.
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
    /**
     * ePDG addresses by LG operator, for SIMs whose own PLMN would not name
     * the operator's ePDG: AT&T and Verizon use their own names, and an MVNO
     * on T-Mobile (MetroPCS is T-Mobile's network under its own LG profile)
     * would derive one from its own PLMN. The values are the ones Google's
     * carrier data gives these carriers. aosp-ims/tools/make-carrier-config.py
     * reads this table.
     */
    private static final Map<String, String> EPDG_BY_OPERATOR = Map.of(
            "TMO.US", "epdg.epc.mnc260.mcc310.pub.3gppnetwork.org",
            "MPCS.US", "epdg.epc.mnc260.mcc310.pub.3gppnetwork.org",
            "ATT.US", "epdg.epc.att.net",
            "VZW.US", "wo.vzwwo.com");

    private static volatile Map<String, String> sProfileByCarrierId;
    private static volatile Map<String, String> sProfileByPlmn;

    /** Our marker in the override: the PLMN whose imported config it carries. */
    static final String KEY_IMPORTED = "aosp_ims_imported_plmn_string";

    // Not exposed in the public SDK jar - string literals by design.
    private static final String KEY_APPLIED = "carrier_config_applied_bool";
    private static final String KEY_HIDE_4G = "hide_enhanced_4g_lte_bool";
    private static final String KEY_EDITABLE_4G = "editable_enhanced_4g_lte_bool";
    private static final String KEY_ALLOW_TURNOFF_IMS = "carrier_allow_turnoff_ims_bool";

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

    /** The gate's own thread: reading a carrier asset must not hold up the main one. */
    private static Handler sHandler;

    private CarrierImsGate() {}

    private static synchronized Handler handler() {
        if (sHandler == null) {
            HandlerThread t = new HandlerThread("ImsStackGate");
            t.start();
            sHandler = new Handler(t.getLooper());
        }
        return sHandler;
    }

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
                    handler().post(() -> {
                        try {
                            TelephonyManager tm0 = app.getSystemService(
                                    TelephonyManager.class);
                            if (tm0 == null) {
                                return;
                            }
                            TelephonyManager tm = tm0.createForSubscriptionId(sub);
                            applyIfNeeded(app, sub, tm);
                            ImsApnGate.apply(app, sub, tm);
                        } catch (Throwable t) {
                            Log.w(TAG, "volte_gate watch: " + t);
                        }
                    });
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
        handler().post(() -> applyAll(app));
    }

    private static void applyAll(Context app) {
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
                TelephonyManager subTm = tm.createForSubscriptionId(sub);
                applyIfNeeded(app, sub, subTm);
                ImsApnGate.apply(app, sub, subTm);
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
        int specificCid = -1;
        try {
            cid = tm.getSimCarrierId();
            specificCid = tm.getSimSpecificCarrierId();
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
        String importedPlmn = "";
        if (cfg != null) {
            try {
                volteAvailable = cfg.getBoolean(KEY_VOLTE, false);
                configApplied = cfg.getBoolean(KEY_APPLIED, true);
                boolean hidden = cfg.getBoolean(KEY_HIDE_4G, false);
                boolean editable = cfg.getBoolean(KEY_EDITABLE_4G, true);
                boolean canTurnOff = cfg.getBoolean(KEY_ALLOW_TURNOFF_IMS, true);
                toggleUsable = !hidden && editable && canTurnOff;
                wfcAvailable = cfg.getBoolean(KEY_WFC, false);
                String epdg = cfg.getString(KEY_EPDG_STATIC, "");
                epdgSet = epdg != null && !epdg.isEmpty();
                importedPlmn = cfg.getString(KEY_IMPORTED, "");
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
        boolean wfcWanted = !wfcAvailable;
        /* The imported config, once per PLMN: the marker says the override
         * already carries it. It can bring its own ePDG address. */
        String plmn = "";
        try {
            plmn = tm.getSimOperator();
        } catch (Throwable t) {
            plmn = "";
        }
        PersistableBundle imported = null;
        if (plmn != null && plmn.length() >= 5 && !plmn.equals(importedPlmn)) {
            imported = importedFor(ctx, tm, plmn, cid, specificCid);
        }
        boolean importedWanted = imported != null;
        String importedEpdg = importedWanted ? imported.getString(KEY_EPDG_STATIC, "") : "";
        String epdg = epdgFor(profile);
        boolean epdgWanted = !epdgSet && epdg != null
                && (importedEpdg == null || importedEpdg.isEmpty());

        if (decision == SKIP_ALREADY && !wfcWanted && !epdgWanted && !importedWanted) {
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
        if (importedWanted) {
            over.putAll(imported);
            over.putString(KEY_IMPORTED, plmn);
        }
        /* Our rules last, over the imported data too: VoLTE and Wi-Fi
         * calling offered, and the opt-out -- a visible, editable toggle
         * that can really turn IMS off -- always there. */
        if (decision == APPLY_FULL || importedWanted) {
            over.putBoolean(KEY_VOLTE, true);
        }
        over.putBoolean(KEY_HIDE_4G, false);
        over.putBoolean(KEY_EDITABLE_4G, true);
        over.putBoolean(KEY_ALLOW_TURNOFF_IMS, true);
        if (wfcWanted || importedWanted) {
            over.putBoolean(KEY_WFC, true);
        }
        if (epdgWanted) {
            over.putString(KEY_EPDG_STATIC, epdg);
            over.putIntArray(KEY_EPDG_PRIORITY, new int[] {EPDG_ADDRESS_STATIC, EPDG_ADDRESS_PLMN});
        }
        String kind = kindFor(decision == SKIP_ALREADY ? APPLY_VISIBILITY : decision, true)
                + (wfcWanted ? "+wfc" : "") + (epdgWanted ? "+epdg" : "")
                + (importedWanted ? "+carrier(" + imported.size() + ")" : "");
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

    /**
     * The imported blocks for this SIM from assets/joan/carrier/<plmn>.xml,
     * merged in order, or null when none match. Filters as CarrierConfig's
     * DefaultCarrierConfigService applies them to vendor.xml.
     */
    static PersistableBundle importedFor(Context ctx, TelephonyManager tm, String plmn,
            int cid, int specificCid) {
        PersistableBundle out = null;
        try (InputStream in = ctx.getAssets().open("joan/carrier/" + plmn + ".xml")) {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, "UTF-8");
            for (int ev = p.next(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev != XmlPullParser.START_TAG || !"carrier_config".equals(p.getName())
                        || !matches(p, tm, plmn, cid, specificCid)) {
                    continue;
                }
                PersistableBundle b = PersistableBundle.restoreFromXml(p);
                if (out == null) {
                    out = new PersistableBundle();
                }
                out.putAll(b);
            }
        } catch (FileNotFoundException e) {
            return null;  // no imported config for this PLMN
        } catch (Throwable t) {
            Log.w(TAG, "carrier config " + plmn + ": " + t);
            return null;
        }
        return out;
    }

    private static boolean matches(XmlPullParser p, TelephonyManager tm, String plmn,
            int cid, int specificCid) {
        for (int i = 0; i < p.getAttributeCount(); i++) {
            String name = p.getAttributeName(i);
            String value = p.getAttributeValue(i);
            boolean ok;
            switch (name) {
                case "mcc":
                    ok = plmn.startsWith(value);
                    break;
                case "mnc":
                    ok = plmn.substring(3).equals(value);
                    break;
                case "gid1":
                    ok = value.equalsIgnoreCase(tm.getGroupIdLevel1());
                    break;
                case "spn": {
                    String spn = tm.getSimOperatorName();
                    ok = "null".equalsIgnoreCase(value)
                            ? (spn == null || spn.isEmpty())
                            : spn != null && Pattern.compile(value, Pattern.CASE_INSENSITIVE)
                                    .matcher(spn).matches();
                    break;
                }
                case "imsi": {
                    String imsi = tm.getSubscriberId();
                    ok = imsi != null && Pattern.compile(value, Pattern.CASE_INSENSITIVE)
                            .matcher(imsi).matches();
                    break;
                }
                case "cid":
                    ok = Integer.parseInt(value) == cid || Integer.parseInt(value) == specificCid;
                    break;
                case "name":
                    ok = true;
                    break;
                default:
                    ok = false;
                    break;
            }
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    static String epdgFor(String profile) {
        return profile == null ? null : EPDG_BY_OPERATOR.get(operatorOf(profile));
    }

    private static String wfcLabel(String profile, boolean wfcAvailable) {
        return " wfc=" + (wfcAvailable ? "on" : "off") + " lg=" + profile;
    }

    private static synchronized void loadMaps(Context ctx) {
        if (sProfileByPlmn != null) {
            return;
        }
        try {
            sProfileByCarrierId = readMap(ctx, "joan/carrier-id-map.json");
            sProfileByPlmn = readMap(ctx, "joan/carrier-plmn-map.json");
        } catch (Throwable t) {
            Log.w(TAG, "carrier maps: " + t);
            sProfileByCarrierId = new HashMap<>();
            sProfileByPlmn = new HashMap<>();
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
