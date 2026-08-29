package org.joan.ims;

import java.security.SecureRandom;

/**
 * SIP REGISTER builder matching native {@code build_register}.
 * Host-testable: no Android imports. Never logs the message (it carries
 * IMPI).
 */
final class JoanSipBuilder {
    static final int REG1_PORT = 15060;
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

        Challenge(String nonceB64, String algorithm, String secServer,
                  String realm, String qop) {
            this.nonceB64 = nonceB64;
            this.algorithm = algorithm;
            this.secServer = secServer;
            this.realm = realm;
            this.qop = qop;
        }

        Challenge(String nonceB64, String algorithm, String secServer) {
            this(nonceB64, algorithm, secServer, null, "auth");
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
        txn.newBranch(new SecureRandom());
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
            String respHex = JoanSipCrypto.akaDigestResponseHex(
                    id.impi, digestRealm, "REGISTER", requestUri,
                    ch.nonceB64, res, qop, "00000001", txn.cnonce,
                    ch.algorithm, ck, ik);
            authLine = "Digest username=\"" + id.impi + "\", realm=\""
                    + digestRealm + "\", nonce=\"" + ch.nonceB64
                    + "\", uri=\"" + requestUri + "\", response=\""
                    + respHex + "\", algorithm=" + ch.algorithm
                    + ", qop=" + qop + ", nc=00000001, cnonce=\""
                    + txn.cnonce + "\", integrity-protected=yes";
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
        a.append("Via: SIP/2.0/UDP ").append(viaHost).append(':')
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
                        + ";+g.3gpp.smsip;audio\r\n");
        a.append("Expires: 600000\r\n");
        a.append("Allow: INVITE, ACK, CANCEL, BYE, UPDATE, REFER, NOTIFY, MESSAGE, OPTIONS, PRACK\r\n");
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

    static String header(String msg, String name) {
        int i = 0;
        while (i < msg.length()) {
            int eol = eol(msg, i);
            String line = msg.substring(i, eol);
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
            i = skipEol(msg, eol);
            if (i == eol) {
                break;
            }
        }
        return null;
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

    private static int indexOfIgnoreCase(String hay, String needle) {
        return hay.toLowerCase(java.util.Locale.ROOT)
                .indexOf(needle.toLowerCase(java.util.Locale.ROOT));
    }
}
