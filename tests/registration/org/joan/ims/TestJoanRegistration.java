package org.joan.ims;

import java.io.*;
import java.net.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Offline regression tests for the registration/IPsec transport and the
 * driver's network-lifecycle wiring. Synthetic sockets only: everything is
 * loopback or in-memory, nothing ever leaves the host, no radio/SIM/AKA
 * secrets are exercised. Exit 1 means regression failures.
 *
 * Defects covered (source-evidenced fixes in JoanRegTransport /
 * JoanRegLifecycle / JoanAppRegister / JoanDriver):
 *  - a REGISTER reply with the wrong Call-ID/branch/CSeq was accepted;
 *  - coalesced 100 Trying + final frames stranded bytes at adoption;
 *  - TCP connected-but-silent was reported as a connect failure, and any
 *    TCP failure fell back to UDP (now: connect-phase only, fail closed);
 *  - inbound transform apply failures on the TCP socket were swallowed;
 *  - a network-loss poke never cleared the UA's stale registration;
 *  - boot/duplicate-start pokes interrupted registered/backoff sleeps;
 *  - the IMS network was not pinned to the selected subscription;
 *  - an in-flight REGISTER cycle could outlive a network/state change.
 */
public class TestJoanRegistration {
    static int checks;
    static final InetAddress PEER;
    static {
        try {
            PEER = InetAddress.getByAddress(new byte[]{(byte) 192, 0, 2, 1});
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    static final String REQUEST = "REGISTER sip:example.invalid SIP/2.0\r\n"
            + "Via: SIP/2.0/UDP 192.0.2.2:40010;branch=z9hG4bKreg\r\n"
            + "Call-ID: reg@example.invalid\r\nCSeq: 2 REGISTER\r\n"
            + "Content-Length: 0\r\n\r\n";

    static void check(boolean yes, String name) {
        checks++;
        if (!yes) {
            throw new AssertionError(name);
        }
        System.out.println("PASS " + name);
    }

    static String reply(int code) {
        return "SIP/2.0 " + code + " Fixture\r\n"
                + REQUEST.substring(REQUEST.indexOf("Via:"));
    }

    static String replyHdr(int code, String via, String callId, String cseq) {
        return "SIP/2.0 " + code + " Fixture\r\nVia: " + via + "\r\nCall-ID: "
                + callId + "\r\nCSeq: " + cseq + "\r\nContent-Length: 0\r\n\r\n";
    }

    /* ------------------------- fake transports ------------------------ */

    static class DatagramWire extends DatagramSocket {
        final Queue<String> frames = new ArrayDeque<>();
        int sends;

        DatagramWire() throws Exception {
            super((SocketAddress) null);
        }

        @Override
        public void send(DatagramPacket p) {
            sends++;
        }

        @Override
        public void receive(DatagramPacket p) throws IOException {
            String s = frames.poll();
            if (s == null) {
                throw new SocketTimeoutException("offline empty");
            }
            byte[] b = s.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(b, 0, p.getData(), 0, b.length);
            p.setLength(b.length);
            p.setAddress(PEER);
            p.setPort(40020);
        }
    }

    static class FailSendWire extends DatagramSocket {
        FailSendWire() throws Exception {
            super((SocketAddress) null);
        }

        @Override
        public void send(DatagramPacket p) throws IOException {
            throw new IOException("fixture no route");
        }
    }

    static JoanAppRegister.JoanRegTransport.UdpResult udp(DatagramWire w,
                                                           String identity,
                                                           int timeoutMs)
            throws Exception {
        return JoanAppRegister.JoanRegTransport.sendRecvUdp(w, null, PEER, 40020,
                REQUEST.getBytes(StandardCharsets.US_ASCII), timeoutMs,
                identity);
    }

    static String legacyUdp(DatagramWire w, int timeoutMs) throws Exception {
        return JoanAppRegister.sendRecv(w, null, PEER, 40020,
                REQUEST.getBytes(StandardCharsets.US_ASCII), timeoutMs);
    }

    /* --------------------------- matching ----------------------------- */

    static void matchingTests() {
        check(JoanAppRegister.JoanRegTransport.finalMatches(REQUEST, reply(200)),
                "matching-final-accepted");
        check(!JoanAppRegister.JoanRegTransport.finalMatches(REQUEST,
                        replyHdr(200, "SIP/2.0/UDP 192.0.2.2:40010;branch=z9hG4bKreg",
                                "wrong@example.invalid", "2 REGISTER")),
                "wrong-call-id-rejected");
        check(!JoanAppRegister.JoanRegTransport.finalMatches(REQUEST,
                        replyHdr(200, "SIP/2.0/UDP 192.0.2.2:40010;branch=z9hG4bKother",
                                "reg@example.invalid", "2 REGISTER")),
                "wrong-branch-rejected");
        check(!JoanAppRegister.JoanRegTransport.finalMatches(REQUEST,
                        replyHdr(200, "SIP/2.0/UDP 192.0.2.2:40010;branch=z9hG4bKreg",
                                "reg@example.invalid", "3 REGISTER")),
                "wrong-cseq-number-rejected");
        check(!JoanAppRegister.JoanRegTransport.finalMatches(REQUEST,
                        replyHdr(200, "SIP/2.0/UDP 192.0.2.2:40010;branch=z9hG4bKreg",
                                "reg@example.invalid", "2 MESSAGE")),
                "wrong-cseq-method-rejected");
        check(!JoanAppRegister.JoanRegTransport.finalMatches(REQUEST,
                        replyHdr(200, "SIP/2.0/UDP 192.0.2.2:40010;branch=z9hG4bKreg",
                                "reg@example.invalid", "2 REGISTER")
                                .replace("Call-ID: reg@example.invalid\r\n", "")),
                "missing-call-id-rejected");
        check(!JoanAppRegister.JoanRegTransport.finalMatches(REQUEST, reply(100)),
                "provisional-never-final");
        check("z9hG4bKreg".equals(JoanAppRegister.JoanRegTransport.viaBranch(
                        "SIP/2.0/UDP 192.0.2.2:40010;branch=z9hG4bKreg;rport")),
                "via-branch-parsed");
        /* One REGISTER series per identity: Call-ID and From-tag survive
         * across attempts, CSeq only rises, and a new identity starts over.
         * A registrar rejects a REGISTER whose CSeq did not advance, so the
         * monotonic check is the load-bearing one. */
        java.security.SecureRandom rng = new java.security.SecureRandom();
        JoanSipBuilder.Params ports = JoanSipBuilder.Params.random(rng);
        JoanAppRegister.RegSeries series = new JoanAppRegister.RegSeries();
        JoanSipBuilder.Txn a = series.newAttempt("impi@example.invalid", ports, rng);
        int c1 = series.nextCseq();
        JoanSipBuilder.Txn b = series.newAttempt("impi@example.invalid", ports, rng);
        int c2 = series.nextCseq();
        check(a.callId.equals(b.callId), "retry keeps the registration Call-ID");
        check(a.fromTag.equals(b.fromTag), "retry keeps the From-tag");
        check(c2 > c1, "CSeq rises across attempts");
        check(!a.branch.equals(b.branch), "each attempt is its own transaction");
        check(!a.cnonce.equals(b.cnonce), "each attempt gets a fresh cnonce");
        int prev = c2;
        for (int i = 0; i < 5; i++) {
            series.newAttempt("impi@example.invalid", ports, rng);
            int c = series.nextCseq();
            check(c > prev, "CSeq still rising at attempt " + (i + 3));
            prev = c;
        }
        JoanSipBuilder.Txn other = series.newAttempt("other@example.invalid", ports, rng);
        check(!other.callId.equals(a.callId),
                "a different identity starts a new series");
        check(series.nextCseq() == 1, "a new series restarts CSeq");

        /* A reject trail names the domain, never the identity: a derived
         * IMPU carries the IMSI in its user part. */
        check("ims.mnc002.mcc460.3gppnetwork.org".equals(JoanAppRegister.domainOf(
                        "<sip:460021234567890@ims.mnc002.mcc460.3gppnetwork.org>")),
                "reject-detail-keeps-domain");
        check(!String.valueOf(JoanAppRegister.domainOf(
                        "<sip:460021234567890@ims.mnc002.mcc460.3gppnetwork.org>"))
                        .contains("460021234567890"),
                "reject-detail-drops-identity");
        check("ims.mnc004.mcc452.3gppnetwork.org".equals(JoanAppRegister.domainOf(
                        "REGISTER sip:ims.mnc004.mcc452.3gppnetwork.org SIP/2.0")),
                "reject-detail-reads-request-uri-domain");
        check(JoanAppRegister.domainOf("tel:+84900000000") == null,
                "reject-detail-ignores-non-sip");
        check("2".equals(JoanAppRegister.JoanRegTransport.cseqNumber(" 2 REGISTER "))
                        && "REGISTER".equals(JoanAppRegister.JoanRegTransport.cseqMethod(" 2 REGISTER ")),
                "cseq-parts-parsed");
    }

    /* ----------------------------- UDP -------------------------------- */

    static void udpTests() throws Exception {
        try (DatagramWire w = new DatagramWire()) {
            // Wrong Call-ID final must be ignored; the matching 401 wins.
            w.frames.add(reply(200).replace("reg@example.invalid",
                    "wrong@example.invalid"));
            w.frames.add(reply(401));
            String got = udp(w, REQUEST, 2000).reply;
            check(got != null && got.startsWith("SIP/2.0 401"),
                    "udp-ignores-wrong-call-id-final");
        }
        try (DatagramWire w = new DatagramWire()) {
            // Wrong branch final must be ignored; the matching 401 wins.
            w.frames.add(replyHdr(200,
                    "SIP/2.0/UDP 192.0.2.2:40010;branch=z9hG4bKother",
                    "reg@example.invalid", "2 REGISTER"));
            w.frames.add(reply(401));
            String got = udp(w, REQUEST, 2000).reply;
            check(got != null && got.startsWith("SIP/2.0 401"),
                    "udp-ignores-wrong-branch-final");
        }
        try (DatagramWire w = new DatagramWire()) {
            // 100 Trying must not end the transaction; the final does.
            w.frames.add(reply(100));
            w.frames.add(reply(401));
            String got = udp(w, REQUEST, 2000).reply;
            check(got != null && got.startsWith("SIP/2.0 401"),
                    "udp-skips-provisional");
        }
        try (DatagramWire w = new DatagramWire()) {
            // Legacy wrapper (identity-less) accepts any final.
            w.frames.add(reply(401));
            check(legacyUdp(w, 1000).startsWith("SIP/2.0 401"),
                    "legacy-sendrecv-wrapper");
        }
        try (DatagramWire w = new DatagramWire()) {
            w.frames.add(reply(100));
            check(legacyUdp(w, 30) == null,
                    "legacy-sendrecv-provisional-not-answer");
        }
        try (FailSendWire w = new FailSendWire()) {
            try {
                JoanAppRegister.JoanRegTransport.sendRecvUdp(w, null, PEER, 40020,
                        REQUEST.getBytes(StandardCharsets.US_ASCII), 50,
                        REQUEST);
                throw new AssertionError(
                        "udp-first-send: expected IOException");
            } catch (IOException expected) {
                check(true, "udp-first-send-failure-propagates");
            }
        }
    }

    /* A timeout must retain evidence instead of discarding the entire
     * result. This also protects the REG2 caller's retransmission count. */
    static void udpDiagnosticTests() throws Exception {
        try (DatagramWire w = new DatagramWire()) {
            w.frames.add(reply(100));
            w.frames.add(reply(401).replace("reg@example.invalid",
                    "wrong@example.invalid"));
            JoanAppRegister.JoanRegTransport.UdpResult result = udp(w, REQUEST, 30);
            check(result != null, "udp-timeout-retains-diagnostic-result");
            check(result.reply == null, "udp-timeout-is-not-a-final-response");
            check(result.retx == w.sends - 1,
                    "udp-timeout-retains-successful-retransmissions");
            check(result.stats.received == 2 && result.stats.provisionals == 1
                            && result.stats.rejected == 1,
                    "udp-timeout-retains-provisional-and-rejected-counts");
            check(result.stats.receiveErrors == 0,
                    "udp-normal-poll-timeout-is-not-receive-error");
        }
        // Real loopback UDP: the peer socket exists but intentionally never
        // answers. Exercises Timer E without sleeps or a fake clock.
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (DatagramSocket peer = new DatagramSocket(0, loopback);
             DatagramSocket client = new DatagramSocket(0, loopback)) {
            JoanAppRegister.JoanRegTransport.UdpResult r =
                    JoanAppRegister.JoanRegTransport.sendRecvUdp(client, null,
                            loopback, peer.getLocalPort(),
                            REQUEST.getBytes(StandardCharsets.US_ASCII), 900, REQUEST);
            check(r.reply == null && r.retx > 0,
                    "udp-real-timeout-retains-nonzero-retransmissions");
            check(r.stats.sent == r.retx + 1 && r.stats.received == 0,
                    "udp-sent-count-is-local-api-observation");
        }
        try (DatagramSocket peer = new DatagramSocket(0, loopback);
             DatagramSocket client = new DatagramSocket(0, loopback) {
                 int attempts;
                 @Override
                 public void send(DatagramPacket p) throws IOException {
                     if (++attempts > 1) {
                         throw new IOException("private-address-and-subscriber");
                     }
                     super.send(p);
                 }
             }) {
            JoanAppRegister.JoanRegTransport.UdpResult r =
                    JoanAppRegister.JoanRegTransport.sendRecvUdp(client, null,
                            loopback, peer.getLocalPort(),
                            REQUEST.getBytes(StandardCharsets.US_ASCII), 900, REQUEST);
            check(r.reply == null && r.stats.sendErrors > 0 && r.retx == 0
                            && r.stats.sent == 1,
                    "udp-failed-retransmit-not-counted-as-success");
            check(r.stats.summary("reg1").contains("reg1_send_error=IOException")
                            && !r.stats.summary("reg1")
                                    .contains("private-address-and-subscriber"),
                    "udp-retransmit-error-type-without-sensitive-message");
        }
        check(JoanAppRegister.JoanRegTransport.fallbackUnprotectedTcp(
                        JoanAppRegister.JoanRegTransport.TcpFail.CONNECT)
                        && JoanAppRegister.JoanRegTransport.fallbackUnprotectedTcp(
                        JoanAppRegister.JoanRegTransport.TcpFail.SETUP),
                "unprotected-tcp-connect-or-setup-may-retry-udp");
        check(!JoanAppRegister.JoanRegTransport.fallbackUnprotectedTcp(
                        JoanAppRegister.JoanRegTransport.TcpFail.TIMEOUT)
                        && !JoanAppRegister.JoanRegTransport.fallbackUnprotectedTcp(
                        JoanAppRegister.JoanRegTransport.TcpFail.SEND)
                        && !JoanAppRegister.JoanRegTransport.fallbackUnprotectedTcp(
                        JoanAppRegister.JoanRegTransport.TcpFail.READ)
                        && !JoanAppRegister.JoanRegTransport.fallbackUnprotectedTcp(null),
                "connected-tcp-timeout-does-not-open-a-second-udp-transaction");
        JoanAppRegister.JoanRegTransport.UdpStats silent =
                new JoanAppRegister.JoanRegTransport.UdpStats();
        silent.sent = 4;
        check(silent.summary("reg2").contains("reg2_send_ok=4")
                        && silent.summary("reg2").contains("reg2_rx=0")
                        && silent.summary("reg2").contains("reg2_rejected=0")
                        && silent.summary("reg2").contains("reg2_rx_err=0"),
                "reg2-timeout-can-emit-receive-counters-not-only-retx");
    }

    static JoanAppRegister.Reg1Result reg1(JoanAppRegister.UdpSocketSource source)
            throws Exception {
        return JoanAppRegister.exchangeReg1(source, PEER,
                REQUEST.getBytes(StandardCharsets.US_ASCII), 30, REQUEST);
    }

    static class ReceiveErrorWire extends DatagramWire {
        boolean first = true;
        ReceiveErrorWire() throws Exception {}

        @Override
        public void receive(DatagramPacket packet) throws IOException {
            if (first) {
                first = false;
                throw new PortUnreachableException("private-address-and-subscriber");
            }
            super.receive(packet);
        }
    }

    static void reg1BoundaryTests() throws Exception {
        // Bind/permission/send failures must not become network silence, and
        // exception messages must never leak into the shareable diagnostic.
        JoanAppRegister.Reg1Result setup = reg1(() -> {
            throw new BindException("private-address-and-subscriber");
        });
        check(setup.reply == null && setup.diagnostic.contains("reg1_result=setup")
                        && setup.diagnostic.contains("error=BindException")
                        && setup.diagnostic.contains("reg1_send_ok=0"),
                "reg1-setup-failure-is-not-timeout");
        check(!setup.diagnostic.contains("private-address-and-subscriber")
                        && !setup.diagnostic.contains(" OK"),
                "reg1-error-message-not-exposed-or-success-shaped");
        JoanAppRegister.Reg1Result permission = reg1(() -> {
            throw new SecurityException("private-address-and-subscriber");
        });
        check(permission.diagnostic.contains("error=SecurityException"),
                "reg1-permission-type-retained");
        try (FailSendWire w = new FailSendWire()) {
            JoanAppRegister.Reg1Result r = reg1(() -> w);
            check(r.reply == null && r.diagnostic.contains("reg1_result=send")
                            && r.diagnostic.contains("reg1_send_err=1")
                            && r.diagnostic.contains("reg1_send_ok=0"),
                    "reg1-first-send-failure-reaches-caller");
            check(!r.diagnostic.contains("fixture no route"),
                    "reg1-send-exception-message-not-exposed");
            check(w.isClosed(), "reg1-closes-failed-send-socket");
        }
        try (DatagramWire w = new DatagramWire()) {
            JoanAppRegister.Reg1Result r = reg1(() -> w);
            check(r.reply == null && r.diagnostic.contains("reg1_result=timeout")
                            && r.diagnostic.contains("reg1_rx=0 ")
                            && r.diagnostic.contains("reg1_send_ok=1"),
                    "reg1-empty-timeout-reported-with-send-count");
            check(r.diagnostic.contains("reg1len="
                            + REQUEST.getBytes(StandardCharsets.US_ASCII).length),
                    "reg1-actual-message-byte-length-retained");
            check(w.isClosed(), "reg1-closes-timed-out-socket");
        }
        try (DatagramWire w = new DatagramWire()) {
            w.frames.add(reply(100));
            w.frames.add("malformed private-address-and-subscriber");
            w.frames.add(reply(401).replace("reg@example.invalid", "wrong@example.invalid"));
            JoanAppRegister.Reg1Result r = reg1(() -> w);
            check(r.reply == null && r.diagnostic.contains("reg1_rx=3 ")
                            && r.diagnostic.contains("reg1_1xx=1 ")
                            && r.diagnostic.contains("reg1_rejected=2 "),
                    "reg1-no-matching-final-distinguished-from-zero-packets");
            check(!r.diagnostic.contains("private-address-and-subscriber")
                            && !r.diagnostic.contains("example.invalid"),
                    "reg1-rejected-sip-never-logged");
        }
        try (ReceiveErrorWire w = new ReceiveErrorWire()) {
            JoanAppRegister.Reg1Result r = reg1(() -> w);
            check(r.reply == null && r.diagnostic.contains("reg1_rx_err=1 ")
                            && r.diagnostic.contains("reg1_rx_error=PortUnreachableException"),
                    "reg1-receive-error-is-visible-on-timeout");
            check(!r.diagnostic.contains("private-address-and-subscriber"),
                    "reg1-receive-exception-message-not-exposed");
        }
        try (ReceiveErrorWire w = new ReceiveErrorWire()) {
            w.frames.add(reply(401));
            JoanAppRegister.Reg1Result r = reg1(() -> w);
            check(reply(401).equals(r.reply) && r.diagnostic.contains("reg1_result=final")
                            && r.diagnostic.contains("reg1_rx_err=1 "),
                    "reg1-preserves-later-final-after-receive-error");
            check(w.isClosed(), "reg1-closes-completed-socket");
        }
        try (ReceiveErrorWire primary = new ReceiveErrorWire();
             DatagramWire alt = new DatagramWire()) {
            alt.frames.add(reply(401));
            JoanAppRegister.JoanRegTransport.UdpResult r =
                    JoanAppRegister.JoanRegTransport.sendRecvUdp(primary, alt, PEER,
                            40020, REQUEST.getBytes(StandardCharsets.US_ASCII), 100, REQUEST);
            check(reply(401).equals(r.reply) && r.stats.receiveErrors == 1,
                    "udp-alternate-socket-still-answers-after-primary-error");
        }
    }

    /* ----------------------------- TCP -------------------------------- */

    static void tcpFramingTests() throws Exception {
        // A coalesced 100 + final must be fully consumed; the leftover
        // complete frame must survive for the next read, not be lost.
        String one = reply(200);
        String two = reply(401);
        byte[] seg = (one + two).getBytes(StandardCharsets.US_ASCII);
        StringBuilder acc = new StringBuilder();
        String first = JoanAppRegister.JoanRegTransport.readFinal(
                new ByteArrayInputStream(seg), REQUEST, acc,
                System.currentTimeMillis() + 1000, new byte[4096]);
        check(one.equals(first), "tcp-coalesced-first-final-drained");
        check(two.equals(acc.toString()),
                "tcp-leftover-frame-preserved-in-acc");
        String second = JoanAppRegister.JoanRegTransport.readFinal(
                new ByteArrayInputStream(new byte[0]), REQUEST, acc,
                System.currentTimeMillis() + 1000, new byte[4096]);
        check(two.equals(second) && acc.length() == 0,
                "tcp-buffered-final-delivered-next-call");

        // A mismatched final is dropped, the matching one wins.
        StringBuilder acc2 = new StringBuilder();
        String wrong = replyHdr(200,
                "SIP/2.0/UDP 192.0.2.2:40010;branch=z9hG4bKreg",
                "wrong@example.invalid", "2 REGISTER");
        String right = reply(401);
        String got = JoanAppRegister.JoanRegTransport.readFinal(
                new ByteArrayInputStream((wrong + right).getBytes(
                        StandardCharsets.US_ASCII)), REQUEST, acc2,
                System.currentTimeMillis() + 1000, new byte[4096]);
        check(right.equals(got), "tcp-drops-mismatched-final");

        // EOF without a final is null, not a partial frame.
        StringBuilder acc3 = new StringBuilder();
        String eof = JoanAppRegister.JoanRegTransport.readFinal(
                new ByteArrayInputStream(new byte[0]), REQUEST, acc3,
                System.currentTimeMillis() + 1000, new byte[4096]);
        check(eof == null, "tcp-eof-returns-null");

        // An expired deadline returns null even with buffered bytes that
        // do not yet form a complete frame.
        StringBuilder acc4 = new StringBuilder();
        acc4.append(one.substring(0, 20)); // no terminating \r\n\r\n
        String expired = JoanAppRegister.JoanRegTransport.readFinal(
                new ByteArrayInputStream(new byte[0]), REQUEST, acc4,
                System.currentTimeMillis() - 1, new byte[4096]);
        check(expired == null, "tcp-deadline-returns-null");
    }

    static void tcpSocketTests() throws Exception {
        // Connect-refused must classify as CONNECT (the only phase the
        // caller may fall back to UDP from). Fully loopback.
        int port;
        try (ServerSocket ss = new ServerSocket(0, 1,
                InetAddress.getLoopbackAddress())) {
            port = ss.getLocalPort();
        }
        try {
            JoanAppRegister.JoanRegTransport.sendRecvTcp(null,
                    InetAddress.getLoopbackAddress(), 0,
                    InetAddress.getLoopbackAddress(), port,
                    REQUEST.getBytes(StandardCharsets.US_ASCII), 300,
                    null, null, null, REQUEST);
            throw new AssertionError("tcp-connect-refused: expected TcpFail");
        } catch (JoanAppRegister.JoanRegTransport.TcpFail tf) {
            check(JoanAppRegister.JoanRegTransport.TcpFail.CONNECT.equals(tf.phase),
                    "tcp-connect-refused-classified");
        }

        // Connected but silent must classify as TIMEOUT (not CONNECT):
        // falling back to UDP here would double-fire the transaction.
        try (ServerSocket ss = new ServerSocket(0, 1,
                InetAddress.getLoopbackAddress())) {
            Thread acceptor = new Thread(() -> {
                try (Socket s = ss.accept()) {
                    Thread.sleep(3000); // hold open, never answer
                } catch (Exception ignored) {
                    // fixture end
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
            try {
                JoanAppRegister.JoanRegTransport.sendRecvTcp(null,
                        InetAddress.getLoopbackAddress(), 0,
                        InetAddress.getLoopbackAddress(), ss.getLocalPort(),
                        REQUEST.getBytes(StandardCharsets.US_ASCII), 400,
                        null, null, null, REQUEST);
                throw new AssertionError("tcp-silent: expected TcpFail");
            } catch (JoanAppRegister.JoanRegTransport.TcpFail tf) {
                check(JoanAppRegister.JoanRegTransport.TcpFail.TIMEOUT.equals(tf.phase),
                        "tcp-connected-silent-is-timeout");
            }
        }

        // A real answered exchange: accept, echo the matching final,
        // assert the reply and the kept socket are returned.
        try (ServerSocket ss = new ServerSocket(0, 1,
                InetAddress.getLoopbackAddress())) {
            final String answer = reply(401);
            Thread acceptor = new Thread(() -> {
                try (Socket s = ss.accept()) {
                    InputStream is = s.getInputStream();
                    byte[] buf = new byte[4096];
                    int n = is.read(buf);
                    if (n <= 0) {
                        return;
                    }
                    s.getOutputStream().write(
                            answer.getBytes(StandardCharsets.US_ASCII));
                    s.getOutputStream().flush();
                    Thread.sleep(500);
                } catch (Exception ignored) {
                    // fixture end
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
            JoanAppRegister.JoanRegTransport.TcpResult tr = JoanAppRegister.JoanRegTransport.sendRecvTcp(null,
                    InetAddress.getLoopbackAddress(), 0,
                    InetAddress.getLoopbackAddress(), ss.getLocalPort(),
                    REQUEST.getBytes(StandardCharsets.US_ASCII), 3000,
                    null, null, null, REQUEST);
            check(answer.equals(tr.reply), "tcp-answered-final-returned");
            check(tr.keep != null && !tr.keep.isClosed(),
                    "tcp-keep-socket-survives");
            tr.keep.close();
        }
    }

    /* ------------------- epoch / in-flight claim ---------------------- */

    static void epochTests() throws Exception {
        Method get = JoanAppRegister.class.getDeclaredMethod("superseded",
                long.class);
        get.setAccessible(true);
        Field epochF = JoanAppRegister.class.getDeclaredField("sEpoch");
        epochF.setAccessible(true);
        long captured = epochF.getLong(null);
        check(!(Boolean) get.invoke(null, captured), "epoch-fresh-attempt");
        JoanAppRegister.stop();
        check((Boolean) get.invoke(null, captured), "epoch-superseded-after-stop");
        JoanAppRegister.stop();
        check((Boolean) get.invoke(null, epochF.getLong(null) - 1),
                "epoch-monotonic");

        Method begin = JoanAppRegister.class.getDeclaredMethod("tryBegin");
        Method end = JoanAppRegister.class.getDeclaredMethod("end");
        Method inProg = JoanAppRegister.class.getDeclaredMethod("inProgress");
        begin.setAccessible(true);
        end.setAccessible(true);
        inProg.setAccessible(true);
        check((Boolean) begin.invoke(null), "in-flight-first-claim-wins");
        check(!(Boolean) begin.invoke(null), "in-flight-second-claim-refused");
        check((Boolean) inProg.invoke(null), "in-flight-visible");
        end.invoke(null);
        check(!(Boolean) inProg.invoke(null), "in-flight-released");
    }

    /* --------------------- lifecycle decision table ------------------- */

    static void lifecycleTableTests() {
        check(JoanAppRegister.JoanRegLifecycle.routinePoke(
                        JoanAppRegister.JoanRegLifecycle.POKE_IMS_AVAILABLE)
                        && JoanAppRegister.JoanRegLifecycle.routinePoke(
                        JoanAppRegister.JoanRegLifecycle.POKE_IMS_LOST),
                "routine-poke-classified");
        check(!JoanAppRegister.JoanRegLifecycle.routinePoke("manual poke"),
                "manual-poke-classified");
        check(JoanAppRegister.JoanRegLifecycle.routinePoke(
                        JoanAppRegister.JoanRegLifecycle.POKE_IMS_IP_CHANGED),
                "an ip change is routine network chatter, not a user poke");

        /* Local address change. The question is not "did the link's
         * addresses change" but "is the one we are bound to still here":
         * a link that gains an address, or drops one we never used, has
         * invalidated nothing, and re-registering for it would drop a
         * working call. */
        String[] two = { "2001:db8::1", "2001:db8::2" };
        check(!JoanAppRegister.JoanRegLifecycle.localAddressGone(
                        two, "2001:db8::1"),
                "our address still present is not a change");
        check(JoanAppRegister.JoanRegLifecycle.localAddressGone(
                        two, "2001:db8::9"),
                "our address missing from the link is a change");
        check(!JoanAppRegister.JoanRegLifecycle.localAddressGone(
                        new String[] { "2001:db8::1" }, "2001:db8::1"),
                "a link that lost an address we never used is not a change");
        check(!JoanAppRegister.JoanRegLifecycle.localAddressGone(
                        new String[0], "2001:db8::1")
                        && !JoanAppRegister.JoanRegLifecycle.localAddressGone(
                                null, "2001:db8::1"),
                "an empty address list is mid-reconfiguration, not a loss");
        check(!JoanAppRegister.JoanRegLifecycle.localAddressGone(two, null)
                        && !JoanAppRegister.JoanRegLifecycle.localAddressGone(
                                two, ""),
                "no address of our own means nothing to compare");
        check(JoanAppRegister.JoanRegLifecycle.reregisterOnIpChange(
                        JoanAppRegister.JoanRegLifecycle.POKE_IMS_IP_CHANGED,
                        true),
                "an ip change with a binding re-registers");
        check(!JoanAppRegister.JoanRegLifecycle.reregisterOnIpChange(
                        JoanAppRegister.JoanRegLifecycle.POKE_IMS_IP_CHANGED,
                        false),
                "an ip change with no binding leaves discovery to it");
        check(!JoanAppRegister.JoanRegLifecycle.reregisterOnIpChange(
                        JoanAppRegister.JoanRegLifecycle.POKE_IMS_LOST, true),
                "only an ip change takes the ip-change path");
        /* A loss during a call is deferred, not acted on: the bearer drops
         * briefly for reasons a call should survive, and releasing the UA
         * takes the dialog with it. Deferring only counts if it expires. */
        check(!JoanAppRegister.JoanRegLifecycle.clearOnLost(
                        JoanAppRegister.JoanRegLifecycle.POKE_IMS_LOST, true, true),
                "a loss during a call does not clear immediately");
        check(JoanAppRegister.JoanRegLifecycle.deferClearForCall(
                        JoanAppRegister.JoanRegLifecycle.POKE_IMS_LOST, true, true),
                "a loss during a call starts the grace period");
        check(!JoanAppRegister.JoanRegLifecycle.deferClearForCall(
                        JoanAppRegister.JoanRegLifecycle.POKE_IMS_LOST, true, false),
                "a loss with no call is not deferred");
        check(!JoanAppRegister.JoanRegLifecycle.deferClearForCall(
                        JoanAppRegister.JoanRegLifecycle.POKE_IMS_AVAILABLE, true, true),
                "availability never starts a grace period");
        /* The hold ends when the CALL ends, not on a clock: a fixed grace
         * period would drop a call that is merely in a long tunnel.
         * AOSP holds with SetHeldByCall() for as long as the call exists. */
        long bs = JoanAppRegister.JoanRegLifecycle.LOSS_HOLD_BACKSTOP_MS;
        check(!JoanAppRegister.JoanRegLifecycle.heldLossMayClear(1000, 1000 + bs - 1, true),
                "a held loss is not honoured while the call is still up");
        check(JoanAppRegister.JoanRegLifecycle.heldLossMayClear(1000, 1001, false),
                "the call ending honours the loss immediately");
        check(JoanAppRegister.JoanRegLifecycle.heldLossMayClear(1000, 1000 + bs, true),
                "a stuck call state still releases at the backstop");
        check(!JoanAppRegister.JoanRegLifecycle.heldLossMayClear(0, 1 << 30, false),
                "no held loss means nothing to honour");
        check(JoanAppRegister.JoanRegLifecycle.clearOnLost(JoanAppRegister.JoanRegLifecycle.POKE_IMS_LOST, true, false),
                "lost-clears-registered");
        check(!JoanAppRegister.JoanRegLifecycle.clearOnLost(JoanAppRegister.JoanRegLifecycle.POKE_IMS_LOST, false, false),
                "lost-clears-nothing-when-unregistered");
        check(!JoanAppRegister.JoanRegLifecycle.clearOnLost(JoanAppRegister.JoanRegLifecycle.POKE_IMS_AVAILABLE, true, false),
                "available-never-clears");
        check(JoanAppRegister.JoanRegLifecycle.reacquireAfterLoss(
                        JoanAppRegister.JoanRegLifecycle.POKE_IMS_AVAILABLE, true, false),
                "available-after-loss-reacquires");
        check(!JoanAppRegister.JoanRegLifecycle.reacquireAfterLoss(
                        JoanAppRegister.JoanRegLifecycle.POKE_IMS_AVAILABLE, false, false),
                "available-without-loss-leaves-alone");
        check(!JoanAppRegister.JoanRegLifecycle.reacquireAfterLoss(
                        JoanAppRegister.JoanRegLifecycle.POKE_IMS_LOST, true, false),
                "lost-not-reacquire");

        /* A loss during a call opens a grace period that nothing ever
         * closed: reacquireAfterLoss was asked first, saw only the stale
         * flag the same loss had set, and released the UA underneath the
         * live call -- closing both transports and the IPsec SA. The
         * branch written to catch this sat below an unconditional return
         * and could never run. */
        check(!JoanAppRegister.JoanRegLifecycle.reacquireAfterLoss(
                        JoanAppRegister.JoanRegLifecycle.POKE_IMS_AVAILABLE, true, true),
                "a live call is never re-acquired out from under");
        check(JoanAppRegister.JoanRegLifecycle.backWithinCallGrace(
                        JoanAppRegister.JoanRegLifecycle.POKE_IMS_AVAILABLE, true, true),
                "the network returning mid-call is the grace period closing");
        check(!JoanAppRegister.JoanRegLifecycle.backWithinCallGrace(
                        JoanAppRegister.JoanRegLifecycle.POKE_IMS_AVAILABLE, false, true),
                "with no loss held there is no grace period to close");
        check(!JoanAppRegister.JoanRegLifecycle.backWithinCallGrace(
                        JoanAppRegister.JoanRegLifecycle.POKE_IMS_AVAILABLE, true, false),
                "and a call that already ended reacquires instead");
        check(!JoanAppRegister.JoanRegLifecycle.backWithinCallGrace(
                        JoanAppRegister.JoanRegLifecycle.POKE_IMS_LOST, true, true),
                "a second loss is not the network coming back");

        check(JoanAppRegister.JoanRegLifecycle.matchesSubscription(null, 1),
                "sub-pin-null-ids-accepted");
        check(JoanAppRegister.JoanRegLifecycle.matchesSubscription(new int[0], 1),
                "sub-pin-empty-ids-accepted");
        check(JoanAppRegister.JoanRegLifecycle.matchesSubscription(new int[]{2}, -1),
                "sub-pin-unresolved-accepted");
        check(JoanAppRegister.JoanRegLifecycle.matchesSubscription(new int[]{2, 3}, 3),
                "sub-pin-matching-included");
        check(!JoanAppRegister.JoanRegLifecycle.matchesSubscription(new int[]{2}, 1),
                "sub-pin-foreign-network-excluded");
    }

    /* ---------------------- driver poke wiring ------------------------ */

    static Object uaGet(String n) throws Exception {
        Field f = JoanSipUa.class.getDeclaredField(n);
        f.setAccessible(true);
        return f.get(null);
    }

    static void uaSet(String n, Object v) throws Exception {
        Field f = JoanSipUa.class.getDeclaredField(n);
        f.setAccessible(true);
        f.set(null, v);
    }

    static long driverBackoff() throws Exception {
        Field f = JoanDriver.class.getDeclaredField("sRegisterBackoffMs");
        f.setAccessible(true);
        return f.getLong(null);
    }

    static void setDriverBackoff(long ms) throws Exception {
        Field f = JoanDriver.class.getDeclaredField("sRegisterBackoffMs");
        f.setAccessible(true);
        f.setLong(null, ms);
    }

    static boolean driverStale() throws Exception {
        Field f = JoanDriver.class.getDeclaredField("sStaleAfterLoss");
        f.setAccessible(true);
        return f.getBoolean(null);
    }

    static void setDriverStale(boolean v) throws Exception {
        Field f = JoanDriver.class.getDeclaredField("sStaleAfterLoss");
        f.setAccessible(true);
        f.setBoolean(null, v);
    }

    static void driverPokeTests() throws Exception {
        boolean wasReg = (Boolean) uaGet("sReg");
        try {
            uaSet("sReg", true);
            setDriverBackoff(240_000L);
            JoanDriver.poke(JoanAppRegister.JoanRegLifecycle.POKE_IMS_AVAILABLE);
            check((Boolean) uaGet("sReg"), "poke-available-keeps-registration");
            check(driverBackoff() == 240_000L,
                    "poke-available-registered-backoff-untouched");

            setDriverBackoff(240_000L);
            JoanDriver.poke("manual poke");
            check((Boolean) uaGet("sReg"), "poke-manual-keeps-registration");
            check(driverBackoff() == 240_000L,
                    "poke-manual-registered-backoff-untouched");

            uaSet("sReg", true);
            setDriverBackoff(240_000L);
            try {
                JoanDriver.poke(JoanAppRegister.JoanRegLifecycle.POKE_IMS_LOST);
            } catch (LinkageError hostOnly) {
                /* Host-only: JoanRegistration's HandlerExecutor needs
                 * Looper.getMainLooper(), which the android.jar stub
                 * cannot provide. releaseLocked() has already cleared
                 * sReg by the time that state-broadcast path runs; on
                 * device the same order completes without throwing. */
            }
            check(!(Boolean) uaGet("sReg"),
                    "poke-lost-clears-stale-registration");
            check(driverBackoff() == 60_000L,
                    "poke-lost-resets-backoff");
            check(driverStale(), "poke-lost-marks-stale");

            // Available after a loss must re-acquire, not trust the UA's
            // still-set registration flag.
            uaSet("sReg", true);
            setDriverBackoff(240_000L);
            setDriverStale(true);
            try {
                JoanDriver.poke(JoanAppRegister.JoanRegLifecycle.POKE_IMS_AVAILABLE);
            } catch (LinkageError hostOnly) {
                // same host-only broadcast limitation as above
            }
            check(!(Boolean) uaGet("sReg"),
                    "poke-available-after-loss-releases");
            check(driverBackoff() == 60_000L,
                    "poke-available-after-loss-resets-backoff");
            check(!driverStale(), "poke-available-clears-stale-flag");
            setDriverStale(false);

            uaSet("sReg", false);
            setDriverBackoff(240_000L);
            JoanDriver.poke(JoanAppRegister.JoanRegLifecycle.POKE_IMS_AVAILABLE);
            check(driverBackoff() == 60_000L,
                    "poke-available-unregistered-resets-backoff");

            // An in-flight cycle's UA state is never touched by a poke,
            // but a loss still marks the state stale and resets backoff
            // so the next availability poke re-registers.
            uaSet("sReg", true);
            setDriverBackoff(240_000L);
            setDriverStale(false);
            Method begin = JoanAppRegister.class.getDeclaredMethod("tryBegin");
            Method end = JoanAppRegister.class.getDeclaredMethod("end");
            begin.setAccessible(true);
            end.setAccessible(true);
            begin.invoke(null);
            try {
                JoanDriver.poke(JoanAppRegister.JoanRegLifecycle.POKE_IMS_LOST);
                check((Boolean) uaGet("sReg"),
                        "poke-lost-inflight-never-touches-ua");
                check(driverStale(), "poke-lost-inflight-marks-stale");
                check(driverBackoff() == 60_000L,
                        "poke-lost-inflight-resets-backoff");
            } finally {
                end.invoke(null);
            }
        } finally {
            uaSet("sReg", wasReg);
        }
    }

    public static void main(String[] args) throws Exception {
        matchingTests();
        udpTests();
        udpDiagnosticTests();
        reg1BoundaryTests();
        tcpFramingTests();
        tcpSocketTests();
        epochTests();
        lifecycleTableTests();
        driverPokeTests();
        ipFlipTests();
        akaParseTests();
        System.out.println("REGISTRATION_CHECKS=" + checks + " FAILURES=0");
    }

    /**
     * Stock-parity IP-version flip (alpha12): only a whole-cycle wait-out
     * failure on a dual-family PDN earns one flip retry; v4-only PDNs,
     * successes, supersessions and non-timeout failures never flip.
     */
    private static void ipFlipTests() throws Exception {
        String reg1Silence = "addrs=ims pani=x pcscf_n=2 pcscf_tried=2 "
                + "FAIL: reg1 no answer from any of 2";
        String reg2Timeout = "reg1=401 aka=AKAv1-MD5 reg2len=1824 tpt=udp "
                + "reg2retx=4 FAIL: reg2 timeout";
        check(JoanAppRegister.shouldFlipIpVersion(
                        "reg1_result=timeout FAIL: reg1 no matching final", true),
                "flip: actual REG1 diagnostic reaches retry decision");
        check(JoanAppRegister.shouldFlipIpVersion(reg1Silence, true),
                "flip: dual-family REG1 silence earns a retry");
        check(JoanAppRegister.shouldFlipIpVersion(reg2Timeout, true),
                "flip: dual-family REG2 timeout earns a retry");
        check(!JoanAppRegister.shouldFlipIpVersion(reg1Silence, false),
                "flip: v4-only PDN (NOS trace) never flips");

        /* A 423 the network explained is not a failure to back off from:
         * Digi.Mobil RO answered 600 with Min-Expires 3600, and once that
         * is adopted the next request is correct by construction. */
        check(JoanAppRegister.expiryRaised(
                        "reg2=423 min_expires=3600 expires_raised"),
                "423: a cycle that raised its expiry is visible to the driver");
        check(!JoanAppRegister.expiryRaised("reg2=423 min_expires=absent"),
                "423: nothing to adopt is not a prompt retry");
        check(!JoanAppRegister.expiryRaised("reg2=500 FAIL"),
                "423: an unrelated failure is not mistaken for one");
        check(!JoanAppRegister.expiryRaised(null),
                "423: no summary at all is not a prompt retry");
        check(!JoanAppRegister.shouldFlipIpVersion(null, true),
                "flip: no result yet never flips");
        check(!JoanAppRegister.shouldFlipIpVersion(
                        "reg1=403 FAIL: reg1 unexpected", true),
                "flip: explicit rejection is not a flip trigger");
        // End-to-end: failure records the retry; the REGISTER planner
        // consumes it on the alternate family; a later failure cannot loop.
        JoanAppRegister.stop();
        JoanAppRegister.noteLastAttemptDualFamily(true);
        check(JoanAppRegister.scheduleFamilyRetry(reg1Silence),
                "flip flag set by failure scheduler");
        check(JoanAppRegister.flipPending(), "flip flag visible before planning");
        List<InetAddress> locals = new ArrayList<>();
        locals.add(InetAddress.getByName("2001:db8::1"));
        locals.add(InetAddress.getByName("192.0.2.1"));
        List<InetAddress> peers = new ArrayList<>();
        peers.add(InetAddress.getByName("2001:db8::99"));
        peers.add(InetAddress.getByName("192.0.2.99"));
        JoanImsDiscovery.Plan plan =
                JoanAppRegister.selectAttemptPlan(locals, peers);
        check(plan.local instanceof java.net.Inet4Address,
                "flip consumed at REGISTER planning (alternate family)");
        check(!JoanAppRegister.flipPending(), "flip flag consumed exactly once");
        check(!JoanAppRegister.scheduleFamilyRetry(reg2Timeout),
                "failed alternate does not loop flips forever");
        // stop() clears any pending flip (fresh state after network loss)
        JoanAppRegister.stop();
        check(!JoanAppRegister.flipPending(),
                "flip flag cleared by stop()/network loss");
    }

    /** APDU/framework AKA material, never live AUTN/IMSI. */
    private static void akaParseTests() {
        String ck = "11".repeat(16);
        String ik = "22".repeat(16);
        check(JoanAka.parseAuthResponse(
                        "RES=" + "33".repeat(8) + " CK=" + ck + " IK=" + ik) != null,
                "8-byte RES control");
        String[] four = JoanAka.parseAuthResponse(
                "RES=" + "33".repeat(4) + " CK=" + ck + " IK=" + ik);
        check(four != null && four[0].equals("33".repeat(4))
                        && four[1].equals(ck) && four[2].equals(ik),
                "4-byte RES accepted across APDU-to-auth-parser seam");
        byte[] raw = new byte[52];
        raw[0] = (byte) 0xdb;
        raw[1] = 16;
        java.util.Arrays.fill(raw, 2, 18, (byte) 0x33);
        raw[18] = 16;
        java.util.Arrays.fill(raw, 19, 35, (byte) 0x11);
        raw[35] = 16;
        java.util.Arrays.fill(raw, 36, 52, (byte) 0x22);
        String[] tlv;
        try {
            java.lang.reflect.Method m = JoanAka.class.getDeclaredMethod(
                    "parseUiccTlv", byte[].class);
            m.setAccessible(true);
            tlv = (String[]) m.invoke(null, (Object) raw);
        } catch (Exception e) {
            throw new AssertionError("parseUiccTlv " + e);
        }
        check(tlv != null && tlv[0].equals("33".repeat(16))
                        && tlv[1].equals(ck) && tlv[2].equals(ik),
                "framework fallback decodes DB/length fields without shifting RES CK IK");
    }
}
