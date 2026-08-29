package org.joan.ims;

import android.content.Context;
import android.net.IpSecManager;
import android.net.IpSecTransform;
import android.net.Network;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * In-app SIP UA: keeps the IPsec sockets after REGISTER 200 so INVITE
 * and inbound requests can use them. Replaces JoanCtl when the native
 * daemon is stopped. Never logs identities or SIP request-URIs.
 */
final class JoanSipUa {
    static final int RTP_PORT = 40000;

    private static final Object LOCK = new Object();
    private static volatile boolean sReg;
    private static volatile boolean sCall;
    private static Context sApp;
    private static Network sNet;
    private static InetAddress sLocal;
    private static InetAddress sPcscf;
    private static DatagramSocket sSockC;
    private static DatagramSocket sSockS;
    private static IpSecManager sIpsec;
    private static AutoCloseable[] sHeld;
    private static JoanSipBuilder.Id sId;
    private static String sPani;
    private static String sSecVerify;
    private static String sServiceRoute;
    private static String sPublicId;
    private static int sPcscfPortS;
    private static Thread sListen;

    private static volatile JoanSipBuilder.Dialog sDlg;
    private static volatile String sDest;
    private static volatile String sTarget;
    private static volatile String sRoute;
    private static volatile String sToHdr;
    private static volatile String sFromHdr;
    private static volatile String sHeldInvite;
    private static volatile String sOurToTag;
    private static volatile InetAddress sMediaIp;
    private static volatile int sMediaPort;
    private static volatile boolean sMediaMux;

    private JoanSipUa() {}

    static boolean isRegistered() {
        return sReg;
    }

    static boolean callActive() {
        return sCall;
    }

    static InetAddress mediaIp() {
        return sMediaIp;
    }

    static int mediaPort() {
        return sMediaPort;
    }

    static boolean mediaMux() {
        return sMediaMux;
    }

    static Network network() {
        return sNet;
    }

    static InetAddress localAddr() {
        return sLocal;
    }

    static String register(Context ctx) {
        return JoanAppRegister.run(ctx);
    }

    static void adopt(Context ctx, Network net, InetAddress local,
                      InetAddress pcscf, int pcscfPortS,
                      JoanSipBuilder.Id id, String pani, String secVerify,
                      String reg2Msg,
                      DatagramSocket sockC, DatagramSocket sockS,
                      IpSecManager ipsec, AutoCloseable[] held) {
        synchronized (LOCK) {
            releaseLocked();
            sApp = ctx.getApplicationContext();
            sNet = net;
            sLocal = local;
            sPcscf = pcscf;
            sPcscfPortS = pcscfPortS;
            sId = id;
            sPani = pani;
            sSecVerify = secVerify;
            sServiceRoute = JoanSipBuilder.header(reg2Msg, "Service-Route");
            sPublicId = JoanSipBuilder.pickPublicId(
                    JoanSipBuilder.header(reg2Msg, "P-Associated-URI"));
            if (sPublicId.isEmpty() && id.impu != null
                    && !id.impu.equals(id.impi)) {
                sPublicId = id.impu;
            }
            sSockC = sockC;
            sSockS = sockS;
            sIpsec = ipsec;
            sHeld = held;
            sReg = sPublicId != null && !sPublicId.isEmpty();
            sCall = false;
        }
        if (sReg) {
            JoanRegistration.setRegistered(true, null);
            startListen();
            JoanTrace.note("app UA registered public=yes");
        } else {
            JoanTrace.note("app UA REGISTER 200 but no public identity");
        }
    }

    static String invite(String dest) {
        if (!sReg) {
            return "ERR call before register";
        }
        if (sPublicId == null || sPublicId.isEmpty()) {
            return "ERR no public identity";
        }
        JoanSipBuilder.Id id = new JoanSipBuilder.Id(
                sId.impi, sPublicId, sId.realm, sId.localIp,
                sId.viaPort, sId.contactPort, sId.imei);
        JoanSipBuilder.Dialog dlg = new JoanSipBuilder.Dialog();
        String msg = JoanSipBuilder.buildInvite(id, dlg, dest, sServiceRoute,
                sSecVerify, RTP_PORT, sPani);
        if (msg == null) {
            return "ERR build invite";
        }
        byte[] pkt = msg.getBytes(StandardCharsets.US_ASCII);
        JoanTrace.note("app invite built bytes=" + pkt.length);
        try {
            send(sSockC, sPcscf, sPcscfPortS, pkt);
        } catch (Exception e) {
            return "ERR invite send";
        }
        long deadline = System.currentTimeMillis() + 30000;
        String toHdr = "";
        String fromHdr = "";
        String target = dest;
        String route = sServiceRoute;
        while (System.currentTimeMillis() < deadline) {
            String rx = recvEither((int) Math.min(400,
                    deadline - System.currentTimeMillis()));
            if (rx == null) {
                continue;
            }
            JoanSipBuilder.Reply p = JoanSipBuilder.parseReply(rx);
            if (p == null) {
                continue;
            }
            JoanTrace.note("app invite reply=" + p.status);
            if (p.status >= 100 && p.status < 200) {
                if (headerRseq(rx) > 0) {
                    String prack = JoanSipBuilder.buildPrack(id, dlg, dest,
                            sServiceRoute, sSecVerify,
                            JoanSipBuilder.header(rx, "To"),
                            JoanSipBuilder.header(rx, "From"),
                            headerRseq(rx));
                    try {
                        send(sSockC, sPcscf, sPcscfPortS,
                                prack.getBytes(StandardCharsets.US_ASCII));
                        JoanTrace.note("app PRACK sent");
                    } catch (Exception ignored) {
                        JoanTrace.note("app PRACK send fail");
                    }
                }
                continue;
            }
            if (p.status >= 200 && p.status < 300) {
                toHdr = nullToEmpty(JoanSipBuilder.header(rx, "To"));
                fromHdr = nullToEmpty(JoanSipBuilder.header(rx, "From"));
                String c = JoanSipBuilder.header(rx, "Contact");
                if (c != null) {
                    target = JoanSipBuilder.contactUri(c);
                }
                String rr = JoanSipBuilder.header(rx, "Record-Route");
                if (rr != null && !rr.isEmpty()) {
                    route = rr;
                }
                JoanSipBuilder.Media media = JoanSipBuilder.parseSdp(rx);
                String ack = JoanSipBuilder.buildAck(id, dlg, target, route,
                        sSecVerify, toHdr, fromHdr);
                try {
                    send(sSockC, sPcscf, sPcscfPortS,
                            ack.getBytes(StandardCharsets.US_ASCII));
                } catch (Exception e) {
                    return "ERR ack send";
                }
                synchronized (LOCK) {
                    sDlg = dlg;
                    sDest = dest;
                    sTarget = target;
                    sRoute = route;
                    sToHdr = toHdr;
                    sFromHdr = fromHdr;
                    sCall = true;
                    if (media != null) {
                        try {
                            sMediaIp = InetAddress.getByName(media.ip);
                            sMediaPort = media.port;
                            sMediaMux = media.mux;
                        } catch (Exception e) {
                            sMediaIp = null;
                        }
                    }
                }
                JoanTrace.note("app invite 200 media="
                        + (sMediaIp != null ? "yes" : "no")
                        + " mux=" + sMediaMux);
                return "OK";
            }
            if (p.status >= 300) {
                return "ERR invite " + p.status;
            }
        }
        return "ERR invite timeout";
    }

    static void hangup() {
        JoanSipBuilder.Dialog dlg;
        JoanSipBuilder.Id id;
        String target, route, toHdr, fromHdr;
        synchronized (LOCK) {
            if (!sCall || sDlg == null || sId == null) {
                sCall = false;
                return;
            }
            dlg = sDlg;
            id = new JoanSipBuilder.Id(sId.impi, sPublicId, sId.realm,
                    sId.localIp, sId.viaPort, sId.contactPort, sId.imei);
            target = sTarget != null ? sTarget : sDest;
            route = sRoute;
            toHdr = sToHdr;
            fromHdr = sFromHdr;
            sCall = false;
        }
        String bye = JoanSipBuilder.buildBye(id, dlg, target, route,
                sSecVerify, toHdr, fromHdr);
        try {
            send(sSockC, sPcscf, sPcscfPortS,
                    bye.getBytes(StandardCharsets.US_ASCII));
            JoanTrace.note("app BYE sent");
        } catch (Exception e) {
            JoanTrace.note("app BYE send fail");
        }
    }

    static String answer() {
        String invite;
        synchronized (LOCK) {
            invite = sHeldInvite;
        }
        if (invite == null) {
            return "ERR no held invite";
        }
        JoanSipBuilder.Id id = new JoanSipBuilder.Id(
                sId.impi, sPublicId, sId.realm, sId.localIp,
                sId.viaPort, sId.contactPort, sId.imei);
        String sdp = JoanSipBuilder.sdpOffer(sId.localIp, RTP_PORT);
        String tag = sOurToTag;
        if (tag == null || tag.isEmpty()) {
            tag = String.format("%012x",
                    new java.security.SecureRandom().nextLong() & 0xffffffffffffL);
            sOurToTag = tag;
        }
        String resp = buildResponse(invite, 200, "OK", id, tag, sdp);
        try {
            send(sSockC, sPcscf, sPcscfPortS,
                    resp.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            return "ERR answer send";
        }
        JoanSipBuilder.Media media = JoanSipBuilder.parseSdp(invite);
        synchronized (LOCK) {
            sCall = true;
            sHeldInvite = null;
            if (media != null) {
                try {
                    sMediaIp = InetAddress.getByName(media.ip);
                    sMediaPort = media.port;
                    sMediaMux = media.mux;
                } catch (Exception ignored) {
                    sMediaIp = null;
                }
            }
        }
        JoanTrace.note("app ANSWER 200");
        return "OK";
    }

    static String reject(int code) {
        String invite;
        synchronized (LOCK) {
            invite = sHeldInvite;
            sHeldInvite = null;
        }
        if (invite == null) {
            return "ERR no held invite";
        }
        String tag = sOurToTag != null ? sOurToTag : "rej";
        String resp = buildResponse(invite, code,
                code == 603 ? "Decline" : "Busy Here", sId, tag, null);
        try {
            send(sSockC, sPcscf, sPcscfPortS,
                    resp.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception ignored) {
            return "ERR reject send";
        }
        return "OK";
    }

    private static void startListen() {
        if (sListen != null && sListen.isAlive()) {
            return;
        }
        sListen = new Thread(JoanSipUa::listenLoop, "joan-sip-ua");
        sListen.setDaemon(true);
        sListen.start();
    }

    private static void listenLoop() {
        byte[] buf = new byte[4096];
        while (sReg) {
            String rx = recvEither(500);
            if (rx == null) {
                continue;
            }
            String method = JoanSipBuilder.requestMethod(rx);
            if (method.isEmpty()) {
                JoanSipBuilder.Reply p = JoanSipBuilder.parseReply(rx);
                if (p != null && p.status >= 200 && p.status < 300 && sCall
                        && sDlg != null) {
                    /* retransmitted 2xx: re-ACK */
                    try {
                        JoanSipBuilder.Id id = new JoanSipBuilder.Id(
                                sId.impi, sPublicId, sId.realm, sId.localIp,
                                sId.viaPort, sId.contactPort, sId.imei);
                        String ack = JoanSipBuilder.buildAck(id, sDlg, sTarget,
                                sRoute, sSecVerify, sToHdr, sFromHdr);
                        send(sSockC, sPcscf, sPcscfPortS,
                                ack.getBytes(StandardCharsets.US_ASCII));
                    } catch (Exception ignored) {
                        // ignore
                    }
                }
                continue;
            }
            if ("BYE".equals(method)) {
                JoanTrace.note("app inbound BYE");
                String tag = sOurToTag != null ? sOurToTag : "x";
                String resp = buildResponse(rx, 200, "OK", sId, tag, null);
                try {
                    send(sSockC, sPcscf, sPcscfPortS,
                            resp.getBytes(StandardCharsets.US_ASCII));
                } catch (Exception ignored) {
                    // ignore
                }
                sCall = false;
                JoanMmTelFeature featureWait = null;
                JoanMedia.stop();
                continue;
            }
            if ("INVITE".equals(method)) {
                if (sCall || sHeldInvite != null) {
                    String busy = buildResponse(rx, 486, "Busy Here", sId,
                            "busy", null);
                    try {
                        send(sSockC, sPcscf, sPcscfPortS,
                                busy.getBytes(StandardCharsets.US_ASCII));
                    } catch (Exception ignored) {
                        // ignore
                    }
                    continue;
                }
                sOurToTag = String.format("%012x",
                        new java.security.SecureRandom().nextLong()
                                & 0xffffffffffffL);
                sHeldInvite = rx;
                try {
                    send(sSockC, sPcscf, sPcscfPortS,
                            buildResponse(rx, 100, "Trying", sId, sOurToTag, null)
                                    .getBytes(StandardCharsets.US_ASCII));
                    send(sSockC, sPcscf, sPcscfPortS,
                            buildResponse(rx, 180, "Ringing", sId, sOurToTag, null)
                                    .getBytes(StandardCharsets.US_ASCII));
                } catch (Exception e) {
                    JoanTrace.note("app 180 send fail");
                }
                String from = JoanSipBuilder.header(rx, "From");
                String pai = JoanSipBuilder.header(rx, "P-Asserted-Identity");
                String uri = pai != null ? JoanSipBuilder.contactUri("<" + pai + ">")
                        : (from != null ? JoanSipBuilder.contactUri(from) : "");
                JoanTrace.note("app inbound INVITE number="
                        + (uri.isEmpty() ? "no" : "yes"));
                if (sApp != null) {
                    JoanMmTelFeature.onIncomingCall(sApp, uri, "");
                }
            }
        }
    }

    private static String buildResponse(String req, int code, String reason,
                                        JoanSipBuilder.Id id, String toTag,
                                        String sdp) {
        String via = JoanSipBuilder.header(req, "Via");
        String from = JoanSipBuilder.header(req, "From");
        String to = JoanSipBuilder.header(req, "To");
        String callId = JoanSipBuilder.header(req, "Call-ID");
        String cseq = JoanSipBuilder.header(req, "CSeq");
        String rr = JoanSipBuilder.header(req, "Record-Route");
        if (to != null && to.indexOf("tag=") < 0 && toTag != null) {
            to = to + ";tag=" + toTag;
        }
        String host = JoanSipBuilder.bracket(id.localIp);
        String contactUser = "joan";
        if (sPublicId != null && !sPublicId.isEmpty()) {
            String a = sPublicId;
            if (a.startsWith("sip:")) {
                a = a.substring(4);
            } else if (a.startsWith("tel:")) {
                a = a.substring(4);
            }
            int at = a.indexOf('@');
            contactUser = at >= 0 ? a.substring(0, at) : a;
        }
        StringBuilder a = new StringBuilder(800);
        a.append("SIP/2.0 ").append(code).append(' ').append(reason)
                .append("\r\n");
        if (via != null) {
            a.append("Via: ").append(via).append("\r\n");
        }
        if (rr != null) {
            a.append("Record-Route: ").append(rr).append("\r\n");
        }
        if (from != null) {
            a.append("From: ").append(from).append("\r\n");
        }
        if (to != null) {
            a.append("To: ").append(to).append("\r\n");
        }
        if (callId != null) {
            a.append("Call-ID: ").append(callId).append("\r\n");
        }
        if (cseq != null) {
            a.append("CSeq: ").append(cseq).append("\r\n");
        }
        a.append("Contact: <sip:").append(contactUser).append('@')
                .append(host).append(':').append(id.contactPort).append(">\r\n");
        if (sdp != null) {
            a.append("Content-Type: application/sdp\r\n");
            a.append("Content-Length: ").append(sdp.length()).append("\r\n\r\n");
            a.append(sdp);
        } else {
            a.append("Content-Length: 0\r\n\r\n");
        }
        return a.toString();
    }

    private static void send(DatagramSocket s, InetAddress dest, int port,
                             byte[] pkt) throws Exception {
        s.send(new DatagramPacket(pkt, pkt.length, dest, port));
    }

    private static String recvEither(int timeoutMs) {
        if (timeoutMs <= 0) {
            return null;
        }
        byte[] buf = new byte[4096];
        String a = JoanAppRegister.tryRecv(sSockC, buf, Math.min(200, timeoutMs));
        if (a != null) {
            return a;
        }
        return JoanAppRegister.tryRecv(sSockS, buf, Math.min(200, timeoutMs));
    }

    private static int headerRseq(String msg) {
        String v = JoanSipBuilder.header(msg, "RSeq");
        if (v == null) {
            return 0;
        }
        try {
            int sp = v.indexOf(' ');
            return Integer.parseInt(sp < 0 ? v : v.substring(0, sp));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static void releaseLocked() {
        sReg = false;
        sCall = false;
        if (sListen != null) {
            sListen.interrupt();
            sListen = null;
        }
        if (sIpsec != null) {
            try {
                if (sSockC != null) {
                    sIpsec.removeTransportModeTransforms(sSockC);
                }
                if (sSockS != null) {
                    sIpsec.removeTransportModeTransforms(sSockS);
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
        if (sSockC != null) {
            sSockC.close();
            sSockC = null;
        }
        if (sSockS != null) {
            sSockS.close();
            sSockS = null;
        }
        if (sHeld != null) {
            for (AutoCloseable c : sHeld) {
                if (c != null) {
                    try {
                        c.close();
                    } catch (Exception ignored) {
                        // ignore
                    }
                }
            }
            sHeld = null;
        }
    }
}
