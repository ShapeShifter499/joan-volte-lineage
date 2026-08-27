package org.joan.ims;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * UDP datagram client for the native UA control plane on loopback.
 * Request/response lines: ID, NET, REG1, REG2, STATUS, KEEPALIVE.
 * Never logs identity or key material.
 */
final class JoanCtl {
    private static final String TAG = "JoanIms";
    static final int CTL_PORT = 15090;
    private static final int TIMEOUT_MS = 20000;

    private JoanCtl() {}

    /** One request line -> one response line, or null on timeout/error. */
    static synchronized String txn(String req) {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
            s.setSoTimeout(TIMEOUT_MS);
            InetAddress lo = InetAddress.getByName("127.0.0.1");
            byte[] out = (req + "\n").getBytes("UTF-8");
            s.send(new DatagramPacket(out, out.length,
                    new InetSocketAddress(lo, CTL_PORT)));
            byte[] buf = new byte[2048];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            s.receive(p);
            return new String(buf, 0, p.getLength(), "UTF-8").trim();
        } catch (Exception e) {
            Log.w(TAG, "ctl " + verb(req) + " failed: "
                    + e.getClass().getSimpleName());
            return null;
        } finally {
            if (s != null) {
                s.close();
            }
        }
    }

    private static String verb(String req) {
        int i = req.indexOf(' ');
        return i < 0 ? req : req.substring(0, i);
    }
}
