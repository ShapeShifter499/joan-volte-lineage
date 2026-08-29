package org.joan.ims;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;

/**
 * The daemon's push channel.
 *
 * Every other exchange with the UA is request/response with the app
 * connecting, which is fine for anything the app initiates and useless for
 * anything the network initiates. An inbound INVITE is the latter: without a
 * way for the daemon to reach the app, the daemon had to answer calls
 * itself, so the dialer never rang, there was no session to route audio
 * through, and the user could not decline.
 *
 * So one connection is parked on EVENTS and left open. The daemon writes a
 * line per event; this thread turns each into a call into the MmTel feature.
 * If the connection drops -- daemon restart, most often -- it reconnects,
 * because a missed INCOMING is a missed call.
 *
 * Events carry no identity. The caller's number is not ours to log, and the
 * app already knows which subscriber it is registered as.
 */
final class JoanEvents {
    private static final String TAG = "JoanIms";
    private static final long RETRY_MS = 3000;

    private static volatile boolean sRunning;
    private static volatile String sLast = "";

    private JoanEvents() {}

    static synchronized void start(Context ctx) {
        if (sRunning) {
            return;
        }
        sRunning = true;
        final Context app = ctx.getApplicationContext();
        Thread t = new Thread(() -> loop(app), "joan-ims-events");
        t.setDaemon(true);
        t.start();
    }

    static String lastEvent() {
        return sLast;
    }

    private static void loop(Context app) {
        while (sRunning) {
            Closeable held = null;
            try {
                Conn c = connect();
                if (c == null) {
                    sleep(RETRY_MS);
                    continue;
                }
                held = c.closer;
                c.out.write("EVENTS\n".getBytes("UTF-8"));
                c.out.flush();
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(c.in, "UTF-8"), 256);
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    if (line.startsWith("OK ")) {
                        Log.i(TAG, "events channel attached");
                        continue;
                    }
                    dispatch(app, line);
                }
                Log.w(TAG, "events channel closed by daemon");
            } catch (Throwable t) {
                Log.w(TAG, "events channel: " + t.getClass().getSimpleName());
            } finally {
                closeQuietly(held);
            }
            sleep(RETRY_MS);
        }
    }

    private static void dispatch(Context app, String line) {
        /* The INCOMING line carries the caller's number. Log the kind of
         * event, never the line. */
        String kind = line;
        int sp0 = kind.indexOf(' ', "EVENT ".length());
        if (sp0 > 0) {
            kind = kind.substring(0, sp0);
        }
        sLast = kind;
        Log.i(TAG, "event: " + kind);
        JoanTrace.note("event " + kind);
        if (line.startsWith("EVENT INCOMING")) {
            /* "EVENT INCOMING <uri-or-dash> [display name...]".
             * The name may contain spaces, so it is the remainder. */
            String uri = "";
            String name = "";
            String rest = line.length() > "EVENT INCOMING".length()
                    ? line.substring("EVENT INCOMING".length()).trim() : "";
            if (!rest.isEmpty()) {
                int sp = rest.indexOf(' ');
                if (sp < 0) {
                    uri = rest;
                } else {
                    uri = rest.substring(0, sp);
                    name = rest.substring(sp + 1).trim();
                }
            }
            if ("-".equals(uri)) {
                uri = "";
            }
            JoanMmTelFeature.onIncomingCall(app, uri, name);
        } else if (line.startsWith("EVENT CANCELLED")
                || line.startsWith("EVENT ENDED")) {
            JoanMmTelFeature.onCallEndedRemotely();
        }
    }

    private static final class Conn {
        InputStream in;
        OutputStream out;
        Closeable closer;
    }

    /** Same route preference as JoanCtl: authenticated unix, then loopback. */
    private static Conn connect() {
        try {
            LocalSocket s = new LocalSocket(LocalSocket.SOCKET_STREAM);
            s.connect(new LocalSocketAddress("joan_ims_ctl",
                    LocalSocketAddress.Namespace.ABSTRACT));
            Conn c = new Conn();
            c.in = s.getInputStream();
            c.out = s.getOutputStream();
            c.closer = s;
            return c;
        } catch (Exception e) {
            // fall through to the bring-up route
        }
        try {
            final FileDescriptor fd = Os.socket(OsConstants.AF_INET,
                    OsConstants.SOCK_STREAM, OsConstants.IPPROTO_TCP);
            InetAddress loop4 = InetAddress.getByAddress(
                    new byte[] { 127, 0, 0, 1 });
            Os.connect(fd, loop4, 15090);
            Conn c = new Conn();
            c.in = new FdInputStream(fd);
            c.out = new FdOutputStream(fd);
            c.closer = () -> {
                try {
                    Os.close(fd);
                } catch (Exception ignored) {
                    // ignore
                }
            };
            return c;
        } catch (Exception e) {
            Log.w(TAG, "events connect failed: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private static final class FdInputStream extends InputStream {
        private final FileDescriptor fd;
        FdInputStream(FileDescriptor fd) { this.fd = fd; }
        @Override public int read() throws java.io.IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n <= 0 ? -1 : (b[0] & 0xff);
        }
        @Override public int read(byte[] b, int off, int len)
                throws java.io.IOException {
            try {
                int n = Os.read(fd, b, off, len);
                return n <= 0 ? -1 : n;
            } catch (Exception e) {
                throw new java.io.IOException(e.getClass().getSimpleName());
            }
        }
    }

    private static final class FdOutputStream extends OutputStream {
        private final FileDescriptor fd;
        FdOutputStream(FileDescriptor fd) { this.fd = fd; }
        @Override public void write(int b) throws java.io.IOException {
            write(new byte[] { (byte) b }, 0, 1);
        }
        @Override public void write(byte[] b, int off, int len)
                throws java.io.IOException {
            try {
                int done = 0;
                while (done < len) {
                    done += Os.write(fd, b, off + done, len - done);
                }
            } catch (Exception e) {
                throw new java.io.IOException(e.getClass().getSimpleName());
            }
        }
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
