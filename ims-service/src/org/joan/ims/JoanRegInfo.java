package org.joan.ims;

/**
 * The reginfo+xml body of a reg-event NOTIFY, RFC 3680.
 *
 * <p>TS 24.229 s5.1.1.3 has the UE subscribe to the registration event
 * package for its own public identity as soon as it is registered. That
 * subscription is how the network says "your binding is gone" -- an
 * administrative deregistration, a re-authentication demand, an S-CSCF
 * reassignment. Without it the first sign of trouble is a refresh
 * failing up to half an hour later, and in between the handset shows
 * itself registered while it is not reachable.
 *
 * <p>This is a deliberately small, tolerant scanner rather than a real
 * XML parse. The body arrives from the network, so it is data, and the
 * only question asked of it is whether our binding was terminated and
 * whether we are invited to come back. Anything it cannot understand
 * reads as "nothing to do", which leaves the existing refresh timer as
 * the backstop.
 */
final class JoanRegInfo {

    /** Nothing in the body says our binding changed. */
    static final int STATE_UNKNOWN = 0;
    /** The binding is alive. */
    static final int STATE_ACTIVE = 1;
    /** The binding is gone and we should register again. */
    static final int STATE_TERMINATED_REREGISTER = 2;
    /** The binding is gone and the network does not want us back. */
    static final int STATE_TERMINATED_FINAL = 3;

    private JoanRegInfo() {}

    /**
     * What a reginfo body says about our contact.
     *
     * @param body the NOTIFY body
     * @param ourUri a URI that identifies our binding, or null to accept
     *        any contact in the document. Matching matters on a public
     *        identity registered from more than one device: another
     *        handset being deregistered is not news about ours.
     */
    static int parse(String body, String ourUri) {
        if (body == null || body.isEmpty()) {
            return STATE_UNKNOWN;
        }
        String low = body.toLowerCase(java.util.Locale.US);
        if (low.indexOf("<reginfo") < 0) {
            return STATE_UNKNOWN;
        }
        String host = hostOf(ourUri);
        int best = STATE_UNKNOWN;
        int at = 0;
        while (true) {
            int c = low.indexOf("<contact", at);
            if (c < 0) {
                break;
            }
            int end = low.indexOf('>', c);
            if (end < 0) {
                break;
            }
            /* The element's own attributes, plus enough of what follows to
             * reach its <uri> child. A self-closing contact has no uri. */
            int close = low.indexOf("</contact", end);
            String element = low.substring(c, end + 1);
            String inner = close > end ? low.substring(end + 1, close) : "";
            at = end + 1;

            if (host != null && !host.isEmpty()
                    && inner.indexOf(host) < 0
                    && element.indexOf(host) < 0) {
                /* A contact that is not ours. */
                continue;
            }
            String state = attr(element, "state");
            if ("active".equals(state)) {
                if (best == STATE_UNKNOWN) {
                    best = STATE_ACTIVE;
                }
                continue;
            }
            if (!"terminated".equals(state)) {
                continue;
            }
            /* RFC 3680 s5.3: the event says whether coming back is
             * welcome. "rejected" and "unregistered" mean it is not, and
             * re-registering into either is a loop against a network that
             * has already said no. */
            String event = attr(element, "event");
            if ("rejected".equals(event) || "unregistered".equals(event)) {
                return STATE_TERMINATED_FINAL;
            }
            best = STATE_TERMINATED_REREGISTER;
        }
        return best;
    }

    /** Value of an unquoted-or-quoted attribute in one element's text. */
    static String attr(String element, String name) {
        if (element == null || name == null) {
            return "";
        }
        int at = element.indexOf(name + "=");
        if (at < 0) {
            return "";
        }
        int i = at + name.length() + 1;
        if (i >= element.length()) {
            return "";
        }
        char q = element.charAt(i);
        if (q == '"' || q == '\'') {
            int e = element.indexOf(q, i + 1);
            return e < 0 ? "" : element.substring(i + 1, e);
        }
        int e = i;
        while (e < element.length()
                && !Character.isWhitespace(element.charAt(e))
                && element.charAt(e) != '>' && element.charAt(e) != '/') {
            e++;
        }
        return element.substring(i, e);
    }

    /**
     * The host part of a SIP URI, lowercased.
     *
     * <p>Matching on host rather than the whole URI is deliberate: the
     * network echoes our contact back with parameters we did not send
     * (+sip.instance, expires, transport), and comparing whole strings
     * would miss our own binding and treat a real deregistration as
     * somebody else's.
     */
    static String hostOf(String uri) {
        if (uri == null) {
            return null;
        }
        String s = uri.trim();
        if (s.startsWith("<")) {
            s = s.substring(1);
        }
        int gt = s.indexOf('>');
        if (gt > 0) {
            s = s.substring(0, gt);
        }
        int colon = s.indexOf(':');
        if (colon >= 0) {
            s = s.substring(colon + 1);
        }
        int at = s.indexOf('@');
        if (at >= 0) {
            s = s.substring(at + 1);
        }
        int semi = s.indexOf(';');
        if (semi >= 0) {
            s = s.substring(0, semi);
        }
        /* The port has to come off BEFORE the brackets do. An IPv6 host
         * is full of colons, so once "[2001:db8::1]:5060" has lost its
         * brackets there is nothing left to say where the address ends
         * and the port begins -- strip them the other way round and the
         * host comes out as "2001:db8::1:5060". */
        if (s.startsWith("[")) {
            int rb = s.indexOf(']');
            s = rb > 0 ? s.substring(1, rb) : s.substring(1);
        } else {
            int port = s.lastIndexOf(':');
            if (port > 0 && s.indexOf(':') == port) {
                s = s.substring(0, port);
            }
        }
        return s.toLowerCase(java.util.Locale.US);
    }
}
