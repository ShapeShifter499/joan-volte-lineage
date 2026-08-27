package org.joan.ims;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.lang.reflect.Method;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Registration driver: watches the IMS network, pulls identity from the
 * ISIM, and runs the two-stage REGISTER with the native UA over ctl.
 * Started by JoanMmTelFeature when the framework binds (or manually).
 */
final class JoanDriver {
    private static final String TAG = "JoanIms";
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final long RETRY_MS = 60_000L;

    static void start(Context ctx) {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> loop(ctx.getApplicationContext()),
                "joan-ims-cycle");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    static boolean isRunning() {
        return STARTED.get();
    }

    private static void loop(Context app) {
        while (true) {
            try {
                Cycle c = discover(app);
                if (c == null) {
                    Log.i(TAG, "no IMS net/identity yet; waiting");
                    Thread.sleep(RETRY_MS);
                    continue;
                }
                if (JoanAka.registerCycle(app, c.impi, c.impu,
                        c.domain, c.imei, c.localIp, c.localPort,
                        c.pcscf, c.pcscfPort)) {
                    JoanRegistration.setRegistered(true, c.pcscf);
                    Log.i(TAG, "registered; re-register in 30m");
                    // Re-REGISTER well before default expiry drift.
                    Thread.sleep(30 * 60_000L);
                } else {
                    JoanRegistration.setRegistered(false, null);
                    Thread.sleep(RETRY_MS);
                }
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                Log.e(TAG, "loop error " + t.getClass().getSimpleName());
                try {
                    Thread.sleep(RETRY_MS);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private static final class Cycle {
        String impi, impu, domain, imei;
        String localIp;
        int localPort = 15060;
        String pcscf;
        int pcscfPort = 5060;
    }

    @SuppressWarnings("unchecked")
    private static Cycle discover(Context app) throws Exception {
        TelephonyManager tm0 = app.getSystemService(TelephonyManager.class);
        if (tm0 == null) {
            return null;
        }
        int sub = SubscriptionManager.getDefaultDataSubscriptionId();
        if (sub < 0) {
            sub = SubscriptionManager.getDefaultSubscriptionId();
        }
        TelephonyManager tm = (sub >= 0)
                ? tm0.createForSubscriptionId(sub) : tm0;

        String domain = hiddenString(tm, "getIsimDomain");
        String impi = hiddenString(tm, "getIsimImpi");
        String impu = firstIsimImpu(tm);
        String imei = safeImei(tm);
        if (impi == null || !impi.contains("@")) {
            Log.w(TAG, "no ISIM IMPI on this device/SIM yet");
            return null;
        }
        if (impu == null || impu.isEmpty()) {
            impu = impi;
        }

        ConnectivityManager cm = app.getSystemService(
                ConnectivityManager.class);
        if (cm == null) {
            return null;
        }
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
                    isIms = s.toLowerCase().contains("ims");
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
            Inet6Address local = pickLocalV6(lp);
            InetAddress pcscf = pickPcscf(lp);
            if (local == null || pcscf == null) {
                continue;
            }
            Cycle c = new Cycle();
            c.localIp = stripScope(local.getHostAddress());
            c.pcscf = stripScope(pcscf.getHostAddress());
            c.impi = impi;
            c.impu = impu;
            c.domain = (domain != null && !domain.isEmpty())
                    ? domain : realmFromImpi(impi);
            c.imei = imei;
            return c;
        }
        return null;
    }

    /** impi nearly always lives under the home realm; derive as fallback. */
    private static String realmFromImpi(String impi) {
        int at = impi.indexOf('@');
        return at > 0 ? impi.substring(at + 1) : "msg.pc.t-mobile.com";
    }

    private static String safeImei(TelephonyManager tm) {
        try {
            Method m = TelephonyManager.class.getMethod("getImei");
            Object v = m.invoke(tm);
            return v == null ? "" : String.valueOf(v);
        } catch (Exception e) {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private static String firstIsimImpu(TelephonyManager tm) {
        try {
            Method m = TelephonyManager.class.getMethod("getIsimImpu");
            Object v = m.invoke(tm);
            if (v instanceof String[]) {
                String[] a = (String[]) v;
                return a.length > 0 ? a[0] : null;
            }
            return v == null ? null : String.valueOf(v);
        } catch (Exception e) {
            return null;
        }
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

    private static Inet6Address pickLocalV6(LinkProperties lp) {
        for (android.net.LinkAddress la : lp.getLinkAddresses()) {
            InetAddress a = la.getAddress();
            if (a instanceof Inet6Address && !a.isLinkLocalAddress()
                    && !a.isLoopbackAddress()) {
                return (Inet6Address) a;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static InetAddress pickPcscf(LinkProperties lp) {
        try {
            Method m = lp.getClass().getMethod("getPcscfServers");
            List<?> list = (List<?>) m.invoke(lp);
            if (list == null || list.isEmpty()) {
                return null;
            }
            for (Object o : list) {
                if (o instanceof Inet6Address) {
                    return (InetAddress) o;
                }
            }
            return (InetAddress) list.get(0);
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
