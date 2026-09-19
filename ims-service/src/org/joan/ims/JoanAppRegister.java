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

    /**
     * Stock parity (alpha12): after a failed registration on one address
     * family, GlobalAoSRegistration::RegistraionOnDifferentIPVersion flips
     * the local IP version (v6&harr;v4) and retries once before falling
     * back to normal backoff. This flag carries that "one flip retry is
     * due" decision from the failure point to the next discovery pass;
     * it never applies to a healthy or single-family path.
     */
    private static volatile boolean sFlipPending;

    /** Whether the last discovery pass saw both address families. */
    private static volatile boolean sLastDualFamily;

    /** Supersede any in-flight attempt (network lost / state change). */
    /* The carrier's retry_pcscf_count: extra attempts against the same
     * P-CSCF before moving to the next. 0 in 133 of 136 profiles, which
     * is one attempt each -- what joan already did. */
    private static volatile int sPcscfRetryCount;

    /* Where the next cycle starts in the P-CSCF list, and the list size
     * it refers to. A different size means a different list. */
    private static volatile int sPcscfCursor;
    private static volatile int sPcscfCursorSize;

    static void setPcscfRetryCount(int count) {
        sPcscfRetryCount = Math.max(0, count);
    }

    static int pcscfRetryCount() {
        return sPcscfRetryCount;
    }

    static void stop() {
        synchronized (EPOCH_LOCK) {
            sEpoch++;
            sFlipPending = false;
            sAttemptWasAlternate = false;
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

    /** Whether a flip retry is due; consumed at REGISTER planning. */
    static boolean flipPending() {
        return sFlipPending;
    }

    /** Discovery notes whether the last PDN was dual-family (test hook). */
    static void noteLastAttemptDualFamily(boolean dual) {
        sLastDualFamily = dual;
    }

    /** Whether the last discovery saw both families (test hook). */
    static boolean lastAttemptDualFamily() {
        return sLastDualFamily;
    }

    /**
     * True when the last cycle raised its expiry on a 423, which the
     * driver answers with a prompt retry instead of a backoff: the
     * network named the term, we took it, and there is nothing for an
     * exponential to discover.
     */
    static boolean expiryRaised(String lastRegister) {
        return lastRegister != null && lastRegister.contains("expires_raised");
    }

    /** Request the one flip retry (driver failure path). */
    static void requestFlipRetry() {
        sFlipPending = true;
    }

    /** Consume the flip preference; true at most once per failure. */
    static boolean consumeFlipRetry() {
        if (sFlipPending) {
            sFlipPending = false;
            return true;
        }
        return false;
    }

    /** Whether the last REGISTER attempt ran on the alternate family. */
    private static volatile boolean sAttemptWasAlternate;

    /**
     * Stock-parity decision helper (pure, testable): a whole-cycle failure
     * that waited out its candidates earns one address-family flip retry
     * only when the PDN actually has a usable pair of the other family
     * (dualFamily is computed from locals+peers, so a v4-only PDN or a
     * P-CSCF-less PDN can never earn an impossible flip). Mid-flight
     * supersession and explicit rejections never flip.
     */
    static boolean shouldFlipIpVersion(String lastRegister,
                                       boolean dualFamily) {
        if (lastRegister == null) {
            return false;
        }
        if (!lastRegister.contains("FAIL: reg1 no answer from any of")
                && !lastRegister.contains("FAIL: reg1 no matching final")
                && !lastRegister.contains("reg1_result=timeout")
                && !lastRegister.contains("FAIL: reg2 timeout")) {
            return false;
        }
        return dualFamily;
    }

    /**
     * Consume the flip decision and choose this attempt's family plan.
     * Called at REGISTER time (not discovery) so the plan matches the
     * sockets that will actually carry REG1/REG2.
     */
    static JoanImsDiscovery.Plan selectAttemptPlan(
            List<InetAddress> locals, List<InetAddress> peers) {
        synchronized (EPOCH_LOCK) {
            JoanImsDiscovery.Plan primary =
                    JoanImsDiscovery.plan(locals, peers, false);
            boolean wantAlternate = sFlipPending;
            sFlipPending = false;
            sAttemptWasAlternate = false;
            if (!wantAlternate) {
                return primary;
            }
            JoanImsDiscovery.Plan alternate =
                    JoanImsDiscovery.plan(locals, peers, true);
            if (alternate.local == null) {
                return primary; // single-family PDN: nothing to flip to
            }
            sAttemptWasAlternate = true;
            return alternate;
        }
    }

    /** Whether a failed cycle earns one family flip; records it if so. */
    static boolean scheduleFamilyRetry(String lastRegister) {
        synchronized (EPOCH_LOCK) {
            if (sAttemptWasAlternate) {
                return false; // stock: one flip, then normal backoff
            }
            if (!shouldFlipIpVersion(lastRegister,
                    lastAttemptDualFamily())) {
                return false;
            }
            sFlipPending = true;
            return true;
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
        JoanImsDiscovery.Plan plan =
                selectAttemptPlan(n.locals, n.pcscfs);
        if (plan.local == null || plan.peers.isEmpty()) {
            return "FAIL: no usable P-CSCF/local pair ("
                    + (n.pcscfDiag == null ? "unread" : n.pcscfDiag) + ")";
        }
        n.local = plan.local;
        String host = plan.local.getHostAddress();
        n.localHost = host != null && host.contains("%")
                ? host.substring(0, host.indexOf('%')) : host;
        n.pcscfs.clear();
        n.pcscfs.addAll(plan.peers);
        JoanImsDiagnostics.noteAttemptContext();

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
        boolean redirected = false;
        String last = null;
        final int pcscfCount = n.pcscfs.size();
        if (sPcscfCursorSize != pcscfCount) {
            sPcscfCursor = 0;
            sPcscfCursorSize = pcscfCount;
        }
        final int start = JoanRegLifecycle.pcscfStartIndex(sPcscfCursor,
                pcscfCount);
        /* Each node gets 1 + retry_pcscf_count attempts before the walk
         * moves on; the vendor default of 0 is the single attempt joan
         * already made. */
        final int perNode = 1 + pcscfRetryCount();
        for (int slot = 0; slot < pcscfCount * perNode; slot++) {
            InetAddress cand = n.pcscfs.get(
                    (start + (slot / perNode)) % pcscfCount);
            tried++;
            if (superseded(epoch)) {
                sb.append("pcscf_tried=").append(tried).append(' ');
                return sb + "FAIL: superseded by network/state change";
            }
            String one = tryPcscf(ctx, n, id, pani, cand, perTry, epoch);
            /* Follow a Use Proxy once, to the node the core named.
             *
             * Only an IP literal: resolving an FQDN here would put a
             * blocking DNS lookup in the registration path, and a core
             * that redirects to a name we cannot reach is no better off
             * than one we never followed. Once per attempt, and never to
             * an address already in the candidate list, because a core
             * that redirects in a cycle would otherwise spin. */
            String rHost = redirectHostOf(one);
            if (rHost != null && !redirected) {
                java.net.InetAddress extra = literalAddress(rHost);
                if (extra != null && !n.pcscfs.contains(extra)) {
                    redirected = true;
                    String two = tryPcscf(ctx, n, id, pani, extra, perTry,
                            epoch);
                    JoanTrace.note("app register redirect_try "
                            + (two == null ? "FAIL: no usable reply" : two));
                    if (two != null && two.indexOf(" OK") >= 0) {
                        sb.append("pcscf_tried=").append(tried)
                                .append(" redirected=1 ");
                        return sb.append(two).toString();
                    }
                }
            }
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
            /* "FAIL: aka" covers every AKA outcome (parse, lengths, sync
             * failure): none of them get better against a second P-CSCF,
             * and retrying burns another authentication vector, which is
             * what pushes the card's SQN further out of step. */
            if (one.indexOf("FAIL: aka") >= 0
                    || one.indexOf("FAIL: no IpSecManager") >= 0
                    || one.indexOf("FAIL: superseded") >= 0) {
                sb.append("pcscf_tried=").append(tried).append(' ');
                return sb.append(one).toString();
            }
        }
        /* Every node failed: start the next cycle on the one after the
         * node this cycle started with. */
        if (pcscfCount > 0) {
            sPcscfCursor = (start + 1) % pcscfCount;
        }
        sb.append("pcscf_tried=").append(tried).append(' ');
        if (last == null) {
            return sb + "FAIL: reg1 no answer from any of " + n.pcscfs.size();
        }
        return sb.append(last).toString();
    }

    /**
     * REG1 + AKA + IPsec + REG2 against one advertised P-CSCF.
     * @return a sanitized diagnosis for every candidate, including REG1
     *         setup/send errors and no-matching-final deadlines. Only a
     *         successful REGISTER 200 carries {@code OK}.
     */
    /**
     * The REGISTER series for one private identity: a stable Call-ID and
     * From-tag, and a CSeq that only ever rises.
     *
     * <p>Every attempt used to mint a fresh Call-ID and restart CSeq at 1,
     * so a registrar saw each retry -- and each of the two P-CSCF
     * candidates inside one attempt -- as an unrelated registration rather
     * than another try at the same one. RFC 3261 10.2 asks for one Call-ID
     * per registrar, and AOSP's ImsStack deliberately keeps both across
     * failures: "Do not check the status code to support re-use of Call-ID
     * and CSeq number when the registration is failed"
     * (engine/registration/Registration.cpp, tag android-17.0.0_r1).
     *
     * <p>Held in memory only. A registrar rejects a REGISTER whose CSeq
     * did not advance, so the series must never restart under a Call-ID it
     * has already used -- surviving a process restart would risk exactly
     * that, and a fresh Call-ID after one is always safe.
     *
     * <p>Protected ports, cnonce and Via branch stay per-attempt: new
     * security associations need new ports and SPIs.
     */
    static final class RegSeries {
        private String impi;
        private String callId;
        private String fromTag;
        private int cseq;

        synchronized JoanSipBuilder.Txn newAttempt(
                String forImpi, JoanSipBuilder.Params mine, SecureRandom rng) {
            if (callId == null || forImpi == null || !forImpi.equals(impi)) {
                JoanSipBuilder.Txn seed = new JoanSipBuilder.Txn(mine, rng);
                impi = forImpi;
                callId = seed.callId;
                fromTag = seed.fromTag;
                cseq = 0;
                return seed;
            }
            return new JoanSipBuilder.Txn(mine, rng, callId, fromTag);
        }

        /** The CSeq for the next REGISTER transaction in this series. */
        synchronized int nextCseq() {
            return ++cseq;
        }
    }

    static final RegSeries REG_SERIES = new RegSeries();

    /** The redirect host a tryPcscf() result reported, or null. */
    private static String redirectHostOf(String result) {
        if (result == null) {
            return null;
        }
        int i = result.indexOf("redirect_host=");
        if (i < 0) {
            return null;
        }
        String rest = result.substring(i + "redirect_host=".length());
        int end = rest.indexOf(' ');
        String h = (end < 0 ? rest : rest.substring(0, end)).trim();
        return h.isEmpty() ? null : h;
    }

    /**
     * Parse an IP literal, without DNS. Returns null for a name, which is
     * deliberate: {@code InetAddress.getByName} would resolve it, and a
     * lookup on this path blocks registration behind the network's DNS.
     */
    private static java.net.InetAddress literalAddress(String host) {
        if (host == null || host.isEmpty()) {
            return null;
        }
        boolean looksNumeric = host.indexOf(':') >= 0;
        if (!looksNumeric) {
            looksNumeric = true;
            for (int i = 0; i < host.length(); i++) {
                char c = host.charAt(i);
                if ((c < '0' || c > '9') && c != '.') {
                    looksNumeric = false;
                    break;
                }
            }
        }
        if (!looksNumeric) {
            return null;
        }
        try {
            return java.net.InetAddress.getByName(host);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String tryPcscf(Context ctx, Net n, Id id, String pani,
                                   InetAddress pcscf, int reg1TimeoutMs,
                                   long epoch) {
        SecureRandom rng = new SecureRandom();
        JoanSipBuilder.Params mine = JoanSipBuilder.Params.random(rng);
        JoanSipBuilder.Txn txn = REG_SERIES.newAttempt(id.impi, mine, rng);
        /* One CSeq per REGISTER transaction, not per build: REG1 is built
         * twice (a UDP variant and a TCP variant) and both are the same
         * transaction, so they must carry the same number. */
        int reg1Cseq = REG_SERIES.nextCseq();
        JoanSipBuilder.Id sipId = new JoanSipBuilder.Id(
                id.impi, id.impu, id.realm, n.localHost,
                JoanSipBuilder.REG1_PORT, JoanSipBuilder.REG1_PORT, id.imei);
        /* The preloaded Route names the P-CSCF this attempt goes
         * through. Set per attempt, because joan walks the discovered
         * list and the second try is a different P-CSCF; the reference
         * likewise re-adds it from whichever one GetNextPcscf handed
         * back. The port stays the signalling port -- the protected
         * REGISTER routes through the same P-CSCF, and
         * AosRegistration keeps m_nPcscfPort across the SA. */
        String routeHost = pcscf.getHostAddress();
        int routeScope = routeHost.indexOf('%');
        if (routeScope >= 0) {
            routeHost = routeHost.substring(0, routeScope);
        }
        JoanSipBuilder.setPcscfRoute(routeHost, 0);
        StringBuilder sb = new StringBuilder();
        String reg1Udp = JoanSipBuilder
                .buildRegister(sipId, txn, reg1Cseq, null, null, null, null, pani,
                        false);
        byte[] reg1Bytes = reg1Udp.getBytes(StandardCharsets.US_ASCII);
        String reg1Str = reg1Udp;
        boolean ipv6 = n.local instanceof Inet6Address;
        sb.append("reg1_local=")
                .append(ipv6 ? "v6" : "v4")
                .append(" reg1_peer=")
                .append(pcscf instanceof Inet6Address ? "v6" : "v4")
                .append(" reg1_mtu=").append(n.mtu)
                .append(" reg1_oh=")
                .append(JoanSipBuilder.udpOverhead(ipv6)).append(' ');
        boolean tcpReg1 = JoanSipBuilder.preferTcp(id.realm, reg1Udp.length(),
                n.mtu, ipv6);
        /* Why that transport was chosen. Without these three the trace
         * says "udp" and gives no way to tell whether the criterion was
         * not exceeded, the platform asked for UDP, or the realm failed
         * to parse as a PLMN -- which are three different bugs. The
         * China Mobile tester needs this more than we do. */
        sb.append("reg1_crit=")
                .append(JoanSipBuilder.registerTcpCriterion(n.mtu, ipv6))
                .append(" tpt_pol=")
                .append(JoanSipBuilder.platformPreferredTransport())
                .append(" plmn=")
                .append(JoanSipBuilder.plmnSource(id.realm))
                .append(' ');
        Reg1Result first;
        /* The identity the reply is matched against must be the message
         * that actually went out: buildRegister() re-rolls txn.branch on
         * every call, so the TCP variant carries a different Via branch
         * than reg1Udp and matching the reply against reg1Udp would fail
         * for every TCP REG1 (REG2 already tracks this as r2Identity). */
        String reg1Identity = reg1Str;
        /* Which transport actually produced the challenge. An AUTS resync
         * REGISTER is another unprotected REGISTER, so it reuses the one
         * already known to work rather than re-running the TCP-then-UDP
         * fallback and eating a second connect timeout. */
        boolean reg1OnTcp = false;
        if (tcpReg1) {
            String reg1Tcp = JoanSipBuilder.buildRegister(sipId, txn, reg1Cseq,
                    null,
                    null, null, null, pani, true);
            reg1Identity = reg1Tcp;
            reg1OnTcp = true;
            first = exchangeReg1Tcp(n, pcscf, reg1Tcp, reg1TimeoutMs);
            if (first.reply == null
                    && JoanRegTransport.fallbackUnprotectedTcp(first.phase)) {
                sb.append(first.diagnostic);
                /* Fallback re-sends the original reg1Udp bytes (branch as
                 * built), not the TCP variant. */
                reg1Identity = reg1Str;
                reg1OnTcp = false;
                first = exchangeReg1(
                        () -> boundUdp(n.network, n.local,
                                JoanSipBuilder.REG1_PORT),
                        pcscf, reg1Bytes, reg1TimeoutMs, reg1Str);
            }
        } else {
            first = exchangeReg1(
                    () -> boundUdp(n.network, n.local, JoanSipBuilder.REG1_PORT),
                    pcscf, reg1Bytes, reg1TimeoutMs, reg1Str);
        }
        sb.append(first.diagnostic);
        String r1 = first.reply;
        if (superseded(epoch)) {
            return sb + "FAIL: superseded by network/state change";
        }
        if (r1 == null) {
            return sb.toString();
        }
        JoanSipBuilder.Reply p1 = JoanSipBuilder.parseReply(r1);
        if (p1 == null) {
            return "FAIL: reg1 parse";
        }
        if (!JoanRegTransport.finalMatches(reg1Identity, r1)) {
            return sb + "FAIL: reg1 mismatch";
        }
        sb.append("reg1=").append(p1.status).append(' ');
        if (p1.status >= 300 && p1.status < 400) {
            /* RFC 3261 s21.3.4. LG carries a CMCC-specific
             * ProcessStartFailed_305, so a Use Proxy on REGISTER is a real
             * condition on that network; we used to call it
             * "reg1 unexpected" and drop the address the core handed us. */
            String to = JoanSipBuilder.redirectHost(r1);
            if (to != null) {
                sb.append("redirect_host=").append(to).append(' ');
            }
            return sb + "FAIL: reg1 redirect";
        }
        if (p1.status != 401) {
            return sb + "FAIL: reg1 unexpected";
        }
        if (p1.wwwAuth == null || p1.secServer == null) {
            return sb + "FAIL: 401 missing challenge/sec-server";
        }

        String nonce;
        String algo;
        String realm;
        String qop;
        JoanSecAgree pcscfSec;
        String[] parts;
        boolean resyncSpent = false;

        for (;;) {
            nonce = JoanSipBuilder.extractNonce(p1.wwwAuth);
            algo = JoanSipBuilder.extractAlgorithm(p1.wwwAuth);
            realm = JoanSipBuilder.extractRealm(p1.wwwAuth);
            qop = JoanSipBuilder.extractQop(p1.wwwAuth);
            if (nonce == null || nonce.isEmpty()) {
                return sb + "FAIL: 401 no nonce";
            }
            sb.append("aka=").append(algo).append(' ');

            /* The P-CSCF's Security-Server header, verbatim.
             *
             * offered= prints only what parsed, so a mechanism we failed
             * to split or read has always been invisible -- and we cannot
             * tell "the network offered one row" from "the network
             * offered three and we understood one". A real header shape
             * exists that does exactly that: mechanisms are separated by
             * commas, so a P-CSCF that writes multiple values into one
             * parameter (ealg=aes-cbc,null, unquoted) has its single
             * mechanism split into two fragments, one losing its SPIs and
             * the other losing its mechanism name, and both are dropped.
             *
             * The header carries no subscriber identity -- mechanism
             * names, algorithms, SPIs, ports and q-values -- and the SPIs
             * in it are the network's own, for an SA that is torn down
             * before any log is read. Printed once per attempt so a
             * tester's trace answers the question without a capture. */
            JoanTrace.note("sec-server raw: "
                    + (p1.secServer == null ? "(absent)" : p1.secServer));
            sb.append("sec_rows=")
                    .append(JoanSecAgree.rawMechanismCount(p1.secServer))
                    .append(" sec_parsed=")
                    .append(JoanSecAgree.parseAll(p1.secServer).size())
                    .append(' ');
            pcscfSec = JoanSecAgree.select(p1.secServer);
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
        /* Time the AKA call itself. A sync failure is a real AUTHENTICATE
         * on the card and should cost about what a success costs, while a
         * telephony-side error returns without reaching the UICC -- and
         * those two want opposite fixes (AUTS resync vs. retry). The trace
         * note inside runIccAuth() is written AFTER the call returns, so
         * the existing log cannot be read for this: the gap from that note
         * to the register line is IPsec setup plus the REG2 round trip,
         * not card time. No baseline is asserted here; this is the
         * measurement that establishes one. */
            long akaStart = System.currentTimeMillis();
            try {
                authHex = JoanAka.runIccAuth(ctx, nonce);
            } catch (Exception e) {
                return sb + "FAIL: aka " + brief(e) + " aka_ms="
                        + (System.currentTimeMillis() - akaStart);
            }
            long akaMs = System.currentTimeMillis() - akaStart;
            if (authHex == null) {
                return sb + "FAIL: aka unavailable aka_ms=" + akaMs;
            }
            parts = JoanAka.parseAuthResponse(authHex);
            if (parts != null) {
                /* The card accepted the AUTN and returned RES/CK/IK: no
                 * synchronisation failure, so no AUTS and no resync
                 * REGISTER. Stamped rather than left absent, because a
                 * missing field and a field that says zero read the same
                 * to anyone holding only the log. */
                sb.append("aka_sync=0 ");
                break;
            }
            if (!JoanAka.isSyncFailure(authHex)) {
                return sb + "FAIL: aka parse len=" + authHex.length()
                        + " aka_ms=" + akaMs;
            }
            /* Reached only on a real card-reported sync failure. The
             * success path stamps aka_sync=0 where the card accepted the
             * AUTN, so a trace can prove the UE never asked for a
             * resynchronisation -- which is the whole question when a
             * core answers "AKA sync proc timeout" and we need to say,
             * to an operator, that the request did not come from us. */
            /* SYNCHRONISATION FAILURE: the card computed AUTS because its
             * SQN is behind the HSS. Nothing retries out of this -- the
             * HSS keeps issuing vectors from the same stale batch -- so
             * the only exit is one REGISTER carrying auts= (RFC 3310 3.2),
             * which makes the HSS resynchronise and challenge us afresh.
             * One attempt only: a second sync failure on the resynced
             * challenge means something other than SQN drift. */
            sb.append("aka_sync=1 auts_len=")
                    .append(JoanAka.autsLength(authHex))
                    .append(" aka_ms=").append(akaMs).append(' ');
            if (resyncSpent) {
                return sb + "FAIL: aka sync failure after resync";
            }
            resyncSpent = true;
            String autsB64 = JoanAka.autsBase64(authHex);
            if (autsB64 == null) {
                return sb + "FAIL: aka sync failure (AUTS unreadable)";
            }
            if (superseded(epoch)) {
                return sb + "FAIL: superseded by network/state change";
            }
            String resyncMsg = JoanSipBuilder.buildRegister(
                    sipId, txn, REG_SERIES.nextCseq(),
                    new JoanSipBuilder.Challenge(nonce, algo, null, realm, qop)
                            .resync(autsB64),
                    new byte[0], null, null, pani, reg1OnTcp);
            Reg1Result again;
            if (reg1OnTcp) {
                again = exchangeReg1Tcp(n, pcscf, resyncMsg, reg1TimeoutMs);
            } else {
                byte[] resyncBytes =
                        resyncMsg.getBytes(StandardCharsets.US_ASCII);
                again = exchangeReg1(
                        () -> boundUdp(n.network, n.local,
                                JoanSipBuilder.REG1_PORT),
                        pcscf, resyncBytes, reg1TimeoutMs, resyncMsg);
            }
            sb.append("resync_").append(again.diagnostic);
            if (again.reply == null) {
                return sb + "FAIL: resync no reply";
            }
            JoanSipBuilder.Reply pr = JoanSipBuilder.parseReply(again.reply);
            if (pr == null) {
                return sb + "FAIL: resync parse";
            }
            if (!JoanRegTransport.finalMatches(resyncMsg, again.reply)) {
                return sb + "FAIL: resync mismatch";
            }
            sb.append("resync=").append(pr.status).append(' ');
            if (pr.status != 401) {
                return sb + "FAIL: resync unexpected";
            }
            if (pr.wwwAuth == null || pr.secServer == null) {
                return sb + "FAIL: resync 401 missing challenge/sec-server";
            }
            if (JoanSipBuilder.extractNonce(pr.wwwAuth) == null
                    || nonce.equals(JoanSipBuilder.extractNonce(pr.wwwAuth))) {
                /* A resynced challenge must carry a NEW nonce. The same one
                 * back means the HSS did not act on the AUTS, and running
                 * the card against it again just burns another vector. */
                return sb + "FAIL: resync nonce unchanged";
            }
            p1 = pr;
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
        /* Likewise one number for REG2, shared by its UDP and TCP forms. */
        int reg2Cseq = REG_SERIES.nextCseq();

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
                /* Stock GetTCPCriterionLength plus RFC 3261 §18.1.1.
                 * REG2 is built first as the UDP variant (also the
                 * fallback bytes); its length plus path MTU drive
                 * the criterion. TMUS still never leaves UDP. */
                String reg2Udp = JoanSipBuilder.buildRegister(sip2, txn,
                        reg2Cseq,
                        ch, res, ck, ik, pani, false);
                byte[] reg2Bytes = reg2Udp.getBytes(StandardCharsets.US_ASCII);
                boolean ipv6Reg2 = n.local instanceof Inet6Address;
                tcpReg2 = JoanSipBuilder.preferTcp(id.realm, reg2Udp.length(),
                        n.mtu, ipv6Reg2)
                        || JoanSipBuilder.preferTcp(realm, reg2Udp.length(),
                        n.mtu, ipv6Reg2);
                sb.append("reg2len=").append(reg2Udp.length())
                        .append(" reg2_mtu=").append(n.mtu)
                        .append(" reg2_hdrs=").append(
                                JoanSipBuilder.headerShape(reg2Udp))
                        .append(" reg_tags=").append(
                                JoanSipBuilder.registerContactTags()
                                        ? "mmtel" : "none")
                        .append(' ');
                if (!tcpReg2) {
                    sb.append("reg2send=").append(mine.portC).append("->")
                            .append(pcscfSec.portS).append(" tpt=udp ");
                    JoanSipCapture.record("REG2 request (udp, protected)",
                            reg2Udp);
                    JoanRegTransport.UdpResult ur = JoanRegTransport
                            .sendRecvUdp(sockC, sockS, pcscf, pcscfSec.portS,
                                    reg2Bytes, REG2_TIMEOUT_MS, reg2Udp);
                    if (ur != null) {
                        r2 = ur.reply;
                        reg2Retx = ur.retx;
                        if (ur.stats != null) {
                            sb.append(ur.stats.summary("reg2"));
                        }
                    }
                    JoanSipCapture.record("REG2 response (udp, protected)", r2);
                    r2Identity = reg2Udp;
                } else {
                    /* Same message, TCP Via. Stock reuses this client
                     * for INVITE after a 200 ("TCP client is
                     * re-used"). */
                    String reg2Tcp = JoanSipBuilder.buildRegister(sip2,
                            txn, reg2Cseq, ch, res, ck, ik, pani, true);
                    byte[] tcpBytes =
                            reg2Tcp.getBytes(StandardCharsets.US_ASCII);
                    r2Identity = reg2Tcp;
                    sb.append("reg2send=").append(mine.portC).append("->")
                            .append(pcscfSec.portS).append(" tpt=tcp ");
                    try {
                        JoanSipCapture.record("REG2 request (tcp, protected)",
                                reg2Tcp);
                        JoanRegTransport.TcpResult tr = JoanRegTransport
                                .sendRecvTcp(n.network, n.local, mine.portC,
                                        pcscf, pcscfSec.portS, tcpBytes,
                                        REG2_TIMEOUT_MS, ipsec, inC, outC,
                                        reg2Tcp);
                        r2 = tr.reply;
                        tcpKeep = tr.keep;
                        JoanSipCapture.record("REG2 response (tcp, protected)",
                                r2);
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
                        /* UDP fallback on a refused/dropped connect
                         * only -- the same trigger AOSP uses in
                         * SipClientTransmissionProxy::NotifyTransportError
                         * (ERROR_CONNECTION_TIMEDOUT, ERROR_CONNECT_FAILED)
                         * and the same one stock TransmissionProxy used.
                         * AOSP gates it off by default; see
                         * JoanSipBuilder.setUdpFallbackOnTcpConnectFail
                         * for why joan gates it on. */
                        if (!JoanSipBuilder.udpFallbackOnTcpConnectFail()) {
                            return sb + "FAIL: reg2 tcp connect "
                                    + "(udp fallback off)";
                        }
                        sb.append("tpt=udp ");
                        JoanSipCapture.record(
                                "REG2 request (udp fallback, protected)",
                                reg2Udp);
                        JoanRegTransport.UdpResult ur = JoanRegTransport
                                .sendRecvUdp(sockC, sockS, pcscf,
                                        pcscfSec.portS, reg2Bytes,
                                        REG2_TIMEOUT_MS, reg2Udp);
                        if (ur != null) {
                            r2 = ur.reply;
                            reg2Retx = ur.retx;
                            if (ur.stats != null) {
                                sb.append(ur.stats.summary("reg2"));
                            }
                        }
                        JoanSipCapture.record(
                                "REG2 response (udp fallback, protected)", r2);
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
            if (p2.status >= 300 && p2.status < 400) {
                String to = JoanSipBuilder.redirectHost(r2);
                if (to != null) {
                    sb.append(" redirect_host=").append(to);
                }
            }
            if (p2.status == 423) {
                /* RFC 3261 10.2.8: the network is not refusing us, it is
                 * naming a term. Adopt it and retry rather than backing
                 * off -- a registration that never happens because the
                 * expiry was too short looks exactly like a rejection on
                 * identity from the outside, which is where this lane
                 * spent a while. */
                int min = JoanSipBuilder.minExpiresOf(r2);
                if (min > 0) {
                    JoanSipBuilder.adoptMinExpires(min, JoanSipBuilder.profileMcc(),
                            JoanSipBuilder.profileMnc());
                    sb.append(" min_expires=").append(min)
                            .append(" expires_raised");
                } else {
                    sb.append(" min_expires=absent");
                }
            }
            if (p2.status >= 300) {
                /* A rejected protected REGISTER: say WHICH identity the
                 * core refused. Warning/Reason carry the core's own text
                 * (3GPP cores put the rejecting entity in Warning), and
                 * To/Request-URI show the identity and home domain we
                 * actually asserted -- the pair needed to tell a wrong
                 * home realm apart from a rejected registration. */
                sb.append(rejectDetail(p2, r2, r2Identity));
            }
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
        final List<InetAddress> locals = new ArrayList<>();
        final List<InetAddress> pcscfs = new ArrayList<>();
        final String pcscfDiag;
        final int mtu;
        InetAddress local;
        String localHost;

        Net(Network network, String pcscfDiag, int mtu) {
            this.network = network;
            this.pcscfDiag = pcscfDiag;
            this.mtu = mtu;
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
            TelephonyManager tm0 = ctx.getSystemService(TelephonyManager.class);
            /* The registration path passes the IMS network, so a P-CSCF
             * that arrives as a name is resolved against the right
             * resolver. The diagnostic sweeps deliberately do not: a
             * trace pass must not put a DNS lookup on the wire. */
            JoanImsDiscovery.Pcscfs pcscfInfo =
                    JoanImsDiscovery.read(lp, tm0, network);
            java.util.List<java.net.InetAddress> fromCard =
                    java.util.Collections.emptyList();
            if (pcscfInfo.addresses.isEmpty()) {
                /* Third discovery source, and the last one: EF_PCSCF read
                 * off the card. AOSP orders its methods PCO, CONFIG then
                 * ISIM (AosPcscf.cpp 684-710); we have no CONFIG data, so
                 * this is the ISIM leg. It only runs when the PDN
                 * advertised nothing and the framework's own ISIM
                 * accessor gave nothing either -- which is precisely the
                 * China Unicom shape, "PDN advertised none". */
                fromCard = JoanImsDiscovery.fromCardPcscf(
                        JoanAka.readIsimFiles(tm0).pcscf, network);
            }
            if (pcscfInfo.addresses.isEmpty() && fromCard.isEmpty()) {
                continue;
            }
            int mtu;
            try {
                mtu = lp.getMtu();
            } catch (Exception e) {
                mtu = 0;
            }
            Net n = new Net(network, pcscfInfo.summary(), mtu);
            n.locals.addAll(JoanImsDiscovery.locals(lp));
            n.pcscfs.addAll(pcscfInfo.addresses);
            for (java.net.InetAddress a : fromCard) {
                if (!n.pcscfs.contains(a)) {
                    n.pcscfs.add(a);
                }
            }
            return n;
        }
        return null;
    }

    /**
     * Whether the IMS network's subscription ids include the selected one.
     * AOSP {@code NetworkCapabilities.getSubscriptionIds} returns a Set that
     * the framework only populates for NETWORK_FACTORY holders — on this
     * priv-app it can arrive EMPTY (redaction), which must never veto a
     * network reached through the subscription-scoped request; only a
     * populated, non-matching set is a mismatch.
     */
    static boolean networkSubsMatch(NetworkCapabilities nc, int sub) {
        if (sub < 0) {
            return true;
        }
        Object ids = null;
        try {
            Method m = nc.getClass().getMethod("getSubscriptionIds");
            ids = m.invoke(nc);
        } catch (Exception e) {
            return true; // API unavailable: capability check above stands
        }
        return JoanImsDiscovery.matchesSubscription(ids, sub);
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
        Read impiRead = readIsim(tm, "getIsimImpi");
        Read domainRead = readIsim(tm, "getIsimDomain");
        String impi = impiRead.value;
        String impu = firstImpu(tm);
        String domain = domainRead.value;
        String imei = hidden(tm, "getImei");
        /* Outcomes only -- never the values. An IMPI contains the IMSI. */
        JoanTrace.note("isim read impi=" + impiRead.outcome
                + " domain=" + domainRead.outcome
                + " impu=" + (impu == null || impu.isEmpty()
                        ? "absent" : "ok")
                + (impi != null && !impi.contains("@")
                        ? " impi_malformed=yes" : ""));
        if (impi == null || !impi.contains("@")) {
            /* The framework reporting no ISIM is not the same as the card
             * having none: its accessors resolve the application through
             * the modem's card status, and we have seen that path come
             * back empty on a card whose USIM plainly answers AKA. EF_DIR
             * settles it, so read ADF_ISIM directly before deriving --
             * registering with identities derived from the IMSI while the
             * card holds provisioned ones is exactly how a network comes
             * to answer "user unknown". */
            JoanAka.IsimFiles card = JoanAka.readIsimFiles(tm);
            if (card.impi != null && card.impi.contains("@")) {
                impi = card.impi;
                if (domain == null || domain.isEmpty()) {
                    domain = card.domain;
                }
                if ((impu == null || impu.isEmpty()) && !card.impu.isEmpty()) {
                    impu = card.impu.get(0);
                }
                JoanTrace.note("isim read: recovered from ADF_ISIM directly");
            }
        }
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
            /* Say which of the failures it was. "no ISIM" was asserted
             * for all of them, including the ones that are our fault. */
            JoanTrace.note("identity derived from IMSI (isim impi="
                    + impiRead.outcome + ")");
        }
        if (impi == null || !impi.contains("@")) {
            return null;
        }
        if (impu == null || impu.isEmpty()) {
            impu = impi;
        }
        String realm = (domain != null && !domain.isEmpty())
                ? domain : impi.substring(impi.indexOf('@') + 1);
        /* The realm is a network name, not a subscriber identity, so it
         * is safe to record -- and it is the one field a rejected
         * REGISTER is most often arguing about. Saying where it came from
         * separates "the card told us" from "we worked it out". */
        JoanTrace.note("ims realm=" + realm + " source="
                + (domain != null && !domain.isEmpty()
                        ? "isim" : "derived-from-imsi"));
        return new Id(impi, impu, realm, imei == null ? "" : imei);
    }

    /** Why the last PANI carried no cell id, for the state row. */
    private static volatile String sPaniCell = "not-attempted";

    static String paniCellStatus() {
        return sPaniCell;
    }

    private static String paniFor(Context ctx) {
        String access = "3GPP-E-UTRAN-FDD";
        try {
            TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
            if (tm == null) {
                sPaniCell = "no-telephony";
                return access;
            }
            int t = tm.getDataNetworkType();
            if (t == TelephonyManager.NETWORK_TYPE_NR) {
                access = "3GPP-NR-FDD";
            } else if (t == TelephonyManager.NETWORK_TYPE_IWLAN) {
                // Wi-Fi carries no 3GPP cell, and utran-cell-id-3gpp does
                // not belong on it.
                sPaniCell = "not-3gpp-access";
                return "IEEE-802.11";
            }
            return withCellId(ctx, tm, access);
        } catch (Exception e) {
            sPaniCell = "error:" + e.getClass().getSimpleName();
            return access;
        }
    }

    /**
     * Append the serving cell, or leave the access type bare and say why.
     *
     * <p>getAllCellInfo needs ACCESS_FINE_LOCATION, a runtime permission
     * that a sideloaded priv-app does not get for free. Rather than fail
     * or fabricate, this records which it was -- a tester reading
     * pani_cell=no-permission can fix it with one adb command, and
     * pani_cell=no-lte-cell is a different problem entirely.
     */
    private static String withCellId(Context ctx, TelephonyManager tm,
                                     String access) {
        if (ctx.checkSelfPermission(
                android.Manifest.permission.ACCESS_FINE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            sPaniCell = "no-permission";
            return access;
        }
        java.util.List<android.telephony.CellInfo> cells;
        try {
            cells = tm.getAllCellInfo();
        } catch (SecurityException e) {
            sPaniCell = "permission-refused";
            return access;
        }
        if (cells != null) {
            for (android.telephony.CellInfo ci : cells) {
                if (!ci.isRegistered()
                        || !(ci instanceof android.telephony.CellInfoLte)) {
                    continue;
                }
                android.telephony.CellIdentityLte id =
                        ((android.telephony.CellInfoLte) ci).getCellIdentity();
                String out = JoanAccessInfo.pani(access, id.getMccString(),
                        id.getMncString(), id.getTac(), id.getCi());
                if (!out.equals(access)) {
                    sCellSuffix = out.substring(access.length());
                    sCellAtMs = android.os.SystemClock.elapsedRealtime();
                    sPaniCell = "ok";
                    return out;
                }
                // A registered cell whose identity is unset reads as
                // Integer.MAX_VALUE, which the formatter rejects.
                sPaniCell = "cell-identity-unset";
                return access;
            }
        }
        /* Empty is ordinary, not an error: getAllCellInfo serves a cache
         * the framework rate-limits, so the first call after boot often
         * has nothing in it. AOSP's CellInfoAgent answers that by firing
         * requestCellInfoUpdate and keeping what comes back, so the NEXT
         * message has a cell -- a one-shot read would have registered
         * without one every time the cache was cold. Do the same: prime
         * it for the next REGISTER, and meanwhile reuse a recent answer
         * rather than either inventing one or going bare. */
        primeCellInfo(tm);
        String cached = recentCellSuffix();
        if (cached != null) {
            sPaniCell = "ok-cached";
            return access + cached;
        }
        sPaniCell = (cells == null || cells.isEmpty())
                ? "no-cell-info" : "no-registered-lte-cell";
        return access;
    }

    /* The last cell we could format, and when. Reused only briefly: a
     * handset moves, and a stale cell id is a false location in the one
     * header an operator reads for location. One registration interval is
     * the bound -- past that, bare is the honest answer. */
    private static volatile String sCellSuffix;
    private static volatile long sCellAtMs;
    private static final long CELL_CACHE_MS = 600000L;

    private static String recentCellSuffix() {
        String c = sCellSuffix;
        if (c == null) {
            return null;
        }
        long age = android.os.SystemClock.elapsedRealtime() - sCellAtMs;
        return (age >= 0 && age <= CELL_CACHE_MS) ? c : null;
    }

    /** Ask the framework to refresh its cell cache; the result lands later. */
    private static void primeCellInfo(TelephonyManager tm) {
        try {
            tm.requestCellInfoUpdate(Runnable::run,
                    new TelephonyManager.CellInfoCallback() {
                        @Override
                        public void onCellInfo(
                                java.util.List<android.telephony.CellInfo> ci) {
                            // Nothing to do: the point is that the next
                            // getAllCellInfo has something to return.
                        }
                    });
        } catch (Throwable ignored) {
            // Best effort; the header simply stays bare this time.
        }
    }

    @FunctionalInterface
    interface UdpSocketSource {
        DatagramSocket open() throws Exception;
    }

    static final class Reg1Result {
        final String reply, diagnostic, phase;

        Reg1Result(String reply, String diagnostic) {
            this(reply, diagnostic, null);
        }

        Reg1Result(String reply, String diagnostic, String phase) {
            this.reply = reply;
            this.diagnostic = diagnostic;
            this.phase = phase;
        }
    }

    /**
     * Own the REG1 socket and retain evidence at the caller boundary.
     * RFC 3261 17.1.4 and pjsip sip_transaction.c distinguish transport
     * errors from deadlines. Counts below describe local API results only;
     * a successful send is NOT evidence that a packet reached the P-CSCF.
     * Exception messages may contain IPs/identities and are never emitted.
     */
    static Reg1Result exchangeReg1(UdpSocketSource source, InetAddress pcscf,
                                    byte[] packet, int timeoutMs, String identity) {
        JoanRegTransport.UdpStats stats = new JoanRegTransport.UdpStats();
        String base = "reg1len=" + packet.length + " reg1_tpt=udp ";
        String phase = "setup";
        DatagramSocket socket = null;
        try {
            socket = source.open();
            phase = "send";
            JoanSipCapture.record("REG1 request (udp)",
                    new String(packet, StandardCharsets.US_ASCII));
            JoanRegTransport.UdpResult r = JoanRegTransport.sendRecvUdp(
                    socket, null, pcscf, JoanSipBuilder.pcscfSipPort(),
                    packet, timeoutMs, identity, stats);
            JoanSipCapture.record("REG1 response (udp)", r.reply);
            String result = r.reply == null
                    ? "reg1_result=timeout FAIL: reg1 no matching final"
                    : "reg1_result=final ";
            return new Reg1Result(r.reply, base + stats.summary("reg1") + result);
        } catch (Exception e) {
            if (stats.sent > 0) {
                phase = "transport";
            }
            return new Reg1Result(null, base + stats.summary("reg1")
                    + "reg1_result=" + phase + " FAIL: reg1 " + phase
                    + " error=" + e.getClass().getSimpleName());
        } finally {
            closeQuietly(socket);
        }
    }

    /**
     * Unprotected REG1 over TCP (no IPsec). RFC 3261 §18.1.1: if the
     * connection is refused or not supported, the caller may retry UDP.
     * CONNECT and SETUP are the only fallback phases; a connected
     * timeout stays fail-closed so we do not open a second transaction.
     */
    static Reg1Result exchangeReg1Tcp(Net n, InetAddress pcscf, String identity,
                                      int timeoutMs) {
        byte[] packet = identity.getBytes(StandardCharsets.US_ASCII);
        String base = "reg1len=" + packet.length + " reg1_tpt=tcp ";
        try {
            JoanSipCapture.record("REG1 request (tcp)", identity);
            JoanRegTransport.TcpResult tr = JoanRegTransport.sendRecvTcp(
                    n.network, n.local, JoanSipBuilder.REG1_PORT, pcscf,
                    JoanSipBuilder.pcscfSipPort(), packet, timeoutMs,
                    null, null, null, identity);
            JoanSipCapture.record("REG1 response (tcp)", tr.reply);
            closeQuietly(tr.keep);
            return new Reg1Result(tr.reply,
                    base + "reg1_result=final ", null);
        } catch (JoanRegTransport.TcpFail tf) {
            String err = tf.phase;
            Throwable cause = tf.getCause();
            if (cause != null) {
                err += " error=" + cause.getClass().getSimpleName();
            }
            return new Reg1Result(null, base + "reg1_result=" + tf.phase
                    + " FAIL: reg1 tcp " + err, tf.phase);
        } catch (Exception e) {
            return new Reg1Result(null, base + "reg1_result=setup"
                    + " FAIL: reg1 setup error=" + e.getClass().getSimpleName());
        }
    }

    /**
     * The Retry-After the network asked for, in ms, or 0 when it did not.
     *
     * <p>Read back out of the summary the same way
     * {@link #scheduleFamilyRetry} reads its own marker, so the driver
     * needs no new channel from the registration path.
     *
     * <p>Zero is a real answer: RFC 3261 20.33 permits
     * {@code Retry-After: 0}, meaning "try again immediately", and the
     * caller distinguishes it from "absent" by the marker being present
     * at all. Absent is what returns 0 here, and the caller's own
     * backoff covers both identically, so the distinction costs nothing
     * to collapse -- but it is collapsed deliberately rather than by
     * accident.
     */
    static long retryAfterMs(String summary) {
        if (summary == null) {
            return 0L;
        }
        int i = summary.indexOf("retry_after=");
        if (i < 0) {
            return 0L;
        }
        int v = i + "retry_after=".length();
        int e = v;
        while (e < summary.length() && summary.charAt(e) >= '0'
                && summary.charAt(e) <= '9') {
            e++;
        }
        if (e == v) {
            return 0L;
        }
        try {
            return Long.parseLong(summary.substring(v, e)) * 1000L;
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    /**
     * Compact reason trail for a non-2xx protected REGISTER. Header values
     * only -- the IMPI/IMSI never reaches the log, so the To user part is
     * reduced to its domain (the part a wrong-realm bug shows up in).
     */
    static String rejectDetail(JoanSipBuilder.Reply reply, String raw,
                               String request) {
        StringBuilder d = new StringBuilder(96);
        int ra = JoanSipBuilder.retryAfterSeconds(raw);
        if (ra >= 0) {
            d.append(" retry_after=").append(ra);
        }
        appendHdr(d, "warn", JoanSipBuilder.header(raw, "Warning"));
        appendHdr(d, "reason", JoanSipBuilder.header(raw, "Reason"));
        appendHdr(d, "to_domain", domainOf(JoanSipBuilder.header(raw, "To")));
        appendHdr(d, "req_domain", domainOf(requestLine(request)));
        return d.toString();
    }

    private static void appendHdr(StringBuilder d, String key, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        String v = value.trim();
        if (v.length() > 120) {
            v = v.substring(0, 120);
        }
        d.append(' ').append(key).append("=\"").append(v).append('"');
    }

    /** First line of a request, or null. */
    private static String requestLine(String msg) {
        if (msg == null) {
            return null;
        }
        int e = msg.indexOf("\r\n");
        return e < 0 ? msg : msg.substring(0, e);
    }

    /**
     * The domain of the first SIP URI in a header value. Everything left of
     * the "@" is an identity and is deliberately dropped.
     */
    static String domainOf(String value) {
        if (value == null) {
            return null;
        }
        int s = value.indexOf("sip:");
        if (s < 0) {
            return null;
        }
        s += 4;
        int at = value.indexOf('@', s);
        if (at >= 0) {
            s = at + 1;
        }
        int e = s;
        while (e < value.length()) {
            char c = value.charAt(e);
            if (c == '>' || c == ';' || c == ',' || c == ' ' || c == '\r') {
                break;
            }
            e++;
        }
        return e > s ? value.substring(s, e) : null;
    }

    private static DatagramSocket boundUdp(Network network, InetAddress local,
                                           int port) throws Exception {
        DatagramSocket s = new DatagramSocket(null);
        try {
            s.setReuseAddress(true);
            if (network != null) {
                network.bindSocket(s);
            }
            s.bind(new InetSocketAddress(local, port));
            return s;
        } catch (Exception e) {
            // The caller cannot close a socket the factory never returned.
            closeQuietly(s);
            throw e;
        }
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

    /**
     * The result of one ISIM read, and why it produced nothing.
     *
     * <p>{@link #hidden} returns null for every failure alike -- no such
     * method, a SecurityException, a card with no ISIM -- and the trace
     * then said "no ISIM" for all of them. That line was a claim we could
     * not support: all we knew was that we got null. On a carrier where
     * the derived identity happens to match the provisioned one nobody
     * notices; on one where it does not, the difference between "this
     * card has no ISIM" and "our read failed" is the whole diagnosis.
     */
    private static final class Read {
        final String value;
        final String outcome;

        Read(String value, String outcome) {
            this.value = value;
            this.outcome = outcome;
        }
    }

    /**
     * Read one ISIM field, recording why it failed.
     *
     * <p>Outcomes: {@code ok}, {@code absent} (the call worked and the
     * card has no such record), {@code no-api} (the platform does not
     * expose it), {@code denied} (we lack the permission), or the
     * exception's simple name.
     */
    private static Read readIsim(TelephonyManager tm, String name) {
        try {
            Method m = TelephonyManager.class.getMethod(name);
            Object v = m.invoke(tm);
            String str = v == null ? null : String.valueOf(v);
            if (str == null || str.isEmpty()) {
                return new Read(null, "absent");
            }
            return new Read(str, "ok");
        } catch (NoSuchMethodException e) {
            return new Read(null, "no-api");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof SecurityException) {
                return new Read(null, "denied");
            }
            return new Read(null,
                    c == null ? "error" : c.getClass().getSimpleName());
        } catch (Exception e) {
            return new Read(null, e.getClass().getSimpleName());
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

        /** Local API observations, NOT proof of delivery to the peer. */
        static final class UdpStats {
            int sent, sendErrors, received, provisionals, rejected, receiveErrors;
            String sendErrorType, receiveErrorType;

            String summary(String prefix) {
                String s = prefix + "_send_ok=" + sent
                        + " " + prefix + "retx=" + Math.max(0, sent - 1)
                        + " " + prefix + "_send_err=" + sendErrors
                        + " " + prefix + "_rx=" + received
                        + " " + prefix + "_1xx=" + provisionals
                        + " " + prefix + "_rejected=" + rejected
                        + " " + prefix + "_rx_err=" + receiveErrors;
                if (sendErrorType != null) {
                    s += " " + prefix + "_send_error=" + sendErrorType;
                }
                if (receiveErrorType != null) {
                    s += " " + prefix + "_rx_error=" + receiveErrorType;
                }
                return s + " ";
            }
        }

        static final class UdpResult {
            final String reply;
            final int retx;
            final UdpStats stats;

            UdpResult(String reply, UdpStats stats) {
                this.reply = reply;
                this.retx = Math.max(0, stats.sent - 1);
                this.stats = stats;
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
         * @return result with a matching final, or reply=null on deadline;
         *         statistics survive both cases. First-send errors propagate.
         */
        static UdpResult sendRecvUdp(DatagramSocket primary,
                                     DatagramSocket alt, InetAddress dest,
                                     int dport, byte[] pkt, int timeoutMs,
                                     String identity) throws Exception {
            return sendRecvUdp(primary, alt, dest, dport, pkt, timeoutMs,
                    identity, new UdpStats());
        }

        private static UdpResult sendRecvUdp(DatagramSocket primary,
                                     DatagramSocket alt, InetAddress dest,
                                     int dport, byte[] pkt, int timeoutMs,
                                     String identity, UdpStats stats) throws Exception {
            DatagramPacket out = new DatagramPacket(pkt, pkt.length, dest,
                    dport);
            try {
                primary.send(out);
                stats.sent++;
            } catch (Exception e) {
                stats.sendErrors++;
                stats.sendErrorType = e.getClass().getSimpleName();
                throw e;
            }
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
                        stats.sent++;
                    } catch (Exception e) {
                        stats.sendErrors++;
                        stats.sendErrorType = e.getClass().getSimpleName();
                        /* The first send returned successfully; preserve
                         * the existing wait/retry behavior, but no longer
                         * hide failed retries from diagnostics. */
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
                String got = tryRecv(primary, buf, slice, stats);
                if (got == null && alt != null) {
                    got = tryRecv(alt, buf, slice, stats);
                }
                if (got == null) {
                    continue;
                }
                int code = statusOf(got);
                if (code >= 100 && code < 200) {
                    stats.provisionals++;
                    continue;
                }
                if (identity == null || finalMatches(identity, got)) {
                    return new UdpResult(got, stats);
                }
                stats.rejected++;
            }
            return new UdpResult(null, stats);
        }

        /** One receive slice; null on timeout or error. */
        static String tryRecv(DatagramSocket s, byte[] buf, int timeoutMs) {
            return tryRecv(s, buf, timeoutMs, null);
        }

        private static String tryRecv(DatagramSocket s, byte[] buf, int timeoutMs,
                                      UdpStats stats) {
            try {
                s.setSoTimeout(Math.max(1, timeoutMs));
                DatagramPacket in = new DatagramPacket(buf, buf.length);
                s.receive(in);
                if (stats != null) {
                    stats.received++;
                }
                return new String(buf, 0, in.getLength(),
                        StandardCharsets.US_ASCII);
            } catch (SocketTimeoutException expected) {
                return null; // A normal poll timeout is not a socket error.
            } catch (Exception e) {
                if (stats != null) {
                    stats.receiveErrors++;
                    stats.receiveErrorType = e.getClass().getSimpleName();
                }
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
         * RFC 3261 §18.1.1 UDP retry after a size-driven TCP attempt.
         * Only a failed connection establishment (or a bind that never
         * reached the peer) is eligible. A connected timeout/send/read
         * already opened a SIP transaction and must not be retried on UDP.
         */
        static boolean fallbackUnprotectedTcp(String phase) {
            return TcpFail.CONNECT.equals(phase) || TcpFail.SETUP.equals(phase);
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
                /* Close with RST instead of FIN, so this socket never
                 * leaves a TIME_WAIT behind.
                 *
                 * SO_REUSEADDR above lets us BIND over a TIME_WAIT
                 * socket, and that is not the problem here. Under
                 * RFC 3329 sec-agree both ends of the protected
                 * connection are fixed -- our port_uc and the P-CSCF's
                 * port_ps are negotiated once and cannot be varied --
                 * so every protected TCP connection reuses the exact
                 * same 4-tuple. A previous one still in TIME_WAIT makes
                 * the next connect() fail, which is a
                 * TcpFail.CONNECT, which is what the China Mobile field
                 * trace shows before it falls back to UDP.
                 *
                 * This is LG's own remedy, read out of its binary:
                 * CMCCAoSIPSecHelper::InitIPSec does exactly one thing,
                 * SetConfig(CONFIG_I_LINGER, option) with the linger
                 * value zeroed -- id 3 in SipRtConfig.h's enum. LG scopes
                 * it to China Mobile because that is the carrier whose
                 * network drove it. joan applies it to every protected
                 * TCP socket instead, because the fixed-4-tuple problem
                 * belongs to sec-agree rather than to a carrier, and a
                 * carrier joan cannot test is a bad place to put a
                 * scope. No configuration key anywhere supplies this,
                 * so there is nothing to make switchable. */
                if (JoanSipBuilder.protectedTcpLingerReset()) {
                    sock.setSoLinger(true, 0);
                }
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
        /** The address our sockets are bound to left the IMS link. */
        static final String POKE_IMS_IP_CHANGED = "ims ip changed";

        /**
         * Routine network-presence chatter (the connectivity callback) vs
         * a genuine user/radio state poke (boot, package-replaced,
         * airplane). Routine pokes must never tear a healthy
         * registration; a lost PDN is the only routine event allowed to
         * clear one.
         */
        static boolean routinePoke(String reason) {
            return POKE_IMS_AVAILABLE.equals(reason)
                    || POKE_IMS_LOST.equals(reason)
                    || POKE_IMS_IP_CHANGED.equals(reason);
        }

        /**
         * Whether the local address we are bound to is gone from a link.
         *
         * <p>AOSP compares the whole cached address set
         * (Apn.ImsNetworkCallback.isIpChanged) and calls any difference an
         * IP change. The sharper question here is whether the ONE address
         * our sockets are actually bound to survived: a link that gains a
         * second address, or loses one we never used, changes nothing we
         * have to act on, and re-registering for it would drop a working
         * call for no reason. A link that no longer carries ours has
         * invalidated every socket and every IPsec SA at once.
         *
         * <p>An empty or unreadable address list is not treated as gone:
         * a callback that arrives mid-reconfiguration would otherwise
         * look identical to a genuine change, and the cost of being wrong
         * is a dropped call. A real loss still arrives as onLost.
         */
        static boolean localAddressGone(String[] linkAddresses,
                                        String inUse) {
            if (inUse == null || inUse.isEmpty()) {
                return false;
            }
            if (linkAddresses == null || linkAddresses.length == 0) {
                return false;
            }
            for (String a : linkAddresses) {
                if (inUse.equals(a)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Whether an IP-change poke must re-register.
         *
         * <p>Only when we believe we hold a binding: with no registration
         * there is nothing bound to the old address, and the ordinary
         * discovery path will pick the new one up on its own.
         */
        static boolean reregisterOnIpChange(String reason,
                                            boolean uaRegistered) {
            return POKE_IMS_IP_CHANGED.equals(reason) && uaRegistered;
        }

        /**
         * Whether this poke must clear the UA's registration. PDN loss
         * leaves {@code sReg} stale: the binding is gone on the network
         * while the UA still believes it is registered, so only an
         * explicit release lets the driver re-register instead of
         * sleeping until refresh.
         */
        /**
         * Whether an IMS loss should tear the registration down now.
         *
         * <p>Not while a call is up. The IMS bearer drops briefly for
         * reasons a call should survive -- a PDN re-establishment, a
         * moment of no coverage -- and releasing the UA takes the live
         * dialog with it, so a blip becomes a dropped call. adopt()
         * already refuses to disturb dialogs when re-registering; loss
         * needs the same restraint.
         *
         * <p>Deferring is not ignoring: the caller starts a grace period
         * and releases anyway if the network does not come back, which is
         * the difference between riding out a handover and pretending a
         * dead call is alive.
         */
        static boolean clearOnLost(String reason, boolean uaRegistered,
                                   boolean callActive) {
            return POKE_IMS_LOST.equals(reason) && uaRegistered && !callActive;
        }

        /** Whether a loss during a call should start the grace period. */
        static boolean deferClearForCall(String reason, boolean uaRegistered,
                                         boolean callActive) {
            return POKE_IMS_LOST.equals(reason) && uaRegistered && callActive;
        }

        /**
         * Whether a loss held for a call may now be acted on.
         *
         * <p>The condition is the CALL ending, not a clock. AOSP holds a
         * registration failure with SetHeldByCall() for as long as an IMS
         * call exists and clears it through the transaction state, never
         * on a timer -- a fixed grace period would drop a call that is
         * simply in a long tunnel, which is the opposite of the point.
         * Ending the call is call-layer work: no media and the peer's BYE
         * or the user finish it, and the loss is honoured straight after.
         *
         * <p>The timeout that remains is a backstop for the case where the
         * call state itself gets stuck, not the policy.
         */
        static boolean heldLossMayClear(long lostAtMs, long nowMs,
                                        boolean callActive) {
            if (lostAtMs <= 0) {
                return false;
            }
            return !callActive || nowMs - lostAtMs >= LOSS_HOLD_BACKSTOP_MS;
        }

        /** Backstop only; the call ending is what normally clears a hold. */
        static final long LOSS_HOLD_BACKSTOP_MS = 120000;

        /**
         * Whether to hold the first REGISTER while the SIM has not
         * published its PLMN.
         *
         * <p>The carrier profile -- User-Agent policy, sec-agree offer
         * mask, {@code algorithm} parameter, P-CSCF port -- is keyed on
         * the PLMN, and a REGISTER sent before it is known carries the
         * compiled-in defaults instead of what the network asked for. The
         * SIM reaching READY does not mean {@code getSimOperator()}
         * answers yet, so the two are separate waits.
         *
         * <p>Bounded, not absolute: a card that never publishes an
         * operator can still register off its ISIM identity, and refusing
         * forever would take that away. Past the backstop the caller goes
         * on with the 3GPP defaults applied explicitly.
         */
        static boolean holdForPlmn(long unknownSinceMs, long nowMs) {
            if (unknownSinceMs <= 0) {
                return false;
            }
            return nowMs - unknownSinceMs < PLMN_WAIT_BACKSTOP_MS;
        }

        /** How long a READY SIM may owe us a PLMN before we go on. */
        static final long PLMN_WAIT_BACKSTOP_MS = 30000;

        /**
         * Where in the P-CSCF list this attempt should start.
         *
         * <p>{@code AosPcscf::GetNextPcscfIndex} walks forward from the
         * node it used last and never returns to the front unless the
         * carrier's retry policy is the circular one; it also skips a
         * node marked unavailable. joan restarted at the first P-CSCF on
         * every cycle, so a node that fails is the one hammered hardest
         * and a second node is only ever the fallback.
         *
         * <p>Rotating the starting point keeps every node in the walk --
         * joan tries them all in a cycle, which is more thorough than
         * stopping at the end of the list -- while making the next cycle
         * begin somewhere else. A list that changes size resets it,
         * because the index no longer refers to the same node.
         */
        static int pcscfStartIndex(int cursor, int size) {
            if (size <= 0) {
                return 0;
            }
            int c = cursor % size;
            return c < 0 ? c + size : c;
        }

        /**
         * The delay before the next REGISTER attempt, from the carrier's
         * own retry policy where it has one.
         *
         * <p>The vendor configuration carries three pieces of this and
         * joan read none of them: {@code retry_interval}, an explicit
         * curve in seconds -- T-Mobile's is 120,240,480,960,1920,3840,
         * 7200 -- plus {@code retry_base_time} (30) and
         * {@code retry_max_time} (1800) for the doubling form. joan used
         * its own 60s doubling to a 15 minute ceiling, which is neither.
         *
         * <p>An explicit curve wins, clamping to its last entry once the
         * steps run out, because that is what the carrier actually asked
         * for. A base time doubles from there and is capped by the max
         * time when one is given. With neither, the caller's own backoff
         * stands, so a carrier joan holds no profile for keeps exactly
         * the behaviour it has today.
         *
         * <p>None of this outranks a Retry-After: the network naming a
         * delay is still the governing answer, handled before this is
         * reached.
         */
        static long regBackoffMs(int step, int baseSec, int maxSec,
                                 int[] intervals, long fallbackMs) {
            int n = step < 0 ? 0 : step;
            if (intervals != null && intervals.length > 0) {
                int at = n < intervals.length ? n : intervals.length - 1;
                return intervals[at] * 1000L;
            }
            if (baseSec > 0) {
                long secs = baseSec;
                for (int i = 0; i < n && secs < Integer.MAX_VALUE / 2; i++) {
                    secs *= 2;
                }
                if (maxSec > 0 && secs > maxSec) {
                    secs = maxSec;
                }
                return secs * 1000L;
            }
            return fallbackMs;
        }

        /**
         * What is left of a Retry-After the network named, in ms, or 0.
         *
         * <p>RFC 3261 10.3 and TS 24.229 both make this the governing
         * delay before the UE tries the same registration again, and both
         * reference stacks treat it that way. joan already preferred it
         * over its own exponential backoff -- but only as the length of
         * one sleep, and every poke interrupts that sleep. A network that
         * asked for ten minutes was retried in one as soon as its own PDN
         * flapped, which on a network that is refusing to register us is
         * exactly when it flaps.
         *
         * <p>A deadline rather than a duration, so that being woken
         * re-checks the wait instead of ending it.
         */
        static long retryHoldRemainingMs(long notBeforeMs, long nowMs) {
            if (notBeforeMs <= 0) {
                return 0L;
            }
            long left = notBeforeMs - nowMs;
            return left > 0 ? left : 0L;
        }

        /**
         * Whether a recorded hold governs the network we are on now.
         *
         * <p>Scoped to the PLMN that named it: a hold from one operator
         * must not delay the first REGISTER on another, and the tester
         * who swaps two SIMs in one slot is the case that proves it.
         */
        static boolean retryHoldGoverns(String heldPlmn, String nowPlmn) {
            return heldPlmn != null && heldPlmn.equals(nowPlmn);
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
                                          boolean staleAfterLoss,
                                          boolean callActive) {
            return POKE_IMS_AVAILABLE.equals(reason) && staleAfterLoss
                    && !callActive;
        }

        /**
         * Whether an availability poke is the network coming back inside
         * the grace period a live call was granted.
         *
         * <p>This is the case {@link #deferClearForCall} opened and
         * nothing ever closed. A loss during a call sets the marker and
         * deliberately tears nothing down; the network then returns a few
         * seconds later, and the right answer is to do nothing at all --
         * the call rode out the gap.
         *
         * <p>It never got the chance. {@code reacquireAfterLoss} was
         * asked first and considered only the stale flag, which the same
         * loss had just set, so the call's own grace period was answered
         * by releasing the UA underneath it: {@code releaseLocked} keeps
         * the dialog but closes both transports and the IPsec SA and
         * clears the call flag. The branch meant to catch this sat below
         * an unconditional return and could never run.
         */
        static boolean backWithinCallGrace(String reason, boolean lossHeld,
                                           boolean callActive) {
            return POKE_IMS_AVAILABLE.equals(reason) && lossHeld
                    && callActive;
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
