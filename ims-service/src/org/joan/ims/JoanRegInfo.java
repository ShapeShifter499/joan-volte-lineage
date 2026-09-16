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
     *
     * <p>Matching is deliberately strict, and deliberately not the whole
     * story. AOSP's ImsStack carries a per-carrier switch,
     * {@code KEY_USE_REGINFO_CONTACT_WITHOUT_URI_CHECK_BOOL}, whose
     * existence says plainly that some networks send a contact whose URI
     * cannot be matched against the one we registered. Their answer is to
     * stop checking, for those carriers only.
     *
     * <p>We do not do that by default and should not: accepting any
     * contact means a second handset on the same public identity can
     * deregister this one. When the trace shows contacts present and none
     * matched, that is the case the switch exists for -- and the decision
     * to add our own equivalent should be made from a real body, per
     * carrier, not as a blanket relaxation.
     */
    /**
     * Index of the next {@code <contact} element at or after {@code from},
     * tolerating a namespace prefix.
     *
     * <p>A core is free to send {@code <reg:contact>} instead of
     * {@code <contact>}; both are the same element, and a plain
     * indexOf("&lt;contact") sees only one of them. Getting this wrong
     * does not throw -- it silently finds no contacts and reads as "I
     * have nothing to say about your binding", which is indistinguishable
     * from a body that genuinely said nothing.
     */
    static int nextContact(String low, int from) {
        int at = from;
        while (at < low.length()) {
            int lt = low.indexOf('<', at);
            if (lt < 0) {
                return -1;
            }
            int i = lt + 1;
            /* Skip an optional "prefix:" between '<' and the name. */
            int colon = -1;
            int j = i;
            while (j < low.length()) {
                char ch = low.charAt(j);
                if (ch == ':') {
                    colon = j;
                    break;
                }
                if (!Character.isLetterOrDigit(ch) && ch != '-' && ch != '_') {
                    break;
                }
                j++;
            }
            int nameAt = colon >= 0 ? colon + 1 : i;
            if (low.startsWith("contact", nameAt)) {
                int after = nameAt + "contact".length();
                char nx = after < low.length() ? low.charAt(after) : ' ';
                if (!Character.isLetterOrDigit(nx)) {
                    return lt;
                }
            }
            at = lt + 1;
        }
        return -1;
    }

    /**
     * Where one contact element's content stops: the first closing tag,
     * or the start of the next contact, whichever comes first.
     */
    private static int contentEnd(String low, int openEnd) {
        int close = low.indexOf("</", openEnd);
        int next = nextContact(low, openEnd);
        if (next > openEnd && (close < 0 || next < close)) {
            return next;
        }
        return close;
    }

    /**
     * A structural description of a reginfo body, for the trace.
     *
     * <p>Carries no identity: element counts and attribute values only,
     * never a URI. When {@link #parse} returns unknown this says whether
     * the body had no contacts at all, or had contacts that were somebody
     * else's -- which are different problems with different fixes, and
     * guessing between them from "unknown" is how a diagnostic becomes a
     * second mystery.
     */
    /** Attribute names on one element, values deliberately dropped. */
    static String attrNames(String element) {
        StringBuilder b = new StringBuilder(32);
        int i = 0;
        while (i < element.length()) {
            int eq = element.indexOf('=', i);
            if (eq < 0) {
                break;
            }
            int st = eq - 1;
            while (st >= 0 && !Character.isWhitespace(element.charAt(st))) {
                st--;
            }
            String name = element.substring(st + 1, eq).trim();
            if (!name.isEmpty() && !name.startsWith("<")) {
                if (b.length() > 0) {
                    b.append('|');
                }
                b.append(name);
            }
            /* Step past the value so a value containing '=' is not read
             * as another attribute. */
            int vs = eq + 1;
            if (vs < element.length()
                    && (element.charAt(vs) == '"' || element.charAt(vs) == '\'')) {
                int ve = element.indexOf(element.charAt(vs), vs + 1);
                i = ve < 0 ? element.length() : ve + 1;
            } else {
                i = eq + 1;
            }
        }
        return b.toString();
    }

    /** Child element names inside one element's content, values dropped. */
    static String childNames(String inner) {
        StringBuilder b = new StringBuilder(32);
        int at = 0;
        while (true) {
            int lt = inner.indexOf('<', at);
            if (lt < 0 || lt + 1 >= inner.length()) {
                break;
            }
            at = lt + 1;
            if (inner.charAt(lt + 1) == '/') {
                continue;
            }
            int e = lt + 1;
            while (e < inner.length()
                    && (Character.isLetterOrDigit(inner.charAt(e))
                            || inner.charAt(e) == ':' || inner.charAt(e) == '-'
                            || inner.charAt(e) == '_')) {
                e++;
            }
            String name = inner.substring(lt + 1, e);
            if (name.isEmpty()) {
                continue;
            }
            if (b.indexOf(name) < 0) {
                if (b.length() > 0) {
                    b.append('|');
                }
                b.append(name);
            }
        }
        return b.toString();
    }

    static String describe(String body, String ourUri) {
        return describe(body, ourUri, null);
    }

    /**
     * @param ourInstance our +sip.instance value, or null. Only whether it
     *        appears is reported -- never the value, which is the IMEI.
     */
    static String describe(String body, String ourUri, String ourInstance) {
        if (body == null || body.isEmpty()) {
            return "body=empty";
        }
        String low = body.toLowerCase(java.util.Locale.US);
        if (low.indexOf("reginfo") < 0) {
            return "body=" + body.length() + "b not-reginfo";
        }
        String host = hostOf(ourUri);
        StringBuilder d = new StringBuilder(64);
        d.append("body=").append(body.length()).append('b');
        d.append(" host_known=").append(host != null && !host.isEmpty());
        int n = 0;
        int mine = 0;
        int at = 0;
        StringBuilder states = new StringBuilder();
        String shape = "";
        String kids = "";
        while (true) {
            int c = nextContact(low, at);
            if (c < 0) {
                break;
            }
            int end = low.indexOf('>', c);
            if (end < 0) {
                break;
            }
            int close = contentEnd(low, end);
            String element = low.substring(c, end + 1);
            String inner = close > end ? low.substring(end + 1, close) : "";
            at = end + 1;
            n++;
            boolean ours = host == null || host.isEmpty()
                    || inner.indexOf(host) >= 0 || element.indexOf(host) >= 0;
            if (ours) {
                mine++;
            }
            if (states.length() > 0) {
                states.append(',');
            }
            states.append(attr(element, "state")).append('/')
                    .append(attr(element, "event"))
                    .append(ours ? "(ours)" : "");
            if (shape.isEmpty()) {
                shape = attrNames(element);
                kids = childNames(inner);
            }
        }
        d.append(" contacts=").append(n).append(" matched=").append(mine);
        if (states.length() > 0) {
            d.append(" [").append(states).append(']');
        }
        /* The shape of one contact: which attributes and children exist,
         * so a better discriminator than the host can be found without
         * anybody pasting a body containing subscriber identities into a
         * bug report. Names only. */
        if (!shape.isEmpty()) {
            d.append(" shape{attrs=").append(shape)
                    .append(" children=").append(kids).append('}');
        }
        d.append(" inst_seen=").append(low.indexOf("+sip.instance") >= 0);
        if (ourInstance != null && !ourInstance.isEmpty()) {
            d.append(" inst_match=").append(
                    low.indexOf(ourInstance.toLowerCase(java.util.Locale.US)) >= 0);
        }
        return d.toString();
    }

    static int parse(String body, String ourUri) {
        if (body == null || body.isEmpty()) {
            return STATE_UNKNOWN;
        }
        String low = body.toLowerCase(java.util.Locale.US);
        /* "<reg:reginfo" is not "<reginfo". The root element takes a
         * namespace prefix as readily as its children, and a guard that
         * misses it rejects the whole body before a single contact is
         * looked at. */
        if (low.indexOf("reginfo") < 0) {
            return STATE_UNKNOWN;
        }
        String host = hostOf(ourUri);
        int best = STATE_UNKNOWN;
        int at = 0;
        while (true) {
            int c = nextContact(low, at);
            if (c < 0) {
                break;
            }
            int end = low.indexOf('>', c);
            if (end < 0) {
                break;
            }
            /* The element's own attributes, plus enough of what follows to
             * reach its <uri> child. A self-closing contact has no uri.
             *
             * The boundary is the first closing tag OR the next contact,
             * whichever comes first -- not a literal "</contact", which a
             * namespace prefix defeats, and not an unbounded run, which
             * would let a self-closing contact absorb the next one's uri
             * and claim somebody else's binding as ours. */
            int close = contentEnd(low, end);
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
