package org.joan.ims;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strip the authentication material out of a SIP message before it is
 * written somewhere a tester will send on.
 *
 * Pure text in, pure text out, and deliberately free of Android imports so
 * the host tests can exercise it directly. What it removes is the AKA
 * challenge and the digest computed from it; what it keeps is every header
 * name, every URI, the Security-* parameters and the identities, because
 * those are the fields the open questions are actually about -- a 404 from
 * an I-CSCF is a statement about the identity, and redacting it would throw
 * away the evidence along with the secret.
 *
 * Lengths are kept. "response was 32 characters" distinguishes a digest
 * that was computed from one that was empty, which is worth knowing and
 * gives nothing away.
 */
final class JoanSipRedact {

    /** Digest and AKA parameters. nc (nonce count) is not one of them. */
    private static final Pattern SECRET = Pattern.compile(
            "(?i)\\b(nonce|cnonce|response|rspauth|nextnonce|auts)"
                    + "\\s*=\\s*(\"[^\"]*\"|[^,;\\s]+)");

    /** Header fields whose parameters are worth looking at. */
    private static final Pattern AUTH_HEADER = Pattern.compile(
            "(?i)^(authorization|proxy-authorization|www-authenticate"
                    + "|proxy-authenticate|authentication-info)\\s*:");

    private JoanSipRedact() {}

    /**
     * Describe the line endings of a message as facts, before they are
     * normalised away.
     *
     * A capture travels through `adb shell` -- which runs on a PTY that
     * rewrites every LF as CRLF, turning a SIP message's own CRLF into
     * CRCRLF -- and then through a chat window, which may do anything at
     * all. So the bytes cannot be trusted to arrive intact, and this is
     * the one file where line endings are themselves evidence: a SIP
     * message with a bare LF where RFC 3261 requires CRLF is a real
     * candidate for a parser rejecting it, and "Server Internal Error" is
     * exactly what a core says when it hits something it cannot parse.
     *
     * Recording the counts here and normalising the body means a mangled
     * transfer can no longer either hide that fault or invent it. A
     * {@code lf} or {@code cr} count above zero is a finding; the digest
     * is over the message as it went to the wire, so two captures of the
     * "same" REGISTER can be compared even though neither can be
     * recomputed from the redacted text.
     */
    static String lineEndings(String message) {
        if (message == null) {
            return "bytes=0";
        }
        int crlf = 0, lf = 0, cr = 0;
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            if (c == '\r') {
                if (i + 1 < message.length() && message.charAt(i + 1) == '\n') {
                    crlf++;
                    i++;
                } else {
                    cr++;
                }
            } else if (c == '\n') {
                lf++;
            }
        }
        return "bytes=" + message.getBytes(StandardCharsets.US_ASCII).length
                + " crlf=" + crlf + " lf=" + lf + " cr=" + cr
                + " sha256=" + shortDigest(message);
    }

    /** First 16 hex of SHA-256, or "unavailable". Identity, not integrity. */
    private static String shortDigest(String message) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(message.getBytes(StandardCharsets.US_ASCII));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(Character.forDigit((d[i] >> 4) & 0xf, 16));
                sb.append(Character.forDigit(d[i] & 0xf, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "unavailable";
        }
    }

    /**
     * One LF per line, so the file reads the same after a PTY, a copy and
     * a paste. What was there before is recorded by {@link #lineEndings}.
     */
    static String normalize(String message) {
        if (message == null) {
            return null;
        }
        return message.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * Redact in place, preserving the message otherwise byte for byte.
     *
     * Folded headers are why this walks lines rather than running the
     * pattern over the whole message: RFC 3261 allows a header to continue
     * on a following line that begins with whitespace, and a nonce split
     * across a fold would otherwise survive. A continuation line inherits
     * the decision made for the header that started it.
     */
    static String redact(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        StringBuilder out = new StringBuilder(message.length());
        boolean inAuth = false;
        boolean inBody = false;
        int i = 0;
        int n = message.length();
        while (i < n) {
            int nl = message.indexOf('\n', i);
            String line = nl < 0 ? message.substring(i) : message.substring(i, nl + 1);
            i = nl < 0 ? n : nl + 1;
            String bare = line.endsWith("\n")
                    ? line.substring(0, line.length() - 1) : line;
            if (bare.endsWith("\r")) {
                bare = bare.substring(0, bare.length() - 1);
            }
            if (inBody) {
                out.append(line);
                continue;
            }
            if (bare.isEmpty()) {
                // The blank line ends the headers. A body cannot carry a
                // digest parameter, and rewriting it would change a
                // Content-Length we did not recompute.
                inBody = true;
                inAuth = false;
                out.append(line);
                continue;
            }
            boolean continuation = bare.charAt(0) == ' ' || bare.charAt(0) == '\t';
            if (!continuation) {
                inAuth = AUTH_HEADER.matcher(bare).find();
            }
            out.append(inAuth ? scrub(line) : line);
        }
        return out.toString();
    }

    private static String scrub(String line) {
        Matcher m = SECRET.matcher(line);
        StringBuffer sb = new StringBuffer(line.length());
        while (m.find()) {
            String raw = m.group(2);
            int len = raw.length();
            if (len >= 2 && raw.charAt(0) == '"' && raw.charAt(len - 1) == '"') {
                len -= 2;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    m.group(1) + "=\"<redacted:" + len + ">\""));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
