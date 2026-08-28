package org.joan.ims;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Stream client for the native UA control plane, over the abstract unix
 * socket @joan_ims_ctl.
 *
 * An earlier revision reached the daemon over a 127.0.0.1 listener. That
 * listener was removed: any app holding INTERNET could reach it, and the
 * ctl verbs include REG2, which injects AKA key material. Authenticating
 * the peer of a TCP connection means resolving its uid from
 * /proc/net/tcp, which is EACCES from the daemon's netmgrd domain. The
 * unix socket has no such problem -- the daemon reads the peer's uid via
 * SO_PEERCRED -- so it is now the only route.
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
        return txnLocal(req);
    }

    private static String txnLocal(String req) {
        LocalSocket s = null;
        try {
            s = new LocalSocket();
            s.setSoTimeout(20000);
            s.connect(new LocalSocketAddress("joan_ims_ctl",
                    LocalSocketAddress.Namespace.ABSTRACT));
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
