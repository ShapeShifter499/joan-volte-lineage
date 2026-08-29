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
    }

    static final class Media {
        String ip;
        int port;
        boolean mux;
        int rtcpPort;
    }

    static String sdpOffer(String ip, int rtpPort) {
        boolean v6 = ip != null && ip.indexOf(':') >= 0;
        String fam = v6 ? "IP6" : "IP4";
        long sess = System.currentTimeMillis() / 1000;
        return "v=0\r\n"
                + "o=- " + sess + " 1 IN " + fam + " " + ip + "\r\n"
                + "s=-\r\n"
                + "c=IN " + fam + " " + ip + "\r\n"
                + "t=0 0\r\n"
                + "m=audio " + rtpPort + " RTP/AVP 0 96 97 101\r\n"
                + "a=rtpmap:0 PCMU/8000\r\n"
                + "a=rtpmap:96 AMR-WB/16000/1\r\n"
                + "a=fmtp:96 octet-align=0;mode-change-capability=2\r\n"
                + "a=rtpmap:97 AMR/8000/1\r\n"
                + "a=fmtp:97 octet-align=0\r\n"
                + "a=rtpmap:101 telephone-event/8000\r\n"
                + "a=fmtp:101 0-15\r\n"
                + "a=ptime:20\r\n"
                + "a=maxptime:240\r\n"
                + "a=rtcp:" + (rtpPort + 1) + "\r\n"
                + "a=rtcp-mux\r\n"
                + "a=sendrecv\r\n";
    }

    /** PCMU-only answer, matching native sdp_answer. */
    static String sdpAnswer(String ip, int rtpPort, String offer) {
        boolean v6 = ip != null && ip.indexOf(':') >= 0;
        String fam = v6 ? "IP6" : "IP4";
        long sess = System.currentTimeMillis() / 1000;
        boolean mux = offer == null || offer.contains("a=rtcp-mux");
        return "v=0\r\n"
                + "o=- " + sess + " 1 IN " + fam + " " + ip + "\r\n"
                + "s=-\r\n"
                + "c=IN " + fam + " " + ip + "\r\n"
                + "t=0 0\r\n"
                + "m=audio " + rtpPort + " RTP/AVP 0\r\n"
                + "a=rtpmap:0 PCMU/8000\r\n"
                + "a=ptime:20\r\n"
                + "a=rtcp:" + (rtpPort + 1) + "\r\n"
                + (mux ? "a=rtcp-mux\r\n" : "")
                + "a=sendrecv\r\n";
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
        if (id.impu == null || id.impu.isEmpty()) {
            return null;
        }
        String aor = aorOf(id.impu);
        String host = bracket(id.localIp);
        SecureRandom rng = new SecureRandom();
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
        a.append("Via: SIP/2.0/UDP ").append(host).append(':')
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
        a.append("Allow: INVITE, ACK, CANCEL, BYE, UPDATE, PRACK, INFO, OPTIONS\r\n");
        a.append("Supported: replaces\r\n");
        a.append("Require: sec-agree\r\n");
        a.append("Proxy-Require: sec-agree\r\n");
        if (secVerify != null && !secVerify.isEmpty()) {
            a.append("Security-Verify: ").append(secVerify).append("\r\n");
        }
        a.append("Accept-Contact: *;+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel\"\r\n");
        a.append("Content-Type: application/sdp\r\n");
        a.append("Content-Length: ").append(sdp.length()).append("\r\n\r\n");
        a.append(sdp);
        return a.toString();
    }

    static String buildAck(Id id, Dialog dlg, String target, String route,
                           String secVerify, String toHdr, String fromHdr) {
        return inDialog("ACK", id, dlg, target, route, secVerify,
                toHdr, fromHdr, dlg.cseq, null);
    }

    static String buildBye(Id id, Dialog dlg, String target, String route,
                           String secVerify, String toHdr, String fromHdr) {
        return inDialog("BYE", id, dlg, target, route, secVerify,
                toHdr, fromHdr, dlg.cseq + 1, null);
    }

    static String buildPrack(Id id, Dialog dlg, String target, String route,
                             String secVerify, String toHdr, String fromHdr,
                             int rseq) {
        return inDialog("PRACK", id, dlg, target, route, secVerify,
                toHdr, fromHdr, dlg.cseq + 1,
                "RAck: " + rseq + " " + dlg.cseq + " INVITE\r\n");
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
                String rest = line.substring(8).trim();
                int sp = rest.indexOf(' ');
                try {
                    m.port = Integer.parseInt(sp < 0 ? rest : rest.substring(0, sp));
                } catch (NumberFormatException ignored) {
                    m.port = 0;
                }
            } else if (line.equalsIgnoreCase("a=rtcp-mux")) {
                m.mux = true;
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
        String aor = aorOf(id.impu != null && !id.impu.isEmpty()
                ? id.impu : id.impi);
        String host = bracket(id.localIp);
        SecureRandom rng = new SecureRandom();
        String branch = String.format("z9hG4bK%08x%08x", rng.nextInt(), rng.nextInt());
        StringBuilder a = new StringBuilder(1200);
        a.append(method).append(' ').append(target).append(" SIP/2.0\r\n");
        a.append("Via: SIP/2.0/UDP ").append(host).append(':')
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
        a.append("Content-Length: 0\r\n\r\n");
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
