package org.joan.ims;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Carrier behavior profile, distilled from stock LG Ims6 configuration
 * XMLs (values transcribed as discovered facts; no LG files ship).
 *
 * The profile picks the conference focus URI, the REFER subscription
 * style, and call/session knobs per carrier. Unknown carriers get the
 * 3GPP defaults (factory conference URI, RFC-typical timers) — the
 * same fallbacks stock's UCSessionConfig uses when a table is empty.
 */
public final class JoanCarrierProfile {
    private static final String TAG = "JoanIms";

    // 3GPP TS 24.147 conference factory URI (stock fallback too).
    public final String confUri;
    public final boolean referSub;
    public final boolean confSub;
    public final boolean confSubInDialog;
    public final int maxSessions;
    public final int cwType;
    public final boolean use180Rpr;
    public final int offerResCode;
    /**
     * The carrier's {@code common_tcp_criterion_len}: a SIP message longer
     * than this goes over TCP. 0 means "no criterion" and &lt;0 means the
     * profile did not carry one.
     */
    public final int tcpCriterionLen;
    public final String srcKey;

    private static volatile JoanCarrierProfile sCached;
    private static volatile String sCachedMccMnc;

    private JoanCarrierProfile(String confUri, boolean referSub,
                               boolean confSub, boolean confSubInDialog,
                               int maxSessions, int cwType,
                               boolean use180Rpr, int offerResCode,
                               int tcpCriterionLen, String srcKey) {
        this.confUri = confUri;
        this.referSub = referSub;
        this.confSub = confSub;
        this.confSubInDialog = confSubInDialog;
        this.maxSessions = maxSessions;
        this.cwType = cwType;
        this.use180Rpr = use180Rpr;
        this.offerResCode = offerResCode;
        this.tcpCriterionLen = tcpCriterionLen;
        this.srcKey = srcKey;
    }

    /** 3GPP defaults when nothing better is known. */
    static JoanCarrierProfile defaults(String mcc, String mnc) {
        String factory = String.format(
                "sip:mmtel@conf-factory.ims.mnc%s.mcc%s.3gppnetwork.org",
                pad3(mnc), mcc);
        return new JoanCarrierProfile(factory, true, true, false,
                2, 1, true, 183, -1, "3gpp-default");
    }

    private static String pad3(String mnc) {
        if (mnc == null || mnc.isEmpty()) {
            return "000";
        }
        if (mnc.length() >= 3) {
            return mnc;
        }
        StringBuilder b = new StringBuilder(mnc);
        while (b.length() < 3) {
            b.insert(0, '0');
        }
        return b.toString();
    }

    /**
     * Load (and cache) the profile for the current network. The JSON
     * asset maps carrier keys like "TMO.US.NAO" to knob values; the
     * MCC/MNC table below picks the key.
     */
    public static JoanCarrierProfile forNetwork(Context ctx,
                                                String mcc,
                                                String mnc) {
        if (mcc == null || mnc == null || mcc.isEmpty() || mnc.isEmpty()) {
            return defaults(mcc, mnc);
        }
        String cacheKey = mcc + ":" + mnc;
        JoanCarrierProfile hit = sCached;
        if (hit != null && cacheKey.equals(sCachedMccMnc)) {
            return hit;
        }
        JoanCarrierProfile p = load(ctx, mcc, mnc);
        sCached = p;
        sCachedMccMnc = cacheKey;
        return p;
    }

    /** Read a whole JSON asset, or null. */
    private static JSONObject asset(Context ctx, String name) {
        try {
            InputStream in = ctx.getAssets().open(name);
            byte[] buf = new byte[in.available()];
            int n = 0, r;
            while ((r = in.read(buf, n, buf.length - n)) > 0) {
                n += r;
                if (n == buf.length) {
                    break;
                }
            }
            in.close();
            return new JSONObject(new String(buf, 0, n,
                    StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The profile key for a PLMN, from the shipped PLMN map.
     *
     * <p>The map is transcribed from stock configuration and covers 294
     * PLMNs. {@link #carrierKey} remains as the fallback for the handful
     * of ranges joan mapped by hand before the table existed, and as the
     * answer for a PLMN the table does not list.
     */
    private static String mappedKey(Context ctx, String mcc, String mnc) {
        JSONObject map = asset(ctx, "carrier-plmn-map.json");
        if (map == null) {
            return null;
        }
        /* A PLMN is MCC + MNC with the MNC's own width: 2-digit and
         * 3-digit MNCs are different networks, so try the SIM's width
         * first and only then the padded form. */
        String k = mcc + mnc;
        if (map.has(k)) {
            return map.optString(k, null);
        }
        String k3 = mcc + pad3(mnc);
        if (map.has(k3)) {
            return map.optString(k3, null);
        }
        return null;
    }

    private static JoanCarrierProfile load(Context ctx, String mcc, String mnc) {
        String key = mappedKey(ctx, mcc, mnc);
        if (key == null) {
            key = carrierKey(mcc, mnc);
        }
        try {
            JSONObject all = asset(ctx, "carrier-profiles.json");
            if (all == null) {
                return defaults(mcc, mnc);
            }
            if (key != null && all.has(key)) {
                JSONObject o = all.getJSONObject(key);
                String conf = o.optString("conf_uri", "");
                String base = conf.isEmpty()
                        ? defaults(mcc, mnc).confUri : conf;
                return new JoanCarrierProfile(
                        base,
                        o.optBoolean("refer_sub", true),
                        o.optBoolean("conf_sub", true),
                        o.optBoolean("conf_sub_in_dialog", false),
                        o.optInt("max_sessions", 2),
                        o.optInt("cw_type", 1),
                        o.optBoolean("use_180_rpr", true),
                        o.optInt("offer_res_code", 183),
                        o.optInt("tcp_criterion_len", -1),
                        key);
            }
        } catch (Throwable t) {
            Log.w(TAG, "carrier profile load failed "
                    + t.getClass().getSimpleName());
        }
        JoanCarrierProfile d = defaults(mcc, mnc);
        Log.i(TAG, "carrier profile: default for " + mcc + "/" + mnc);
        return d;
    }

    /**
     * China Mobile's own MNCs under MCC 460.
     *
     * <p>Confirmed against a shipping LineageOS device tree rather than
     * guessed: OnePlus's CarrierConfigResCommon vendor.xml (sm8250-common
     * and siblings) lists exactly these five under
     * {@code <carrier_config operator="CMCC">}, with China Unicom
     * (46001/46006/46009), China Telecom (46003/46005/46011/46012) and
     * China Broadnet (46015) as separate operators with their own
     * entries.
     *
     * <p>An MNC left out of this set gets the 3GPP defaults rather than
     * another operator's profile, so being wrong by omission costs a
     * tuned profile, while being wrong by inclusion would apply China
     * Mobile's settings to a Unicom or Telecom subscriber.
     */
    private static final java.util.Set<String> CMCC_MNCS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "000", "002", "004", "007", "008"));

    /**
     * MCC/MNC -> profile key, from the distilled stock XML tree.
     * US carriers keyed by MCC only where stock keys them by brand;
     * these are the LG profile families, not a PLMN database.
     */
    /** True for a China Mobile PLMN, by the confirmed MNC set. */
    static boolean isCmcc(String mcc, String mnc) {
        return "460".equals(mcc) && mnc != null
                && CMCC_MNCS.contains(pad3(mnc));
    }

    static String carrierKey(String mcc, String mnc) {
        // T-Mobile family (US): MCC 310-316 across the merged TMUS/Sprint
        // network; stock keys all of these profiles as TMO.US.NAO.
        if (mcc.compareTo("310") >= 0 && mcc.compareTo("316") <= 0) {
            if ("120".equals(mnc)) {
                return "SPR.US";
            }
            if ("030".equals(mnc)) {
                return "ATT.US.NAO";
            }
            if ("004".equals(mnc)) {
                return "VZW.US.VOWIFI";
            }
            return "TMO.US.NAO";
        }
        if ("460".equals(mcc)) {
            /* MCC 460 is all of China, not one operator. Mapping the whole
             * MCC to CMCC handed China Unicom and China Telecom
             * subscribers China Mobile's conference URI, session timers,
             * TCP criterion and offer response code -- another operator's
             * settings, applied with no way to tell from the outside.
             *
             * Only China Mobile's own MNCs get the profile; everything
             * else under 460 falls through to the 3GPP defaults, which is
             * what an unknown carrier has always got. LG shipped no
             * Unicom or Telecom profile, so there is nothing better to
             * return for them -- and the defaults are right far more
             * often than a competitor's file. */
            return CMCC_MNCS.contains(pad3(mnc)) ? "CMCC.CN" : null;
        }
        if ("440".equals(mcc) || "441".equals(mcc)) {
            return "DCM.JP";
        }
        if ("450".equals(mcc)) {
            return "LGU.KR";
        }
        return null;
    }
}
