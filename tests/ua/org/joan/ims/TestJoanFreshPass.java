package org.joan.ims;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Production-path counterexamples for the fresh review. No carrier or Android services. */
public final class TestJoanFreshPass extends TestJoanUa {
    static int checks, failures;
    static void check(String name, boolean ok) {
        checks++;
        if (!ok) failures++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }
    static String response(String req, int status, String body, String extra) {
        return TestJoanMerge.response(req, status, body, extra);
    }
    static String initial(String cid, int seq) {
        return request("INVITE", cid, seq, SDP).replace("To: " + FROM,
                "To: <sip:joan@example.invalid>");
    }
    static boolean sentStatus(Wire w, int status) {
        return w.sent.stream().anyMatch(s -> s.startsWith("SIP/2.0 " + status + " "));
    }
    static String[] parseUicc(byte[] b) throws Exception {
        Method m = JoanAka.class.getDeclaredMethod("parseUiccTlv", byte[].class);
        m.setAccessible(true);
        return (String[]) m.invoke(null, b);
    }
    static String apdu(String hex) throws Exception {
        Method m = JoanAka.class.getDeclaredMethod("parseApduAka", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, hex);
    }
    static byte[] success(int len) {
        byte[] d = new byte[len + 36];
        d[0] = (byte) 0xdb; d[1] = (byte) len;
        Arrays.fill(d, 2, 2 + len, (byte) 0x11);
        d[2 + len] = 16; Arrays.fill(d, 3 + len, 19 + len, (byte) 0x22);
        d[19 + len] = 16; Arrays.fill(d, 20 + len, d.length, (byte) 0x33);
        return d;
    }
    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte v : b) s.append(String.format("%02x", v & 255));
        return s.toString();
    }
    static class AnswerWire extends Wire {
        final boolean reliable;
        AnswerWire(boolean reliable) throws Exception { this.reliable = reliable; }
        @Override public void send(DatagramPacket p) throws IOException {
            super.send(p);
            String req = new String(p.getData(), p.getOffset(), p.getLength(), StandardCharsets.US_ASCII);
            if (req.startsWith("INVITE ")) {
                try {
                    if (reliable) inbound(response(req, 183, SDP, "RSeq: 19\r\nRequire: 100rel\r\n"));
                    inbound(response(req, 200, SDP, "Contact: <sip:peer@example.invalid>\r\n"));
                } catch (Exception e) { throw new IOException(e); }
            }
        }
    }
    static AnswerWire mo(boolean reliable) throws Exception {
        reset().close(); set("sCall", false); set("sDlg", null); set("sOurToTag", null);
        AnswerWire w = new AnswerWire(reliable); set("sSockC", w);
        JoanSipUa.sInviteTimeoutMs = 300;
        check("MO-setup", "OK".equals(JoanSipUa.invite("sip:peer@example.invalid")));
        return w;
    }
    public static void main(String[] args) throws Exception {
        // AOSP EAP-AKA parser: explicit DB + three length-delimited fields.
        for (int len : new int[]{4, 8, 16}) {
            String[] p = parseUicc(success(len));
            check("AKA-valid-RES-" + len, p != null && p[0].equals("11".repeat(len))
                    && p[1].equals("22".repeat(16)) && p[2].equals("33".repeat(16)));
        }
        byte[] bad = success(16); bad[18] = 15;
        check("AKA-malformed-DB-not-raw-keys", parseUicc(bad) == null);
        bad = success(16); bad[0] = (byte) 0xdc;
        check("AKA-sync-failure-not-success", parseUicc(bad) == null);
        check("AKA-untagged-random-not-keys", parseUicc(new byte[48]) == null);
        check("AKA-good-APDU-status", apdu(hex(success(4)) + "9000") != null);
        check("AKA-failed-APDU-status", apdu(hex(success(4)) + "6982") == null);
        check("AKA-extra-hex-key-data-rejected", JoanAka.parseAuthResponse(
                "RES=11111111 CK=" + "22".repeat(17) + " IK=" + "33".repeat(16)) == null);

        // Real MO -> reliable provisional -> final ACK -> remote BYE.
        AnswerWire aw = mo(true);
        String ack = aw.sent.stream().filter(s -> s.startsWith("ACK ")).findFirst().orElse("");
        check("MO-ACK-retains-INVITE-CSeq-after-PRACK", JoanSipBuilder.cseqForMethod(ack, "ACK") == 1);
        String remoteBye = request("BYE", JoanSipUa.currentCallId(), 17, "")
                .replace("From: " + TO, "From: " + get("sToHdr"))
                .replace("To: " + FROM, "To: " + get("sFromHdr"));
        aw.sent.clear(); inbound(remoteBye);
        check("MO-peer-BYE-ends-call", !JoanSipUa.callActive() && sentStatus(aw, 200));

        // Local CSeq is not the remote sequence space; never fake a 200 SDP answer.
        Wire w = reset(); ((JoanSipBuilder.Dialog) get("sDlg")).cseq = 900;
        inbound(request("INVITE", "A", 42, SDP));
        check("remote-CSeq-independent-of-local", !sentStatus(w, 200) && get("sHeldInvite") == null);
        w = reset(); inbound(request("INVITE", "A", 20, SDP).replace("tag=remote", "tag=other"));
        check("reINVITE-must-match-both-tags", sentStatus(w, 481) && get("sHeldInvite") == null);
        w = reset(); set("sCall", false); set("sDlg", null);
        String ringing = initial("ring", 71); inbound(ringing); w.sent.clear();
        inbound(initial("second", 72));
        check("new-INVITE-cannot-overwrite-ringing", ringing.equals(get("sHeldInvite")) && sentStatus(w, 486));
        w.sent.clear(); String wrongCancel = request("CANCEL", "ring", 999, "")
                .replace("To: " + FROM, "To: <sip:joan@example.invalid>");
        inbound(wrongCancel);
        check("CANCEL-requires-transaction-not-Call-ID", ringing.equals(get("sHeldInvite")) && sentStatus(w, 481));
        w = reset(); set("sCall", false); set("sDlg", null); ringing = initial("mt", 71);
        inbound(ringing); check("MT-answer-setup", "OK".equals(JoanSipUa.answer()));
        String first200 = w.sent.stream().filter(s -> s.startsWith("SIP/2.0 200 ")).findFirst().orElse("");
        w.sent.clear(); inbound(ringing);
        check("MT-retransmitted-INVITE-replays-exact-200", w.sent.contains(first200) && !first200.isEmpty());

        // Multi-hop responses must retain every Via and Record-Route in original order.
        w = reset(); String req = request("OPTIONS", "probe", 2, "")
                .replace("From: ", "Via: SIP/2.0/UDP 127.0.0.2;branch=z9hG4bKsecond\r\nRecord-Route: <sip:a.invalid;lr>\r\nRecord-Route: <sip:b.invalid;lr>\r\nFrom: ");
        inbound(req); String r = w.sent.peek();
        check("response-preserves-all-Vias", JoanSipBuilder.headers(r, "Via").size() == 2);
        check("response-preserves-all-Record-Routes", JoanSipBuilder.headers(r, "Record-Route").size() == 2);
        String extra = (String) invoke("buildResponse", new Class<?>[]{String.class,int.class,String.class,JoanSipBuilder.Id.class,String.class,String.class,String.class}, req, 488, "Not Acceptable Here", identity(), "local", null, "Reason: SIP;cause=488");
        check("extra-header-terminated-before-Content-Length", "0".equals(JoanSipBuilder.header(extra, "Content-Length")));

        // A focus dialog must adopt its negotiated media and local tag, not a zeroed Leg.
        TestJoanMerge.FocusWire fw = TestJoanMerge.setup();
        String merged = JoanSipUa.merge(JoanCarrierProfile.defaults("310", "260"));
        check("focus-merge-setup", merged.startsWith("OK"));
        check("focus-negotiated-media-reaches-live-leg", JoanSipUa.mediaIp() != null && JoanSipUa.mediaPort() == 40002);
        check("focus-local-tag-preserved", JoanSipBuilder.tagOf((String)get("sFromHdr")).equals(get("sOurToTag")));
        fw.close();
        System.out.println("FRESH_PASS_CHECKS=" + checks + " FAILURES=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
