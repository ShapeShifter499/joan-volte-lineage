package org.joan.ims;

import android.content.Context;
import android.net.LinkProperties;
import android.net.RouteInfo;
import android.os.Build;
import android.os.SystemClock;
import android.telephony.PreciseDataConnectionState;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/** Event-driven, read-only diagnostics. No raw APN objects, IP addresses,
 * identifiers, exception messages, modem commands, or periodic DNS traffic.
 * PreciseDataConnectionState is telephony's framework view, NOT raw PCO.
 */
final class JoanImsDiagnostics {
    private static TelephonyManager sTm;
    private static DataListener sListener;
    private static int sSub = -1;
    private static volatile String sListenerState = "not_started";
    private static volatile String sData = "unobserved";
    private static volatile String sNetwork = "unobserved";
    private static volatile long sDataAt, sNetworkAt;
    private JoanImsDiagnostics() {}

    static synchronized void start(Context ctx, int sub) {
        if (sListener != null && sSub == sub) return;
        stop();
        sSub = sub;
        if (Build.VERSION.SDK_INT < 31) {
            sListenerState = "unavailable_api_lt31";
            return;
        }
        try {
            TelephonyManager base = ctx.getSystemService(TelephonyManager.class);
            if (base == null) { sListenerState = "no_telephony"; return; }
            sTm = base.createForSubscriptionId(sub);
            sListener = new DataListener(sub);
            sTm.registerTelephonyCallback(ctx.getMainExecutor(), sListener);
            sListenerState = "listening_sub=" + sub;
        } catch (Exception e) {
            sListener = null;
            sListenerState = "unavailable_" + e.getClass().getSimpleName();
        }
        JoanTrace.note("IMS diagnostics " + sListenerState);
    }

    static synchronized void stop() {
        DataListener old = sListener;
        sListener = null; // fence queued callbacks before unregister
        if (sTm != null && old != null) {
            try { sTm.unregisterTelephonyCallback(old); } catch (Exception ignored) { }
        }
        sTm = null;
        sSub = -1;
        sListenerState = "stopped";
        sData = "unobserved";
        sDataAt = 0;
    }

    private static final class DataListener extends TelephonyCallback
            implements TelephonyCallback.PreciseDataConnectionStateListener {
        final int sub;
        DataListener(int s) { sub = s; }
        @Override public void onPreciseDataConnectionStateChanged(PreciseDataConnectionState state) {
            synchronized (JoanImsDiagnostics.class) {
                if (sListener != this || state == null) return;
                try {
                    ApnSetting apn = state.getApnSetting();
                    if (apn == null || (apn.getApnTypeBitmask() & ApnSetting.TYPE_IMS) == 0) return;
                    String roaming;
                    try { roaming = String.valueOf(sTm.isNetworkRoaming()); }
                    catch (Exception e) { roaming = "unknown"; }
                    String value = "sub=" + sub + " transport=" + state.getTransportType()
                            + " state=" + state.getState() + " rat=" + state.getNetworkType()
                            + " cause=" + state.getLastCauseCode()
                            + " apn_name=" + apnClass(apn.getApnName())
                            + " apn_types=" + apn.getApnTypeBitmask()
                            + " configured_protocol=" + protocol(apn.getProtocol())
                            + " roaming_protocol=" + protocol(apn.getRoamingProtocol())
                            + " network_roaming=" + roaming
                            + " framework_link={" + linkSummary(state.getLinkProperties()) + "}"
                            + " raw_modem_pco=unobserved";
                    sDataAt = SystemClock.elapsedRealtime();
                    if (!value.equals(sData)) { sData = value; JoanTrace.note("IMS data_call " + value); }
                } catch (Exception e) {
                    sData = "unavailable_" + e.getClass().getSimpleName();
                    sDataAt = SystemClock.elapsedRealtime();
                    JoanTrace.note("IMS data_call " + sData);
                }
            }
        }
    }

    static String protocol(int p) {
        switch (p) {
            case ApnSetting.PROTOCOL_IP: return "IP";
            case ApnSetting.PROTOCOL_IPV6: return "IPV6";
            case ApnSetting.PROTOCOL_IPV4V6: return "IPV4V6";
            case ApnSetting.PROTOCOL_PPP: return "PPP";
            case ApnSetting.PROTOCOL_NON_IP: return "NON_IP";
            case ApnSetting.PROTOCOL_UNSTRUCTURED: return "UNSTRUCTURED";
            default: return "UNKNOWN";
        }
    }
    static String apnClass(String name) { return "ims".equalsIgnoreCase(name) ? "ims" : "other"; }

    static String linkSummary(LinkProperties lp) {
        if (lp == null) return "unavailable";
        List<InetAddress> all = new ArrayList<>();
        for (android.net.LinkAddress la : lp.getLinkAddresses()) all.add(la.getAddress());
        List<InetAddress> usable = JoanImsDiscovery.locals(lp);
        int r4 = 0, r6 = 0, d4 = 0, d6 = 0;
        for (RouteInfo r : lp.getRoutes()) {
            boolean v6 = r.getDestination().getAddress() instanceof Inet6Address;
            if (v6) { r6++; if (r.isDefaultRoute()) d6++; }
            else { r4++; if (r.isDefaultRoute()) d4++; }
        }
        JoanImsDiscovery.Pcscfs p = JoanImsDiscovery.read(lp, null);
        return "addr4=" + JoanImsDiscovery.count(all, false)
                + " addr6=" + JoanImsDiscovery.count(all, true)
                + " usable4=" + JoanImsDiscovery.count(usable, false)
                + " usable6=" + JoanImsDiscovery.count(usable, true)
                + " dns4=" + JoanImsDiscovery.count(lp.getDnsServers(), false)
                + " dns6=" + JoanImsDiscovery.count(lp.getDnsServers(), true)
                + " route4=" + r4 + " route6=" + r6 + " default4=" + d4 + " default6=" + d6
                + " mtu=" + lp.getMtu() + " " + p.summary();
    }

    static void network(int sub, String plmn, LinkProperties lp, JoanImsDiscovery.Pcscfs p) {
        String safePlmn = plmn != null && plmn.matches("[0-9]{5,6}") ? plmn : "unknown";
        String value = "sub=" + sub + " plmn=" + safePlmn + " " + linkSummary(lp)
                + " selected={" + p.summary() + "}";
        sNetworkAt = SystemClock.elapsedRealtime();
        if (!value.equals(sNetwork)) { sNetwork = value; JoanTrace.note("IMS network " + value); }
    }
    static void networkGone() { sNetwork = "unavailable"; sNetworkAt = 0; }

    /**
     * Compact, identity-free snapshot for a REGISTER cycle. Change-only
     * {@link #network} lines can be rotated out of joan-trace.log; this
     * always reprints the cached values so a later excerpt still has MTU
     * and family counts.
     */
    static String attemptContextLine() {
        return "IMS attempt listener=" + sListenerState
                + " network={" + sNetwork + "} data={" + sData + "}";
    }

    static void noteAttemptContext() {
        JoanTrace.note(attemptContextLine());
    }
    static String listener() { return sListenerState; }
    static String data() { return sData; }
    static String network() { return sNetwork; }
    static String ages() {
        long now = SystemClock.elapsedRealtime();
        return "network_ms=" + (sNetworkAt == 0 ? -1 : Math.max(0, now - sNetworkAt))
                + " data_call_ms=" + (sDataAt == 0 ? -1 : Math.max(0, now - sDataAt));
    }
}
