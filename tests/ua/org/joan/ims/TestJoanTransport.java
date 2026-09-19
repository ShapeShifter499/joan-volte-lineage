package org.joan.ims;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Production builders, sender and subscription caller; all sinks are offline. */
public final class TestJoanTransport {
    private static int checks, failures;
    private static final Class<?> UA = JoanSipUa.class;
    private static final String FROM = "<sip:joan@example.invalid>;tag=local-test";
    private static final String TO = "<sip:peer@example.invalid>;tag=remote-test";

    private static void check(boolean ok, String name) {
        checks++;
        if (!ok) failures++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }
    private static Field field(Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
    private static Object get(String name) throws Exception { return field(UA, name).get(null); }
    private static void set(String name, Object value) throws Exception { field(UA, name).set(null, value); }
    private static Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = UA.getDeclaredMethod(name, types);
        m.setAccessible(true);
        try { return m.invoke(null, args); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            throw e;
        }
    }
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.ISO_8859_1); }
    private static String text(byte[] b) { return new String(b, StandardCharsets.ISO_8859_1); }
    private static String via(String msg) { return JoanSipBuilder.header(msg, "Via"); }
    @SuppressWarnings("unchecked")
    private static List<String> captures() throws Exception {
        return (List<String>) field(JoanSipCapture.class, "sCall").get(null);
    }
    private static void clearCapture() throws Exception { captures().clear(); }
    private static void captured(String msg, String name) throws Exception {
        List<String> entries = captures();
        String normalized = JoanSipRedact.normalize(JoanSipRedact.redact(msg));
        check(entries.size() == 1 && entries.get(0).endsWith(normalized),
                name + " capture equals emitted bytes after transport selection");
    }
    private interface OnWrite { void accept(String msg) throws Exception; }
    private static final class TcpWire extends Socket {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean closed, fail;
        OnWrite onWrite;
        @Override public boolean isClosed() { return closed; }
        @Override public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int b) { out.write(b); }
                @Override public void write(byte[] b, int off, int len) throws IOException {
                    if (fail) throw new IOException("offline TCP failure");
                    out.write(b, off, len);
                    if (onWrite != null) {
                        try { onWrite.accept(text(Arrays.copyOfRange(b, off, off + len))); }
                        catch (Exception e) { throw new IOException(e); }
                    }
                }
            };
        }
    }
    private static final class UdpWire extends DatagramSocket {
        byte[] sent;
        boolean fail;
        UdpWire() throws Exception { super((SocketAddress) null); }
        @Override public void send(DatagramPacket p) throws IOException {
            if (fail) throw new IOException("offline UDP failure");
            sent = Arrays.copyOfRange(p.getData(), p.getOffset(), p.getOffset() + p.getLength());
        }
    }
    /** Used only by the test runtime's android.system.Os.write shim. */
    public static final class PeerWrites {
        public static final Map<FileDescriptor, ByteArrayOutputStream> sinks = new IdentityHashMap<>();
        static int limit = Integer.MAX_VALUE;
        static boolean fail, zero;
        static Runnable afterFirstWrite;
        public static int write(FileDescriptor fd, byte[] b, int off, int count) throws IOException {
            ByteArrayOutputStream sink = sinks.get(fd);
            if (sink == null) throw new AssertionError("unbound test descriptor");
            if (fail) throw new IOException("offline peer failure");
            if (zero) return 0;
            int n = Math.min(count, limit);
            sink.write(b, off, n);
            Runnable action = afterFirstWrite;
            afterFirstWrite = null;
            if (action != null) action.run();
            return n;
        }
    }
    private static JoanSipBuilder.Id identity() {
        return new JoanSipBuilder.Id("private@example.invalid", "sip:joan@example.invalid",
                "example.invalid", "127.0.0.1", 40010, 40011, "");
    }
    private static String request(String method, String transport, String body) {
        return method + " sip:peer@example.invalid SIP/2.0\r\n"
                + "Via: SIP/2.0/" + transport + " 127.0.0.1:40010;branch=z9hG4bK-test;rport\r\n"
                + "From: " + FROM + "\r\nTo: " + TO + "\r\n"
                + "Call-ID: test@example.invalid\r\nCSeq: 1 " + method + "\r\n"
                + "Content-Length: " + bytes(body).length + "\r\n\r\n" + body;
    }
    private static void sendReply(String msg) throws Exception {
        invoke("sendReply", new Class<?>[] { byte[].class }, (Object) bytes(msg));
    }
    private static void reset(UdpWire udp) throws Exception {
        set("sTcpClient", null); set("sTcpPeer", null); set("sReplyTcp", false);
        set("sSockC", udp); set("sPcscf", InetAddress.getLoopbackAddress()); set("sPcscfPortS", 5060);
        set("sId", identity()); set("sPublicId", "sip:joan@example.invalid");
        set("sServiceRoute", null); set("sSecVerify", null); set("sExpiresSec", 3600);
        PeerWrites.sinks.clear(); PeerWrites.limit = Integer.MAX_VALUE;
        PeerWrites.fail = PeerWrites.zero = false; PeerWrites.afterFirstWrite = null;
        udp.sent = null; udp.fail = false;
        clearCapture();
    }
    private static void sinks() throws Exception {
        try (UdpWire udp = new UdpWire(); TcpWire tcp = new TcpWire()) {
            for (String method : new String[] { "SUBSCRIBE", "INVITE", "ACK", "BYE", "PRACK", "UPDATE", "CANCEL" }) {
                reset(udp); set("sTcpClient", tcp); tcp.out.reset();
                String original = request(method, "UDP", "");
                sendReply(original);
                String actual = text(tcp.out.toByteArray());
                String expected = original.replace("Via: SIP/2.0/UDP", "Via: SIP/2.0/TCP");
                check(expected.equals(actual) && udp.sent == null, method + " retained TCP Via matches socket");
                captured(expected, method + " retained TCP");
            }
            reset(udp); set("sTcpClient", tcp); tcp.out.reset();
            String direct = request("INVITE", "UDP", "");
            invoke("send", new Class<?>[] { DatagramSocket.class, InetAddress.class, int.class, byte[].class },
                    udp, InetAddress.getLoopbackAddress(), 5060, bytes(direct));
            check(via(text(tcp.out.toByteArray())).startsWith("SIP/2.0/TCP "), "direct INVITE send path also selects TCP Via");

            reset(udp);
            String udpRequest = request("INVITE", "UDP", "");
            sendReply(udpRequest);
            check(Arrays.equals(bytes(udpRequest), udp.sent), "UDP path stays byte-identical (TMUS control)");
            captured(udpRequest, "UDP");

            reset(udp); tcp.closed = true; set("sTcpClient", tcp);
            String previouslyTcp = request("ACK", "TCP", "");
            sendReply(previouslyTcp);
            check(request("ACK", "UDP", "").equals(text(udp.sent)), "closed TCP fallback emits UDP Via");
            tcp.closed = false;

            for (boolean peer : new boolean[] { false, true }) {
                reset(udp); tcp.out.reset();
                FileDescriptor fd = new FileDescriptor();
                ByteArrayOutputStream peerOut = new ByteArrayOutputStream();
                if (peer) {
                    set("sReplyTcp", true); set("sTcpPeer", fd); PeerWrites.sinks.put(fd, peerOut);
                    PeerWrites.limit = 13;
                } else set("sTcpClient", tcp);
                String response = "SIP/2.0 200 OK\r\nVia: SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK-peer\r\n"
                        + "Via: SIP/2.0/UDP next.invalid:5060;branch=z9hG4bK-next\r\n"
                        + "Call-ID: test@example.invalid\r\nCSeq: 1 OPTIONS\r\nContent-Length: 0\r\n\r\n";
                sendReply(response);
                String actual = text((peer ? peerOut : tcp.out).toByteArray());
                check(response.equals(actual), (peer ? "accepted" : "retained") + " TCP response preserves every Via");
                captured(response, (peer ? "accepted" : "retained") + " TCP response");
            }

            reset(udp);
            FileDescriptor fd = new FileDescriptor(), replacement = new FileDescriptor();
            ByteArrayOutputStream peerOut = new ByteArrayOutputStream(), replacementOut = new ByteArrayOutputStream();
            PeerWrites.sinks.put(fd, peerOut); PeerWrites.sinks.put(replacement, replacementOut);
            PeerWrites.limit = 11;
            PeerWrites.afterFirstWrite = () -> { try { set("sTcpPeer", replacement); } catch (Exception e) { throw new AssertionError(e); } };
            set("sReplyTcp", true); set("sTcpPeer", fd);
            String peerRequest = request("BYE", "UDP", "");
            sendReply(peerRequest);
            String expectedPeer = peerRequest.replace("Via: SIP/2.0/UDP", "Via: SIP/2.0/TCP");
            check(expectedPeer.equals(text(peerOut.toByteArray())) && replacementOut.size() == 0,
                    "partial peer writes stay on one snapshotted descriptor");
            captured(expectedPeer, "accepted TCP request");

            for (String sink : new String[] { "tcp", "udp", "peer", "peer-zero" }) {
                reset(udp); tcp.out.reset(); tcp.fail = false;
                if (sink.equals("tcp")) { tcp.fail = true; set("sTcpClient", tcp); }
                else if (sink.equals("udp")) udp.fail = true;
                else {
                    set("sReplyTcp", true); set("sTcpPeer", fd);
                    PeerWrites.sinks.put(fd, new ByteArrayOutputStream());
                    PeerWrites.fail = sink.equals("peer"); PeerWrites.zero = sink.equals("peer-zero");
                }
                boolean failed = false;
                try { sendReply(request("INVITE", "UDP", "")); } catch (IOException expected) { failed = true; }
                check(failed && captures().isEmpty() && udp.sent == null,
                        sink + " failed write is not captured as sent or silently retried on UDP");
            }
            tcp.fail = false;

            reset(udp); set("sTcpClient", tcp); tcp.out.reset();
            String binary = request("MESSAGE", "UDP", "\u0000\u0080\u00ff\r\nSIP/2.0/UDP");
            sendReply(binary);
            String expectedBinary = binary.replace("Via: SIP/2.0/UDP", "Via: SIP/2.0/TCP");
            check(Arrays.equals(bytes(expectedBinary), tcp.out.toByteArray()), "transport selection preserves non-ASCII body octets");
        } finally {
            set("sTcpClient", null); set("sTcpPeer", null); set("sSockC", null); set("sReplyTcp", false);
        }
    }
    private static void topViaOnly() {
        String req = request("OPTIONS", "UDP", "SIP/2.0/UDP");
        String decoy = req.replace("Via:", "X-Echo: SIP/2.0/UDP\r\nVia:");
        String expected = decoy.replace("Via: SIP/2.0/UDP", "Via: SIP/2.0/TCP");
        check(expected.equals(JoanSipBuilder.retargetRequestViaToTcp(decoy)), "only a Via header is rewritten, not an earlier token");
        String compact = req.replace("Via:", "v :\t");
        check(compact.replace("SIP/2.0/UDP 127.", "SIP/2.0/TCP 127.").equals(
                JoanSipBuilder.retargetRequestViaToTcp(compact)), "compact Via with whitespace is recognized");
        String two = req.replace("Via: SIP/2.0/UDP", "Via: SIP/2.0/TCP").replace("From:",
                "Via: SIP/2.0/UDP next.invalid;branch=z9hG4bK-next\r\nFrom:");
        check(two.equals(JoanSipBuilder.retargetRequestViaToTcp(two)), "already-correct top Via does not rewrite a later Via or body");
        String noVia = req.replace("Via:", "X-Not-Via:");
        check(noVia.equals(JoanSipBuilder.retargetRequestViaToTcp(noVia)), "missing top Via is not fabricated from an unrelated token");
    }
    private static void subscriptions() throws Exception {
        JoanSipBuilder.Id id = identity();
        JoanSipBuilder.Dialog d = new JoanSipBuilder.Dialog(); d.cseq = 0;
        String first = JoanSipBuilder.buildRegEventSubscribe(id, d, "sip:joan@example.invalid", null, null, 3600);
        String cid = d.callId, tag = d.fromTag;
        check(cid != null && !cid.isEmpty() && !"null".equals(cid), "fresh reg-event Call-ID generated");
        check(tag != null && !tag.isEmpty() && !"null".equals(tag), "fresh reg-event local tag generated");
        check(JoanSipBuilder.cseqForMethod(first, "SUBSCRIBE") == 1, "fresh reg-event CSeq starts at one");
        String again = JoanSipBuilder.buildRegEventSubscribe(id, d, "sip:joan@example.invalid", null, null, 1800);
        check(cid != null && cid.equals(d.callId) && tag.equals(d.fromTag)
                && JoanSipBuilder.cseqForMethod(again, "SUBSCRIBE") == 2
                && !JoanSipBuilder.branchOf(first).equals(JoanSipBuilder.branchOf(again)),
                "same subscription retains identity and advances transaction once");
        JoanSipBuilder.Dialog other = new JoanSipBuilder.Dialog(); other.cseq = 0;
        JoanSipBuilder.buildRegEventSubscribe(id, other, "sip:joan@example.invalid", null, null, 3600);
        check(cid != null && !cid.equals(other.callId) && tag != null && !tag.equals(other.fromTag), "independent subscription gets independent identity");
        check(JoanSipBuilder.header(first, "Session-ID") == null, "reg-event does not invent a communication Session-ID");

        try (UdpWire udp = new UdpWire(); TcpWire tcp = new TcpWire()) {
            reset(udp); set("sTcpClient", tcp); set("sRegEventSubscribed", false);
            JoanTrace.notes.clear();
            CountDownLatch sent = new CountDownLatch(1);
            AtomicReference<Thread> worker = new AtomicReference<>();
            AtomicReference<Throwable> error = new AtomicReference<>();
            boolean[] bound = new boolean[1];
            tcp.onWrite = msg -> {
                worker.set(Thread.currentThread());
                try {
                    String key = JoanSipBuilder.header(msg, "Call-ID") + "#SUBSCRIBE#"
                            + JoanSipBuilder.cseqForMethod(msg, "SUBSCRIBE");
                    Object wait = ((Map<?, ?>) get("sNonInviteWaits")).get(key);
                    bound[0] = wait != null && JoanSipBuilder.branchOf(msg).equals(
                            field(wait.getClass(), "branch").get(wait));
                    String reply = "SIP/2.0 200 OK\r\nVia: " + via(msg)
                            + "\r\nFrom: " + JoanSipBuilder.header(msg, "From")
                            + "\r\nTo: " + JoanSipBuilder.header(msg, "To") + ";tag=server-test"
                            + "\r\nCall-ID: " + JoanSipBuilder.header(msg, "Call-ID")
                            + "\r\nCSeq: " + JoanSipBuilder.header(msg, "CSeq") + "\r\nContent-Length: 0\r\n\r\n";
                    invoke("handleInbound", new Class<?>[] { String.class }, reply);
                } catch (Throwable t) { error.set(t); }
                finally { sent.countDown(); }
            };
            invoke("subscribeRegEvent", new Class<?>[0]);
            boolean reached = sent.await(3, TimeUnit.SECONDS);
            Thread t = worker.get();
            if (t != null) t.join(3000);
            check(reached && error.get() == null && t != null && !t.isAlive(), "actual reg-event worker sends and consumes its final without timeout");
            check(bound[0], "actual reg-event waiter records the emitted SUBSCRIBE branch");
            check(JoanTrace.notes.contains("reg-event subscribe 200")
                    && ((Map<?, ?>) get("sNonInviteWaits")).isEmpty(), "actual SUBSCRIBE response routes and waiter is retired");
            if (t != null && t.isAlive()) { t.interrupt(); t.join(1000); }
        } finally {
            set("sTcpClient", null); set("sSockC", null); set("sRegEventSubscribed", false);
        }
    }
    public static void main(String[] args) throws Exception {
        topViaOnly();
        sinks();
        subscriptions();
        System.out.println("transport checks=" + checks + " failures=" + failures);
        if (failures != 0) System.exit(1);
    }
}
