package org.joan.ims;

import android.content.Context;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;

/**
 * The carrier's RTT knobs, read from CarrierConfigManager.
 *
 * <p>Reading only. Nothing here offers RTT, and that separation is the
 * point: RTT is advertised in two places -- an {@code m=text} line in the
 * SDP offer, and the RTT flags on {@code ImsCallProfile} that make the
 * dialer show an RTT button -- and a carrier that routes text to a
 * handset which renders none of it is worse off than one that was never
 * offered it at all. So this file learns what the carrier wants and stops
 * there. {@link JoanT140} is the other half, and is equally inert.
 *
 * <p>The defaults are RFC 4103's own shape rather than invented ones:
 * no static payload type exists for T.140 or for the RFC 2198
 * redundancy that carries it, so an unconfigured carrier yields
 * {@link #supported} false and payload types of 0, which read as
 * "unknown" everywhere rather than as a usable offer.
 */
final class JoanImsRttConfig {

    /** RFC 4103 3: the text stream's own RTCP bandwidth, bps. */
    static final int DEFAULT_RS_BPS = 100;
    static final int DEFAULT_RR_BPS = 300;
    /** RFC 4103 8: 1 kbps carries T.140 comfortably; AOSP asks for 4. */
    static final int DEFAULT_AS_KBPS = 4;

    final boolean supported;
    final boolean supportedWhileRoaming;
    final boolean upgradeSupported;
    final boolean downgradeSupported;
    /**
     * T.140 and RFC 2198 redundancy payload types, or 0 when unset.
     *
     * <p>Both are dynamic types (RFC 3551 6: 96-127). There is no
     * registered static number to fall back on, so 0 means "the carrier
     * did not say", never "use this".
     */
    final int t140PayloadType;
    final int redPayloadType;
    final int asKbps;
    final int rsBps;
    final int rrBps;
    /**
     * Whether the carrier will carry text on the default bearer.
     *
     * <p>False means text wants a dedicated bearer, which on this network
     * means RFC 3312 preconditions -- see {@link #qosPrecondition}.
     */
    final boolean onDefaultBearer;
    /**
     * Whether the carrier expects QoS preconditions for the text stream.
     *
     * <p>RFC 3312: the offer carries {@code a=curr}/{@code a=des}/
     * {@code a=conf} and the session does not alert until the bearer is
     * established. joan implements none of that for any media type, so a
     * carrier answering true here cannot be served by a text line alone
     * however well the codec works. Recorded so that the blocker is
     * visible in the trace rather than discovered during a call.
     */
    final boolean qosPrecondition;
    /** Where the values came from, for the trace. */
    final String source;

    JoanImsRttConfig(boolean supported, boolean supportedWhileRoaming,
                             boolean upgradeSupported,
                             boolean downgradeSupported,
                             int t140PayloadType, int redPayloadType,
                             int asKbps, int rsBps, int rrBps,
                             boolean onDefaultBearer, boolean qosPrecondition,
                             String source) {
        this.supported = supported;
        this.supportedWhileRoaming = supportedWhileRoaming;
        this.upgradeSupported = upgradeSupported;
        this.downgradeSupported = downgradeSupported;
        this.t140PayloadType = t140PayloadType;
        this.redPayloadType = redPayloadType;
        this.asKbps = asKbps;
        this.rsBps = rsBps;
        this.rrBps = rrBps;
        this.onDefaultBearer = onDefaultBearer;
        this.qosPrecondition = qosPrecondition;
        this.source = source;
    }

    static JoanImsRttConfig defaults(String source) {
        return new JoanImsRttConfig(false, false, false, false, 0, 0,
                DEFAULT_AS_KBPS, DEFAULT_RS_BPS, DEFAULT_RR_BPS,
                false, false, source);
    }

    private static volatile JoanImsRttConfig sCached;
    private static volatile int sCachedSub = -1;

    static JoanImsRttConfig forSub(Context ctx, int subId) {
        JoanImsRttConfig hit = sCached;
        if (hit != null && sCachedSub == subId) {
            return hit;
        }
        JoanImsRttConfig p = load(ctx, subId);
        sCached = p;
        sCachedSub = subId;
        return p;
    }

    private static JoanImsRttConfig load(Context ctx, int subId) {
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
        JoanImsRttConfig d = defaults("carrier-config");
        boolean anySet = cfg.containsKey(
                CarrierConfigManager.KEY_RTT_SUPPORTED_BOOL);
        return new JoanImsRttConfig(
                cfg.getBoolean(CarrierConfigManager.KEY_RTT_SUPPORTED_BOOL,
                        d.supported),
                cfg.getBoolean(
                        CarrierConfigManager.KEY_RTT_SUPPORTED_WHILE_ROAMING_BOOL,
                        d.supportedWhileRoaming),
                cfg.getBoolean(
                        CarrierConfigManager.KEY_RTT_UPGRADE_SUPPORTED_BOOL,
                        d.upgradeSupported),
                cfg.getBoolean(
                        CarrierConfigManager.KEY_RTT_DOWNGRADE_SUPPORTED_BOOL,
                        d.downgradeSupported),
                payloadType(cfg,
                        CarrierConfigManager.ImsRtt.KEY_T140_PAYLOAD_TYPE_INT),
                payloadType(cfg,
                        CarrierConfigManager.ImsRtt.KEY_RED_PAYLOAD_TYPE_INT),
                cfg.getInt(CarrierConfigManager.ImsRtt
                        .KEY_TEXT_AS_BANDWIDTH_KBPS_INT, d.asKbps),
                cfg.getInt(CarrierConfigManager.ImsRtt
                        .KEY_TEXT_RS_BANDWIDTH_BPS_INT, d.rsBps),
                cfg.getInt(CarrierConfigManager.ImsRtt
                        .KEY_TEXT_RR_BANDWIDTH_BPS_INT, d.rrBps),
                cfg.getBoolean(CarrierConfigManager.ImsRtt
                        .KEY_TEXT_ON_DEFAULT_BEARER_SUPPORTED_BOOL,
                        d.onDefaultBearer),
                cfg.getBoolean(CarrierConfigManager.ImsRtt
                        .KEY_TEXT_QOS_PRECONDITION_SUPPORTED_BOOL,
                        d.qosPrecondition),
                anySet ? "carrier-config" : "carrier-config-unset");
    }

    /**
     * Read a payload type that lives inside the codec-capability bundle.
     *
     * <p>The two payload-type keys are NOT top-level. A live
     * {@code dumpsys carrier_config} prints them nested:
     *
     * <pre>
     * imsrtt.text_codec_capability_payload_types_bundle =
     *     PersistableBundle[{imsrtt.t140_payload_type_int=111,
     *                        imsrtt.red_payload_type_int=112}]
     * </pre>
     *
     * <p>Asking the outer bundle for {@code imsrtt.t140_payload_type_int}
     * therefore returns the fallback on a carrier that configured it --
     * silently, because a missing key and a key holding the default look
     * identical through {@code getInt}. The nested bundle is read first
     * and the top level only as a fallback, since the shape is a carrier's
     * to choose and not one this file should assume.
     */
    private static int payloadType(PersistableBundle cfg, String key) {
        PersistableBundle inner = null;
        try {
            inner = cfg.getPersistableBundle(CarrierConfigManager.ImsRtt
                    .KEY_TEXT_CODEC_CAPABILITY_PAYLOAD_TYPES_BUNDLE);
        } catch (Throwable ignored) {
            /* A carrier that stored something else under that key. */
        }
        if (inner != null && inner.containsKey(key)) {
            return dynamicOrZero(inner.getInt(key, 0));
        }
        return dynamicOrZero(cfg.getInt(key, 0));
    }

    /** RFC 3551 6: dynamic payload types are 96-127. Anything else is unusable. */
    static int dynamicOrZero(int pt) {
        return pt >= 96 && pt <= 127 ? pt : 0;
    }

    /**
     * True when the carrier's configuration describes a text stream joan
     * could actually carry.
     *
     * <p>Deliberately stricter than {@link #supported}: a carrier can
     * enable RTT and still require preconditions joan does not implement,
     * and the honest answer there is no.
     */
    boolean usable() {
        return supported && t140PayloadType != 0 && !qosPrecondition;
    }

    String summary() {
        return "rtt=" + supported
                + " roaming=" + supportedWhileRoaming
                + " upgrade=" + upgradeSupported
                + " t140=" + (t140PayloadType == 0 ? "unset" : t140PayloadType)
                + " red=" + (redPayloadType == 0 ? "unset" : redPayloadType)
                + " as=" + asKbps + "kbps rs=" + rsBps + " rr=" + rrBps
                + " default_bearer=" + onDefaultBearer
                + " precondition=" + qosPrecondition
                + " usable=" + usable()
                + " src=" + source;
    }
}
