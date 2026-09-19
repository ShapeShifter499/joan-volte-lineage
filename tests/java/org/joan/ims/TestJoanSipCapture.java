package org.joan.ims;

/**
 * The redactor decides what leaves somebody else's handset. A miss here is
 * not a failing test, it is a subscriber's authentication material sent to
 * a stranger in a chat window, so the cases below are deliberately hostile:
 * folded headers, unquoted values, mixed case, and the parameters that must
 * SURVIVE because the diagnosis needs them.
 */
public final class TestJoanSipCapture {
    private static int fail;

    private static void check(boolean ok, String what) {
        if (ok) {
            System.out.println("ok   " + what);
        } else {
            System.out.println("FAIL " + what);
            fail++;
        }
    }

    private static void gone(String out, String secret, String what) {
        check(!out.contains(secret), what);
    }

    public static void main(String[] args) {
        // A real protected REGISTER's Authorization header shape.
        String reg2 = "REGISTER sip:ims.mnc005.mcc226.3gppnetwork.org SIP/2.0\r\n"
                + "Via: SIP/2.0/TCP 10.1.2.3:38967;branch=z9hG4bK1234\r\n"
                + "From: <sip:226051234567890@ims.mnc005.mcc226.3gppnetwork.org>;tag=abc\r\n"
                + "Call-ID: 0f8c1b2a3d4e\r\n"
                + "Authorization: Digest username=\"226051234567890@ims.mnc005"
                + ".mcc226.3gppnetwork.org\", realm=\"ims.mnc005.mcc226"
                + ".3gppnetwork.org\", nonce=\"SECRETNONCEVALUE0123456789\", "
                + "uri=\"sip:ims.mnc005.mcc226.3gppnetwork.org\", "
                + "response=\"0123456789abcdef0123456789abcdef\", "
                + "algorithm=AKAv1-MD5, cnonce=\"SECRETCNONCE\", qop=auth, nc=00000001\r\n"
                + "Security-Verify: ipsec-3gpp;alg=hmac-sha-1-96;prot=esp;"
                + "mod=trans;ealg=null;spi-c=1234;spi-s=5678;port-c=38967;port-s=9900\r\n"
                + "Content-Length: 0\r\n\r\n";
        String out = JoanSipRedact.redact(reg2);

        gone(out, "SECRETNONCEVALUE0123456789", "the nonce value is gone");
        gone(out, "0123456789abcdef0123456789abcdef", "the digest response is gone");
        gone(out, "SECRETCNONCE", "the cnonce value is gone");
        check(out.contains("nonce=\"<redacted:26>\""), "the nonce length is kept");
        check(out.contains("response=\"<redacted:32>\""), "the response length is kept");

        // What must survive, because it is the evidence.
        check(out.contains("username=\"226051234567890@ims.mnc005"
                + ".mcc226.3gppnetwork.org\""), "the IMPI survives redaction");
        check(out.contains("algorithm=AKAv1-MD5"), "the algorithm survives");
        check(out.contains("qop=auth"), "qop survives");
        check(out.contains("nc=00000001"), "the nonce COUNT is not mistaken for a nonce");
        check(out.contains("spi-c=1234") && out.contains("port-s=9900"),
                "Security-Verify is untouched");
        check(out.contains("Call-ID: 0f8c1b2a3d4e"), "the Call-ID survives");
        check(out.startsWith("REGISTER sip:ims.mnc005"), "the request line survives");

        // A 401 challenge carries the same material in the other direction.
        String chal = "SIP/2.0 401 Unauthorized\r\n"
                + "WWW-Authenticate: Digest realm=\"ims.example\", "
                + "nonce=CHALLENGEWITHOUTQUOTES, algorithm=AKAv1-MD5\r\n\r\n";
        out = JoanSipRedact.redact(chal);
        gone(out, "CHALLENGEWITHOUTQUOTES", "an UNQUOTED nonce is redacted too");
        check(out.contains("nonce=\"<redacted:22>\""), "an unquoted nonce keeps its length");
        check(out.contains("realm=\"ims.example\""), "the realm survives on a challenge");

        // RFC 3261 line folding: a header may continue on the next line.
        String folded = "SIP/2.0 401 Unauthorized\r\n"
                + "WWW-Authenticate: Digest realm=\"ims.example\",\r\n"
                + " nonce=\"FOLDEDSECRET\",\r\n"
                + "\talgorithm=AKAv1-MD5\r\n"
                + "Contact: <sip:10.1.2.3>\r\n\r\n";
        out = JoanSipRedact.redact(folded);
        gone(out, "FOLDEDSECRET", "a nonce on a FOLDED continuation line is redacted");
        check(out.contains("Contact: <sip:10.1.2.3>"),
                "the header after the fold is not swallowed");

        // Case-insensitivity, both in the header name and the parameter.
        String mixed = "REGISTER sip:x SIP/2.0\r\n"
                + "AUTHORIZATION: Digest NONCE=\"MIXEDCASE\", Response=\"deadbeef\"\r\n\r\n";
        out = JoanSipRedact.redact(mixed);
        gone(out, "MIXEDCASE", "an upper-case NONCE= is redacted");
        gone(out, "deadbeef", "a mixed-case Response= is redacted");

        // A word ending in "nonce" is not a nonce parameter, and a
        // non-auth header is not rewritten at all.
        String other = "REGISTER sip:x SIP/2.0\r\n"
                + "Subject: nonce=NOTAHEADERSECRET\r\n"
                + "P-Access-Network-Info: 3GPP-E-UTRAN-FDD\r\n\r\n";
        out = JoanSipRedact.redact(other);
        check(out.contains("nonce=NOTAHEADERSECRET"),
                "a non-auth header is left alone");
        check(out.contains("P-Access-Network-Info: 3GPP-E-UTRAN-FDD"),
                "PANI is left alone");

        // The body is never rewritten: Content-Length was not recomputed.
        String withBody = "REGISTER sip:x SIP/2.0\r\n"
                + "Authorization: Digest nonce=\"HDRSECRET\"\r\n"
                + "Content-Length: 18\r\n"
                + "\r\n"
                + "nonce=BODYVERBATIM\r\n";
        out = JoanSipRedact.redact(withBody);
        gone(out, "HDRSECRET", "a header secret is still redacted when a body follows");
        check(out.contains("nonce=BODYVERBATIM"),
                "the body is passed through byte for byte");

        // --- line endings: the one thing a PTY destroys silently ---
        // adb shell rewrites LF as CRLF, so a SIP message's own CRLF
        // arrives as CRCRLF. The counts are taken before normalisation so
        // a mangled transfer can neither hide a real fault nor invent one.
        String crlfMsg = "REGISTER sip:x SIP/2.0\r\nVia: a\r\n\r\n";
        String facts = JoanSipRedact.lineEndings(crlfMsg);
        check(facts.contains("crlf=3") && facts.contains("lf=0")
                        && facts.contains("cr=0"),
                "a well-formed message counts only CRLF (got " + facts + ")");
        check(facts.contains("bytes=" + crlfMsg.length()),
                "the byte count is of the message as sent");
        check(facts.contains("sha256=") && !facts.contains("unavailable"),
                "the digest is computed");

        String bareLf = "REGISTER sip:x SIP/2.0\nVia: a\r\n\r\n";
        facts = JoanSipRedact.lineEndings(bareLf);
        check(facts.contains("lf=1") && facts.contains("crlf=2"),
                "a BARE LF is counted, not silently accepted (got " + facts + ")");

        facts = JoanSipRedact.lineEndings("a\rb");
        check(facts.contains("cr=1"), "a lone CR is counted too (got " + facts + ")");

        // Normalisation is what makes the file survive the trip.
        String norm = JoanSipRedact.normalize(crlfMsg);
        check(norm.indexOf('\r') < 0, "normalize leaves no CR behind");
        check(norm.equals("REGISTER sip:x SIP/2.0\nVia: a\n\n"),
                "normalize collapses CRLF to LF without losing a line");
        check(JoanSipRedact.normalize("a\rb").equals("a\nb"),
                "a lone CR normalises to a line break, not to nothing");
        check(JoanSipRedact.normalize(null) == null, "normalize passes null through");

        // Two different messages must not collide; the same one must match.
        check(JoanSipRedact.lineEndings(crlfMsg).equals(
                        JoanSipRedact.lineEndings(crlfMsg)),
                "the digest is stable across calls");
        check(!JoanSipRedact.lineEndings(crlfMsg).equals(
                        JoanSipRedact.lineEndings(bareLf)),
                "a message differing only in line endings gets a different digest");

        // Redaction must still work on a message that arrives LF-only.
        String lfOnly = "REGISTER sip:x SIP/2.0\n"
                + "Authorization: Digest nonce=\"LFONLYSECRET\"\n\n";
        String red = JoanSipRedact.redact(lfOnly);
        gone(red, "LFONLYSECRET", "redaction works on an LF-only message");

        check(JoanSipRedact.redact(null) == null, "null in, null out");
        check("".equals(JoanSipRedact.redact("")), "empty in, empty out");

        if (fail != 0) {
            System.out.println("sip capture redaction: FAIL " + fail);
            System.exit(1);
        }
        System.out.println("ok   sip capture redaction + line-ending tests (37 checks)");
    }
}
