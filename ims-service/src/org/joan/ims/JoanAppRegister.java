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
import java.net.Socket;
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
 * Transport follows stock GetTCPCriterionLength: per message by size.
 * A protected REG2 measures ~1.8 kB on the wire (bench reg2len=1823),
 * so CMCC (criterion 1300) still takes REG2 over TCP to P-CSCF port-s,
 * with UDP fallback on connect failure — matching stock, which ships a
 * CMCC-specific TCP IPsec helper. T-Mobile's per-reg criterion is 0
 * (disabled): its proven REG2 stays UDP at any size. Unknown PLMNs
 * stay UDP; no blanket per-carrier TCP rule survives.
 *
 * Reply adoption is fail-closed: a REGISTER answer is only accepted when
 * its Via branch and CSeq match the request (RFC 3261 §17.1.3) and its
 * Call-ID matches (§8.1.3.4); a protected-TCP attempt only falls back to
 * UDP on a connect-phase failure, and transform/setup failures abort
 * instead of downgrading the security of the exchange. An attempt is
 * superseded (epoch-bumped) by network/state changes so a stale result
 * is never adopted after the PDN moved on.
 *
 * Never logs IMPI, nonce, RES, CK, IK, or SIP request lines.
 */
final class JoanAppRegister {
    private static final int REG1_TIMEOUT_MS = 8000;
    private static final int REG2_TIMEOUT_MS = 8000;

    /* ---------- attempt lifecycle: epoch + single in-flight claim ------ */

    /** Bumped by {@link #stop()} to supersede an in-flight attempt. */
    private static volatile long sEpoch;
    private static final Object EPOCH_LOCK = new Object();
    private static boolean sInProgress;

    /** Supersede any in-flight attempt (network lost / state change). */
    static void stop() {
        synchronized (EPOCH_LOCK) {
            sEpoch++;
        }
    }

    /** Whether the attempt that captured {@code epoch} is superseded. */
    static boolean superseded(long epoch) {
        return epoch != sEpoch;
    }

    /** Whether a REGISTER cycle is currently running. */
    static boolean inProgress() {
        synchronized (EPOCH_LOCK) {
            return sInProgress;
        }
    }

    private static boolean tryBegin() {
        synchronized (EPOCH_LOCK) {
            if (sInProgress) {
                return false;
            }
            sInProgress = true;
            return true;
        }
    }

    private static void end() {
        synchronized (EPOCH_LOCK) {
            sInProgress = false;
        }
    }

    private JoanAppRegister() {}

    static String run(Context ctx) {
        if (!tryBegin()) {
            return "FAIL: registration already in progress";
        }
        try {
            return runAttempt(ctx, sEpoch);
        } finally {
            end();
        }
    }

    private static String runAttempt(Context ctx, long epoch) {
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
        if (superseded(epoch)) {
            return "FAIL: superseded by network/state change";
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
            if (superseded(epoch)) {
                sb.append("pcscf_tried=").append(tried).append(' ');
                return sb + "FAIL: superseded by network/state change";
            }
            String one = tryPcscf(ctx, n, id, pani, cand, perTry, epoch);
            // Keep each candidate's sanitized result, not only the last one.
            // No IPs, subscriber identity, raw SIP, or AKA key material.
            JoanTrace.note("app register pcscf_try=" + tried + " "
                    + (one == null ? "FAIL: reg1 no usable reply" : one));
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
                    || one.indexOf("FAIL: aka lengths") >= 0
                    || one.indexOf("FAIL: superseded") >= 0) {
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
                                   InetAddress pcscf, int reg1TimeoutMs,
                                   long epoch) {
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
        String reg1Str = new String(reg1Bytes, StandardCharsets.US_ASCII);
        String r1 = null;
        DatagramSocket s1 = null;
        try {
            s1 = boundUdp(n.network, n.local, JoanSipBuilder.REG1_PORT);
            JoanRegTransport.UdpResult r1r = JoanRegTransport.sendRecvUdp(
                    s1, null, pcscf, JoanSipBuilder.PCSCF_SIP_PORT,
                    reg1Bytes, reg1TimeoutMs, reg1Str);
            r1 = r1r == null ? null : r1r.reply;
        } catch (Exception e) {
            r1 = null;
        } finally {
            closeQuietly(s1);
        }
        if (r1 == null) {
            return null;
        }
        if (superseded(epoch)) {
            return sb + "FAIL: superseded by network/state change";
        }
        JoanSipBuilder.Reply p1 = JoanSipBuilder.parseReply(r1);
        if (p1 == null) {
            return "FAIL: reg1 parse";
        }
        if (!JoanRegTransport.finalMatches(reg1Str, r1)) {
            return sb + "FAIL: reg1 mismatch";
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
        if (superseded(epoch)) {
            return sb + "FAIL: superseded by network/state change";
        }

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
        if (superseded(epoch)) {
            return sb + "FAIL: superseded by network/state change";
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
            if (superseded(epoch)) {
                return sb + "FAIL: superseded by network/state change";
            }

            JoanSipBuilder.Id sip2 = new JoanSipBuilder.Id(
                    id.impi, id.impu, id.realm, n.localHost,
                    mine.portC, mine.portS, id.imei);
            String xfrmBefore = JoanXfrmStats.capture();
            String r2 = null;
            int reg2Retx = 0;
            String r2Identity = null;
            boolean tcpReg2;
            {
                /* Stock GetTCPCriterionLength semantics: transport is
                 * chosen per message by size. REG2 is built first as
                 * the UDP variant (also the fallback bytes); its
                 * length drives the criterion. */
                String reg2Udp = JoanSipBuilder.buildRegister(sip2, txn, 2,
                        ch, res, ck, ik, pani, false);
                byte[] reg2Bytes = reg2Udp.getBytes(StandardCharsets.US_ASCII);
                tcpReg2 = JoanSipBuilder.preferProtectedTcp(
                        id.realm, reg2Udp.length())
                        || JoanSipBuilder.preferProtectedTcp(
                        realm, reg2Udp.length());
                sb.append("reg2len=").append(reg2Udp.length()).append(' ');
                if (!tcpReg2) {
                    sb.append("reg2send=").append(mine.portC).append("->")
                            .append(pcscfSec.portS).append(" tpt=udp ");
                    JoanRegTransport.UdpResult ur = JoanRegTransport
                            .sendRecvUdp(sockC, sockS, pcscf, pcscfSec.portS,
                                    reg2Bytes, REG2_TIMEOUT_MS, reg2Udp);
                    if (ur != null) {
                        r2 = ur.reply;
                        reg2Retx = ur.retx;
                    }
                    r2Identity = reg2Udp;
                } else {
                    /* Same message, TCP Via. Stock reuses this client
                     * for INVITE after a 200 ("TCP client is
                     * re-used"). */
                    String reg2Tcp = JoanSipBuilder.buildRegister(sip2,
                            txn, 2, ch, res, ck, ik, pani, true);
                    byte[] tcpBytes =
                            reg2Tcp.getBytes(StandardCharsets.US_ASCII);
                    r2Identity = reg2Tcp;
                    sb.append("reg2send=").append(mine.portC).append("->")
                            .append(pcscfSec.portS).append(" tpt=tcp ");
                    try {
                        JoanRegTransport.TcpResult tr = JoanRegTransport
                                .sendRecvTcp(n.network, n.local, mine.portC,
                                        pcscf, pcscfSec.portS, tcpBytes,
                                        REG2_TIMEOUT_MS, ipsec, inC, outC,
                                        reg2Tcp);
                        r2 = tr.reply;
                        tcpKeep = tr.keep;
                    } catch (JoanRegTransport.TcpFail tf) {
                        sb.append("tcp_fail=").append(tf.phase);
                        Throwable cause = tf.getCause();
                        if (cause != null) {
                            sb.append('(')
                                    .append(cause.getClass().getSimpleName())
                                    .append(')');
                        }
                        sb.append(' ');
                        if (!JoanRegTransport.TcpFail.CONNECT.equals(
                                tf.phase)) {
                            /* Setup/send/read/timeout failures fail
                             * closed: falling back here would either
                             * downgrade the security or retry a
                             * transaction whose TCP path already
                             * connected. */
                            return sb + "FAIL: reg2 tcp " + tf.phase
                                    + " (fail closed)";
                        }
                        /* UDP fallback, matching stock TransmissionProxy
                         * ("UDP fallback"): same protected REGISTER,
                         * only after a refused/dropped connect. */
                        sb.append("tpt=udp ");
                        JoanRegTransport.UdpResult ur = JoanRegTransport
                                .sendRecvUdp(sockC, sockS, pcscf,
                                        pcscfSec.portS, reg2Bytes,
                                        REG2_TIMEOUT_MS, reg2Udp);
                        if (ur != null) {
                            r2 = ur.reply;
                            reg2Retx = ur.retx;
                        }
                        r2Identity = reg2Udp;
                    }
                }
            }
            sb.append("reg2retx=").append(reg2Retx).append(' ');
            sb.append(JoanXfrmStats.delta(xfrmBefore, JoanXfrmStats.capture()))
                    .append(' ');
            if (r2 == null) {
                return sb + "FAIL: reg2 timeout";
            }
            JoanSipBuilder.Reply p2 = JoanSipBuilder.parseReply(r2);
            if (p2 == null) {
                return sb + "FAIL: reg2 parse";
            }
            if (!JoanRegTransport.finalMatches(r2Identity, r2)) {
                /* Fail closed at the adoption boundary: never hand the
                 * UA a reply that was not this transaction's answer. */
                return sb + "FAIL: reg2 mismatch (fail closed)";
            }
            if (superseded(epoch)) {
                return sb + "FAIL: superseded by network/state change";
            }
            sb.append("reg2=").append(p2.status);
            if (p2.status >= 200 && p2.status < 300) {
                sb.append(" OK");
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
        /* Pin the network choice to the subscription the identity will be
         * read from: a second SIM's IMS PDN must never win. */
        int sub = SubscriptionManager.getDefaultDataSubscriptionId();
        if (sub < 0) {
            sub = SubscriptionManager.getDefaultSubscriptionId();
        }
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(network);
            if (nc == null
                    || !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_IMS)) {
                continue;
            }
            if (!networkSubsMatch(nc, sub)) {
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
                /* Same-family P-CSCFs first: the local address family is
                 * what the sockets bind to, so a mixed v4/v6 advertisement
                 * must not send a v6-bound socket at a v4 node. Stable
                 * sort keeps the PDN's own preference inside each group. */
                orderPcscfsByFamily(n.pcscfs, local);
                return n;
            }
        }
        return null;
    }

    /**
     * Whether the IMS network's subscription ids include the selected one.
     * Reflects the SystemApi {@code NetworkCapabilities.getSubscriptionIds}
     * like the P-CSCF read; an opaque/unavailable API accepts (see
     * {@link JoanRegLifecycle#matchesSubscription}).
     */
    static boolean networkSubsMatch(NetworkCapabilities nc, int sub) {
        if (sub < 0) {
            return true;
        }
        int[] ids = null;
        try {
            Method m = nc.getClass().getMethod("getSubscriptionIds");
            Object v = m.invoke(nc);
            if (v instanceof int[]) {
                ids = (int[]) v;
            }
        } catch (Exception e) {
            return true; // API unavailable: capability check above stands
        }
        return JoanRegLifecycle.matchesSubscription(ids, sub);
    }

    private static void orderPcscfsByFamily(List<InetAddress> pcscfs,
                                            InetAddress local) {
        final boolean wantV6 = local instanceof Inet6Address;
        pcscfs.sort((a, b) -> {
            boolean a6 = a instanceof Inet6Address;
            boolean b6 = b instanceof Inet6Address;
            if (a6 == b6) {
                return 0;
            }
            return a6 == wantV6 ? -1 : 1;
        });
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

    /**
     * Legacy send-and-wait kept for the offline UA audit probe and older
     * callers: delegates to {@link JoanRegTransport#sendRecvUdp} with no
     * transaction identity (any final accepted, as before).
     */
    static String sendRecv(DatagramSocket primary, DatagramSocket alt,
                           InetAddress dest, int dport, byte[] pkt,
                           int timeoutMs) throws Exception {
        JoanRegTransport.UdpResult r = JoanRegTransport.sendRecvUdp(
                primary, alt, dest, dport, pkt, timeoutMs, null);
        return r == null ? null : r.reply;
    }

    /** One receive slice; kept here because JoanSipUa calls it. */
    static String tryRecv(DatagramSocket s, byte[] buf, int timeoutMs) {
        return JoanRegTransport.tryRecv(s, buf, timeoutMs);
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

    /* ------------------------------------------------------------------
     * Registration transport. Nested here deliberately: the shared UA
     * audit harness (tests/run-ua-tests.sh) compiles JoanAppRegister
     * with a fixed source list, so the transport must travel inside it.
     * ------------------------------------------------------------------ */

    /**
     * Registration transport: UDP send/retransmit and the protected-TCP
     * path, plus the RFC 3261 transaction-identity matching both must
     * apply before any reply is adopted as this REGISTER's answer.
     *
     * <p>Reply matching (RFC 3261 §17.1.3): a response belongs to the
     * client transaction when its top Via branch and CSeq method match
     * the request. Call-ID equality is additionally required here
     * (§8.1.3.4: a UAC MUST check the Call-ID of any received response)
     * because this stack sends REGISTER straight from the registration
     * flow rather than through a transaction table. A reply that fails
     * any check is dropped and the wait continues; one missing the
     * headers entirely is never accepted.
     *
     * <p>Fail-closed rules: the first UDP send must succeed (an
     * unprotected REGISTER that never left the host must not fall
     * through to IPsec/UA setup); both IPsec directions must apply on
     * the TCP socket; only a connect-phase failure may trigger the
     * caller's UDP fallback (a connected-and-timed-out transaction is a
     * timeout, not a connect problem, and stock's TransmissionProxy
     * fallback exists for the connect failure case).
     */
    static final class JoanRegTransport {
        private JoanRegTransport() {}

        /** True when {@code reply} is a final response for {@code request}. */
        static boolean finalMatches(String request, String reply) {
            if (request == null || reply == null) {
                return false;
            }
            int code = statusOf(reply);
            if (code < 200 || code > 699) {
                return false;
            }
            String reqBranch = viaBranch(JoanSipBuilder.header(request, "Via"));
            String repBranch = viaBranch(JoanSipBuilder.header(reply, "Via"));
            if (reqBranch == null || reqBranch.isEmpty()
                    || !reqBranch.equals(repBranch)) {
                return false;
            }
            String reqCallId = JoanSipBuilder.header(request, "Call-ID");
            String repCallId = JoanSipBuilder.header(reply, "Call-ID");
            if (reqCallId == null || repCallId == null
                    || !reqCallId.trim().equals(repCallId.trim())) {
                return false;
            }
            String reqCSeq = JoanSipBuilder.header(request, "CSeq");
            String repCSeq = JoanSipBuilder.header(reply, "CSeq");
            return cseqNumber(reqCSeq) != null
                    && cseqNumber(reqCSeq).equals(cseqNumber(repCSeq))
                    && cseqMethod(reqCSeq) != null
                    && cseqMethod(reqCSeq).equals(cseqMethod(repCSeq));
        }

        /** Status code of a SIP message, 0 when malformed. */
        static int statusOf(String msg) {
            if (msg == null || !msg.startsWith("SIP/2.0 ")) {
                return 0;
            }
            try {
                return Integer.parseInt(msg.substring(8, 11).trim());
            } catch (RuntimeException e) {
                return 0;
            }
        }

        /** The branch parameter value of a top Via header, or null. */
        static String viaBranch(String via) {
            if (via == null) {
                return null;
            }
            int i = via.indexOf("branch=");
            if (i < 0) {
                return null;
            }
            int s = i + "branch=".length();
            int e = via.indexOf(';', s);
            return (e < 0 ? via.substring(s) : via.substring(s, e)).trim();
        }

        /** The CSeq sequence number as written (exact string compare). */
        static String cseqNumber(String cseq) {
            if (cseq == null) {
                return null;
            }
            String t = cseq.trim();
            int sp = t.indexOf(' ');
            return sp < 0 ? (t.isEmpty() ? null : t) : t.substring(0, sp);
        }

        /** The CSeq method token, or null when absent. */
        static String cseqMethod(String cseq) {
            if (cseq == null) {
                return null;
            }
            String t = cseq.trim();
            int sp = t.indexOf(' ');
            if (sp < 0) {
                return null;
            }
            String m = t.substring(sp + 1).trim();
            return m.isEmpty() ? null : m;
        }

        static final class UdpResult {
            final String reply;
            final int retx;

            UdpResult(String reply, int retx) {
                this.reply = reply;
                this.retx = retx;
            }
        }

        /**
         * Send and wait over UDP, retransmitting on the RFC 3261
         * §17.1.2.2 schedule (Timer E: T1 = 500 ms, doubling, capped at
         * T2 = 4 s). The first send failure propagates: the caller must
         * fail closed instead of proceeding to IPsec/AKA steps for a
         * message that never left.
         *
         * <p>Provisionals are not the transaction's answer (a 100 Trying
         * must not end the wait), and finals that do not match
         * {@code identity} are dropped as stray datagrams rather than
         * adopted. Pass {@code identity == null} to accept any final
         * (legacy callers).
         *
         * @return the matching final, or null on deadline.
         */
        static UdpResult sendRecvUdp(DatagramSocket primary,
                                     DatagramSocket alt, InetAddress dest,
                                     int dport, byte[] pkt, int timeoutMs,
                                     String identity) throws Exception {
            DatagramPacket out = new DatagramPacket(pkt, pkt.length, dest,
                    dport);
            primary.send(out);
            int retx = 0;
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
                        retx++;
                    } catch (Exception e) {
                        /* The first send already left; keep listening for
                         * the answer instead of abandoning the
                         * transaction. */
                    }
                    interval = Math.min(interval * 2, 4000);
                    nextTx = now + interval;
                }
                int slice = (int) Math.min(200,
                        Math.min(deadline, nextTx)
                                - System.currentTimeMillis());
                if (slice <= 0) {
                    continue;
                }
                String got = tryRecv(primary, buf, slice);
                if (got == null && alt != null) {
                    got = tryRecv(alt, buf, slice);
                }
                if (got == null) {
                    continue;
                }
                int code = statusOf(got);
                if (code >= 100 && code < 200) {
                    continue;
                }
                if (identity == null || finalMatches(identity, got)) {
                    return new UdpResult(got, retx);
                }
            }
            return null;
        }

        /** One receive slice; null on timeout or error. */
        static String tryRecv(DatagramSocket s, byte[] buf, int timeoutMs) {
            try {
                s.setSoTimeout(Math.max(1, timeoutMs));
                DatagramPacket in = new DatagramPacket(buf, buf.length);
                s.receive(in);
                return new String(buf, 0, in.getLength(),
                        StandardCharsets.US_ASCII);
            } catch (Exception e) {
                return null;
            }
        }

        static final class TcpResult {
            final String reply;
            final Socket keep;

            TcpResult(String reply, Socket keep) {
                this.reply = reply;
                this.keep = keep;
            }
        }

        /**
         * Failure of the protected-TCP attempt, carrying the phase where
         * it died. The trace maps phases to the stock-vs-dropped
         * discrimination: SETUP (bind/transform), CONNECT (refused or
         * dropped handshake; the only phase eligible for the caller's
         * UDP fallback), SEND, READ, TIMEOUT (connected but no matching
         * final arrived).
         */
        static final class TcpFail extends Exception {
            static final String SETUP = "setup";
            static final String CONNECT = "connect";
            static final String SEND = "send";
            static final String READ = "read";
            static final String TIMEOUT = "timeout";

            final String phase;

            TcpFail(String phase, Exception cause) {
                super(phase);
                this.phase = phase;
                if (cause != null) {
                    initCause(cause);
                }
            }
        }

        /**
         * Protected REGISTER over TCP: bind UE port-c, apply BOTH IPsec
         * directions (fail closed if either fails), connect to the
         * P-CSCF's protected server port, write once, then read complete
         * frames until a final matching {@code identity} arrives.
         *
         * <p>TCP has no SIP retransmission (RFC 3261 §18.2.2; Timer E is
         * UDP only). The successful socket is returned in
         * {@link TcpResult#keep} with an infinite read timeout so the UA
         * can reuse it for INVITE, matching stock libims' "TCP client is
         * re-used".
         */
        static TcpResult sendRecvTcp(Network network, InetAddress local,
                                     int localPort, InetAddress dest,
                                     int dport, byte[] pkt, int timeoutMs,
                                     IpSecManager ipsec,
                                     IpSecTransform inXf,
                                     IpSecTransform outXf, String identity)
                throws TcpFail {
            Socket sock = new Socket();
            try {
                sock.setReuseAddress(true);
                /* Bounded per-read slices so the deadline holds to ~2 s. */
                sock.setSoTimeout(Math.max(1, Math.min(2000, timeoutMs)));
                try {
                    if (network != null) {
                        network.bindSocket(sock);
                    }
                    sock.bind(new InetSocketAddress(local, localPort));
                } catch (Exception e) {
                    throw new TcpFail(TcpFail.SETUP, e);
                }
                try {
                    if (ipsec != null) {
                        if (outXf != null) {
                            ipsec.applyTransportModeTransform(sock,
                                    IpSecManager.DIRECTION_OUT, outXf);
                        }
                        if (inXf != null) {
                            /* Fail closed: a socket that cannot receive
                             * protected responses must not talk to the
                             * protected port at all. */
                            ipsec.applyTransportModeTransform(sock,
                                    IpSecManager.DIRECTION_IN, inXf);
                        }
                    }
                } catch (Exception e) {
                    throw new TcpFail(TcpFail.SETUP, e);
                }
                try {
                    sock.connect(new InetSocketAddress(dest, dport),
                            Math.min(4000, Math.max(1, timeoutMs)));
                } catch (Exception e) {
                    throw new TcpFail(TcpFail.CONNECT, e);
                }
                try {
                    java.io.OutputStream os = sock.getOutputStream();
                    os.write(pkt);
                    os.flush();
                } catch (Exception e) {
                    throw new TcpFail(TcpFail.SEND, e);
                }
                String got = readFinal(sock.getInputStream(), identity,
                        new StringBuilder(),
                        System.currentTimeMillis() + timeoutMs,
                        new byte[4096]);
                if (got == null) {
                    throw new TcpFail(TcpFail.TIMEOUT, null);
                }
                sock.setSoTimeout(0);
                return new TcpResult(got, sock);
            } catch (TcpFail f) {
                releaseTcp(ipsec, sock);
                throw f;
            } catch (Exception e) {
                releaseTcp(ipsec, sock);
                throw new TcpFail(TcpFail.READ, e);
            }
        }

        /**
         * Read complete SIP frames until a matching final arrives or the
         * deadline passes. Drains every frame already buffered in
         * {@code acc} BEFORE another blocking read, so a coalesced
         * 100 Trying + final in one segment is fully consumed; 1xx is
         * skipped and mismatched finals (wrong Call-ID/branch/CSeq) are
         * dropped, never returned.
         *
         * @return the matching final, or null on deadline/EOF.
         */
        static String readFinal(java.io.InputStream is, String identity,
                                StringBuilder acc, long deadlineMs,
                                byte[] buf) throws Exception {
            while (true) {
                String got = JoanSipBuilder.extractOne(acc);
                while (got != null) {
                    int code = statusOf(got);
                    if (code >= 200 && code <= 699
                            && (identity == null
                            || finalMatches(identity, got))) {
                        return got;
                    }
                    got = JoanSipBuilder.extractOne(acc);
                }
                if (System.currentTimeMillis() >= deadlineMs) {
                    return null;
                }
                int n;
                try {
                    n = is.read(buf, 0, buf.length);
                } catch (java.net.SocketTimeoutException e) {
                    continue; // deadline governs
                }
                if (n <= 0) {
                    return null; // EOF
                }
                acc.append(new String(buf, 0, n, StandardCharsets.US_ASCII));
            }
        }

        private static void releaseTcp(IpSecManager ipsec, Socket sock) {
            if (sock == null) {
                return;
            }
            if (ipsec != null) {
                try {
                    ipsec.removeTransportModeTransforms(sock);
                } catch (Exception ignored) {
                    // socket is going away either way
                }
            }
            try {
                sock.close();
            } catch (Exception ignored) {
                // already closed
            }
        }
    }

    /* ------------------------------------------------------------------
     * Network-lifecycle decisions, shared by the registration driver and
     * this registration flow. Pure logic (no android types) so the whole
     * table is provable offline with fixtures.
     * ------------------------------------------------------------------ */

    static final class JoanRegLifecycle {
        private JoanRegLifecycle() {}

        /** Poke reasons produced by the IMS network callback. */
        static final String POKE_IMS_AVAILABLE = "ims available";
        static final String POKE_IMS_LOST = "ims lost";

        /**
         * Routine network-presence chatter (the connectivity callback) vs
         * a genuine user/radio state poke (boot, package-replaced,
         * airplane). Routine pokes must never tear a healthy
         * registration; a lost PDN is the only routine event allowed to
         * clear one.
         */
        static boolean routinePoke(String reason) {
            return POKE_IMS_AVAILABLE.equals(reason)
                    || POKE_IMS_LOST.equals(reason);
        }

        /**
         * Whether this poke must clear the UA's registration. PDN loss
         * leaves {@code sReg} stale: the binding is gone on the network
         * while the UA still believes it is registered, so only an
         * explicit release lets the driver re-register instead of
         * sleeping until refresh.
         */
        static boolean clearOnLost(String reason, boolean uaRegistered) {
            return POKE_IMS_LOST.equals(reason) && uaRegistered;
        }

        /**
         * Whether an availability poke must re-acquire a binding that a
         * preceding loss may have invalidated. A loss observed while a
         * REGISTER attempt was in flight could not be acted on at the
         * time (the attempt owns the UA), so the flag is remembered and
         * the next availability poke forces a fresh REGISTER even when
         * the UA still believes it is registered.
         */
        static boolean reacquireAfterLoss(String reason,
                                          boolean staleAfterLoss) {
            return POKE_IMS_AVAILABLE.equals(reason) && staleAfterLoss;
        }

        /**
         * Whether an IMS network carrying {@code networkSubs} belongs to
         * the subscription the driver resolved ({@code selectedSub}). A
         * network whose subscription ids are readable and exclude the
         * selected one is another SIM's IMS PDN and must not be
         * registered against. When the ids are unavailable the network
         * is accepted: the capability and transport checks still stand,
         * and refusing on an opaque API would break single-SIM devices
         * whose NetworkCapabilities don't publish subscription ids.
         */
        static boolean matchesSubscription(int[] networkSubs,
                                           int selectedSub) {
            if (selectedSub < 0) {
                return true; // nothing resolved to pin against
            }
            if (networkSubs == null || networkSubs.length == 0) {
                return true; // API opaque: capability/transport stand
            }
            for (int s : networkSubs) {
                if (s == selectedSub) {
                    return true;
                }
            }
            return false;
        }
    }
}
