package org.joan.ims;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.IpSecAlgorithm;
import android.net.IpSecManager;
import android.net.IpSecTransform;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.io.FileDescriptor;
import java.lang.reflect.Method;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.List;

/**
 * Capability spike: can a privileged app establish the IPsec security
 * association that 3GPP sec-agree needs, using only the public framework
 * APIs, with no native helper and no SELinux policy of its own?
 *
 * The whole native daemon rests on the claim that it cannot -- that app
 * domains are blocked from programming IPsec by a platform neverallow. That
 * is true of raw xfrm netlink. It is not obviously true of
 * IpSecManager/IpSecTransform, which are public APIs.
 *
 * The awkward part of sec-agree is that the UE does not get to choose the
 * SPIs it sends to: the P-CSCF names its own spi-c and spi-s in
 * Security-Server, and the UE must use exactly those on the outbound SAs.
 * So the question is not merely "can this app do IPsec" but "can it
 * allocate a *named* SPI, for a *remote* destination". That is what step 2
 * below tests, and it is the step that decides whether the daemon can go.
 *
 * This deliberately does not perform a registration. It exercises the
 * kernel-facing calls only, so a failure is unambiguous: an API or
 * permission problem, not a carrier problem. Nothing here logs identity or
 * key material -- the keys are constants, not derived from a SIM.
 */
final class JoanIpsecSpike {

    private JoanIpsecSpike() {}

    /* Sec-agree uses HMAC-SHA-1-96 and AES-CBC. Fixed non-secret test
     * vectors: this spike proves the mechanism, never a real association. */
    private static final byte[] AUTH_KEY = new byte[20];
    private static final byte[] CRYPT_KEY = new byte[16];

    static String run(Context ctx) {
        StringBuilder sb = new StringBuilder();
        IpSecManager ipsec;
        try {
            ipsec = ctx.getSystemService(IpSecManager.class);
            if (ipsec == null) {
                return "FAIL: no IpSecManager service";
            }
            sb.append("ipsecmanager=ok ");
        } catch (Throwable t) {
            return "FAIL: IpSecManager lookup: " + brief(t);
        }

        InetAddress local = null;
        InetAddress peer = null;
        try {
            InetAddress[] pair = imsAddresses(ctx);
            local = pair[0];
            peer = pair[1];
        } catch (Throwable t) {
            sb.append("imslookup=").append(brief(t)).append(' ');
        }
        boolean real = local != null && peer != null;
        if (!real) {
            /* Still worth running: the calls below are local kernel
             * operations and do not need a reachable peer. */
            try {
                local = anyGlobalV6();
                peer = InetAddress.getByName("2001:db8::1");
            } catch (Throwable t) {
                return sb + "FAIL: no usable addresses: " + brief(t);
            }
        }
        sb.append("addrs=").append(real ? "ims" : "synthetic").append(' ');
        if (local == null) {
            return sb + "FAIL: no local address";
        }

        /* 1. Our own inbound SPI. The UE picks this one, so a plain
         *    allocation would do -- but sec-agree advertises it in
         *    Security-Client before the SA exists, so we must be able to
         *    request a chosen value here too. */
        int wantIn = 0x11223344;
        IpSecManager.SecurityParameterIndex spiIn = null;
        try {
            spiIn = ipsec.allocateSecurityParameterIndex(local, wantIn);
            sb.append("spi_in=").append(spiIn.getSpi() == wantIn
                    ? "exact" : "differs(" + spiIn.getSpi() + ")").append(' ');
        } catch (Throwable t) {
            sb.append("spi_in=FAIL(").append(brief(t)).append(") ");
        }

        /* 2. THE decisive one. The P-CSCF names the outbound SPIs, so we
         *    must be able to allocate a specific SPI toward a remote
         *    destination. If this is refused, sec-agree cannot be driven
         *    from the app and the native daemon has to stay. */
        int wantOut = 0x55667788;
        IpSecManager.SecurityParameterIndex spiOut = null;
        try {
            spiOut = ipsec.allocateSecurityParameterIndex(peer, wantOut);
            sb.append("spi_out_named=").append(spiOut.getSpi() == wantOut
                    ? "exact" : "differs(" + spiOut.getSpi() + ")").append(' ');
        } catch (Throwable t) {
            sb.append("spi_out_named=FAIL(").append(brief(t)).append(") ");
        }

        /* 3. The algorithms sec-agree negotiates. */
        IpSecTransform transform = null;
        try {
            IpSecAlgorithm auth = new IpSecAlgorithm(
                    IpSecAlgorithm.AUTH_HMAC_SHA1, AUTH_KEY, 96);
            IpSecAlgorithm crypt = new IpSecAlgorithm(
                    IpSecAlgorithm.CRYPT_AES_CBC, CRYPT_KEY);
            sb.append("algs=ok ");
            if (spiOut != null) {
                transform = new IpSecTransform.Builder(ctx)
                        .setAuthentication(auth)
                        .setEncryption(crypt)
                        .buildTransportModeTransform(local, spiOut);
                sb.append("transform=ok ");
            } else {
                sb.append("transform=skipped ");
            }
        } catch (Throwable t) {
            sb.append("transform=FAIL(").append(brief(t)).append(") ");
        }

        /* 4. Apply it to a real socket, which is where a policy denial
         *    would surface if there is one. */
        DatagramSocket sock = null;
        try {
            if (transform != null) {
                sock = new DatagramSocket();
                ipsec.applyTransportModeTransform(sock,
                        IpSecManager.DIRECTION_OUT, transform);
                sb.append("apply_out=ok ");
                ipsec.removeTransportModeTransforms(sock);
                sb.append("remove=ok");
            } else {
                sb.append("apply_out=skipped");
            }
        } catch (Throwable t) {
            sb.append("apply_out=FAIL(").append(brief(t)).append(")");
        } finally {
            closeQuietly(sock);
            closeQuietly(transform);
            closeQuietly(spiIn);
            closeQuietly(spiOut);
        }
        return sb.toString();
    }

    /** {local, pcscf} from the IMS network, or nulls. */
    @SuppressWarnings("unchecked")
    private static InetAddress[] imsAddresses(Context ctx) throws Exception {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        if (cm == null) {
            return new InetAddress[] { null, null };
        }
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc == null || !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_IMS)) {
                continue;
            }
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) {
                continue;
            }
            InetAddress local = null;
            for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                InetAddress a = la.getAddress();
                if (a instanceof Inet6Address && !a.isLinkLocalAddress()) {
                    local = a;
                    break;
                }
            }
            InetAddress pcscf = null;
            try {
                Method m = lp.getClass().getMethod("getPcscfServers");
                List<?> list = (List<?>) m.invoke(lp);
                if (list != null && !list.isEmpty()) {
                    pcscf = (InetAddress) list.get(0);
                }
            } catch (Exception ignored) {
                // reflection unavailable; synthetic peer will be used
            }
            if (local != null && pcscf != null) {
                return new InetAddress[] { local, pcscf };
            }
        }
        return new InetAddress[] { null, null };
    }

    private static InetAddress anyGlobalV6() throws Exception {
        for (java.net.NetworkInterface ni :
                java.util.Collections.list(
                        java.net.NetworkInterface.getNetworkInterfaces())) {
            for (InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                if (a instanceof Inet6Address && !a.isLinkLocalAddress()
                        && !a.isLoopbackAddress()) {
                    return a;
                }
            }
        }
        return InetAddress.getByName("::1");
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private static void closeQuietly(DatagramSocket s) {
        if (s != null) {
            s.close();
        }
    }

    private static String brief(Throwable t) {
        String m = t.getMessage();
        String n = t.getClass().getSimpleName();
        if (m == null || m.isEmpty()) {
            return n;
        }
        m = m.replace('\n', ' ').replace('\r', ' ');
        if (m.length() > 60) {
            m = m.substring(0, 60);
        }
        return n + ":" + m;
    }
}
