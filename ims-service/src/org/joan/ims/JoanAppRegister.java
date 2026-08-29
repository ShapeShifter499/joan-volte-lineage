package org.joan.ims;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.IpSecAlgorithm;
import android.net.IpSecManager;
import android.net.IpSecTransform;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * REGISTER 200 from the app over {@code IpSecTransform}, with the native
 * daemon stopped. This is the gate for deleting the unauthenticated
 * loopback listener.
 *
 * Crypto is not T-Mobile-shaped: Security-Client offers the 3GPP set
 * (hmac-sha-1-96 / hmac-md5-96 × aes-cbc / null); Security-Server picks;
 * unknown names fail instead of silently using SHA-1/AES.
 *
 * SA layout follows the working C daemon (four SAs, REG2 from UE port-c
 * to P-CSCF port-s using the P-CSCF's spi-s). Algorithm wrapping follows
 * TS 33.203 Annex I as implemented by Kamailio ims_ipsec_pcscf and the
 * joan pmOS helper. IpSecManager application follows PhhIms' public
 * pattern (pad SHA-1 IK to 160 bits; omit setEncryption when ealg=null)
 * without importing that GPL tree.
 *
 * Never logs IMPI, nonce, RES, CK, IK, or SIP request lines.
 */
final class JoanAppRegister {
    private static final int REG1_TIMEOUT_MS = 8000;
    private static final int REG2_TIMEOUT_MS = 8000;

    private JoanAppRegister() {}

    static String run(Context ctx) {
        String daemon = JoanCtl.txn("STATUS");
        if (daemon != null && (daemon.startsWith("STATE")
                || daemon.startsWith("OK") || daemon.startsWith("ERR"))) {
            return "FAIL: daemon still up; stop joan-ims first";
        }

        Net n;
        try {
            n = findIms(ctx);
        } catch (Exception e) {
            return "FAIL: ims lookup " + brief(e);
        }
        if (n == null) {
            return "FAIL: no IMS network";
        }
        if (n.local == null || n.pcscfs.isEmpty()) {
            return "FAIL: no IMS addresses";
        }

        Id id;
        try {
            id = readIdentity(ctx);
        } catch (Exception e) {
            return "FAIL: identity " + brief(e);
        }
        if (id == null) {
            return "FAIL: no ISIM IMPI";
        }

        String pani = paniFor(ctx);
        SecureRandom rng = new SecureRandom();
        JoanSipBuilder.Params mine = JoanSipBuilder.Params.random(rng);
        JoanSipBuilder.Txn txn = new JoanSipBuilder.Txn(mine, rng);
        JoanSipBuilder.Id sipId = new JoanSipBuilder.Id(
                id.impi, id.impu, id.realm, n.localHost,
                JoanSipBuilder.REG1_PORT, JoanSipBuilder.REG1_PORT, id.imei);

        InetAddress pcscf = n.pcscfs.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append("addrs=ims pani=").append(pani)
                .append(" pcscf_n=").append(n.pcscfs.size()).append(' ');

        String reg1 = JoanSipBuilder.buildRegister(sipId, txn, 1, null, null,
                null, null, pani);
        byte[] reg1Bytes = reg1.getBytes(StandardCharsets.US_ASCII);
        DatagramSocket s1 = null;
        String r1;
        try {
            s1 = boundUdp(n.network, n.local, JoanSipBuilder.REG1_PORT);
            r1 = sendRecv(s1, null, pcscf, JoanSipBuilder.PCSCF_SIP_PORT,
                    reg1Bytes, REG1_TIMEOUT_MS);
        } catch (Exception e) {
            return sb + "FAIL: reg1 send " + brief(e);
        } finally {
            closeQuietly(s1);
        }
        if (r1 == null) {
            return sb + "FAIL: reg1 timeout";
        }
        JoanSipBuilder.Reply p1 = JoanSipBuilder.parseReply(r1);
        if (p1 == null) {
            return sb + "FAIL: reg1 parse";
        }
        sb.append("reg1=").append(p1.status).append(' ');
        if (p1.status != 401) {
            return sb + "FAIL: reg1 unexpected";
        }
        if (p1.wwwAuth == null || p1.secServer == null) {
            return sb + "FAIL: 401 missing challenge/sec-server";
        }

        String nonce = JoanSipBuilder.extractNonce(p1.wwwAuth);
        String algo = JoanSipBuilder.extractAlgorithm(p1.wwwAuth);
        String realm = JoanSipBuilder.extractRealm(p1.wwwAuth);
        String qop = JoanSipBuilder.extractQop(p1.wwwAuth);
        if (nonce == null || nonce.isEmpty()) {
            return sb + "FAIL: 401 no nonce";
        }
        sb.append("aka=").append(algo).append(' ');

        JoanSecAgree pcscfSec = JoanSecAgree.select(p1.secServer);
        if (pcscfSec == null) {
            return sb + "FAIL: no supported Security-Server mechanism";
        }
        sb.append("ealg=").append(pcscfSec.ealg)
                .append(" alg=").append(pcscfSec.alg).append(' ');

        String authHex;
        try {
            authHex = JoanAka.runIccAuth(ctx, nonce);
        } catch (Exception e) {
            return sb + "FAIL: aka " + brief(e);
        }
        if (authHex == null) {
            return sb + "FAIL: aka unavailable";
        }
        String[] parts = JoanAka.parseAuthResponse(authHex);
        if (parts == null) {
            return sb + "FAIL: aka parse";
        }
        byte[] res = JoanSipCrypto.hexBytes(parts[0]);
        byte[] ck = JoanSipCrypto.hexBytes(parts[1]);
        byte[] ik = JoanSipCrypto.hexBytes(parts[2]);
        if (res == null || ck == null || ik == null
                || (res.length != 8 && res.length != 16)
                || ck.length < 16 || ik.length < 16) {
            return sb + "FAIL: aka lengths";
        }
        sb.append("reslen=").append(res.length).append(' ');

        JoanSipCrypto.EspKeys keys;
        try {
            keys = JoanSipCrypto.espKeys(pcscfSec.alg, pcscfSec.ealg, ck, ik);
        } catch (IllegalArgumentException e) {
            return sb + "FAIL: " + e.getMessage();
        }

        JoanSipBuilder.Challenge ch = new JoanSipBuilder.Challenge(
                nonce, algo, p1.secServer, realm, qop);

        IpSecManager ipsec = ctx.getSystemService(IpSecManager.class);
        if (ipsec == null) {
            return sb + "FAIL: no IpSecManager";
        }

        IpSecManager.SecurityParameterIndex spiUeC = null;
        IpSecManager.SecurityParameterIndex spiUeS = null;
        IpSecManager.SecurityParameterIndex spiPeerC = null;
        IpSecManager.SecurityParameterIndex spiPeerS = null;
        IpSecTransform outC = null, inC = null, outS = null, inS = null;
        DatagramSocket sockC = null, sockS = null;
        try {
            spiUeC = ipsec.allocateSecurityParameterIndex(n.local, (int) mine.spiC);
            spiUeS = ipsec.allocateSecurityParameterIndex(n.local, (int) mine.spiS);
            spiPeerC = ipsec.allocateSecurityParameterIndex(pcscf, (int) pcscfSec.spiC);
            spiPeerS = ipsec.allocateSecurityParameterIndex(pcscf, (int) pcscfSec.spiS);
            sb.append("spi_in=")
                    .append(spiUeC.getSpi() == (int) mine.spiC ? "exact" : "diff")
                    .append(" spi_out_named=")
                    .append(spiPeerS.getSpi() == (int) pcscfSec.spiS ? "exact" : "diff")
                    .append(' ');

            IpSecTransform.Builder b = new IpSecTransform.Builder(ctx)
                    .setAuthentication(new IpSecAlgorithm(
                            keys.androidAuth.equals("hmac(sha1)")
                                    ? IpSecAlgorithm.AUTH_HMAC_SHA1
                                    : IpSecAlgorithm.AUTH_HMAC_MD5,
                            keys.authKey, keys.authTruncBits));
            if (keys.hasEncryption()) {
                b.setEncryption(new IpSecAlgorithm(
                        IpSecAlgorithm.CRYPT_AES_CBC, keys.encKey));
            }

            /* Four SAs, same matrix as native xfrm.c:
             *  OUT port-c -> pcscf port-s  spi=pcscf spi-s
             *  IN  pcscf port-s -> port-c  spi=ue spi-c
             *  OUT port-s -> pcscf port-c  spi=pcscf spi-c
             *  IN  pcscf port-c -> port-s  spi=ue spi-s
             */
            outC = b.buildTransportModeTransform(n.local, spiPeerS);
            inC = b.buildTransportModeTransform(pcscf, spiUeC);
            outS = b.buildTransportModeTransform(n.local, spiPeerC);
            inS = b.buildTransportModeTransform(pcscf, spiUeS);

            sockC = boundUdp(n.network, n.local, mine.portC);
            sockS = boundUdp(n.network, n.local, mine.portS);
            ipsec.applyTransportModeTransform(sockC,
                    IpSecManager.DIRECTION_OUT, outC);
            sb.append("apply_out=ok ");
            String inNote = "in=ok";
            try {
                ipsec.applyTransportModeTransform(sockC,
                        IpSecManager.DIRECTION_IN, inC);
                ipsec.applyTransportModeTransform(sockS,
                        IpSecManager.DIRECTION_IN, inS);
                ipsec.applyTransportModeTransform(sockS,
                        IpSecManager.DIRECTION_OUT, outS);
            } catch (Exception e) {
                /* C daemon omits inbound xfrm policy because it dropped
                 * decrypted replies. Socket-scoped IN may need the same. */
                inNote = "in=FAIL(" + brief(e) + ")";
            }
            sb.append(inNote).append(' ');

            try {
                Thread.sleep(300);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            JoanSipBuilder.Id sip2 = new JoanSipBuilder.Id(
                    id.impi, id.impu, id.realm, n.localHost,
                    mine.portC, mine.portS, id.imei);
            String reg2 = JoanSipBuilder.buildRegister(sip2, txn, 2, ch,
                    res, ck, ik, pani);
            byte[] reg2Bytes = reg2.getBytes(StandardCharsets.US_ASCII);
            sb.append("reg2send=").append(mine.portC).append("->")
                    .append(pcscfSec.portS).append(' ');
            String r2 = sendRecv(sockC, sockS, pcscf, pcscfSec.portS,
                    reg2Bytes, REG2_TIMEOUT_MS);
            if (r2 == null) {
                return sb + "FAIL: reg2 timeout";
            }
            JoanSipBuilder.Reply p2 = JoanSipBuilder.parseReply(r2);
            if (p2 == null) {
                return sb + "FAIL: reg2 parse";
            }
            sb.append("reg2=").append(p2.status);
            if (p2.status >= 200 && p2.status < 300) {
                sb.append(" OK");
            }
            return sb.toString();
        } catch (Exception e) {
            return sb + "FAIL: ipsec/reg2 " + brief(e);
        } finally {
            if (ipsec != null) {
                removeXf(ipsec, sockC);
                removeXf(ipsec, sockS);
            }
            closeQuietly(sockC);
            closeQuietly(sockS);
            closeQuietly(outC);
            closeQuietly(inC);
            closeQuietly(outS);
            closeQuietly(inS);
            closeQuietly(spiUeC);
            closeQuietly(spiUeS);
            closeQuietly(spiPeerC);
            closeQuietly(spiPeerS);
        }
    }

    private static final class Net {
        final Network network;
        final InetAddress local;
        final String localHost;
        final List<InetAddress> pcscfs = new ArrayList<>();

        Net(Network network, InetAddress local, String localHost) {
            this.network = network;
            this.local = local;
            this.localHost = localHost;
        }
    }

    private static final class Id {
        final String impi, impu, realm, imei;

        Id(String impi, String impu, String realm, String imei) {
            this.impi = impi;
            this.impu = impu;
            this.realm = realm;
            this.imei = imei;
        }
    }

    @SuppressWarnings("unchecked")
    private static Net findIms(Context ctx) throws Exception {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        if (cm == null) {
            return null;
        }
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(network);
            if (nc == null
                    || !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_IMS)) {
                continue;
            }
            LinkProperties lp = cm.getLinkProperties(network);
            if (lp == null) {
                continue;
            }
            InetAddress local = null;
            for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                InetAddress a = la.getAddress();
                if (a instanceof Inet6Address && !a.isLinkLocalAddress()
                        && !a.isLoopbackAddress()) {
                    local = a;
                    break;
                }
            }
            if (local == null) {
                for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                    InetAddress a = la.getAddress();
                    if (!a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                        local = a;
                        break;
                    }
                }
            }
            if (local == null) {
                continue;
            }
            String host = local.getHostAddress();
            if (host != null && host.contains("%")) {
                host = host.substring(0, host.indexOf('%'));
            }
            Net n = new Net(network, local, host);
            try {
                Method m = lp.getClass().getMethod("getPcscfServers");
                List<?> list = (List<?>) m.invoke(lp);
                if (list != null) {
                    for (Object o : list) {
                        if (o instanceof InetAddress) {
                            n.pcscfs.add((InetAddress) o);
                        }
                    }
                }
            } catch (Exception ignored) {
                // no P-CSCF API
            }
            if (!n.pcscfs.isEmpty()) {
                return n;
            }
        }
        return null;
    }

    private static Id readIdentity(Context ctx) {
        TelephonyManager tm0 = ctx.getSystemService(TelephonyManager.class);
        if (tm0 == null) {
            return null;
        }
        int sub = SubscriptionManager.getDefaultDataSubscriptionId();
        if (sub < 0) {
            sub = SubscriptionManager.getDefaultSubscriptionId();
        }
        TelephonyManager tm = sub >= 0 ? tm0.createForSubscriptionId(sub) : tm0;
        String impi = hidden(tm, "getIsimImpi");
        String impu = firstImpu(tm);
        String domain = hidden(tm, "getIsimDomain");
        String imei = hidden(tm, "getImei");
        if (impi == null || !impi.contains("@")) {
            return null;
        }
        if (impu == null || impu.isEmpty()) {
            impu = impi;
        }
        String realm = (domain != null && !domain.isEmpty())
                ? domain : impi.substring(impi.indexOf('@') + 1);
        return new Id(impi, impu, realm, imei == null ? "" : imei);
    }

    private static String paniFor(Context ctx) {
        try {
            TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
            if (tm == null) {
                return "3GPP-E-UTRAN-FDD";
            }
            int t = tm.getDataNetworkType();
            if (t == TelephonyManager.NETWORK_TYPE_NR) {
                return "3GPP-NR-FDD";
            }
            if (t == TelephonyManager.NETWORK_TYPE_IWLAN) {
                return "IEEE-802.11";
            }
        } catch (Exception ignored) {
            // default LTE token
        }
        return "3GPP-E-UTRAN-FDD";
    }

    private static DatagramSocket boundUdp(Network network, InetAddress local,
                                           int port) throws Exception {
        DatagramSocket s = new DatagramSocket(null);
        s.setReuseAddress(true);
        if (network != null) {
            network.bindSocket(s);
        }
        s.bind(new InetSocketAddress(local, port));
        return s;
    }

    private static String sendRecv(DatagramSocket primary, DatagramSocket alt,
                                   InetAddress dest, int dport, byte[] pkt,
                                   int timeoutMs) throws Exception {
        DatagramPacket out = new DatagramPacket(pkt, pkt.length, dest, dport);
        primary.send(out);
        long deadline = System.currentTimeMillis() + timeoutMs;
        byte[] buf = new byte[4096];
        while (System.currentTimeMillis() < deadline) {
            int slice = (int) Math.min(200,
                    deadline - System.currentTimeMillis());
            if (slice <= 0) {
                break;
            }
            String got = tryRecv(primary, buf, slice);
            if (got != null) {
                return got;
            }
            if (alt != null) {
                got = tryRecv(alt, buf, slice);
                if (got != null) {
                    return got;
                }
            }
        }
        return null;
    }

    private static String tryRecv(DatagramSocket s, byte[] buf, int timeoutMs) {
        try {
            s.setSoTimeout(Math.max(1, timeoutMs));
            DatagramPacket in = new DatagramPacket(buf, buf.length);
            s.receive(in);
            return new String(buf, 0, in.getLength(), StandardCharsets.US_ASCII);
        } catch (SocketTimeoutException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String hidden(TelephonyManager tm, String name) {
        try {
            Method m = TelephonyManager.class.getMethod(name);
            Object v = m.invoke(tm);
            return v == null ? null : String.valueOf(v);
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstImpu(TelephonyManager tm) {
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

    private static void removeXf(IpSecManager ipsec, DatagramSocket s) {
        if (s == null) {
            return;
        }
        try {
            ipsec.removeTransportModeTransforms(s);
        } catch (Exception ignored) {
            // ignore
        }
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
