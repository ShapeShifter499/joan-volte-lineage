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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * REGISTER 200 from the app over {@code IpSecTransform}.
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
 * Transport is carrier-scoped: T-Mobile's proven REG2 stays UDP. MCC 460
 * cores (China Mobile live traces) take the protected REGISTER over TCP
 * to P-CSCF port-s, which TS 33.203 requires the UE to open if no TCP
 * connection exists yet. Connect/RST failure falls back to UDP.
 *
 * Never logs IMPI, nonce, RES, CK, IK, or SIP request lines.
 */
final class JoanAppRegister {
    private static final int REG1_TIMEOUT_MS = 8000;
    private static final int REG2_TIMEOUT_MS = 8000;

    private JoanAppRegister() {}

    static String run(Context ctx) {
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
        StringBuilder sb = new StringBuilder();
        sb.append("addrs=ims pani=").append(pani)
                .append(" pcscf_n=").append(n.pcscfs.size()).append(' ');

        /* One P-CSCF at a time: REG1 then protected REG2. Stock
         * CMCCAoSRegistration.RecoverPCSCF / ProcessFlowRecoveryWithNewPCSCF
         * advances after a silent protected REGISTER instead of dying on
         * the first advertised node (CMCC dumps had pcscf_n=2). T-Mobile
         * succeeds on the first, so this loop is a no-op there. */
        int perTry = n.pcscfs.size() > 1
                ? REG1_TIMEOUT_MS / 2 : REG1_TIMEOUT_MS;
        int tried = 0;
        String last = null;
        for (InetAddress cand : n.pcscfs) {
            tried++;
            String one = tryPcscf(ctx, n, id, pani, cand, perTry);
            if (one == null) {
                continue;
            }
            last = one;
            if (one.indexOf(" OK") >= 0) {
                sb.append("pcscf_tried=").append(tried).append(' ');
                return sb.append(one).toString();
            }
            if (one.indexOf("FAIL: aka") >= 0
                    || one.indexOf("FAIL: no IpSecManager") >= 0
                    || one.indexOf("FAIL: aka parse") >= 0
                    || one.indexOf("FAIL: aka lengths") >= 0) {
                sb.append("pcscf_tried=").append(tried).append(' ');
                return sb.append(one).toString();
            }
        }
        sb.append("pcscf_tried=").append(tried).append(' ');
        if (last == null) {
            return sb + "FAIL: reg1 no answer from any of " + n.pcscfs.size();
        }
        return sb.append(last).toString();
    }

    /**
     * REG1 + AKA + IPsec + REG2 against one advertised P-CSCF.
     * @return null if REG1 was silent (try the next); otherwise a
     *         diagnosis string, with {@code OK} on REGISTER 200.
     */
    private static String tryPcscf(Context ctx, Net n, Id id, String pani,
                                   InetAddress pcscf, int reg1TimeoutMs) {
        SecureRandom rng = new SecureRandom();
        JoanSipBuilder.Params mine = JoanSipBuilder.Params.random(rng);
        JoanSipBuilder.Txn txn = new JoanSipBuilder.Txn(mine, rng);
        JoanSipBuilder.Id sipId = new JoanSipBuilder.Id(
                id.impi, id.impu, id.realm, n.localHost,
                JoanSipBuilder.REG1_PORT, JoanSipBuilder.REG1_PORT, id.imei);
        StringBuilder sb = new StringBuilder();
        byte[] reg1Bytes = JoanSipBuilder
                .buildRegister(sipId, txn, 1, null, null, null, null, pani)
                .getBytes(StandardCharsets.US_ASCII);
        String r1 = null;
        DatagramSocket s1 = null;
        try {
            s1 = boundUdp(n.network, n.local, JoanSipBuilder.REG1_PORT);
            r1 = sendRecv(s1, null, pcscf, JoanSipBuilder.PCSCF_SIP_PORT,
                    reg1Bytes, reg1TimeoutMs);
        } catch (Exception e) {
            r1 = null;
        } finally {
            closeQuietly(s1);
        }
        if (r1 == null) {
            return null;
        }
        JoanSipBuilder.Reply p1 = JoanSipBuilder.parseReply(r1);
        if (p1 == null) {
            return "FAIL: reg1 parse";
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
                .append(" alg=").append(pcscfSec.alg)
                .append(" offered=")
                .append(JoanSecAgree.offerSummary(p1.secServer, pcscfSec))
                .append(' ');

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
        sb.append("reslen=").append(res == null ? -1 : res.length)
                .append(" cklen=").append(ck == null ? -1 : ck.length)
                .append(" iklen=").append(ik == null ? -1 : ik.length)
                .append(' ');
        if (res == null || ck == null || ik == null
                || res.length < 4 || res.length > 16) {
            return sb + "FAIL: aka lengths";
        }
        if (ck.length != 16 || ik.length != 16) {
            return sb + "FAIL: aka parse shape (CK/IK must be 16 octets)";
        }

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
        Socket tcpKeep = null;
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
            boolean tcpReg2 = JoanSipBuilder.preferProtectedTcp(id.realm)
                    || JoanSipBuilder.preferProtectedTcp(realm);
            String r2 = null;
            sTcpAttempted = false;
            sTcpConnected = false;
            sTcpKeep = null;
            if (tcpReg2) {
                String reg2Tcp = JoanSipBuilder.buildRegister(sip2, txn, 2,
                        ch, res, ck, ik, pani, true);
                byte[] tcpBytes = reg2Tcp.getBytes(StandardCharsets.US_ASCII);
                sb.append("reg2send=").append(mine.portC).append("->")
                        .append(pcscfSec.portS).append(" tpt=tcp ");
                r2 = sendRecvTcp(n.network, n.local, mine.portC,
                        pcscf, pcscfSec.portS, tcpBytes, REG2_TIMEOUT_MS,
                        ipsec, inC, outC);
                if (r2 == null) {
                    if (sTcpAttempted && !sTcpConnected) {
                        sb.append("tcp_fail=connect ");
                    } else if (sTcpConnected) {
                        sb.append("tcp_fail=timeout ");
                    } else {
                        sb.append("tcp_fail=setup ");
                    }
                }
            }
            if (r2 == null && !sTcpConnected) {
                String reg2 = JoanSipBuilder.buildRegister(sip2, txn, 2, ch,
                        res, ck, ik, pani, false);
                byte[] reg2Bytes = reg2.getBytes(StandardCharsets.US_ASCII);
                if (!tcpReg2) {
                    sb.append("reg2send=").append(mine.portC).append("->")
                            .append(pcscfSec.portS).append(" tpt=udp ");
                } else {
                    sb.append("tpt=udp ");
                }
                r2 = sendRecv(sockC, sockS, pcscf, pcscfSec.portS,
                        reg2Bytes, REG2_TIMEOUT_MS);
            }
            sb.append("reg2retx=").append(sRetx).append(' ');
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
                tcpKeep = sTcpKeep;
                sTcpKeep = null;
                JoanSipUa.adopt(ctx, n.network, n.local, pcscf, pcscfSec.portS,
                        sip2, pani, p1.secServer, r2,
                        sockC, sockS, tcpKeep, ipsec,
                        new AutoCloseable[] { outC, inC, outS, inS,
                                spiUeC, spiUeS, spiPeerC, spiPeerS });
                sockC = null;
                sockS = null;
                tcpKeep = null;
                outC = inC = outS = inS = null;
                spiUeC = spiUeS = spiPeerC = spiPeerS = null;
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
            closeQuietly(tcpKeep);
            closeQuietly(outC);
            closeQuietly(inC);
            closeQuietly(outS);
            closeQuietly(inS);
            closeQuietly(spiUeC);
            closeQuietly(spiUeS);
            closeQuietly(spiPeerC);
            closeQuietly(spiPeerS);
            if (sTcpKeep != null) {
                try {
                    sTcpKeep.close();
                } catch (Exception ignored) {
                    // ignore
                }
                sTcpKeep = null;
            }
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
            /* USIM-only card: derive per TS 23.003 13.3. impu stays null,
             * so the public identity must come from P-Associated-URI in
             * the 200 OK -- a derived IMPI contains the IMSI and must
             * never become a display identity. */
            String mccMnc;
            try {
                mccMnc = tm.getSimOperator();
            } catch (Throwable t) {
                mccMnc = null;
            }
            impi = JoanSipBuilder.derivedImpi(
                    hidden(tm, "getSubscriberId"), mccMnc);
            if (domain == null || domain.isEmpty()) {
                domain = JoanSipBuilder.derivedDomain(mccMnc);
            }
            JoanTrace.note("identity derived from IMSI (no ISIM)");
        }
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

    /** Retransmissions used by the last sendRecv, for the summary line. */
    private static int sRetx;
    /** Last protected-TCP attempt: attempted connect, and whether it completed. */
    private static boolean sTcpAttempted;
    private static boolean sTcpConnected;
    /** Successful REG2 TCP client, handed to the UA. Closed on failure. */
    private static Socket sTcpKeep;

    /**
     * Send and wait, retransmitting on RFC 3261 timers.
     *
     * SIP over UDP is not reliable and the transaction layer is supposed to
     * retransmit: T1 = 500 ms, doubling, capped at T2 = 4 s. This sent once
     * and waited, so a single lost datagram was indistinguishable from a
     * core that never answers -- and the protected REGISTER goes out
     * immediately after the security associations are installed, which is
     * exactly when a packet is most likely to be dropped while the peer
     * finishes plumbing its inbound SA.
     */
    private static String sendRecv(DatagramSocket primary, DatagramSocket alt,
                                   InetAddress dest, int dport, byte[] pkt,
                                   int timeoutMs) throws Exception {
        DatagramPacket out = new DatagramPacket(pkt, pkt.length, dest, dport);
        primary.send(out);
        sRetx = 0;
        long start = System.currentTimeMillis();
        long deadline = start + timeoutMs;
        long interval = 500;
        long nextTx = start + interval;
        byte[] buf = new byte[4096];
        while (System.currentTimeMillis() < deadline) {
            long now = System.currentTimeMillis();
            if (now >= nextTx) {
                try {
                    primary.send(out);
                    sRetx++;
                } catch (Exception e) {
                    /* Keep listening: the first send may still be in
                     * flight and the answer can still arrive. */
                }
                interval = Math.min(interval * 2, 4000);
                nextTx = now + interval;
            }
            int slice = (int) Math.min(200,
                    Math.min(deadline, nextTx) - System.currentTimeMillis());
            if (slice <= 0) {
                continue;
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

    /**
     * Protected REGISTER over TCP: bind UE port-c, apply the same
     * client-port transforms as the UDP socket, connect to P-CSCF
     * port-s, write once, read until a complete SIP message or timeout.
     *
     * TCP has no SIP retransmission (RFC 3261 §18.2.2 / Timer E is UDP
     * only). A failed connect is reported via {@link #sTcpConnected} so
     * the caller can fall back to UDP; a completed handshake that never
     * answers is a transaction timeout.
     */
    private static String sendRecvTcp(Network network, InetAddress local,
                                      int localPort, InetAddress dest,
                                      int dport, byte[] pkt, int timeoutMs,
                                      IpSecManager ipsec,
                                      IpSecTransform inXf,
                                      IpSecTransform outXf) {
        sTcpAttempted = true;
        sTcpConnected = false;
        sRetx = 0;
        Socket sock = new Socket();
        try {
            sock.setReuseAddress(true);
            sock.setSoTimeout(Math.max(1, timeoutMs));
            if (network != null) {
                network.bindSocket(sock);
            }
            sock.bind(new InetSocketAddress(local, localPort));
            if (ipsec != null) {
                if (outXf != null) {
                    ipsec.applyTransportModeTransform(sock,
                            IpSecManager.DIRECTION_OUT, outXf);
                }
                if (inXf != null) {
                    try {
                        ipsec.applyTransportModeTransform(sock,
                                IpSecManager.DIRECTION_IN, inXf);
                    } catch (Exception e) {
                        /* Same as the UDP path: inbound transform may
                         * fail on this kernel; keep the outbound SA. */
                    }
                }
            }
            sock.connect(new InetSocketAddress(dest, dport),
                    Math.min(4000, Math.max(1, timeoutMs)));
            sTcpConnected = true;
            OutputStream os = sock.getOutputStream();
            os.write(pkt);
            os.flush();
            InputStream is = sock.getInputStream();
            StringBuilder acc = new StringBuilder();
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                int n;
                try {
                    n = is.read(buf);
                } catch (SocketTimeoutException e) {
                    break;
                }
                if (n <= 0) {
                    break;
                }
                acc.append(new String(buf, 0, n, StandardCharsets.US_ASCII));
                String got = JoanSipBuilder.extractOne(acc);
                if (got != null) {
                    /* Keep this client: stock libims reuses SIPoTCP
                     * ("TCP client is re-used") for INVITE after REG2. */
                    sock.setSoTimeout(0);
                    sTcpKeep = sock;
                    sock = null;
                    return got;
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (sock != null) {
                if (ipsec != null) {
                    try {
                        ipsec.removeTransportModeTransforms(sock);
                    } catch (Exception ignored) {
                        // ignore
                    }
                }
                try {
                    sock.close();
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
    }

    static String tryRecv(DatagramSocket s, byte[] buf, int timeoutMs) {
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
