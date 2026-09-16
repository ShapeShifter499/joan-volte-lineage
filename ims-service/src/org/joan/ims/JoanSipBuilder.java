package org.joan.ims;

import java.security.SecureRandom;

/**
 * SIP REGISTER builder matching native {@code build_register}.
 * Host-testable: no Android imports. Never logs the message (it carries
 * IMPI).
 */
final class JoanSipBuilder {
    /* One RNG. Constructing a SecureRandom per message seeds it afresh,
     * and inDialog() runs for every ACK, BYE and PRACK. It is thread-safe. */
    private static final SecureRandom RNG = new SecureRandom();

    static final int REG1_PORT = 15060;

    /**
     * Methods this UA actually accepts. handleInbound() dispatches INVITE,
     * ACK (by correctly ignoring it), CANCEL, BYE and OPTIONS; anything
     * else is dropped. Advertising more than that invites the network to
     * send us traffic we silently discard.
     */
    static final String ALLOW = "INVITE, ACK, CANCEL, BYE, OPTIONS, REFER, SUBSCRIBE, NOTIFY, PRACK, INFO";
    static final int PCSCF_SIP_PORT = 5060;

    static final class Params {
        final long spiC;
        final long spiS;
        final int portC;
        final int portS;

        Params(long spiC, long spiS, int portC, int portS) {
            this.spiC = spiC;
            this.spiS = spiS;
            this.portC = portC;
            this.portS = portS;
        }

        static Params random(SecureRandom rng) {
            long spiC = 256L + (rng.nextInt(0x7fffffff - 256));
            long spiS = 256L + (rng.nextInt(0x7fffffff - 256));
            int base = 10000 + rng.nextInt(20000);
            return new Params(spiC, spiS, base, base + 1000);
        }
    }

    static final class Txn {
        final String callId;
        final String fromTag;
        final Params mine;
        final String cnonce;
        String branch;

        /**
         * Continue an existing REGISTER series: same Call-ID and From-tag,
         * fresh protected ports, cnonce and branch. RFC 3261 10.2 wants
         * one Call-ID for every registration a UA sends to a registrar,
         * and AOSP's ImsStack keeps it across failures too ("Do not check
         * the status code to support re-use of Call-ID and CSeq number
         * when the registration is failed", Registration.cpp).
         */
        Txn(Params mine, SecureRandom rng, String callId, String fromTag) {
            this.mine = mine;
            this.callId = callId;
            this.fromTag = fromTag;
            byte[] cn = new byte[4];
            rng.nextBytes(cn);
            this.cnonce = JoanSipCrypto.hex(cn);
            this.branch = freshBranch(rng);
        }

        Txn(Params mine, SecureRandom rng) {
            this.mine = mine;
            this.callId = String.format(
                    "%08x-%04x-%04x-%04x-%06x%04x",
                    rng.nextInt(),
                    rng.nextInt() & 0xffff,
                    rng.nextInt() & 0xffff,
                    rng.nextInt() & 0xffff,
                    rng.nextInt() & 0xffffff,
                    rng.nextInt() & 0xffff);
            this.fromTag = String.format("%012x", rng.nextLong() & 0xffffffffffffL);
            byte[] cn = new byte[4];
            rng.nextBytes(cn);
            this.cnonce = JoanSipCrypto.hex(cn);
            this.branch = freshBranch(rng);
        }

        void newBranch(SecureRandom rng) {
            this.branch = freshBranch(rng);
        }

        private static String freshBranch(SecureRandom rng) {
            return String.format("z9hG4bK%08x%08x", rng.nextInt(), rng.nextInt());
        }
    }

    static final class Id {
        final String impi;
        final String impu;
        final String realm;
        final String localIp;
        final int viaPort;
        final int contactPort;
        final String imei;

        Id(String impi, String impu, String realm, String localIp,
           int viaPort, int contactPort, String imei) {
            this.impi = impi;
            this.impu = (impu == null || impu.isEmpty()) ? impi : impu;
            this.realm = realm;
            this.localIp = localIp;
            this.viaPort = viaPort;
            this.contactPort = contactPort == 0 ? viaPort : contactPort;
            this.imei = imei == null ? "" : imei;
        }
    }

    static final class Challenge {
        final String nonceB64;
        final String algorithm;
        final String secServer;
        final String realm; /* WWW-Authenticate realm; may differ from home */
        final String qop;
        /**
         * Base64 AUTS, set only on the resynchronisation REGISTER that
         * answers a card SYNCHRONISATION FAILURE (RFC 3310 3.2). When it
         * is set there is no RES, so the digest is computed over an empty
         * password and the network replies with a fresh challenge.
         */
        final String auts;

        Challenge(String nonceB64, String algorithm, String secServer,
                  String realm, String qop, String auts) {
            this.nonceB64 = nonceB64;
            this.algorithm = algorithm;
            this.secServer = secServer;
            this.realm = realm;
            this.qop = qop;
            this.auts = auts;
        }

        Challenge(String nonceB64, String algorithm, String secServer,
                  String realm, String qop) {
            this(nonceB64, algorithm, secServer, realm, qop, null);
        }

        /** The same challenge, re-aimed as an AUTS resynchronisation. */
        Challenge resync(String autsB64) {
            return new Challenge(nonceB64, algorithm, null, realm, qop,
                    autsB64);
        }

        Challenge(String nonceB64, String algorithm, String secServer) {
            this(nonceB64, algorithm, secServer, null, "auth", null);
        }
    }

    static final class Reply {
        final int status;
        final String reason;
        final String wwwAuth;
        final String secServer;

        Reply(int status, String reason, String wwwAuth, String secServer) {
            this.status = status;
            this.reason = reason;
            this.wwwAuth = wwwAuth;
            this.secServer = secServer;
        }
    }

    private JoanSipBuilder() {}

    static String securityClientValue(Params m) {
        return JoanSecAgree.cartesianClientValue(m);
    }

    static String imeiInstance(String imei) {
        StringBuilder digits = new StringBuilder();
        if (imei != null) {
            for (int i = 0; i < imei.length() && digits.length() < 23; i++) {
                char c = imei.charAt(i);
                if (c >= '0' && c <= '9') {
                    digits.append(c);
                }
            }
        }
        if (digits.length() >= 14) {
            char last = digits.length() > 14 ? digits.charAt(14) : '0';
            return digits.substring(0, 8) + "-" + digits.substring(8, 14)
                    + "-" + last;
        }
        return "00000000-000000-0";
    }

    /**
     * TS 23.003 13.3: when the card has no ISIM, the private identity and
     * the home domain are derived from the IMSI.
     *
     *   domain = ims.mnc<MNC>.mcc<MCC>.3gppnetwork.org   (MNC padded to 3)
     *   IMPI   = <IMSI>@<domain>
     *
     * mccMnc is the SIM operator numeric: MCC (3 digits) then MNC (2 or 3).
     * Returns null rather than guessing if either input is malformed -- a
     * wrong realm produces a REGISTER that fails in a way nobody can read.
     */
    static String derivedDomain(String mccMnc) {
        if (mccMnc == null || mccMnc.length() < 5 || mccMnc.length() > 6) {
            return null;
        }
        for (int i = 0; i < mccMnc.length(); i++) {
            char c = mccMnc.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
        }
        String mcc = mccMnc.substring(0, 3);
        String mnc = mccMnc.substring(3);
        if (mnc.length() == 2) {
            mnc = "0" + mnc;
        }
        return "ims.mnc" + mnc + ".mcc" + mcc + ".3gppnetwork.org";
    }

    /** Derived IMPI, or null. The IMSI is an authenticator: never log it. */
    static String derivedImpi(String imsi, String mccMnc) {
        String domain = derivedDomain(mccMnc);
        if (domain == null || imsi == null || imsi.length() < 6) {
            return null;
        }
        for (int i = 0; i < imsi.length(); i++) {
            char c = imsi.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
        }
        return imsi + "@" + domain;
    }

    /**
     * Protected-REGISTER transport, from stock {@code libims.lge.so}
     * (read-only: {@code GetTCPCriterionLength}).
     *
     * Stock selects transport per message by SIZE, not per carrier:
     * TCP only when the message exceeds the carrier's criterion
     * (CMCC XML {@code common_tcp_criterion_len=1300}, per-reg 0 =
     * fall back to common; T-Mobile 1200; Korea/GLOBAL 4096).
     * Measured REG1 is ~1.6 kB and protected REG2 ~1.8 kB, so the
     * GLOBAL 4096 threshold still keeps both on UDP. Alpha7's blanket
     * MCC-460 forced-TCP misread this (field result: TCP connect to
     * port-s refused, silent UDP fallback after ESP SYNs had already
     * hit port-s) -- see
     * docs/lg-ims-carrier-extract-2026-09-04.md and the CMCC alpha7
     * field trace reference.
     *
     * Returns true only when the message exceeds the carrier's
     * criterion, mirroring GetTCPCriterionLength. Policy is full
     * PLMN (MCC+MNC), never MCC alone. Callers that also have a path
     * MTU must use {@link #preferTcp} so RFC 3261 §18.1.1 can still
     * select TCP under GLOBAL 4096.
     */
    static boolean preferProtectedTcp(String realm, int messageLen) {
        int criterion = tcpCriterionFor(realm);
        return criterion > 0 && messageLen > criterion;
    }

    /**
     * UDP/IP header bytes used by RFC 3261 §18.1.1's path-MTU check.
     * IPv4+UDP is 28; IPv6+UDP is 48. No IPsec ESP overhead here:
     * unprotected REG1 is the NOS failure, and TMUS stays UDP.
     */
    static int udpOverhead(boolean ipv6) {
        return ipv6 ? 48 : 28;
    }

    /**
     * Per-message TCP selection for REG1 and REG2.
     *
     * <p>Order, all fail-closed on TMUS/non-3GPP ({@code criterion <= 0}):
     * stock {@code GetTCPCriterionLength}; then, for IPv6 only, RFC 3261
     * §18.1.1 when {@code mtu > 0} ({@code sipLen + 48 + 200 > mtu});
     * then, for IPv6 with unknown MTU, RFC's 1300-byte unknown-path-MTU
     * rule. IPv4 stays on the stock criterion: Viettel REG1 already
     * answers 401 over ~1568-byte IPv4 UDP.
     *
     * <p>pjsip {@code sip_util.c} uses the same 1300-byte UDP threshold
     * ({@code PJSIP_UDP_SIZE_THRESHOLD}) when TCP switch is enabled.
     * Joan's 310-260 UDP exception remains explicit Joan policy, not
     * stock {@code AdjustTcpCriterionPerMtu}.
     */
    static boolean preferTcp(String realm, int messageLen, int mtu,
                             boolean ipv6) {
        int criterion = tcpCriterionFor(realm);
        if (criterion <= 0) {
            return false;
        }
        if (messageLen > criterion) {
            return true;
        }
        if (!ipv6) {
            return false;
        }
        if (mtu > 0) {
            return messageLen + udpOverhead(true) + 200 > mtu;
        }
        return messageLen > 1300;
    }

    /**
     * TCP criterion length from stock Ims6 XML, resolved by full PLMN.
     * CMCC 460-00 keeps 1300. Unprofiled MCC-460 (CU 460-01) and
     * unprofiled MCC-310 fall back to GLOBAL 4096. Only TMUS
     * 310-260 keeps Joan's bench-proven UDP exception (-1 / never
     * TCP) — that is Joan policy, not stock AdjustTcpCriterionPerMtu.
     */
    private static int tcpCriterionFor(String realm) {
        int mcc = plmnOf(realm);
        int mnc = mncOf(realm);
        if (mcc == -1) {
            return -1; // non-3GPP realm: never flip transport
        }
        if (mcc == 460 && mnc == 0) {
            return 1300; // CMCC common_tcp_criterion_len
        }
        if (mcc == 310 && mnc == 260) {
            return 0; // Joan TMUS UDP exception, PLMN-scoped
        }
        return 4096; // stock GLOBAL root config
    }

    /**
     * Home MCC from an IMS realm ({@code ims.mncXXX.mccYYY.3gppnetwork.org}),
     * or -1. Used only for routing; never logged.
     */
    static int plmnOf(String realm) {
        if (realm == null || realm.isEmpty()) {
            return -1;
        }
        String r = realm.toLowerCase(java.util.Locale.ROOT);
        int i = r.indexOf(".mcc");
        if (i < 0) {
            return -1;
        }
        int from = i + 4;
        int to = from;
        while (to < r.length() && r.charAt(to) >= '0' && r.charAt(to) <= '9') {
            to++;
        }
        if (to - from != 3) {
            return -1;
        }
        try {
            return Integer.parseInt(r.substring(from, to));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Home MNC from the same realm, or -1. 3-digit field, unpadded value. */
    static int mncOf(String realm) {
        if (realm == null || realm.isEmpty()) {
            return -1;
        }
        String r = realm.toLowerCase(java.util.Locale.ROOT);
        int i = r.indexOf(".mnc");
        if (i < 0) {
            return -1;
        }
        int from = i + 4;
        int to = from;
        while (to < r.length() && r.charAt(to) >= '0' && r.charAt(to) <= '9') {
            to++;
        }
        if (to - from != 3) {
            return -1;
        }
        try {
            return Integer.parseInt(r.substring(from, to));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static String bracket(String ip) {
        if (ip != null && ip.indexOf(':') >= 0) {
            return "[" + ip + "]";
        }
        return ip == null ? "" : ip;
    }

    /**
     * @param res raw RES or null for unprotected REG1
     * @param pani P-Access-Network-Info token (radio, not carrier)
     */
    static String buildRegister(Id id, Txn txn, int cseq,
                                Challenge ch, byte[] res) {
        return buildRegister(id, txn, cseq, ch, res, null, null,
                "3GPP-E-UTRAN-FDD");
    }

    static String buildRegister(Id id, Txn txn, int cseq,
                                Challenge ch, byte[] res,
                                byte[] ck, byte[] ik, String pani) {
        return buildRegister(id, txn, cseq, ch, res, ck, ik, pani, false);
    }

    static String buildRegister(Id id, Txn txn, int cseq,
                                Challenge ch, byte[] res,
                                byte[] ck, byte[] ik, String pani,
                                boolean tcp) {
        txn.newBranch(RNG);
        String publicId = id.impu;
        String aor;
        if (publicId.startsWith("tel:") || publicId.startsWith("sip:")) {
            aor = publicId;
        } else {
            aor = "sip:" + publicId;
        }
        /* Request-URI stays on the home IMS realm. Digest realm comes
         * from the 401; some cores challenge with an EPC/operator realm
         * that must not replace the REGISTER authority. */
        String requestUri = "sip:" + id.realm;
        String viaHost = bracket(id.localIp);
        String contactHost = bracket(id.localIp);
        if (pani == null || pani.isEmpty()) {
            pani = "3GPP-E-UTRAN-FDD";
        }

        String authLine;
        if (res != null && ch != null && ch.nonceB64 != null) {
            String digestRealm = (ch.realm != null && !ch.realm.isEmpty())
                    ? ch.realm : id.realm;
            String qop = (ch.qop != null && !ch.qop.isEmpty())
                    ? ch.qop : "auth";
            boolean resync = ch.auts != null && !ch.auts.isEmpty();
            /* RFC 3310 3.2: "when the AUTS is present, the included
             * response parameter is calculated using an empty password
             * (password of ""), instead of a RES". For AKAv1-MD5 the
             * password IS the RES, so an empty RES is that empty password.
             * AOSP states the same rule in ImsStack SipAuHelper.cpp. */
            byte[] digestRes = resync ? new byte[0] : res;
            String respHex = JoanSipCrypto.akaDigestResponseHex(
                    id.impi, digestRealm, "REGISTER", requestUri,
                    ch.nonceB64, digestRes, qop, "00000001", txn.cnonce,
                    ch.algorithm, ck, ik);
            authLine = "Digest username=\"" + id.impi + "\", realm=\""
                    + digestRealm + "\", nonce=\"" + ch.nonceB64
                    + "\", uri=\"" + requestUri + "\", response=\""
                    + respHex + "\", algorithm=" + ch.algorithm
                    + ", qop=" + qop + ", nc=00000001, cnonce=\""
                    + txn.cnonce + "\"";
            if (resync) {
                /* Quoted base64, matching ImsStack's STR_AUTS parameter. */
                authLine += ", auts=\"" + ch.auts + "\"";
            }
            // Stock parity (alpha12): libims.lge.so NEVER emits an
            // `integrity-protected` parameter (verified: zero occurrences
            // of the token in the 18.7 MB US998/V300L/H930DS engines;
            // HTTP_encodeAuthorization* builds the header without it).
            // T-Mobile's core accepted ours, but strict cores can reject
            // the protected REGISTER over a digest parameter they do not
            // expect, so match stock byte-shape instead.
        } else {
            authLine = "Digest username=\"" + id.impi + "\", realm=\""
                    + id.realm + "\", nonce=\"\", uri=\"" + requestUri
                    + "\", response=\"\", algorithm=AKAv1-MD5";
        }

        String cu = aor;
        if (cu.startsWith("sip:")) {
            cu = cu.substring(4);
        } else if (cu.startsWith("tel:")) {
            cu = cu.substring(4);
        }
        int at = cu.indexOf('@');
        String contactUser = at >= 0 ? cu.substring(0, at) : cu;

        StringBuilder a = new StringBuilder(1600);
        a.append("REGISTER ").append(requestUri).append(" SIP/2.0\r\n");
        a.append("Via: SIP/2.0/").append(tcp ? "TCP" : "UDP").append(' ')
                .append(viaHost).append(':')
                .append(id.viaPort).append(";branch=").append(txn.branch)
                .append(";rport\r\n");
        a.append("Max-Forwards: 70\r\n");
        a.append("From: <").append(aor).append(">;tag=")
                .append(txn.fromTag).append("\r\n");
        a.append("To: <").append(aor).append(">\r\n");
        a.append("Call-ID: ").append(txn.callId).append("\r\n");
        a.append("CSeq: ").append(cseq).append(" REGISTER\r\n");
        a.append("Contact: <sip:").append(contactUser).append('@')
                .append(contactHost).append(':').append(id.contactPort)
                .append(">;+sip.instance=\"<urn:gsma:imei:")
                .append(imeiInstance(id.imei))
                .append(">\";+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel\""
                        + ";audio\r\n");
        a.append("Expires: 600000\r\n");
        a.append("Allow: ").append(ALLOW).append("\r\n");
        a.append("Supported: path, sec-agree\r\n");
        a.append("Require: sec-agree\r\n");
        a.append("Proxy-Require: sec-agree\r\n");
        a.append("Security-Client: ").append(securityClientValue(txn.mine))
                .append("\r\n");
        a.append("P-Access-Network-Info: ").append(pani).append("\r\n");
        a.append("P-Preferred-Identity: <").append(aor).append(">\r\n");
        if (ch != null && ch.secServer != null && !ch.secServer.isEmpty()) {
            a.append("Security-Verify: ").append(ch.secServer).append("\r\n");
        }
        a.append("Authorization: ").append(authLine).append("\r\n");
        a.append("Content-Length: 0\r\n\r\n");
        return a.toString();
    }

    static Reply parseReply(String msg) {
        if (msg == null || !msg.startsWith("SIP/2.0 ")) {
            return null;
        }
        int sp = msg.indexOf(' ', 8);
        int status;
        String reason = "";
        try {
            status = Integer.parseInt(
                    (sp < 0 ? msg.substring(8) : msg.substring(8, sp)).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (sp > 0) {
            int eol = eol(msg, sp + 1);
            reason = msg.substring(sp + 1, eol).trim();
        }
        return new Reply(status, reason,
                header(msg, "WWW-Authenticate"),
                header(msg, "Security-Server"));
    }

    static String extractNonce(String wwwAuth) {
        if (wwwAuth == null) {
            return null;
        }
        String key = "nonce=\"";
        int i = indexOfIgnoreCase(wwwAuth, key);
        if (i < 0) {
            return null;
        }
        int v = i + key.length();
        int e = wwwAuth.indexOf('"', v);
        if (e < 0) {
            return null;
        }
        return wwwAuth.substring(v, e);
    }

    static String extractRealm(String wwwAuth) {
        if (wwwAuth == null) {
            return null;
        }
        String key = "realm=\"";
        int i = indexOfIgnoreCase(wwwAuth, key);
        if (i >= 0) {
            int v = i + key.length();
            int e = wwwAuth.indexOf('"', v);
            if (e > v) {
                return wwwAuth.substring(v, e);
            }
        }
        String raw = "realm=";
        i = indexOfIgnoreCase(wwwAuth, raw);
        if (i < 0) {
            return null;
        }
        int v = i + raw.length();
        int e = v;
        while (e < wwwAuth.length()) {
            char c = wwwAuth.charAt(e);
            if (c == ',' || c == ' ') {
                break;
            }
            e++;
        }
        String s = wwwAuth.substring(v, e).trim();
        return s.isEmpty() ? null : s;
    }

    static String extractQop(String wwwAuth) {
        if (wwwAuth == null) {
            return "auth";
        }
        String key = "qop=\"";
        int i = indexOfIgnoreCase(wwwAuth, key);
        if (i >= 0) {
            int v = i + key.length();
            int e = wwwAuth.indexOf('"', v);
            if (e > v) {
                String q = wwwAuth.substring(v, e).trim();
                int comma = q.indexOf(',');
                return comma < 0 ? q : q.substring(0, comma).trim();
            }
        }
        return "auth";
    }

    static String extractAlgorithm(String wwwAuth) {
        if (wwwAuth == null) {
            return "AKAv1-MD5";
        }
        String key = "algorithm=";
        int i = indexOfIgnoreCase(wwwAuth, key);
        if (i < 0) {
            return "AKAv1-MD5";
        }
        int v = i + key.length();
        if (v < wwwAuth.length() && wwwAuth.charAt(v) == '"') {
            v++;
            int e = wwwAuth.indexOf('"', v);
            if (e < 0) {
                return "AKAv1-MD5";
            }
            String q = wwwAuth.substring(v, e).trim();
            return q.isEmpty() ? "AKAv1-MD5" : q;
        }
        int e = v;
        while (e < wwwAuth.length()) {
            char c = wwwAuth.charAt(e);
            if (c == ',' || c == '"' || c == ' ') {
                break;
            }
            e++;
        }
        String raw = wwwAuth.substring(v, e).trim();
        return raw.isEmpty() ? "AKAv1-MD5" : raw;
    }

    private static String canonicalHeader(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        switch (n) {
            case "v": return "via";
            case "f": return "from";
            case "t": return "to";
            case "i": return "call-id";
            case "m": return "contact";
            case "l": return "content-length";
            case "c": return "content-type";
            case "k": return "supported";
            default: return n;
        }
    }

    static String header(String msg, String name) {
        java.util.List<String> v = headers(msg, name);
        return v.isEmpty() ? null : v.get(0);
    }

    static String parameter(String value, String name) {
        if (value == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?:^|;)\\s*" + java.util.regex.Pattern.quote(name)
                + "=([^;,\\s]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(value);
        return m.find() ? m.group(1) : "";
    }

    /** Top Via branch parameter, or "". */
    static String branchOf(String msg) {
        String via = header(msg, "Via");
        if (via == null) {
            return "";
        }
        int i = via.indexOf("branch=");
        if (i < 0) {
            return "";
        }
        int v = i + 7;
        int e = v;
        while (e < via.length() && via.charAt(e) != ';'
                && via.charAt(e) != ' ' && via.charAt(e) != '\r') {
            e++;
        }
        return via.substring(v, e);
    }

    /** Message body after the first CRLFCRLF, or "". */
    static String bodyOf(String msg) {
        int i = msg.indexOf("\r\n\r\n");
        return i < 0 ? "" : msg.substring(i + 4);
    }

    static String tagOf(String value) {
        if (value == null) return "";
        int end = value.lastIndexOf('>');
        return parameter(end < 0 ? value : value.substring(end + 1), "tag");
    }

    /** SIP transaction identity plus dialog metadata, not Call-ID alone. */
    static String transactionKey(String msg, String method) {
        String cid = header(msg, "Call-ID"), via = header(msg, "Via");
        String actual = requestMethod(msg);
        int cseq = cseqForMethod(msg, actual.isEmpty() ? method : actual);
        String branch = parameter(via, "branch");
        if (cid == null || via == null || branch.isEmpty() || cseq < 0) return null;
        String sentBy = via.split("[;,]", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
        return cid + "#" + cseq + "#" + method + "#" + sentBy + "#" + branch
                + "#" + tagOf(header(msg, "From"));
    }

    /**
     * Return the CSeq number only when this is a CSeq for {@code method}.
     * A response can be a retransmission for an older in-dialog INVITE while
     * a newer hold/resume is active, so Call-ID alone is never a sufficient
     * transaction key.
     */
    static int cseqForMethod(String msg, String method) {
        if (msg == null || method == null || method.isEmpty()) {
            return -1;
        }
        String value = header(msg, "CSeq");
        if (value == null) {
            return -1;
        }
        int i = 0;
        while (i < value.length() && value.charAt(i) <= ' ') {
            i++;
        }
        int start = i;
        while (i < value.length() && value.charAt(i) >= '0'
                && value.charAt(i) <= '9') {
            i++;
        }
        if (i == start) {
            return -1;
        }
        int cseq;
        try {
            cseq = Integer.parseInt(value.substring(start, i));
        } catch (NumberFormatException ignored) {
            return -1;
        }
        if (cseq <= 0) {
            return -1;
        }
        while (i < value.length() && value.charAt(i) <= ' ') {
            i++;
        }
        int methodStart = i;
        while (i < value.length() && value.charAt(i) > ' ') {
            i++;
        }
        return methodStart < i && value.substring(methodStart, i)
                .equalsIgnoreCase(method) ? cseq : -1;
    }

    /** Every header line with this name, in message order. */
    static java.util.List<String> headers(String msg, String name) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int i = 0;
        while (i < msg.length()) {
            int eol = eol(msg, i);
            if (eol == i) {
                break; /* blank line: headers end, body begins */
            }
            String line = msg.substring(i, eol);
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(name)) {
                out.add(line.substring(colon + 1).trim());
            }
            i = skipEol(msg, eol);
            if (i == eol) {
                break;
            }
        }
        return out;
    }

    /**
     * Seconds the registrar actually granted, or -1 when it said nothing.
     *
     * A REGISTER 200 carries the granted lifetime as an expires= parameter
     * on the returned Contact, or as an Expires header. Several contacts
     * can come back when the same IMPU is registered from more than one
     * device, so prefer the one bound to our own contact port and only
     * then fall back.
     */
    static int grantedExpiresSeconds(String reg200, int contactPort) {
        java.util.List<String> contacts = headers(reg200, "Contact");
        String portMark = ":" + contactPort;
        int fallback = -1;
        for (String c : contacts) {
            int e = expiresParam(c);
            if (e < 0) {
                continue;
            }
            int at = c.indexOf(portMark);
            if (at >= 0) {
                char after = at + portMark.length() < c.length()
                        ? c.charAt(at + portMark.length()) : '>';
                if (after < '0' || after > '9') {
                    return e;
                }
            }
            if (fallback < 0) {
                fallback = e;
            }
        }
        if (fallback >= 0) {
            return fallback;
        }
        String h = header(reg200, "Expires");
        if (h != null) {
            try {
                return Integer.parseInt(h.trim());
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * How long to wait before refreshing a registration the registrar
     * granted for {@code grantedSec}.
     *
     * The lifetime is the registrar's to choose and carriers differ widely
     * -- this handset's core grants 3600s against the 600000s we ask for,
     * and others use 600 or 1800. So aim at 80% of whatever came back,
     * capped so a very long grant is still re-validated periodically, and
     * rate-limited so a very short one cannot spin.
     *
     * The rate limit must never win outright: a floor applied on top of a
     * short grant would schedule the refresh at or after expiry, which is
     * the failure it was supposed to prevent. Clamp it to half the grant.
     *
     * @param grantedSec seconds granted, or <= 0 when the registrar said
     *                   nothing, in which case the cap is used
     */
    static long refreshLeadMs(int grantedSec, long capMs, long floorMs) {
        if (grantedSec <= 0) {
            return capMs;
        }
        long grantMs = grantedSec * 1000L;
        long lead = Math.min(grantMs / 5 * 4, capMs);
        long floor = Math.min(floorMs, grantMs / 2);
        return Math.max(lead, floor);
    }

    private static int expiresParam(String contact) {
        int i = indexOfIgnoreCase(contact, "expires=");
        if (i < 0) {
            return -1;
        }
        int v = i + "expires=".length();
        int e = v;
        while (e < contact.length()) {
            char c = contact.charAt(e);
            if (c < '0' || c > '9') {
                break;
            }
            e++;
        }
        if (e == v) {
            return -1;
        }
        try {
            return Integer.parseInt(contact.substring(v, e));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static int eol(String msg, int from) {
        int r = msg.indexOf('\r', from);
        int n = msg.indexOf('\n', from);
        if (r < 0) {
            return n < 0 ? msg.length() : n;
        }
        if (n < 0) {
            return r;
        }
        return Math.min(r, n);
    }

    private static int skipEol(String msg, int eol) {
        int i = eol;
        if (i < msg.length() && msg.charAt(i) == '\r') {
            i++;
        }
        if (i < msg.length() && msg.charAt(i) == '\n') {
            i++;
        }
        return i;
    }

    static final class Dialog {
        String callId;
        String fromTag;
        String branch;
        int cseq = 1;
        /** Peer's CSeq space; never compared to {@link #cseq}. */
        int remoteCseq;
        String remoteTag;
    }

    /**
     * Small, bounded archive of INVITE final-response ACKs. An ACK to a 2xx
     * is an in-dialog request; an ACK to a non-2xx is part of the INVITE
     * transaction. Both must be retransmitted byte-for-byte for a repeated
     * final response, but their Via-branch rules differ. Keeping the exact
     * wire message avoids rebuilding an old ACK from a dialog whose CSeq has
     * already advanced for a later hold or resume.
     */
    static final class InviteAckArchive {
        private static final int MAX_RECORDS = 16;
        /* Timer G/H are at most 64*T1 (normally 32 seconds); retain twice
         * that without turning a long-running call into an unbounded map. */
        private static final long RETAIN_MS = 64_000L;

        private static final class Record {
            final String callId;
            final int cseq;
            long untilMs;
            String ack2xx;
            String ackNon2xx;
            /* Snapshot of the dialog and routing taken when the INVITE
             * went out. A final response can arrive after the waiting
             * transaction has given up (slow SBC chain): the ACK must
             * then be built from THIS state, not from the live dialog
             * that later hold/resume requests have advanced. */
            final Dialog sendDlg;
            final String toHdr;
            final String fromHdr;
            final String target;
            final String route;

            Record(String callId, int cseq, long untilMs, Dialog sendDlg,
                   String toHdr, String fromHdr, String target, String route) {
                this.callId = callId;
                this.cseq = cseq;
                this.untilMs = untilMs;
                this.sendDlg = sendDlg;
                this.toHdr = toHdr;
                this.fromHdr = fromHdr;
                this.target = target;
                this.route = route;
            }
        }

        private final java.util.LinkedHashMap<String, Record> records =
                new java.util.LinkedHashMap<>(MAX_RECORDS, 0.75f, true);

        synchronized void begin(String callId, int cseq, Dialog sendDlg,
                                String toHdr, String fromHdr, String target,
                                String route) {
            if (callId == null || callId.isEmpty() || cseq <= 0) {
                return;
            }
            long now = System.currentTimeMillis();
            purge(now);
            Dialog d = new Dialog();
            if (sendDlg != null) {
                d.callId = sendDlg.callId;
                d.fromTag = sendDlg.fromTag;
                d.branch = sendDlg.branch;
                d.cseq = sendDlg.cseq;
            }
            records.put(key(callId, cseq), new Record(callId, cseq,
                    now + RETAIN_MS, d, toHdr, fromHdr, target, route));
            while (records.size() > MAX_RECORDS) {
                java.util.Iterator<String> it = records.keySet().iterator();
                it.next();
                it.remove();
            }
        }

        /** ACK for a late 2xx of a known INVITE: built once from the
         * send-time snapshot (response headers fill any gaps, e.g. the
         * initial INVITE that had no To-tag yet), then resent verbatim. */
        synchronized String ackLate2xx(Id id, String callId, int cseq,
                                       String secVerify, String rx) {
            Record r = get(callId, cseq);
            if (r == null || id == null) {
                return null;
            }
            if (r.ack2xx != null) {
                return r.ack2xx;
            }
            /* Established dialog: the send-time snapshot is authoritative
             * (re-INVITEs). Initial INVITE: the dialog did not exist yet,
             * so To/From/Contact come from the late response itself. */
            boolean established = r.toHdr != null && !r.toHdr.isEmpty();
            String to = established ? r.toHdr : rxFirst(r.toHdr, rx, "To");
            String from = established ? r.fromHdr
                    : rxFirst(r.fromHdr, rx, "From");
            String tgt = established ? r.target
                    : rxFirst(r.target, rx, "Contact");
            if (tgt == null || tgt.isEmpty()) {
                return null;
            }
            String ack = buildAck2xx(id, r.sendDlg, tgt,
                    r.route != null && !r.route.isEmpty() ? r.route : null,
                    secVerify, to, from, cseq);
            if (ack == null) {
                return null;
            }
            r.ack2xx = ack;
            r.untilMs = System.currentTimeMillis() + RETAIN_MS;
            return ack;
        }

        /** ACK for a late non-2xx final: transaction-scoped, so it must
         * reuse the INVITE's own Via branch (RFC 3261 17.1.1.3). */
        synchronized String ackLateNon2xx(Id id, String callId, int cseq,
                                          String secVerify, String rx) {
            Record r = get(callId, cseq);
            if (r == null || id == null) {
                return null;
            }
            if (r.ackNon2xx != null) {
                return r.ackNon2xx;
            }
            boolean established = r.toHdr != null && !r.toHdr.isEmpty();
            String to = established ? r.toHdr : rxFirst(r.toHdr, rx, "To");
            String from = established ? r.fromHdr
                    : rxFirst(r.fromHdr, rx, "From");
            String tgt = established ? r.target
                    : rxFirst(r.target, rx, "Contact");
            if (tgt == null || tgt.isEmpty()) {
                return null;
            }
            String ack = buildAckNon2xx(id, r.sendDlg, tgt,
                    r.route != null && !r.route.isEmpty() ? r.route : null,
                    secVerify, to, from, cseq, r.sendDlg.branch);
            if (ack == null) {
                return null;
            }
            r.ackNon2xx = ack;
            r.untilMs = System.currentTimeMillis() + RETAIN_MS;
            return ack;
        }

        /** Response header first, snapshot as fallback: before the dialog
         * exists (initial INVITE), only the response knows To/From/Contact. */
        private String rxFirst(String snap, String rx, String hdr) {
            if (rx != null) {
                String v = header(rx, hdr);
                if (v != null) {
                    return hdr.equals("Contact") ? contactUri(v) : v;
                }
            }
            return snap;
        }

        synchronized void remember2xx(String callId, int cseq, String ack) {
            remember(callId, cseq, ack, true);
        }

        synchronized void rememberNon2xx(String callId, int cseq, String ack) {
            remember(callId, cseq, ack, false);
        }

        synchronized String ack2xx(String callId, int cseq) {
            Record r = get(callId, cseq);
            return r == null ? null : r.ack2xx;
        }

        synchronized String ackNon2xx(String callId, int cseq) {
            Record r = get(callId, cseq);
            return r == null ? null : r.ackNon2xx;
        }

        synchronized void clear() {
            records.clear();
        }

        private void remember(String callId, int cseq, String ack,
                              boolean response2xx) {
            if (ack == null || ack.isEmpty()) {
                return;
            }
            long now = System.currentTimeMillis();
            Record r = get(callId, cseq);
            if (r == null) {
                return;
            }
            r.untilMs = now + RETAIN_MS;
            if (response2xx) {
                r.ack2xx = ack;
            } else {
                r.ackNon2xx = ack;
            }
        }

        private Record get(String callId, int cseq) {
            if (callId == null || callId.isEmpty() || cseq <= 0) {
                return null;
            }
            purge(System.currentTimeMillis());
            return records.get(key(callId, cseq));
        }

        private void purge(long now) {
            java.util.Iterator<java.util.Map.Entry<String, Record>> it =
                    records.entrySet().iterator();
            while (it.hasNext()) {
                if (it.next().getValue().untilMs <= now) {
                    it.remove();
                }
            }
        }

        private static String key(String callId, int cseq) {
            return callId + '\u0000' + cseq;
        }
    }

    /** Transport of in-dialog requests; mirrors the UA's reply socket.
     * Set by JoanSipUa before sends (sendReply / TCP peer adopt). */
    /**
     * Transport token for the top Via of an outbound REQUEST.
     *
     * <p>Always UDP: {@code JoanSipUa} transmits every outbound request on
     * its protected UDP client socket ({@code sSockC}). An inbound TCP
     * connection from the P-CSCF carries MT requests and their responses
     * only -- it is never used to send a request -- so it must not change
     * what our Via claims. Claiming TCP on a datagram we sent over UDP is
     * a transport mismatch the P-CSCF answers with 400 Bad Request, and it
     * breaks every MO call for the life of the UA once one MT call has
     * arrived (Viettel, alpha20).
     *
     * <p>The receiving side of this rule is written out in AOSP's IMS
     * stack, which is what a P-CSCF does to us:
     * {@code SipServerTransport::ValidateViaHeader()} -- "If the topmost
     * Via header has scheme SIP/2.0/TCP, but actually came on UDP, (or
     * vice versa) flag off error. Application SHOULD respond to this with
     * 400 Bad Request." See RFC 3261 18.1.1 and
     * packages/modules/ImsStack native/libimsstack/engine/sipcore/
     * SipServerTransport.cpp at tag android-17.0.0_r1. Referenced only --
     * no AOSP code is used here.
     */
    static String requestViaTransport() {
        return "UDP";
    }

    /**
     * Re-aim the top Via sent-protocol of a REQUEST at TCP, for the one
     * path that does not use the UDP client socket: {@code sendReply()}
     * writes to the P-CSCF's accepted TCP connection when it has one, and
     * it carries in-dialog requests (ACK, BYE, re-INVITE, SUBSCRIBE,
     * REFER) as well as responses.
     *
     * <p>This is done here, at the write, rather than at each builder,
     * because the transport is only known to the code performing the send:
     * {@code inDialog()} output leaves over UDP for PRACK and over TCP for
     * a BYE, from the same builder. Deciding it at the choke point is what
     * makes it impossible for a new call site to get it wrong. AOSP fixes
     * the same field at the same kind of choke point on the receiving side
     * (ImsStack {@code SipStack::UpdateSentProtocol} from
     * {@code SipServerTransport::ValidateViaHeader}).
     *
     * <p>Responses are returned untouched: RFC 3261 8.2.6.2 requires a
     * response to echo the request's Via headers verbatim, so rewriting
     * one would misroute it.
     */
    static String retargetRequestViaToTcp(String msg) {
        if (msg == null || msg.startsWith("SIP/2.0 ")) {
            return msg; // a response: its Via belongs to the request
        }
        int i = msg.indexOf("SIP/2.0/UDP");
        if (i < 0) {
            return msg;
        }
        int hdrEnd = msg.indexOf("\r\n\r\n");
        if (hdrEnd >= 0 && i > hdrEnd) {
            return msg; // only in the body; not a Via
        }
        return msg.substring(0, i) + "SIP/2.0/TCP"
                + msg.substring(i + "SIP/2.0/UDP".length());
    }

    static final class Media {
        String ip;
        int port;
        boolean mux;
        int rtcpPort;
        /** First payload type on m=audio: the selected one in an answer. */
        int payloadType = -1;
        /** Whether payload type 0 (PCMU) appears at all: offers list many. */
        boolean offersPcmu;
        /** rtpmap encoding name for payloadType, e.g. "AMR-WB". */
        String codecName = "";
        /**
         * Every payload type on m=audio, in the order offered. The order is
         * the offerer's preference, and answering respects it instead of
         * imposing ours.
         */
        final java.util.List<Codec> codecs = new java.util.ArrayList<>();

        Codec codec(int pt) {
            for (Codec c : codecs) {
                if (c.pt == pt) {
                    return c;
                }
            }
            return null;
        }
    }

    /** One payload type from an m=audio line, with its rtpmap and fmtp. */
    static final class Codec {
        final int pt;
        String name = "";
        int rate;
        String fmtp = "";

        Codec(int pt) {
            this.pt = pt;
            if (pt == 0) {
                /* PCMU is statically assigned (RFC 3551): an offer may name
                 * it on m=audio and never emit an a=rtpmap for it. */
                name = "PCMU";
                rate = 8000;
            }
        }

        boolean is(String enc, int hz) {
            return name.equalsIgnoreCase(enc) && rate == hz;
        }

        /**
         * AMR is only usable if the offer asked for octet-aligned framing.
         * JoanAmr does not implement the bandwidth-efficient packing, and
         * its absence means bandwidth-efficient (RFC 4867 3.6), so an AMR
         * entry without it must be skipped rather than silently accepted.
         */
        boolean amrOctetAligned() {
            return fmtp.replace(" ", "").contains("octet-align=1");
        }
    }

    /**
     * One codec this stack can actually carry.
     *
     * <p>{@link #CAPABILITIES} is the single source of truth for that, and
     * both directions read it: {@link #sdpMedia} renders it as our offer,
     * and {@link #selectAnswerCodec} filters an incoming offer against it.
     * They used to be separate -- a hardcoded offer string and a hardcoded
     * PCMU answer -- and drifted, which is how answered calls ended up
     * ignoring the negotiation entirely. AOSP keeps one local profile and
     * feeds it to both sides for the same reason (AudioProfileGenerator
     * builds it, AudioProfileNegotiator matches against it).
     */
    static final class Capability {
        final String name;
        final int rate;
        /** Payload type we use when OFFERING; an answer echoes theirs. */
        final int offerPt;
        final String fmtp;
        /** AMR without octet-aligned framing is not implemented. */
        final boolean needsOctetAlign;

        Capability(String name, int rate, int offerPt, String fmtp,
                   boolean needsOctetAlign) {
            this.name = name;
            this.rate = rate;
            this.offerPt = offerPt;
            this.fmtp = fmtp;
            this.needsOctetAlign = needsOctetAlign;
        }

        /** TRUE for AMR-WB, FALSE for AMR-NB, null for anything else. */
        Boolean amrWideband() {
            if ("AMR-WB".equalsIgnoreCase(name)) {
                return Boolean.TRUE;
            }
            return "AMR".equalsIgnoreCase(name) ? Boolean.FALSE : null;
        }
    }

    /**
     * What we can carry, in our own preference order -- which is the order
     * we offer. An offerer's order wins when we answer.
     *
     * <p>AMR-NB is the codec IR.92 makes mandatory for VoLTE (it is entry 1
     * in AOSP's own codec enum); AMR-WB is the wideband tier above it.
     * G.711 is not in the VoLTE profile at all and is kept last as the
     * interoperability floor.
     */
    static final java.util.List<Capability> CAPABILITIES =
            java.util.Collections.unmodifiableList(java.util.Arrays.asList(
                    new Capability("AMR-WB", 16000, 96,
                            "octet-align=1;mode-change-capability=2", true),
                    new Capability("AMR", 8000, 97,
                            "octet-align=1;mode-change-capability=2", true),
                    new Capability("PCMU", 8000, 0, "", false)));

    /**
     * The capabilities actually usable on THIS build, which is what both
     * the offer and the answer read. {@link #CAPABILITIES} is what the code
     * implements; this is what the ROM can really run.
     *
     * <p>AMR goes through MediaCodec, so its availability is a property of
     * the ROM, not of this app. Offering a codec the device cannot open is
     * worse than not offering it: the carrier selects it, the encoder fails
     * to open, the media layer falls back to PCMU, and the peer carries on
     * sending AMR -- a connected call with no audio and nothing in the log
     * to explain it. A ROM that drops or adds a codec is handled by
     * re-probing rather than by editing this file.
     */
    private static volatile java.util.List<Capability> sProfile = CAPABILITIES;

    static java.util.List<Capability> profile() {
        return sProfile;
    }

    /**
     * Narrow the profile to the encoding names the device can encode AND
     * decode. PCMU is always kept: it is implemented in this process
     * (JoanMedia's u-law tables), needs no platform codec, and dropping
     * every capability would leave a UA that can neither call nor answer.
     */
    static void restrictProfile(java.util.Collection<String> availableNames) {
        java.util.List<Capability> keep = new java.util.ArrayList<>();
        for (Capability c : CAPABILITIES) {
            if (c.amrWideband() == null || availableNames.contains(c.name)) {
                keep.add(c);
            }
        }
        sProfile = java.util.Collections.unmodifiableList(keep);
    }

    /** Codec names in the active profile, for the trace. */
    static String profileSummary() {
        StringBuilder b = new StringBuilder();
        for (Capability c : sProfile) {
            if (b.length() > 0) {
                b.append(',');
            }
            b.append(c.name);
        }
        return b.toString();
    }

    /** The capability matching a codec's encoding name and rate, or null. */
    static Capability capabilityFor(Codec c) {
        if (c == null) {
            return null;
        }
        for (Capability cap : sProfile) {
            if (c.is(cap.name, cap.rate)) {
                return cap;
            }
        }
        return null;
    }

    /** Wideband flag for the media layer, from the one profile. */
    static Boolean amrWideband(Codec c) {
        Capability cap = capabilityFor(c);
        return cap == null ? null : cap.amrWideband();
    }

    /**
     * The first payload type in the OFFERER's order that we can carry.
     * Returns null when none is usable, which is the only honest reason to
     * decline the call.
     */
    static Codec selectAnswerCodec(Media offer) {
        if (offer == null) {
            return null;
        }
        for (Codec c : offer.codecs) {
            Capability cap = capabilityFor(c);
            if (cap == null) {
                continue;
            }
            if (cap.needsOctetAlign && !c.amrOctetAligned()) {
                continue;
            }
            return c;
        }
        return null;
    }

    static String sdpOffer(String ip, int rtpPort) {
        return sdpMedia(ip, rtpPort, "sendrecv");
    }

    /** Hold is a=sendonly (RFC 3264). Resume is a=sendrecv. */
    static String sdpHold(String ip, int rtpPort, boolean held) {
        return sdpMedia(ip, rtpPort, held ? "sendonly" : "sendrecv");
    }

    /** m=audio plus rtpmap/fmtp for every capability, in offer order. */
    private static String offerMediaLines(int rtpPort) {
        StringBuilder m = new StringBuilder("m=audio ").append(rtpPort)
                .append(" RTP/AVP");
        StringBuilder attrs = new StringBuilder();
        for (Capability c : sProfile) {
            m.append(' ').append(c.offerPt);
            attrs.append("a=rtpmap:").append(c.offerPt).append(' ')
                    .append(c.name).append('/').append(c.rate);
            if (c.amrWideband() != null) {
                attrs.append("/1");
            }
            attrs.append("\r\n");
            if (!c.fmtp.isEmpty()) {
                attrs.append("a=fmtp:").append(c.offerPt).append(' ')
                        .append(c.fmtp).append("\r\n");
            }
        }
        return m.append("\r\n").append(attrs).toString();
    }

    private static String sdpMedia(String ip, int rtpPort, String direction) {
        boolean v6 = ip != null && ip.indexOf(':') >= 0;
        String fam = v6 ? "IP6" : "IP4";
        long sess = System.currentTimeMillis() / 1000;
        return "v=0\r\n"
                + "o=- " + sess + " 1 IN " + fam + " " + ip + "\r\n"
                + "s=-\r\n"
                + "c=IN " + fam + " " + ip + "\r\n"
                + "t=0 0\r\n"
                /* Rendered from CAPABILITIES so the offer cannot disagree
                 * with what we will accept in an answer. octet-align=1 is
                 * stated, never left implicit: its absence means
                 * bandwidth-efficient (RFC 4867 3.6), which is not
                 * implemented. */
                + offerMediaLines(rtpPort)
                + "a=ptime:20\r\n"
                + "a=maxptime:240\r\n"
                + "a=rtcp:" + (rtpPort + 1) + "\r\n"
                + "a=rtcp-mux\r\n"
                + "a=" + direction + "\r\n";
    }

    /**
     * Answer an offer with the codec {@link #selectAnswerCodec} chose.
     *
     * <p>The answer echoes the OFFER's payload number: AMR is dynamic, so
     * the offerer owns that number and it is rarely the 96 we use in our
     * own offer. Answering with our number instead is a silent no-audio
     * bug -- the far end sends what it named, and we decode something
     * else.
     *
     * <p>AOSP's ImsStack negotiates the same way, walking the peer's
     * payload list in the peer's order (AudioProfileNegotiator.cpp), and
     * always emits octet-align when it is 1 rather than leaving it
     * implicit. We do not negotiate mode-set, which it does.
     */
    static String sdpAnswer(String ip, int rtpPort, Media offer, Codec chosen) {
        boolean v6 = ip != null && ip.indexOf(':') >= 0;
        String fam = v6 ? "IP6" : "IP4";
        long sess = System.currentTimeMillis() / 1000;
        boolean mux = offer == null || offer.mux;
        Capability cap = capabilityFor(chosen);
        int pt = cap == null ? 0 : chosen.pt;
        String rtpmap;
        String fmtp = "";
        if (cap == null) {
            rtpmap = "a=rtpmap:0 PCMU/8000\r\n";
        } else {
            rtpmap = "a=rtpmap:" + pt + ' ' + cap.name + '/' + cap.rate
                    + (cap.amrWideband() != null ? "/1" : "") + "\r\n";
            if (cap.needsOctetAlign) {
                fmtp = "a=fmtp:" + pt + " octet-align=1\r\n";
            }
        }
        return "v=0\r\n"
                + "o=- " + sess + " 1 IN " + fam + " " + ip + "\r\n"
                + "s=-\r\n"
                + "c=IN " + fam + " " + ip + "\r\n"
                + "t=0 0\r\n"
                + "m=audio " + rtpPort + " RTP/AVP " + pt + "\r\n"
                + rtpmap
                + fmtp
                + "a=ptime:20\r\n"
                + "a=rtcp:" + (rtpPort + 1) + "\r\n"
                + (mux ? "a=rtcp-mux\r\n" : "")
                + "a=sendrecv\r\n";
    }

    /** Parse, select and answer in one step. */
    static String sdpAnswer(String ip, int rtpPort, String offer) {
        Media m = offer == null ? null : parseSdp(offer);
        return sdpAnswer(ip, rtpPort, m, selectAnswerCodec(m));
    }

    /** Pull one SIP message off a TCP accumulator. */
    static String extractOne(StringBuilder acc) {
        String s = acc.toString();
        int sep = s.indexOf("\r\n\r\n");
        if (sep < 0) {
            return null;
        }
        int cl = 0;
        String clh = header(s, "Content-Length");
        if (clh != null) {
            try {
                int sp = clh.indexOf(' ');
                cl = Integer.parseInt((sp < 0 ? clh : clh.substring(0, sp)).trim());
            } catch (NumberFormatException e) {
                cl = 0;
            }
        }
        int total = sep + 4 + cl;
        if (s.length() < total) {
            return null;
        }
        String msg = s.substring(0, total);
        acc.delete(0, total);
        return msg;
    }

    static String buildInvite(Id id, Dialog dlg, String dest, String route,
                              String secVerify, int rtpPort, String pani) {
        return buildInvite(id, dlg, dest, route, secVerify, rtpPort, pani,
                true);
    }

    /**
     * @param requireSecAgree emit Require/Proxy-Require: sec-agree.
     *   sec-agree is hop-by-hop between UE and P-CSCF, but Proxy-Require is
     *   examined by every proxy on the path, so a downstream one that does
     *   not implement it answers 420 Bad Extension. The P-CSCF is supposed
     *   to strip the option tags before forwarding; not all do. RFC 3329's
     *   model is to negotiate once on REGISTER and carry Security-Verify
     *   afterwards, which is what the in-dialog requests here already do.
     *   Sent by default because the C UA did and it works on the one core
     *   this was built against; invite() drops it and retries on a 420
     *   rather than guessing which reading a given network takes.
     */
    static String buildInvite(Id id, Dialog dlg, String dest, String route,
                              String secVerify, int rtpPort, String pani,
                              boolean requireSecAgree) {
        if (id.impu == null || id.impu.isEmpty()) {
            return null;
        }
        String aor = aorOf(id.impu);
        String host = bracket(id.localIp);
        SecureRandom rng = RNG;
        dlg.branch = String.format("z9hG4bK%08x%08x", rng.nextInt(), rng.nextInt());
        dlg.callId = String.format("%08x-%04x-%04x-%04x-%06x%04x",
                rng.nextInt(), rng.nextInt() & 0xffff, rng.nextInt() & 0xffff,
                rng.nextInt() & 0xffff, rng.nextInt() & 0xffffff,
                rng.nextInt() & 0xffff);
        dlg.fromTag = String.format("%012x", rng.nextLong() & 0xffffffffffffL);
        dlg.cseq = 1;
        String sdp = sdpOffer(id.localIp, rtpPort);
        String contactUser = contactUser(aor);
        if (pani == null || pani.isEmpty()) {
            pani = "3GPP-E-UTRAN-FDD";
        }
        StringBuilder a = new StringBuilder(1800);
        a.append("INVITE ").append(dest).append(" SIP/2.0\r\n");
        a.append("Via: SIP/2.0/").append(requestViaTransport()).append(' ').append(host).append(':')
                .append(id.viaPort).append(";branch=").append(dlg.branch)
                .append(";rport\r\n");
        a.append("Max-Forwards: 70\r\n");
        if (route != null && !route.isEmpty()) {
            a.append("Route: ").append(route).append("\r\n");
        }
        a.append("From: <").append(aor).append(">;tag=")
                .append(dlg.fromTag).append("\r\n");
        a.append("To: <").append(dest).append(">\r\n");
        a.append("Call-ID: ").append(dlg.callId).append("\r\n");
        a.append("CSeq: ").append(dlg.cseq).append(" INVITE\r\n");
        a.append("Contact: <sip:").append(contactUser).append('@')
                .append(host).append(':').append(id.contactPort)
                .append(">;+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel\";audio\r\n");
        a.append("P-Preferred-Identity: <").append(aor).append(">\r\n");
        a.append("P-Access-Network-Info: ").append(pani).append("\r\n");
        a.append("Allow: ").append(ALLOW).append("\r\n");
        if (requireSecAgree) {
            a.append("Require: sec-agree\r\n");
            a.append("Proxy-Require: sec-agree\r\n");
        }
        if (secVerify != null && !secVerify.isEmpty()) {
            a.append("Security-Verify: ").append(secVerify).append("\r\n");
        }
        a.append("Accept-Contact: *;+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel\"\r\n");
        a.append("Content-Type: application/sdp\r\n");
        a.append("Content-Length: ").append(sdp.length()).append("\r\n\r\n");
        a.append(sdp);
        return a.toString();
    }

    /**
     * ACK a 2xx to INVITE. RFC 3261 §13.2.2.4 makes this a new in-dialog
     * request: it keeps the INVITE CSeq but gets a fresh Via branch.
     */
    static String buildAck2xx(Id id, Dialog dlg, String target, String route,
                              String secVerify, String toHdr, String fromHdr,
                              int inviteCseq) {
        return inDialog("ACK", id, dlg, target, route, secVerify,
                toHdr, fromHdr, inviteCseq, null, null, null);
    }

    /**
     * ACK a non-2xx to INVITE. RFC 3261 §17.1.1.3 keeps this inside the
     * INVITE transaction, so it must use that INVITE's Via branch exactly.
     */
    static String buildAckNon2xx(Id id, Dialog dlg, String target, String route,
                                 String secVerify, String toHdr, String fromHdr,
                                 int inviteCseq, String inviteBranch) {
        if (inviteBranch == null || inviteBranch.isEmpty()) {
            return null;
        }
        return inDialog("ACK", id, dlg, target, route, secVerify,
                toHdr, fromHdr, inviteCseq, null, null, inviteBranch);
    }

    static String buildBye(Id id, Dialog dlg, String target, String route,
                           String secVerify, String toHdr, String fromHdr) {
        return inDialog("BYE", id, dlg, target, route, secVerify,
                toHdr, fromHdr, dlg.cseq + 1, null);
    }

    /**
     * Out-of-dialog SUBSCRIBE to the conference event package, exactly
     * the request stock's Conference::SubscribeConferenceState builds:
     * Event: conference, Accept: application/conference-info+xml,
     * Supported: replaces, Expires: 21600 (stock hardcodes all four).
     * The conference focus URI (conference factory URI or the carrier's
     * tConfURI) is the Request-URI.
     */
    static String buildConfSubscribe(Id id, Dialog dlg, String focusUri,
                                     String route, String secVerify,
                                     int expires) {
        dlg.cseq++;
        String contactUser = contactUser(aorOf(id.impu != null
                && !id.impu.isEmpty() ? id.impu : id.impi));
        String extra = "Contact: <sip:" + contactUser
                + "@" + bracket(id.localIp) + ":" + id.contactPort + ">\r\n"
                + "Event: conference\r\n"
                + "Accept: application/conference-info+xml\r\n"
                + "Supported: replaces\r\n"
                + "Expires: " + expires + "\r\n"
                + "Allow: " + ALLOW + "\r\n";
        return inDialog("SUBSCRIBE", id, dlg, focusUri, route, secVerify,
                "<" + focusUri + ">", null, dlg.cseq, extra);
    }

    /**
     * In-dialog REFER that moves an existing call leg into the
     * conference (RFC 3515 + 5589 Replaces usage). referTo points at
     * the conference focus with a Replaces header naming this dialog,
     * so the focus replaces the leg instead of creating a second one.
     * Referred-By identifies us (RFC 3892) as stock's IsReferredBy
     * knob allows. referSub=false asks the far end to skip the
     * implicit subscription (RFC 4488) — stock LG sends it when the
     * carrier profile's bReferSub is set.
     */
    static String buildReferConf(Id id, Dialog dlg, String target,
                                 String route, String secVerify,
                                 String toHdr, String fromHdr,
                                 String referTo, String referredBy,
                                 boolean referSub) {
        dlg.cseq++;
        StringBuilder extra = new StringBuilder(256);
        extra.append("Refer-To: <").append(referTo).append(">\r\n");
        if (referredBy != null && !referredBy.isEmpty()) {
            extra.append("Referred-By: <").append(referredBy).append(">\r\n");
        }
        if (!referSub) {
            extra.append("Refer-Sub: false\r\n");
        }
        extra.append("Allow: ").append(ALLOW).append("\r\n");
        return inDialog("REFER", id, dlg, target, route, secVerify,
                toHdr, fromHdr, dlg.cseq, extra.toString());
    }

    /**
     * In-dialog re-INVITE for hold/resume. Increments CSeq. SDP is the
     * same offer with a=sendonly or a=sendrecv.
     */
    static String buildReInvite(Id id, Dialog dlg, String target, String route,
                                String secVerify, String toHdr, String fromHdr,
                                String sdp) {
        dlg.cseq++;
        String extra = "Contact: <sip:" + contactUser(aorOf(id.impu != null
                && !id.impu.isEmpty() ? id.impu : id.impi))
                + "@" + bracket(id.localIp) + ":" + id.contactPort + ">\r\n"
                + "Allow: " + ALLOW + "\r\n";
        return inDialog("INVITE", id, dlg, target, route, secVerify,
                toHdr, fromHdr, dlg.cseq, extra, sdp, null);
    }

    static String buildPrack(Id id, Dialog dlg, String target, String route,
                             String secVerify, String toHdr, String fromHdr,
                             int rseq) {
        int inviteCseq = dlg.cseq;
        dlg.cseq++;
        return inDialog("PRACK", id, dlg, target, route, secVerify,
                toHdr, fromHdr, dlg.cseq,
                "RAck: " + rseq + " " + inviteCseq + " INVITE\r\n");
    }

    static String extractToTag(String msg) {
        String to = header(msg, "To");
        if (to == null) {
            to = header(msg, "t");
        }
        if (to == null) {
            return "";
        }
        int i = indexOfIgnoreCase(to, "tag=");
        if (i < 0) {
            return "";
        }
        int v = i + 4;
        int e = v;
        while (e < to.length()) {
            char c = to.charAt(e);
            if (c == ';' || c == '>' || c == ' ') {
                break;
            }
            e++;
        }
        int n = e - v;
        if (n > 190) {
            n = 190;
        }
        return to.substring(v, v + n);
    }

    static String pickPublicId(String pAssociated) {
        if (pAssociated == null) {
            return "";
        }
        String pick = extractAngle(pAssociated, "<tel:");
        if (pick == null) {
            pick = extractAngle(pAssociated, "<sip:");
        }
        return pick == null ? "" : pick;
    }

    static String contactUri(String contact) {
        if (contact == null) {
            return "";
        }
        int lt = contact.indexOf('<');
        int gt = contact.indexOf('>');
        if (lt >= 0 && gt > lt) {
            return contact.substring(lt + 1, gt);
        }
        return contact.trim();
    }

    static Media parseSdp(String msg) {
        String body = msg;
        int sep = msg.indexOf("\r\n\r\n");
        if (sep >= 0) {
            body = msg.substring(sep + 4);
        }
        Media m = new Media();
        for (String line : body.split("\r\n")) {
            if (line.startsWith("c=IN IP6 ")) {
                m.ip = line.substring(9).trim();
            } else if (line.startsWith("c=IN IP4 ")) {
                m.ip = line.substring(9).trim();
            } else if (line.startsWith("m=audio ")) {
                /* m=audio <port> <proto> <pt> [<pt> ...] */
                String[] tok = line.substring(8).trim().split("\\s+");
                try {
                    m.port = Integer.parseInt(tok[0]);
                } catch (Exception ignored) {
                    m.port = 0;
                }
                for (int i = 2; i < tok.length; i++) {
                    int pt;
                    try {
                        pt = Integer.parseInt(tok[i]);
                    } catch (NumberFormatException ignored) {
                        continue;
                    }
                    if (m.payloadType < 0) {
                        m.payloadType = pt;
                    }
                    if (pt == 0) {
                        m.offersPcmu = true;
                    }
                    if (m.codec(pt) == null) {
                        m.codecs.add(new Codec(pt));
                    }
                }
            } else if (line.equalsIgnoreCase("a=rtcp-mux")) {
                m.mux = true;
            } else if (line.startsWith("a=rtpmap:")) {
                /* a=rtpmap:<pt> <encoding>/<rate>[/<channels>] -- the name
                 * for the selected payload type is how we know whether the
                 * answer chose AMR-WB, AMR or G.711. */
                String r = line.substring(9).trim();
                int sp = r.indexOf(' ');
                if (sp > 0) {
                    try {
                        int pt = Integer.parseInt(r.substring(0, sp).trim());
                        String enc = r.substring(sp + 1).trim();
                        int slash = enc.indexOf('/');
                        if (slash > 0) {
                            enc = enc.substring(0, slash);
                        }
                        if (pt == m.payloadType) {
                            m.codecName = enc;
                        }
                        Codec c = m.codec(pt);
                        if (c != null) {
                            c.name = enc;
                            if (slash > 0) {
                                String rest = r.substring(sp + 1).trim()
                                        .substring(slash + 1);
                                int sl2 = rest.indexOf('/');
                                try {
                                    c.rate = Integer.parseInt(
                                            (sl2 < 0 ? rest : rest.substring(0, sl2))
                                                    .trim());
                                } catch (NumberFormatException ignored) {
                                    c.rate = 0;
                                }
                            }
                        }
                    } catch (NumberFormatException ignored) {
                        // not a payload type; skip the line
                    }
                }
            } else if (line.startsWith("a=fmtp:")) {
                String f = line.substring(7).trim();
                int sp = f.indexOf(' ');
                if (sp > 0) {
                    try {
                        Codec c = m.codec(
                                Integer.parseInt(f.substring(0, sp).trim()));
                        if (c != null) {
                            c.fmtp = f.substring(sp + 1).trim();
                        }
                    } catch (NumberFormatException ignored) {
                        // not a payload type; skip the line
                    }
                }
            } else if (line.startsWith("a=rtcp:")) {
                try {
                    String p = line.substring(7).trim();
                    int sp = p.indexOf(' ');
                    m.rtcpPort = Integer.parseInt(sp < 0 ? p : p.substring(0, sp));
                } catch (NumberFormatException ignored) {
                    m.rtcpPort = 0;
                }
            }
        }
        if (m.port > 0 && m.rtcpPort == 0 && !m.mux) {
            m.rtcpPort = m.port + 1;
        }
        return m.ip != null && m.port > 0 ? m : null;
    }

    static String requestMethod(String msg) {
        if (msg == null || msg.startsWith("SIP/2.0 ")) {
            return "";
        }
        int sp = msg.indexOf(' ');
        return sp < 0 ? "" : msg.substring(0, sp);
    }

    private static String inDialog(String method, Id id, Dialog dlg,
                                   String target, String route, String secVerify,
                                   String toHdr, String fromHdr, int cseq,
                                   String extra) {
        return inDialog(method, id, dlg, target, route, secVerify,
                toHdr, fromHdr, cseq, extra, null, null);
    }

    private static String inDialog(String method, Id id, Dialog dlg,
                                   String target, String route, String secVerify,
                                   String toHdr, String fromHdr, int cseq,
                                   String extra, String sdp, String branchOverride) {
        String aor = aorOf(id.impu != null && !id.impu.isEmpty()
                ? id.impu : id.impi);
        String host = bracket(id.localIp);
        SecureRandom rng = RNG;
        /* Every in-dialog request gets a new branch. The one exception is
         * the ACK to a non-2xx INVITE final, which supplies the original
         * transaction branch explicitly through branchOverride. */
        String branch = branchOverride;
        if (branch == null || branch.isEmpty()) {
            branch = String.format("z9hG4bK%08x%08x", rng.nextInt(), rng.nextInt());
        }
        if ("INVITE".equals(method)) {
            dlg.branch = branch;
        }
        StringBuilder a = new StringBuilder(1200);
        a.append(method).append(' ').append(target).append(" SIP/2.0\r\n");
        a.append("Via: SIP/2.0/").append(requestViaTransport()).append(' ')
                .append(host).append(':')
                .append(id.viaPort).append(";branch=").append(branch)
                .append(";rport\r\n");
        a.append("Max-Forwards: 70\r\n");
        if (route != null && !route.isEmpty()) {
            a.append("Route: ").append(route).append("\r\n");
        }
        if (fromHdr != null && !fromHdr.isEmpty()) {
            a.append("From: ").append(fromHdr).append("\r\n");
        } else {
            a.append("From: <").append(aor).append(">;tag=")
                    .append(dlg.fromTag).append("\r\n");
        }
        if (toHdr != null && !toHdr.isEmpty()) {
            a.append("To: ").append(toHdr).append("\r\n");
        }
        a.append("Call-ID: ").append(dlg.callId).append("\r\n");
        a.append("CSeq: ").append(cseq).append(' ').append(method).append("\r\n");
        if (extra != null) {
            a.append(extra);
        }
        if (secVerify != null && !secVerify.isEmpty()) {
            a.append("Security-Verify: ").append(secVerify).append("\r\n");
        }
        if (sdp != null && !sdp.isEmpty()) {
            a.append("Content-Type: application/sdp\r\n");
            a.append("Content-Length: ").append(sdp.length()).append("\r\n\r\n");
            a.append(sdp);
        } else {
            a.append("Content-Length: 0\r\n\r\n");
        }
        return a.toString();
    }

    static final class Cli {
        final String uri;
        final String name;
        final boolean withheld;

        Cli(String uri, String name, boolean withheld) {
            this.uri = uri;
            this.name = name;
            this.withheld = withheld;
        }
    }

    /**
     * Same rules as native sip_calling_identity: P-Asserted-Identity
     * tel: wins for the number, From supplies the display name.
     */
    static Cli callingIdentity(String msg) {
        String pai = header(msg, "P-Asserted-Identity");
        String from = header(msg, "From");
        String uri = "";
        String name = "";
        if (pai != null) {
            String pick = pai;
            int tel = indexOfIgnoreCase(pai, "<tel:");
            if (tel >= 0) {
                pick = pai.substring(tel);
            }
            String[] p = splitNameAddr(pick);
            uri = p[0];
            name = p[1];
        }
        if (from != null) {
            String[] p = splitNameAddr(from);
            if (uri.isEmpty()) {
                uri = p[0];
            }
            if (name.isEmpty()) {
                name = p[1];
            }
        }
        if (uri.isEmpty() || uri.toLowerCase(java.util.Locale.ROOT)
                .contains("anonymous")) {
            return new Cli("", "", true);
        }
        if (name.toLowerCase(java.util.Locale.ROOT).contains("anonymous")) {
            name = "";
        }
        return new Cli(uri, name, false);
    }

    /** "Display" <uri> -> [uri, cleaned name]. */
    static String[] splitNameAddr(String val) {
        if (val == null) {
            return new String[] { "", "" };
        }
        int lt = val.indexOf('<');
        int gt = lt >= 0 ? val.indexOf('>', lt) : -1;
        if (lt >= 0 && gt > lt) {
            return new String[] {
                    val.substring(lt + 1, gt),
                    cleanDisplayName(val.substring(0, lt))
            };
        }
        int e = 0;
        while (e < val.length() && val.charAt(e) != ';' && val.charAt(e) != ',') {
            e++;
        }
        return new String[] { cleanDisplayName(val.substring(0, e)), "" };
    }

    static String cleanDisplayName(String s) {
        if (s == null) {
            return "";
        }
        s = s.trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            s = s.substring(1, s.length() - 1);
        }
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                b.append(' ');
            } else {
                b.append(c);
            }
        }
        return b.toString().trim();
    }

    /** AoR of the public identity; public for Referred-By construction. */
    static String aorOfPublic(Id id) {
        return aorOf(id.impu != null && !id.impu.isEmpty()
                ? id.impu : id.impi);
    }

    /**
     * Minimal RFC 4579 conference-info reader: the display URIs of the
     * users currently in the conference (entity + connection status is
     * enough for a participant list; full-state diffs come as separate
     * documents we re-parse wholesale each time).
     */
    static java.util.List<String> parseConferenceUsers(String notify) {
        if (notify == null) {
            return null;
        }
        int sep = notify.indexOf("\r\n\r\n");
        if (sep < 0) {
            return null;
        }
        String body = notify.substring(sep + 4);
        java.util.List<String> users = new java.util.ArrayList<>();
        /* <user entity="..."> with a connected <connection>. Line-based
         * scan, not a real XML parser: NOTIFY bodies are machine
         * generated, bounded, and we only read attributes. */
        String[] lines = body.split("\r\n");
        String entity = null;
        boolean connected = false;
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("<user ")) {
                entity = attr(t, "entity");
                connected = false;
                /* The compact form puts <connection> on the same line:
                 * <user entity=".."><connection status=".."/></user>. */
                String st = attrAfter(t, "status",
                        t.indexOf("<connection "));
                if (st != null) {
                    connected = st.equals("connected");
                }
            } else if (t.startsWith("<connection ")) {
                String st = attr(t, "status");
                connected = st == null || "connected".equals(st);
            }
            if (entity != null && t.contains("</user>")) {
                if (connected) {
                    users.add(entity);
                }
                entity = null;
                connected = false;
            }
        }
        return users;
    }

    /** attr() that only matches after a given index (same-line tags). */
    private static String attrAfter(String tag, String name, int from) {
        if (from < 0) {
            return null;
        }
        int i = tag.indexOf(name + "=\"", from);
        if (i < 0) {
            return null;
        }
        int v = i + name.length() + 2;
        int e = tag.indexOf('"', v);
        if (e < 0) {
            return null;
        }
        return tag.substring(v, e);
    }

    private static String attr(String tag, String name) {
        int i = tag.indexOf(name + "=\"");
        if (i < 0) {
            return null;
        }
        int v = i + name.length() + 2;
        int e = tag.indexOf('"', v);
        if (e < 0) {
            return null;
        }
        return tag.substring(v, e);
    }

    private static String aorOf(String publicId) {
        if (publicId.startsWith("tel:") || publicId.startsWith("sip:")) {
            return publicId;
        }
        return "sip:" + publicId;
    }

    private static String contactUser(String aor) {
        String cu = aor;
        if (cu.startsWith("sip:")) {
            cu = cu.substring(4);
        } else if (cu.startsWith("tel:")) {
            cu = cu.substring(4);
        }
        int at = cu.indexOf('@');
        return at >= 0 ? cu.substring(0, at) : cu;
    }

    private static String extractAngle(String s, String start) {
        int i = s.indexOf(start);
        if (i < 0) {
            return null;
        }
        int gt = s.indexOf('>', i);
        if (gt < 0) {
            return null;
        }
        return s.substring(i + 1, gt);
    }

    private static int indexOfIgnoreCase(String hay, String needle) {
        return hay.toLowerCase(java.util.Locale.ROOT)
                .indexOf(needle.toLowerCase(java.util.Locale.ROOT));
    }
}
