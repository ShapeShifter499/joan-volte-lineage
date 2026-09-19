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
    /** Per-transport-family criteria; 0 means "use the common one". */
    public final int tcpCriterionV4;
    public final int tcpCriterionV6;
    /** The carrier's REGISTER Expires, seconds; 0 if not carried. */
    public final int regExpiration;
    /**
     * The carrier's ordered VoLTE audio offer, or empty.
     *
     * <p>From the stock media configuration, which is the only source
     * that carries an AMR mode-set: Android's carrier config supplies
     * payload types and framing on some networks and leaves the codec
     * attribute bundles empty on every one tested so far.
     */
    public final java.util.List<JoanSipBuilder.Capability> codecs;
    /** The carrier's unprotected P-CSCF port, or 0. */
    public final int pcscfPort;
    /** Whether this carrier's stock profile sends a User-Agent. */
    public final boolean sendUserAgent;
    /**
     * The carrier's declared sec-agree algorithm set,
     * {@code aos_reg_0_ipsec_algs} from the vendor snapshot, or -1 when
     * absent (offer everything implemented). Distilled into the assets
     * since the beginning and read by nothing until now -- the same
     * shape of gap {@code xcap_server} had. Bit layout in
     * {@link JoanSipCrypto#setOfferMask}.
     */
    public final int ipsecAlgs;
    /**
     * Whether this carrier's stock profile puts the {@code algorithm}
     * parameter in the REGISTER's Authorization header -- bit 24 of
     * {@code common_sip_features}.
     *
     * <p>True when the profile says nothing, which is the RFC 3310
     * reading and keeps every carrier we have no configuration for on the
     * behaviour it registers with today.
     */
    public final boolean sendAuthAlgorithm;
    /**
     * Whether this carrier expects a preloaded {@code Route} on the
     * REGISTER -- {@code common_sip_features} bit 20, which AOSP names
     * {@code SIP_FEATURE_CAPS_ROUTE_HEADER_IN_REG} and
     * {@code RegParameter::FormHeaders} tests before it adds the header
     * at all.
     *
     * <p>Only 7 of 136 shipped profiles set it, T-Mobile among them and
     * China Mobile not. TS 24.229 5.1.1.2 describes the preloaded route
     * set, but the reference stack makes emitting it a per-carrier
     * decision rather than a rule, so joan follows the carrier and not
     * the paraphrase: false where nothing says otherwise, which is the
     * behaviour every network joan registers on today already has.
     */
    public final boolean routeHeaderInReg;
    /**
     * Ut/XCAP: where this carrier keeps the subscriber's supplementary
     * services, and whether it expects them controlled that way.
     *
     * <p>Carried in the shipped profile since it was distilled and read
     * by nothing until now. 56 of 136 profiles name a server, and
     * {@code utControl} is {@code "ut"} for 123 of them -- the rest say
     * {@code "sip"} or {@code "ps"}, meaning the carrier does not expect
     * XCAP to be the control path at all.
     */
    public final String xcapServer;
    public final int xcapPort;
    public final boolean xcapTls;
    public final String xcapPdn;
    public final String utControl;
    public final String srcKey;

    private static volatile JoanCarrierProfile sCached;
    private static volatile String sCachedMccMnc;

    private JoanCarrierProfile(String confUri, boolean referSub,
                               boolean confSub, boolean confSubInDialog,
                               int maxSessions, int cwType,
                               boolean use180Rpr, int offerResCode,
                               int tcpCriterionLen, int tcpCriterionV4,
                               int tcpCriterionV6, int regExpiration,
                               java.util.List<JoanSipBuilder.Capability> codecs,
                               int pcscfPort, boolean sendUserAgent,
                               int ipsecAlgs,
                               boolean sendAuthAlgorithm,
                               boolean routeHeaderInReg,
                               String xcapServer, int xcapPort,
                               boolean xcapTls, String xcapPdn,
                               String utControl,
                               String srcKey) {
        this.confUri = confUri;
        this.referSub = referSub;
        this.confSub = confSub;
        this.confSubInDialog = confSubInDialog;
        this.maxSessions = maxSessions;
        this.cwType = cwType;
        this.use180Rpr = use180Rpr;
        this.offerResCode = offerResCode;
        this.tcpCriterionLen = tcpCriterionLen;
        this.tcpCriterionV4 = tcpCriterionV4;
        this.tcpCriterionV6 = tcpCriterionV6;
        this.regExpiration = regExpiration;
        this.codecs = codecs == null
                ? java.util.Collections.<JoanSipBuilder.Capability>emptyList()
                : java.util.Collections.unmodifiableList(codecs);
        this.pcscfPort = pcscfPort;
        this.sendUserAgent = sendUserAgent;
        this.ipsecAlgs = ipsecAlgs;
        this.sendAuthAlgorithm = sendAuthAlgorithm;
        this.routeHeaderInReg = routeHeaderInReg;
        this.xcapServer = xcapServer;
        this.xcapPort = xcapPort;
        this.xcapTls = xcapTls;
        this.xcapPdn = xcapPdn;
        this.utControl = utControl;
        this.srcKey = srcKey;
    }

    /**
     * The carrier's audio offer, as capabilities the SIP builder can use.
     *
     * <p>Only AMR is taken. EVS appears in several carriers' lists and
     * LineageOS 22 ships no EVS encoder, so offering it would name a codec
     * this device cannot open -- the failure mode that makes a carrier
     * pick it, the encoder fail, and the media layer fall back to PCMU
     * while the peer keeps sending EVS. telephone-event is handled
     * separately because it is not a speech codec.
     */
    private static java.util.List<JoanSipBuilder.Capability> parseCodecs(
            org.json.JSONArray arr) {
        if (arr == null) {
            return null;
        }
        java.util.List<JoanSipBuilder.Capability> out =
                new java.util.ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null || !"AMR".equalsIgnoreCase(c.optString("type"))) {
                continue;
            }
            int pt = c.optInt("pt", -1);
            int rate = c.optInt("rate", -1);
            if (pt < 96 || pt > 127 || (rate != 8000 && rate != 16000)) {
                continue;
            }
            org.json.JSONArray ms = c.optJSONArray("mode_set");
            int[] modes = null;
            if (ms != null && ms.length() > 0) {
                modes = new int[ms.length()];
                for (int k = 0; k < ms.length(); k++) {
                    modes[k] = ms.optInt(k, -1);
                }
            }
            out.add(JoanSipBuilder.Capability.amr(
                    rate == 16000 ? "AMR-WB" : "AMR", rate, pt,
                    c.optBoolean("octet_align", false), modes));
        }
        return out;
    }

    /** 3GPP defaults when nothing better is known. */
    static JoanCarrierProfile defaults(String mcc, String mnc) {
        String factory = String.format(
                "sip:mmtel@conf-factory.ims.mnc%s.mcc%s.3gppnetwork.org",
                pad3(mnc), mcc);
        return new JoanCarrierProfile(factory, true, true, false,
                2, 1, true, 183, -1, 0, 0, 0, null, 0, true, -1, true,
                false, "", 0, false, "", "", "3gpp-default");
    }

    /**
     * {@code common_sip_features} bit 24. AOSP names it
     * {@code SIP_FEATURE_CAPS_AUTHENTICATION_ALGORITHM_PARAMETER} and
     * sets it only from the carrier key
     * {@code ims.allow_algorithm_param_in_sip_authorization_header_bool}
     * -- an ALLOW flag absent from its baseline, so the reference stack's
     * default is to omit the parameter and sending it is what a carrier
     * opts into. 130 of LG's 136 profiles leave it off.
     */
    private static final long SIP_FEATURE_AUTH_ALGORITHM_PARAM = 0x01000000L;

    /**
     * {@code common_sip_features} bit 20, AOSP's
     * {@code SIP_FEATURE_CAPS_ROUTE_HEADER_IN_REG}. Gates the preloaded
     * Route on a REGISTER in {@code RegParameter::FormHeaders}.
     */
    private static final long SIP_FEATURE_ROUTE_HEADER_IN_REG = 0x00100000L;

    /**
     * Test one bit of a profile's {@code sip_features} mask.
     *
     * <p>A profile carrying no readable mask answers true: a missing
     * declaration is not the value zero, and the standards-clean default
     * is the safe one to fall back to.
     */
    private static boolean hasSipFeature(String features, long bit) {
        if (features == null) {
            return true;
        }
        String v = features.trim();
        if (v.isEmpty()) {
            return true;
        }
        if (v.startsWith("0x") || v.startsWith("0X")) {
            v = v.substring(2);
        }
        try {
            return (Long.parseLong(v, 16) & bit) != 0L;
        } catch (NumberFormatException e) {
            Log.w(TAG, "carrier profile: unreadable sip_features");
            return true;
        }
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
                        o.optInt("reg_tcp_criterion_v4", 0),
                        o.optInt("reg_tcp_criterion_v6", 0),
                        o.optInt("reg_expiration", 0),
                        parseCodecs(o.optJSONArray("codecs")),
                        o.optInt("pcscf_port", 0),
                        !o.optString("user_agent_fmt", "").isEmpty(),
                        o.optInt("ipsec_algs", -1),
                        hasSipFeature(o.optString("sip_features", ""),
                                SIP_FEATURE_AUTH_ALGORITHM_PARAM),
                        hasSipFeature(o.optString("sip_features", ""),
                                SIP_FEATURE_ROUTE_HEADER_IN_REG),
                        o.optString("xcap_server", ""),
                        o.optInt("xcap_port", 0),
                        o.optBoolean("xcap_tls", false),
                        o.optString("xcap_pdn", ""),
                        o.optString("ut_control_preference", ""),
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
