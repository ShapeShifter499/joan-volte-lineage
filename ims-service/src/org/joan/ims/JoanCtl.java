package org.joan.ims;

import android.net.LocalSocket;
import android.system.Os;
import android.system.OsConstants;
import android.net.LocalSocketAddress;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;

/**
 * Stream client for the native UA control plane, over the abstract unix
 * socket @joan_ims_ctl.
 *
 * Two routes, tried in that order of preference:
 *
 *   1. @joan_ims_ctl, the abstract unix socket. The daemon authenticates
 *      this peer with SO_PEERCRED, so it is the route we want. On a
 *      sideloaded install it currently fails with EACCES: connecting from
 *      priv_app to the daemon's netmgrd domain needs an allow for
 *      unix_stream_socket connectto, and joan cannot load a policy append.
 *      An in-ROM install can ship that policy, and then this is the only
 *      route used.
 *
 *   2. 127.0.0.1:15090. Bring-up only, and unauthenticated -- TCP has no
 *      connectto check, which is the sole reason it works today. The
 *      daemon compiles this listener out when the unix route is usable.
 *
 * Protocol is one request line -> one response line. Never logs identity
 * or key material.
 */
final class JoanCtl {
    private static final String TAG = "JoanIms";
    private static volatile String sLastError = "";

    private JoanCtl() {}

    /** One request line -> one response line, or null on timeout/error. */
    static synchronized String txn(String req) {
        String r = txnLocal(req);
        if (r != null) {
            return r;
        }
        return txnOsTcp(req);
    }

    /* Bring-up fallback; see the class comment. */
    private static String txnOsTcp(String req) {
        FileDescriptor fd = null;
        try {
            fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM,
                    OsConstants.IPPROTO_TCP);
            InetAddress loop4 = InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 });
            Os.connect(fd, loop4, 15090);
            byte[] out = (req + "\n").getBytes("UTF-8");
            int off = 0;
            while (off < out.length) {
                off += Os.write(fd, out, off, out.length - off);
            }
            byte[] buf = new byte[2048];
            int n = Os.read(fd, buf, 0, buf.length);
            if (n <= 0) {
                sLastError = verb(req) + " ostcp EOF";
                return null;
            }
            String resp = new String(buf, 0, n, "UTF-8").trim();
            sLastError = "OK ostcp " + verb(req);
            return resp;
        } catch (Exception e) {
            sLastError = verb(req) + " ostcp " + describe(e);
            Log.w(TAG, "ctl " + verb(req) + " ostcp failed: " + describe(e));
            return null;
        } finally {
            if (fd != null) {
                try {
                    Os.close(fd);
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
    }

    private static String txnLocal(String req) {
        LocalSocket s = null;
        try {
            /* Create the fd explicitly: LocalSocket defers creation, and
             * touching options on a deferred socket has been observed to
             * leave it unmade ("socket not created" at connect time). */
            s = new LocalSocket(LocalSocket.SOCKET_STREAM);
            s.connect(new LocalSocketAddress("joan_ims_ctl",
                    LocalSocketAddress.Namespace.ABSTRACT));
            s.setSoTimeout(20000);
            return exchangeLocal(s.getInputStream(), s.getOutputStream(), req);
        } catch (Exception e) {
            sLastError = verb(req) + " local " + describe(e);
            Log.w(TAG, "ctl " + verb(req) + " local failed: " + describe(e));
            return null;
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
    }

    private static String exchangeLocal(InputStream is, OutputStream os,
                                        String req) throws Exception {
        os.write((req + "\n").getBytes("UTF-8"));
        os.flush();
        byte[] buf = new byte[2048];
        int n = is.read(buf);
        if (n <= 0) {
            sLastError = verb(req) + " local EOF";
            return null;
        }
        String resp = new String(buf, 0, n, "UTF-8").trim();
        sLastError = "OK local " + verb(req);
        return resp;
    }

    static String lastError() {
        return sLastError;
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) {
            return e.getClass().getSimpleName();
        }
        m = m.replace('\n', ' ').replace('\r', ' ');
        if (m.length() > 80) {
            m = m.substring(0, 80);
        }
        return e.getClass().getSimpleName() + ":" + m;
    }

    private static String verb(String req) {
        int i = req.indexOf(' ');
        return i < 0 ? req : req.substring(0, i);
    }
}
