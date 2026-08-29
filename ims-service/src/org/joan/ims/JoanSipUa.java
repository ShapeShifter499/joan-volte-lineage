package org.joan.ims;

import android.content.Context;
import android.net.IpSecManager;
import android.net.IpSecTransform;
import android.net.Network;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import java.io.FileDescriptor;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet6Address;
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

    /** Refresh at 80% of the granted lifetime, within these bounds. */
    private static final long REFRESH_FLOOR_MS = 60_000L;
    private static final long REFRESH_CAP_MS = 30 * 60_000L;

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
    private static IpSecTransform sOutC, sInC, sOutS, sInS;
    private static FileDescriptor sTcpS, sTcpC, sTcpPeer;
    private static final StringBuilder sTcpAcc = new StringBuilder();
    private static volatile boolean sReplyTcp;
    private static volatile long sRegisteredAtMs;
    private static volatile int sExpiresSec;
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

    /**
     * Milliseconds until this registration must be refreshed, 0 when it is
     * due now or there is nothing registered.
     *
     * The registrar is free to grant less than the Expires we asked for,
     * and when its grant lapses the binding is gone while this side still
     * believes it is registered: MO calls fail and MT calls never arrive.
     * The cap re-validates the binding periodically even when the grant is
     * long, which is what the driver's old "re-register in 30m" comment
     * intended before the app path stopped re-registering at all.
     */
    static long msUntilRefresh() {
        if (!sReg) {
            return 0;
        }
        long grantMs = sExpiresSec > 0 ? sExpiresSec * 1000L : REFRESH_CAP_MS;
        long lead = Math.max(REFRESH_FLOOR_MS,
                Math.min(REFRESH_CAP_MS, grantMs / 5 * 4));
        long left = sRegisteredAtMs + lead - System.currentTimeMillis();
        return left > 0 ? left : 0;
    }

    /** Tear the binding down so the driver's backoff path takes over. */
    static void release() {
        synchronized (LOCK) {
            releaseLocked();
        }
        JoanRegistration.setRegistered(false, null);
        JoanTrace.note("app UA released");
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
            if (held != null && held.length >= 4) {
                sOutC = held[0] instanceof IpSecTransform ? (IpSecTransform) held[0] : null;
                sInC = held[1] instanceof IpSecTransform ? (IpSecTransform) held[1] : null;
                sOutS = held[2] instanceof IpSecTransform ? (IpSecTransform) held[2] : null;
                sInS = held[3] instanceof IpSecTransform ? (IpSecTransform) held[3] : null;
            }
            sReg = sPublicId != null && !sPublicId.isEmpty();
            sCall = false;
            sReplyTcp = false;
            sExpiresSec = JoanSipBuilder.grantedExpiresSeconds(
                    reg2Msg, id.contactPort);
            sRegisteredAtMs = System.currentTimeMillis();
        }
        if (sReg) {
            /* Same as native hold_protected_ports: TCP listen on port-s
             * and port-c. P-CSCF delivers inbound INVITE over TCP. */
            sTcpS = tcpListen(id.contactPort, sInS, sOutS);
            sTcpC = tcpListen(id.viaPort, sInC, sOutC);
            JoanRegistration.setRegistered(true, null);
            startListen();
            JoanTrace.note("app UA registered public=yes tcp_s="
                    + (sTcpS != null) + " tcp_c=" + (sTcpC != null)
                    + " granted=" + sExpiresSec + "s refresh_in="
                    + (msUntilRefresh() / 1000) + "s");
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
            if (!sCall || sId == null) {
                sCall = false;
                return;
            }
            dlg = sDlg;
            if (dlg == null) {
                sCall = false;
                return;
            }
            id = new JoanSipBuilder.Id(sId.impi, sPublicId, sId.realm,
                    sId.localIp, sId.viaPort, sId.contactPort, sId.imei);
            target = sTarget != null && !sTarget.isEmpty() ? sTarget : sDest;
            route = sRoute;
            toHdr = sToHdr;
            fromHdr = sFromHdr;
            sCall = false;
        }
        if (target == null || target.isEmpty()) {
            JoanTrace.note("app BYE skipped no target");
            return;
        }
        String bye = JoanSipBuilder.buildBye(id, dlg, target, route,
                sSecVerify, toHdr, fromHdr);
        try {
            sendReply(bye.getBytes(StandardCharsets.US_ASCII));
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
        String sdp = JoanSipBuilder.sdpAnswer(sId.localIp, RTP_PORT, invite);
        String tag = sOurToTag;
        if (tag == null || tag.isEmpty()) {
            tag = String.format("%012x",
                    new java.security.SecureRandom().nextLong() & 0xffffffffffffL);
            sOurToTag = tag;
        }
        String resp = buildResponse(invite, 200, "OK", id, tag, sdp);
        try {
            sendReply(resp.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            return "ERR answer send";
        }
        JoanSipBuilder.Media media = JoanSipBuilder.parseSdp(invite);
        synchronized (LOCK) {
            sCall = true;
            sHeldInvite = null;
            /* UAS dialog: From is our To+tag, To is the INVITE From.
             * Without this, hangup() has no dialog and sends no BYE. */
            JoanSipBuilder.Dialog dlg = new JoanSipBuilder.Dialog();
            dlg.callId = JoanSipBuilder.header(invite, "Call-ID");
            dlg.cseq = 0;
            dlg.fromTag = tag;
            sDlg = dlg;
            String invTo = JoanSipBuilder.header(invite, "To");
            if (invTo == null) {
                invTo = "";
            }
            if (invTo.indexOf("tag=") < 0) {
                invTo = invTo + ";tag=" + tag;
            }
            sFromHdr = invTo;
            sToHdr = JoanSipBuilder.header(invite, "From");
            String c = JoanSipBuilder.header(invite, "Contact");
            sTarget = c != null ? JoanSipBuilder.contactUri(c) : "";
            String rr = JoanSipBuilder.header(invite, "Record-Route");
            sRoute = rr != null ? rr : sServiceRoute;
            sDest = sTarget;
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
            sendReply(resp.getBytes(StandardCharsets.US_ASCII));
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
        while (sReg) {
            try {
                String rx = pollTcp();
                if (rx == null) {
                    rx = recvEither(80);
                }
                if (rx == null) {
                    continue;
                }
                handleInbound(rx);
            } catch (Throwable t) {
                JoanTrace.note("listen " + t.getClass().getSimpleName());
            }
        }
    }

    private static void handleInbound(String rx) {
        String method = JoanSipBuilder.requestMethod(rx);
        if (method.isEmpty()) {
            JoanSipBuilder.Reply p = JoanSipBuilder.parseReply(rx);
            if (p != null && p.status >= 200 && p.status < 300 && sCall
                    && sDlg != null) {
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
            return;
        }
        if ("BYE".equals(method)) {
            JoanTrace.note("app inbound BYE");
            String tag = sOurToTag != null ? sOurToTag : "x";
            try {
                sendReply(buildResponse(rx, 200, "OK", sId, tag, null)
                        .getBytes(StandardCharsets.US_ASCII));
            } catch (Exception ignored) {
                // ignore
            }
            sCall = false;
            JoanMedia.stop();
            return;
        }
        if ("CANCEL".equals(method)) {
            handleCancel(rx);
            return;
        }
        if (!"INVITE".equals(method)) {
            return;
        }
        if (sCall || sHeldInvite != null) {
            try {
                sendReply(buildResponse(rx, 486, "Busy Here", sId, "busy", null)
                        .getBytes(StandardCharsets.US_ASCII));
            } catch (Exception ignored) {
                // ignore
            }
            JoanTrace.note("app inbound INVITE busy");
            return;
        }
        sOurToTag = String.format("%012x",
                new java.security.SecureRandom().nextLong() & 0xffffffffffffL);
        sHeldInvite = rx;
        try {
            sendReply(buildResponse(rx, 100, "Trying", sId, null, null)
                    .getBytes(StandardCharsets.US_ASCII));
            sendReply(buildResponse(rx, 180, "Ringing", sId, sOurToTag, null)
                    .getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            JoanTrace.note("app 180 send fail");
        }
        JoanSipBuilder.Cli cli = JoanSipBuilder.callingIdentity(rx);
        JoanTrace.note("app inbound INVITE tcp=" + sReplyTcp
                + " number=" + (cli.withheld || cli.uri.isEmpty() ? "no" : "yes")
                + " name=" + (cli.name.isEmpty() ? "no" : "yes"));
        if (sApp != null) {
            JoanMmTelFeature.onIncomingCall(sApp, cli.uri, cli.name);
        }
    }

    /**
     * The caller gave up while we were ringing.
     *
     * CANCEL used to fall off the end of handleInbound, which had two
     * consequences. Telecom was never told, so the dialer went on ringing
     * for a call the network had already abandoned. And sHeldInvite --
     * cleared only by answer() and reject() -- stayed set forever, so the
     * busy guard above answered 486 to every later inbound INVITE and the
     * phone silently stopped receiving calls until the process restarted.
     *
     * Answer the CANCEL, 487 the INVITE it names, and let go of the dialog.
     */
    private static void handleCancel(String rx) {
        String held;
        synchronized (LOCK) {
            held = sHeldInvite;
        }
        String callId = JoanSipBuilder.header(rx, "Call-ID");
        boolean mine = held != null && callId != null
                && callId.equals(JoanSipBuilder.header(held, "Call-ID"));
        String tag = sOurToTag != null ? sOurToTag : "x";
        /* A UAS answers the CANCEL transaction either way. */
        try {
            sendReply(buildResponse(rx, 200, "OK", sId, tag, null)
                    .getBytes(StandardCharsets.US_ASCII));
        } catch (Exception ignored) {
            // ignore
        }
        JoanTrace.note("app inbound CANCEL held=" + (held != null)
                + " matched=" + mine);
        if (!mine) {
            return;
        }
        try {
            sendReply(buildResponse(held, 487, "Request Terminated",
                    sId, tag, null).getBytes(StandardCharsets.US_ASCII));
        } catch (Exception ignored) {
            // ignore
        }
        synchronized (LOCK) {
            if (sHeldInvite == held) {
                sHeldInvite = null;
            }
        }
        sOurToTag = null;
        JoanMmTelFeature.onCallEndedRemotely();
    }

    private static FileDescriptor tcpListen(int port, IpSecTransform inXf,
                                            IpSecTransform outXf) {
        try {
            int af = (sLocal instanceof Inet6Address)
                    ? OsConstants.AF_INET6 : OsConstants.AF_INET;
            FileDescriptor fd = Os.socket(af, OsConstants.SOCK_STREAM,
                    OsConstants.IPPROTO_TCP);
            Os.setsockoptInt(fd, OsConstants.SOL_SOCKET,
                    OsConstants.SO_REUSEADDR, 1);
            if (sNet != null) {
                sNet.bindSocket(fd);
            }
            Os.bind(fd, sLocal, port);
            applyXf(fd, inXf, outXf);
            Os.listen(fd, 4);
            JoanTrace.note("tcp listen port=" + port);
            return fd;
        } catch (Exception e) {
            JoanTrace.note("tcp listen fail " + e.getClass().getSimpleName());
            return null;
        }
    }

    private static void applyXf(FileDescriptor fd, IpSecTransform inXf,
                                IpSecTransform outXf) {
        if (sIpsec == null || fd == null) {
            return;
        }
        if (inXf != null) {
            try {
                sIpsec.applyTransportModeTransform(fd,
                        IpSecManager.DIRECTION_IN, inXf);
            } catch (Exception e) {
                JoanTrace.note("tcp in xf " + e.getClass().getSimpleName());
            }
        }
        if (outXf != null) {
            try {
                sIpsec.applyTransportModeTransform(fd,
                        IpSecManager.DIRECTION_OUT, outXf);
            } catch (Exception e) {
                JoanTrace.note("tcp out xf " + e.getClass().getSimpleName());
            }
        }
    }

    private static String pollTcp() {
        FileDescriptor got = acceptOne(sTcpS, sInS, sOutS);
        if (got == null) {
            got = acceptOne(sTcpC, sInC, sOutC);
        }
        FileDescriptor peer = sTcpPeer;
        if (got == null && peer == null) {
            return null;
        }
        if (got != null) {
            closeFd(sTcpPeer);
            sTcpPeer = got;
            sTcpAcc.setLength(0);
            sReplyTcp = true;
            JoanTrace.note("tcp accept");
        }
        peer = sTcpPeer;
        if (peer == null) {
            return null;
        }
        try {
            StructPollfd p = new StructPollfd();
            p.fd = peer;
            p.events = (short) OsConstants.POLLIN;
            if (Os.poll(new StructPollfd[] { p }, 40) <= 0) {
                return null;
            }
            byte[] buf = new byte[4096];
            int n = Os.read(peer, buf, 0, buf.length);
            if (n <= 0) {
                closeFd(sTcpPeer);
                sTcpPeer = null;
                sReplyTcp = false;
                return null;
            }
            sTcpAcc.append(new String(buf, 0, n, StandardCharsets.US_ASCII));
            return JoanSipBuilder.extractOne(sTcpAcc);
        } catch (Exception e) {
            JoanTrace.note("tcp read " + e.getClass().getSimpleName());
            closeFd(sTcpPeer);
            sTcpPeer = null;
            sReplyTcp = false;
            return null;
        }
    }

    private static FileDescriptor acceptOne(FileDescriptor ls,
                                            IpSecTransform inXf,
                                            IpSecTransform outXf) {
        if (ls == null) {
            return null;
        }
        try {
            StructPollfd p = new StructPollfd();
            p.fd = ls;
            p.events = (short) OsConstants.POLLIN;
            if (Os.poll(new StructPollfd[] { p }, 0) <= 0) {
                return null;
            }
            InetSocketAddress peer = new InetSocketAddress(0);
            FileDescriptor c = Os.accept(ls, peer);
            applyXf(c, inXf, outXf);
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    private static void sendReply(byte[] pkt) throws Exception {
        if (sReplyTcp && sTcpPeer != null) {
            int off = 0;
            while (off < pkt.length) {
                int n = Os.write(sTcpPeer, pkt, off, pkt.length - off);
                if (n <= 0) {
                    throw new java.io.IOException("tcp write");
                }
                off += n;
            }
            return;
        }
        send(sSockC, sPcscf, sPcscfPortS, pkt);
    }

    private static void closeFd(FileDescriptor fd) {
        if (fd == null) {
            return;
        }
        try {
            Os.close(fd);
        } catch (Exception ignored) {
            // ignore
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
        sExpiresSec = 0;
        sRegisteredAtMs = 0;
        closeFd(sTcpPeer);
        closeFd(sTcpS);
        closeFd(sTcpC);
        sTcpPeer = sTcpS = sTcpC = null;
        sReplyTcp = false;
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
