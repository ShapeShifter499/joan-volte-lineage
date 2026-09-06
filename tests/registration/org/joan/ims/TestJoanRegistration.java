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
        check(JoanAppRegister.JoanRegLifecycle.clearOnLost(JoanAppRegister.JoanRegLifecycle.POKE_IMS_LOST, true),
                "lost-clears-registered");
        check(!JoanAppRegister.JoanRegLifecycle.clearOnLost(JoanAppRegister.JoanRegLifecycle.POKE_IMS_LOST, false),
                "lost-clears-nothing-when-unregistered");
        check(!JoanAppRegister.JoanRegLifecycle.clearOnLost(JoanAppRegister.JoanRegLifecycle.POKE_IMS_AVAILABLE, true),
                "available-never-clears");
        check(JoanAppRegister.JoanRegLifecycle.reacquireAfterLoss(
                        JoanAppRegister.JoanRegLifecycle.POKE_IMS_AVAILABLE, true),
                "available-after-loss-reacquires");
        check(!JoanAppRegister.JoanRegLifecycle.reacquireAfterLoss(
                        JoanAppRegister.JoanRegLifecycle.POKE_IMS_AVAILABLE, false),
                "available-without-loss-leaves-alone");
        check(!JoanAppRegister.JoanRegLifecycle.reacquireAfterLoss(
                        JoanAppRegister.JoanRegLifecycle.POKE_IMS_LOST, true),
                "lost-not-reacquire");

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
        tcpFramingTests();
        tcpSocketTests();
        epochTests();
        lifecycleTableTests();
        driverPokeTests();
        System.out.println("REGISTRATION_CHECKS=" + checks + " FAILURES=0");
    }
}
