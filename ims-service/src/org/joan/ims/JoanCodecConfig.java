package org.joan.ims;

import android.content.Context;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;

/**
 * The carrier's audio codec offer, read from {@code CarrierConfigManager
 * .ImsVoice}, and pushed into {@link JoanSipBuilder}.
 *
 * <p>Android 15 publishes the whole offer per carrier, and we were
 * ignoring all of it. T-Mobile 310-260 asks for:
 *
 * <pre>
 *   amrwb_payload_description_bundle = {97={}, 98={payload_format=1}}
 *   amrnb_payload_description_bundle = {99={}, 100={payload_format=1}}
 *   audio_codec_capability_payload_types_bundle =
 *       {amrwb=[97,98], amrnb=[99,100], dtmfwb=[101], dtmfnb=[102]}
 * </pre>
 *
 * <p>Two things follow from that, and we had both wrong.
 *
 * <p><b>Each codec is offered twice, once per framing.</b> An empty
 * attribute bundle means the default, {@code BANDWIDTH_EFFICIENT}; a
 * {@code payload_format=1} means {@code OCTET_ALIGNED}. So the carrier
 * wants AMR-WB bandwidth-efficient on 97 and octet-aligned on 98, and
 * lets the network choose. We offered each codec once and asserted
 * {@code octet-align=1}, which is what made a network defaulting to
 * bandwidth-efficient skip our AMR entirely and answer G.711.
 *
 * <p><b>The payload numbers are the carrier's, not ours.</b> Ours
 * collided with different codecs in this carrier's own map: our AMR-NB 97
 * is their AMR-WB, our telephone-event 100 is their AMR-NB octet-aligned,
 * our 101 is their wideband telephone-event. As the offerer we may pick
 * any dynamic number and a correct answerer reads {@code a=rtpmap} -- but
 * a core that leans on its configured table instead decodes our speech as
 * the wrong codec, and there is nothing to gain by differing.
 *
 * <p>Nothing here overrides the {@code MediaCodecList} probe: this says
 * what the carrier wants offered, {@link JoanSipBuilder#restrictProfile}
 * says what the ROM can run, and the offer needs both to be true.
 */
final class JoanCodecConfig {

    /** {@code ImsVoice.BANDWIDTH_EFFICIENT}. Also the value when absent. */
    static final int BANDWIDTH_EFFICIENT = 0;
    /** {@code ImsVoice.OCTET_ALIGNED}. */
    static final int OCTET_ALIGNED = 1;

    private JoanCodecConfig() {
    }

    private static volatile String sSummary = "";

    /** Drop any memo of the last applied profile; config was rebuilt. */
    static void invalidate() {
        sSummary = "";
    }

    /**
     * Read the carrier's codec offer and apply it. Silently leaves the
     * built-in profile in place when the carrier publishes nothing, which
     * is the common case.
     *
     * @return a one-line summary, or "" when nothing changed
     */
    static String apply(Context ctx, int subId) {
        if (ctx == null || !SubscriptionManager.isValidSubscriptionId(subId)) {
            return fallback("no-sub");
        }
        PersistableBundle cfg;
        try {
            CarrierConfigManager ccm =
                    ctx.getSystemService(CarrierConfigManager.class);
            if (ccm == null) {
                return fallback("no-service");
            }
            cfg = ccm.getConfigForSubId(subId);
        } catch (Throwable t) {
            return fallback("read-failed:" + t.getClass().getSimpleName());
        }
        if (cfg == null || cfg.isEmpty()) {
            return fallback("empty");
        }

        PersistableBundle types = cfg.getPersistableBundle(
                CarrierConfigManager.ImsVoice
                        .KEY_AUDIO_CODEC_CAPABILITY_PAYLOAD_TYPES_BUNDLE);
        if (types == null || types.isEmpty()) {
            return fallback("carrier-silent");
        }

        int[] wbPts = types.getIntArray(CarrierConfigManager.ImsVoice
                .KEY_AMRWB_PAYLOAD_TYPE_INT_ARRAY);
        int[] nbPts = types.getIntArray(CarrierConfigManager.ImsVoice
                .KEY_AMRNB_PAYLOAD_TYPE_INT_ARRAY);
        int[] dtmfWb = types.getIntArray(CarrierConfigManager.ImsVoice
                .KEY_DTMFWB_PAYLOAD_TYPE_INT_ARRAY);
        int[] dtmfNb = types.getIntArray(CarrierConfigManager.ImsVoice
                .KEY_DTMFNB_PAYLOAD_TYPE_INT_ARRAY);

        PersistableBundle wbDesc = cfg.getPersistableBundle(
                CarrierConfigManager.ImsVoice
                        .KEY_AMRWB_PAYLOAD_DESCRIPTION_BUNDLE);
        PersistableBundle nbDesc = cfg.getPersistableBundle(
                CarrierConfigManager.ImsVoice
                        .KEY_AMRNB_PAYLOAD_DESCRIPTION_BUNDLE);

        java.util.List<JoanSipBuilder.Capability> out =
                new java.util.ArrayList<>();
        /* Wideband first: the carrier's array order decides within a
         * codec, but AMR-WB above AMR-NB is our preference, and an
         * offerer's order is what an answerer honours. */
        addAll(out, "AMR-WB", 16000, wbPts, wbDesc);
        addAll(out, "AMR", 8000, nbPts, nbDesc);

        if (out.isEmpty()) {
            return fallback("carrier-no-amr");
        }

        JoanSipBuilder.applyCarrierCodecs(out, first(dtmfWb), first(dtmfNb));

        StringBuilder b = new StringBuilder("carrier-config ");
        for (int i = 0; i < out.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            JoanSipBuilder.Capability c = out.get(i);
            b.append(c.name).append('/').append(c.offerPt)
                    .append('[').append(c.fmtp).append(']');
        }
        b.append(" te_wb=").append(first(dtmfWb))
                .append(" te_nb=").append(first(dtmfNb));
        return memo(b.toString());
    }

    private static void addAll(java.util.List<JoanSipBuilder.Capability> out,
                               String name, int rate, int[] pts,
                               PersistableBundle desc) {
        if (pts == null) {
            return;
        }
        for (int pt : pts) {
            /* A dynamic payload type, or nothing. A carrier publishing a
             * static number for AMR is publishing a mistake, and offering
             * it would put AMR on a payload type that already means
             * something else on the wire. */
            if (pt < 96 || pt > 127) {
                continue;
            }
            PersistableBundle attr = desc == null ? null
                    : desc.getPersistableBundle(Integer.toString(pt));
            int format = BANDWIDTH_EFFICIENT;
            int[] modeSet = null;
            if (attr != null) {
                format = attr.getInt(CarrierConfigManager.ImsVoice
                        .KEY_AMR_CODEC_ATTRIBUTE_PAYLOAD_FORMAT_INT,
                        BANDWIDTH_EFFICIENT);
                modeSet = attr.getIntArray(CarrierConfigManager.ImsVoice
                        .KEY_AMR_CODEC_ATTRIBUTE_MODESET_INT_ARRAY);
            }
            out.add(JoanSipBuilder.Capability.amr(name, rate, pt,
                    format == OCTET_ALIGNED, modeSet));
        }
    }

    private static int first(int[] a) {
        return (a == null || a.length == 0) ? 0 : a[0];
    }

    private static String fallback(String why) {
        JoanSipBuilder.applyCarrierCodecs(null, 0, 0);
        return memo("built-in (" + why + ")");
    }

    private static String memo(String s) {
        if (s.equals(sSummary)) {
            return "";
        }
        sSummary = s;
        return s;
    }
}
