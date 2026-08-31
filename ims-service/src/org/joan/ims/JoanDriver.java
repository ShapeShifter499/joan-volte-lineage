package org.joan.ims;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.lang.reflect.Method;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Registration driver: watches the IMS network, checks that the radio,
 * SIM and IMS PDN are all ready, and then runs the two-stage REGISTER in
 * JoanAppRegister, refreshing it before the registrar's grant lapses.
 *
 * Important battery/user-intent rule: if cellular/LTE is intentionally off
 * (airplane mode, no active SIM/subscription, preferred network mode excludes
 * LTE/NR), this driver enters quiet idle. It must never spin AKA, SIP, xfrm,
 * or framework retries just because the user turned the radio state off.
 */
final class JoanDriver {
    private static final String TAG = "JoanIms";
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static volatile Thread sThread;

    /** Normal wait while radio/framework are still bringing IMS up. */
    private static final long WAIT_MS = 60_000L;
    /** Quiet user-off state: don't keep poking radio/SIM every minute. */
    private static final long USER_OFF_IDLE_MS = 10 * 60_000L;
    /** Failed REGISTER backoff range. */
    private static final long REG_RETRY_MIN_MS = 60_000L;
    private static final long REG_RETRY_MAX_MS = 15 * 60_000L;

    private static String sLastState = "";
    /* The full REGISTER summary, surfaced by JoanStateProvider. The trace
     * file needs root and logcat gets lost; the content provider is the
     * artifact a user can actually produce, so the line that says why
     * registration failed belongs in it. */
    private static volatile String sLastRegister = "";
    private static final Object NET_LOCK = new Object();
    private static ConnectivityManager.NetworkCallback sImsCallback;
    private static boolean sImsRequested;

    static void start(Context ctx) {
        JoanTrace.init(ctx.getApplicationContext());
        if (!STARTED.compareAndSet(false, true)) {
            Thread old = sThread;
            if (old != null) {
                old.interrupt();
            }
            return;
        }
        JoanTrace.note("starting registration driver");
        Thread t = new Thread(() -> loop(ctx.getApplicationContext()),
                "joan-ims-cycle");
        t.setPriority(Thread.MIN_PRIORITY);
        sThread = t;
        t.start();
    }

    static boolean isRunning() {
        return STARTED.get();
    }

    static String lastState() {
        return sLastState;
    }

    static String lastRegister() {
        return sLastRegister;
    }

    static boolean imsRequested() {
        synchronized (NET_LOCK) {
            return sImsRequested;
        }
    }

    private static void loop(Context app) {
        long registerBackoff = REG_RETRY_MIN_MS;
        while (true) {
            try {
                Discovery d = discover(app);
                if (d.cycle == null) {
                    JoanRegistration.setRegistered(false, null);
                    if (d.quietIdle) {
                        releaseImsRequest(app);
                    }
                    logState((d.quietIdle ? "quiet-idle: " : "waiting: ")
                            + d.reason);
                    registerBackoff = REG_RETRY_MIN_MS;
                    Thread.sleep(d.sleepMs);
                    continue;
                }

                Cycle c = d.cycle;
                boolean refreshing = JoanSipUa.isRegistered();
                if (refreshing) {
                    long wait = JoanSipUa.msUntilRefresh();
                    if (wait > 0) {
                        logState("registered via app UA; refresh in "
                                + (wait / 60_000L) + "m");
                        Thread.sleep(wait);
                        continue;
                    }
                }
                logState(refreshing ? "refreshing REGISTER via app UA"
                        : "attempt REGISTER via app UA");
                String r = JoanSipUa.register(app);
                sLastRegister = (r == null) ? "null" : r;
                boolean ok = r != null && r.contains("reg2=200");
                JoanTrace.note("app register: "
                        + (r == null ? "null" : r));
                if (ok && JoanSipUa.isRegistered()) {
                    JoanRegistration.setRegistered(true, c.pcscf);
                    registerBackoff = REG_RETRY_MIN_MS;
                    /* The next pass reads the granted lifetime and
                     * sleeps until the refresh is due. */
                    continue;
                }
                if (refreshing) {
                    /* A failed refresh leaves the old binding in place
                     * but unrenewed, and isRegistered() would send us
                     * straight back to sleep instead of retrying. */
                    JoanSipUa.release();
                }
                JoanRegistration.setRegistered(false, null);
                logState("app REGISTER failed; backoff "
                        + (registerBackoff / 1000) + "s");
                Thread.sleep(registerBackoff);
                registerBackoff = Math.min(REG_RETRY_MAX_MS,
                        registerBackoff * 2);
                continue;
            } catch (InterruptedException ie) {
                // A receiver/provider/service poke woke us after a user/radio
                // state change. Re-run discovery immediately instead of
                // staying in a stale quiet-idle sleep.
                continue;
            } catch (Throwable t) {
                JoanRegistration.setRegistered(false, null);
                Log.e(TAG, "loop error " + t.getClass().getSimpleName());
                try {
                    Thread.sleep(WAIT_MS);
                } catch (InterruptedException ie) {
                    continue;
                }
            }
        }
    }

    private static void logState(String state) {
        if (!state.equals(sLastState)) {
            JoanTrace.note(state);
            sLastState = state;
        }
    }

    /**
     * What discovery proved is ready. JoanAppRegister reads the IMS network
     * and the ISIM itself; the only field anyone still consumes is the
     * P-CSCF list, which JoanRegistration reports as registration state.
     */
    private static final class Cycle {
        String pcscf;
    }

    private static final class Discovery {
        final Cycle cycle;
        final String reason;
        final boolean quietIdle;
        final long sleepMs;

        private Discovery(Cycle c, String r, boolean q, long ms) {
            cycle = c;
            reason = r;
            quietIdle = q;
            sleepMs = ms;
        }

        static Discovery ready(Cycle c) {
            return new Discovery(c, "ready", false, 0);
        }

        static Discovery waitFor(String reason) {
            return new Discovery(null, reason, false, WAIT_MS);
        }

        static Discovery quietIdle(String reason) {
            return new Discovery(null, reason, true, USER_OFF_IDLE_MS);
        }
    }

    @SuppressWarnings("unchecked")
    private static Discovery discover(Context app) throws Exception {
        TelephonyManager tm0 = app.getSystemService(TelephonyManager.class);
        if (tm0 == null) {
            return Discovery.waitFor("telephony service unavailable");
        }

        if (airplaneModeOn(app)) {
            return Discovery.quietIdle("airplane mode is on");
        }

        int sub = defaultOrActiveSubscriptionId(app);
        if (sub < 0) {
            return Discovery.quietIdle("no active default subscription");
        }

        TelephonyManager tm = tm0.createForSubscriptionId(sub);
        int simState = safeSimState(tm);
        if (simState != TelephonyManager.SIM_STATE_READY) {
            return Discovery.quietIdle("SIM not ready (state=" + simState
                    + ")");
        }

        Integer preferredMode = preferredNetworkMode(app, sub);
        if (preferredMode != null && !networkModeAllowsLte(preferredMode)) {
            return Discovery.quietIdle("preferred network mode disables LTE ("
                    + preferredMode + ")");
        }

        ConnectivityManager cm = app.getSystemService(
                ConnectivityManager.class);
        if (cm == null) {
            return Discovery.waitFor("connectivity service unavailable");
        }

        // If the modem is not camped on LTE/NR and no IMS network is exposed,
        // do not fetch ISIM identity or run AKA yet. This covers the user's
        // "LTE off after reboot" case gracefully.
        /* The RAT check is applied further down, only when no usable IMS
         * network was found. It exists to stop this spinning AKA and xfrm
         * when the user has turned the radio down, not to veto a PDN that
         * is demonstrably up: getDataNetworkType() reports the default
         * data bearer, which on a marginal cell flaps to HSPA while the
         * IMS PDN is still there. Checking it first meant alternating
         * between "not LTE/NR (15)" and a working network, forever. */
        int dataNetwork = safeDataNetworkType(tm);

        Network ims = null;
        LinkProperties imsLp = null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities cap = cm.getNetworkCapabilities(n);
            if (cap == null
                    || !cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                continue;
            }
            boolean isIms = cap.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_IMS);
            if (!isIms) {
                try {
                    Object spec = cap.getNetworkSpecifier();
                    String s = spec == null ? "" : spec.toString();
                    isIms = s.toLowerCase(Locale.US).contains("ims");
                } catch (Exception ignore) {
                    // no specifier access; capability check above stands
                }
            }
            if (!isIms) {
                continue;
            }
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) {
                continue;
            }
            ims = n;
            imsLp = lp;
            break;
        }

        if (ims == null || imsLp == null) {
            if (dataNetwork != TelephonyManager.NETWORK_TYPE_UNKNOWN
                    && !isLteLike(dataNetwork)) {
                return Discovery.waitFor(
                        "no IMS network and data is not LTE/NR ("
                                + dataNetwork + ")");
            }
            ensureImsRequest(cm);
            return Discovery.waitFor("LTE on, IMS APN/network requested; waiting");
        }

        InetAddress local = pickLocal(imsLp);
        String pcscf = collectPcscfs(imsLp);
        if (local == null) {
            return Discovery.waitFor("IMS network has no usable local address");
        }
        if (pcscf == null) {
            return Discovery.waitFor("IMS network has no P-CSCF yet");
        }

        // Only after radio+IMS prerequisites are met do we ask for identity.
        String domain = hiddenString(tm, "getIsimDomain");
        String impi = hiddenString(tm, "getIsimImpi");
        String idSource = "isim";
        if (impi == null || !impi.contains("@")) {
            /* No ISIM on the card. TS 23.003 13.3 derives the private
             * identity and home domain from the IMSI, which is what a
             * handset does with a USIM-only card. Requiring an ISIM was
             * why such cards stopped here with "no ISIM IMPI". */
            String mccMnc = safeSimOperator(tm);
            impi = JoanSipBuilder.derivedImpi(
                    hiddenString(tm, "getSubscriberId"), mccMnc);
            if (domain == null || domain.isEmpty()) {
                domain = JoanSipBuilder.derivedDomain(mccMnc);
            }
            idSource = "derived";
        }
        if (impi == null || !impi.contains("@")) {
            return Discovery.waitFor(
                    "no ISIM IMPI, and none derivable from the IMSI");
        }
        logState("identity=" + idSource);

        String realm = (domain != null && !domain.isEmpty())
                ? domain : realmFromImpi(impi);
        if (realm == null || realm.isEmpty()) {
            return Discovery.waitFor("no IMS realm derivable from SIM");
        }
        Cycle c = new Cycle();
        c.pcscf = pcscf;
        return Discovery.ready(c);
    }

    private static boolean airplaneModeOn(Context app) {
        try {
            return Settings.Global.getInt(app.getContentResolver(),
                    Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** MCC+MNC as reported by the SIM, or null. Not an identity. */
    private static String safeSimOperator(TelephonyManager tm) {
        try {
            String s = tm.getSimOperator();
            return (s == null || s.isEmpty()) ? null : s;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int safeSimState(TelephonyManager tm) {
        try {
            return tm.getSimState();
        } catch (Throwable t) {
            return TelephonyManager.SIM_STATE_UNKNOWN;
        }
    }

    private static void ensureImsRequest(ConnectivityManager cm) {
        synchronized (NET_LOCK) {
            if (sImsRequested) {
                return;
            }
            try {
                NetworkRequest req = new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_IMS)
                        .build();
                sImsCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        JoanTrace.note("IMS network callback available");
                        Thread t = sThread;
                        if (t != null) {
                            t.interrupt();
                        }
                    }

                    @Override
                    public void onLost(Network network) {
                        JoanTrace.note("IMS network callback lost");
                        Thread t = sThread;
                        if (t != null) {
                            t.interrupt();
                        }
                    }
                };
                cm.requestNetwork(req, sImsCallback);
                sImsRequested = true;
                JoanTrace.note("requested IMS cellular network");
            } catch (Throwable t) {
                JoanTrace.note("IMS network request failed: "
                        + t.getClass().getSimpleName());
            }
        }
    }

    private static void releaseImsRequest(Context app) {
        synchronized (NET_LOCK) {
            if (!sImsRequested || sImsCallback == null) {
                return;
            }
            try {
                ConnectivityManager cm = app.getSystemService(
                        ConnectivityManager.class);
                if (cm != null) {
                    cm.unregisterNetworkCallback(sImsCallback);
                }
                JoanTrace.note("released IMS network request");
            } catch (Throwable t) {
                JoanTrace.note("IMS network release failed: "
                        + t.getClass().getSimpleName());
            } finally {
                sImsCallback = null;
                sImsRequested = false;
            }
        }
    }

    private static int safeDataNetworkType(TelephonyManager tm) {
        try {
            return tm.getDataNetworkType();
        } catch (Throwable t) {
            return TelephonyManager.NETWORK_TYPE_UNKNOWN;
        }
    }

    private static int defaultOrActiveSubscriptionId(Context app) {
        int sub = SubscriptionManager.getDefaultDataSubscriptionId();
        if (sub >= 0) {
            return sub;
        }
        sub = SubscriptionManager.getDefaultSubscriptionId();
        if (sub >= 0) {
            return sub;
        }
        try {
            SubscriptionManager sm = app.getSystemService(
                    SubscriptionManager.class);
            if (sm == null) {
                return -1;
            }
            List<SubscriptionInfo> list = sm.getActiveSubscriptionInfoList();
            if (list != null && !list.isEmpty()) {
                return list.get(0).getSubscriptionId();
            }
            SubscriptionInfo slot0 = sm.getActiveSubscriptionInfoForSimSlotIndex(0);
            if (slot0 != null) {
                return slot0.getSubscriptionId();
            }
            int[] ids = sm.getSubscriptionIds(0);
            if (ids != null && ids.length > 0) {
                return ids[0];
            }
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    static String subscriptionDebug(Context app) {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("defaultData=")
                    .append(SubscriptionManager.getDefaultDataSubscriptionId());
            sb.append(" default=")
                    .append(SubscriptionManager.getDefaultSubscriptionId());
            SubscriptionManager sm = app.getSystemService(
                    SubscriptionManager.class);
            if (sm == null) {
                sb.append(" sm=null");
            } else {
                try {
                    List<SubscriptionInfo> list = sm.getActiveSubscriptionInfoList();
                    sb.append(" activeList=").append(list == null ? -1 : list.size());
                } catch (Throwable t) {
                    sb.append(" activeList=").append(t.getClass().getSimpleName());
                }
                try {
                    SubscriptionInfo slot0 = sm.getActiveSubscriptionInfoForSimSlotIndex(0);
                    sb.append(" slot0Info=")
                            .append(slot0 == null ? -1 : slot0.getSubscriptionId());
                } catch (Throwable t) {
                    sb.append(" slot0Info=").append(t.getClass().getSimpleName());
                }
                try {
                    int[] ids = sm.getSubscriptionIds(0);
                    sb.append(" slot0Ids=");
                    if (ids == null) {
                        sb.append("null");
                    } else {
                        sb.append('[');
                        for (int i = 0; i < ids.length; i++) {
                            if (i != 0) sb.append(',');
                            sb.append(ids[i]);
                        }
                        sb.append(']');
                    }
                } catch (Throwable t) {
                    sb.append(" slot0Ids=").append(t.getClass().getSimpleName());
                }
            }
            int sub = defaultOrActiveSubscriptionId(app);
            sb.append(" chosen=").append(sub);
            TelephonyManager tm0 = app.getSystemService(TelephonyManager.class);
            if (tm0 != null) {
                TelephonyManager tm = sub >= 0 ? tm0.createForSubscriptionId(sub) : tm0;
                sb.append(" sim=").append(safeSimState(tm));
                sb.append(" dataNet=").append(safeDataNetworkType(tm));
                Integer mode = preferredNetworkMode(app, sub);
                sb.append(" pref=").append(mode == null ? "null" : mode);
            }
        } catch (Throwable t) {
            sb.append(" error=").append(t.getClass().getSimpleName());
        }
        return sb.toString();
    }

    /** Return the first available preferred-network setting for this sub. */
    private static Integer preferredNetworkMode(Context app, int sub) {
        String[] keys = new String[] {
                "preferred_network_mode" + sub,
                "preferred_network_mode0",
                "preferred_network_mode1",
                "preferred_network_mode"
        };
        for (String key : keys) {
            try {
                String v = Settings.Global.getString(app.getContentResolver(),
                        key);
                if (v == null || v.isEmpty() || "null".equals(v)) {
                    continue;
                }
                return Integer.valueOf(v.trim());
            } catch (Throwable ignore) {
                // try next key
            }
        }
        return null;
    }

    /** Android RIL preferred-network modes that include LTE. */
    private static boolean networkModeAllowsLte(int mode) {
        switch (mode) {
            case 8:   // LTE_CDMA_EVDO
            case 9:   // LTE_GSM_WCDMA
            case 10:  // LTE_CDMA_EVDO_GSM_WCDMA
            case 11:  // LTE_ONLY
            case 12:  // LTE_WCDMA
            case 15:  // LTE_TD_SCDMA
            case 17:  // LTE_TD_SCDMA_GSM
            case 19:  // LTE_TD_SCDMA_WCDMA
            case 20:  // LTE_TD_SCDMA_GSM_WCDMA
            case 22:  // LTE_TD_SCDMA_CDMA_EVDO_GSM_WCDMA
            case 24:  // NR_LTE
            case 25:
            case 26:
            case 27:
            case 28:
            case 29:
            case 30:
            case 31:
            case 32:
            case 33:
                return true;
            default:
                return false;
        }
    }

    private static boolean isLteLike(int networkType) {
        // LTE_CA is radio type 19 but is not exposed by every public SDK jar.
        return networkType == TelephonyManager.NETWORK_TYPE_LTE
                || networkType == 19
                || networkType == TelephonyManager.NETWORK_TYPE_NR;
    }

    /** Realm comes from the IMPI suffix; null when IMPI is malformed. */
    private static String realmFromImpi(String impi) {
        int at = impi.indexOf('@');
        return at > 0 ? impi.substring(at + 1) : null;
    }

    private static String hiddenString(TelephonyManager tm, String name) {
        try {
            Method m = TelephonyManager.class.getMethod(name);
            Object v = m.invoke(tm);
            return v == null ? null : String.valueOf(v);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A usable local address on the IMS PDN: IPv6 preferred, IPv4 accepted.
     *
     * This used to demand IPv6 and stop otherwise, which was stricter than
     * the code behind it -- JoanAppRegister.findIms() has always fallen
     * back to IPv4. A handset whose IMS PDN is v4-only was refused at the
     * readiness check for a capability the registration path had, and the
     * only sign was "IMS network has no usable IPv6 local address"
     * repeating forever.
     */
    private static InetAddress pickLocal(LinkProperties lp) {
        for (android.net.LinkAddress la : lp.getLinkAddresses()) {
            InetAddress a = la.getAddress();
            if (a instanceof Inet6Address && !a.isLinkLocalAddress()
                    && !a.isLoopbackAddress()) {
                return a;
            }
        }
        for (android.net.LinkAddress la : lp.getLinkAddresses()) {
            InetAddress a = la.getAddress();
            if (!a.isLinkLocalAddress() && !a.isLoopbackAddress()) {
                return a;
            }
        }
        return null;
    }

    /**
     * Every P-CSCF the IMS PDN advertises, comma-separated, IPv6 first.
     *
     * This used to return only the first address. When the carrier drained
     * that node mid-session the daemon retried it forever and registration
     * stayed down until the radio was bounced, even though the PDN was
     * advertising two other addresses that answered immediately. The daemon
     * fails over across whatever it is given, so give it all of them.
     */
    private static String collectPcscfs(LinkProperties lp) {
        try {
            Method m = lp.getClass().getMethod("getPcscfServers");
            List<?> list = (List<?>) m.invoke(lp);
            if (list == null || list.isEmpty()) {
                return null;
            }
            StringBuilder v6 = new StringBuilder();
            StringBuilder rest = new StringBuilder();
            for (Object o : list) {
                if (!(o instanceof InetAddress)) {
                    continue;
                }
                String a = stripScope(((InetAddress) o).getHostAddress());
                if (a == null || a.isEmpty() || a.indexOf(',') >= 0) {
                    continue;
                }
                StringBuilder into = (o instanceof Inet6Address) ? v6 : rest;
                if (into.length() > 0) {
                    into.append(',');
                }
                into.append(a);
            }
            if (v6.length() > 0 && rest.length() > 0) {
                v6.append(',').append(rest);
            } else if (v6.length() == 0) {
                v6 = rest;
            }
            return v6.length() > 0 ? v6.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripScope(String host) {
        if (host != null && host.contains("%")) {
            return host.substring(0, host.indexOf('%'));
        }
        return host;
    }
}
