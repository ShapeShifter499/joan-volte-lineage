package org.joan.ims;

/**
 * Ut/XCAP: where the carrier keeps this subscriber's supplementary
 * services, and how to address them.
 *
 * <p>Call forwarding, call barring and identity restriction are held as
 * an XML document on the carrier's XCAP server (TS 24.623, RFC 4825).
 * Today those settings reach the network over CS as MMI codes, which
 * works; this exists so the IMS path can eventually be offered without
 * guessing at any of the addressing.
 *
 * <p>Nothing here is advertised to anyone. Ut is not a SIP capability
 * tag, so unlike most of what this stack does not implement, building it
 * incrementally claims nothing the stack cannot do -- which is why it
 * could be started at all.
 *
 * <p>Pure string and XML work, no Android, so the host tests prove it.
 */
final class JoanXcap {

    /** TS 24.623: the AUID under which simservs documents live. */
    static final String AUID = "simservs.ngn.etsi.org";
    /** The document every supplementary service lives in. */
    static final String DOCUMENT = "simservs.xml";

    private JoanXcap() {}

    /**
     * The XCAP URI for this subscriber's simservs document.
     *
     * <p>RFC 4825 4.: {@code <root>/<auid>/users/<XUI>/<document>}. The
     * XUI is the subscriber's public identity, and it is percent-encoded
     * because it is a SIP URI sitting inside a path segment -- the colon
     * after {@code sip} and any {@code @} would otherwise read as URI
     * syntax rather than as data.
     *
     * <p>Returns null rather than a half-built URI when the profile has
     * no server or the identity is missing: a request to the wrong place
     * is worse than no request.
     */
    static String documentUri(String server, int port, boolean tls,
                              String impu) {
        if (server == null || server.trim().isEmpty()
                || impu == null || impu.trim().isEmpty()) {
            return null;
        }
        String host = server.trim();
        if (host.indexOf('/') >= 0 || host.indexOf(' ') >= 0) {
            return null;
        }
        String scheme = tls ? "https" : "http";
        int p = port > 0 ? port : (tls ? 443 : 80);
        boolean defaultPort = (tls && p == 443) || (!tls && p == 80);
        StringBuilder b = new StringBuilder();
        b.append(scheme).append("://").append(host);
        if (!defaultPort) {
            b.append(':').append(p);
        }
        b.append('/').append(AUID).append("/users/")
                .append(encodeSegment(impu.trim()))
                .append('/').append(DOCUMENT);
        return b.toString();
    }

    /**
     * Percent-encode one path segment, RFC 3986 2.3 unreserved set.
     *
     * <p>Deliberately conservative: anything outside unreserved is
     * escaped, including characters a laxer encoder would leave alone.
     * An over-escaped segment is still correct; an under-escaped one
     * changes which resource is addressed.
     */
    static String encodeSegment(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean unreserved = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~';
            if (unreserved) {
                b.append(c);
            } else if (c < 0x80) {
                b.append('%').append(String.format("%02X", (int) c));
            } else {
                byte[] utf8;
                try {
                    utf8 = String.valueOf(c).getBytes("UTF-8");
                } catch (java.io.UnsupportedEncodingException e) {
                    return s;
                }
                for (byte x : utf8) {
                    b.append('%').append(String.format("%02X", x & 0xff));
                }
            }
        }
        return b.toString();
    }

    /** One supplementary service's state, as the document reports it. */
    static final class Service {
        final String name;
        final boolean active;
        Service(String name, boolean active) {
            this.name = name;
            this.active = active;
        }
    }

    /**
     * Whether a named service element is present and active.
     *
     * <p>TS 24.623 wraps each service in an element carrying an
     * {@code active} attribute. Absent means the service is not
     * provisioned; present with {@code active="false"} means provisioned
     * and switched off, and those are different answers -- so this
     * returns null for absent rather than folding it into false.
     *
     * <p>Namespace prefixes vary between carriers, so the element is
     * matched on its local name.
     */
    static Boolean serviceActive(String xml, String localName) {
        if (xml == null || localName == null) {
            return null;
        }
        int i = 0;
        while (true) {
            i = xml.indexOf('<', i);
            if (i < 0) {
                return null;
            }
            int close = xml.indexOf('>', i);
            if (close < 0) {
                return null;
            }
            String tag = xml.substring(i + 1, close);
            if (tag.startsWith("/") || tag.startsWith("?")
                    || tag.startsWith("!")) {
                i = close + 1;
                continue;
            }
            int sp = tag.length();
            for (int k = 0; k < tag.length(); k++) {
                char c = tag.charAt(k);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n'
                        || c == '/') {
                    sp = k;
                    break;
                }
            }
            String qname = tag.substring(0, sp);
            int colon = qname.indexOf(':');
            String local = colon < 0 ? qname : qname.substring(colon + 1);
            if (local.equals(localName)) {
                String attrs = tag.substring(sp);
                String v = attrValue(attrs, "active");
                /* An element with no active attribute is provisioned and
                 * on: TS 24.623 makes the attribute default to true. */
                return v == null ? Boolean.TRUE
                        : Boolean.valueOf("true".equalsIgnoreCase(v)
                                || "1".equals(v));
            }
            i = close + 1;
        }
    }

    /** The value of an attribute in a start-tag's attribute text. */
    static String attrValue(String attrs, String name) {
        int i = attrs.indexOf(name + "=");
        while (i > 0) {
            char before = attrs.charAt(i - 1);
            if (before == ' ' || before == '\t' || before == '\n'
                    || before == '\r') {
                break;
            }
            i = attrs.indexOf(name + "=", i + 1);
        }
        if (i < 0) {
            return null;
        }
        int q = i + name.length() + 1;
        if (q >= attrs.length()) {
            return null;
        }
        char quote = attrs.charAt(q);
        if (quote != '"' && quote != '\'') {
            return null;
        }
        int end = attrs.indexOf(quote, q + 1);
        return end < 0 ? null : attrs.substring(q + 1, end);
    }
}
