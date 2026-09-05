package org.joan.ims;

import android.content.Context;
import android.net.IpSecManager;
import android.net.IpSecTransform;
import android.net.Network;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import java.io.FileDescriptor;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * In-app SIP UA: keeps the IPsec sockets after REGISTER 200 so INVITE and
 * inbound requests can use them. Never logs identities or request-URIs.
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
    private static Socket sTcpClient;
    private static final StringBuilder sTcpClientAcc = new StringBuilder();
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
    private static volatile String sRingingToTag;
    private static volatile InetAddress sMediaIp;
    private static volatile int sMediaPort;
    private static volatile int sMediaRtcpPort;
    /** Negotiated payload type, and TRUE/FALSE for AMR-WB/NB, null=PCMU. */
    private static volatile int sMediaPt;
    private static volatile Boolean sMediaAmrWb;
    private static volatile boolean sMediaMux;
    /** True when the live dialog is on hold (sendonly, no RTP). */
    private static volatile boolean sLiveHeld;
    /** Second established dialog, parked (held) while another is live. */
    private static volatile Leg sParked;
    /**
     * Every outbound INVITE is routed by both Call-ID and INVITE CSeq. A
     * parked leg can legitimately retransmit a 2xx while another leg has a
     * newer re-INVITE in flight, so a single global "last 2xx" queue is not
     * a safe transaction boundary.
     */
    private static volatile InviteWait sInitialInviteWait;
    private static volatile InviteWait sReinviteWait;
    private static final JoanSipBuilder.InviteAckArchive sInviteAcks =
            new JoanSipBuilder.InviteAckArchive();

    private static final class Leg {
        JoanSipBuilder.Dialog dlg;
        String dest, target, route, toHdr, fromHdr, ourToTag;
        InetAddress mediaIp;
        int mediaPort, mediaRtcpPort, mediaPt;
        Boolean mediaAmrWb;
        boolean mux;
        boolean held;
    }

    private static final class InviteWait {
        final String callId;
        final int cseq;
        final java.util.concurrent.LinkedBlockingQueue<String> replies =
                new java.util.concurrent.LinkedBlockingQueue<>();

        InviteWait(String callId, int cseq) {
            this.callId = callId;
            this.cseq = cseq;
        }

        boolean matches(String otherCallId, int otherCseq) {
            return callId != null && callId.equals(otherCallId)
                    && cseq == otherCseq;
        }
    }

    private JoanSipUa() {}

    static boolean isRegistered() {
        return sReg;
    }

    static boolean callActive() {
        return sCall;
    }

    /** True when the live dialog is already held (sendonly). answer() uses
     * this to avoid a second hold re-INVITE when the framework already
     * held the call during switchWaitingOrHoldingAndActive. */
    static boolean liveHeld() {
        return sCall && sLiveHeld;
    }

    static String currentCallId() {
        JoanSipBuilder.Dialog d = sDlg;
        return d != null ? d.callId : null;
    }

    static boolean dialogAlive(String sipCallId) {
        if (sipCallId == null || sipCallId.isEmpty()) {
            return false;
        }
        synchronized (LOCK) {
            if (sDlg != null && sipCallId.equals(sDlg.callId)) {
                return sCall;
            }
            if (sParked != null && sParked.dlg != null
                    && sipCallId.equals(sParked.dlg.callId)) {
                return true;
            }
            if (sHeldInvite != null && sipCallId.equals(
                    JoanSipBuilder.header(sHeldInvite, "Call-ID"))) {
                return true;
            }
        }
        return false;
    }

    static boolean callHeld(String sipCallId) {
        if (sipCallId == null) {
            return false;
        }
        synchronized (LOCK) {
            if (sDlg != null && sipCallId.equals(sDlg.callId)) {
                return sLiveHeld;
            }
            if (sParked != null && sParked.dlg != null
                    && sipCallId.equals(sParked.dlg.callId)) {
                return true;
            }
        }
        return false;
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
        long lead = JoanSipBuilder.refreshLeadMs(sExpiresSec,
                REFRESH_CAP_MS, REFRESH_FLOOR_MS);
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

    static int mediaPt() {
        return sMediaPt;
    }

    static Boolean mediaAmrWideband() {
        return sMediaAmrWb;
    }

    /** The peer's RTCP port from its a=rtcp:, or the RTP port + 1. */
    static int mediaRtcpPort() {
        return sMediaRtcpPort > 0 ? sMediaRtcpPort : sMediaPort + 1;
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
                      Socket tcpClient,
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
            sTcpClient = tcpClient;
            sTcpClientAcc.setLength(0);
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
        boolean addingSecond;
        synchronized (LOCK) {
            if (sCall && sParked != null) {
                return "ERR two calls";
            }
            if (sCall && !sLiveHeld) {
                return "ERR hold first";
            }
            addingSecond = sCall && sLiveHeld;
        }
        JoanSipBuilder.Id id = new JoanSipBuilder.Id(
                sId.impi, sPublicId, sId.realm, sId.localIp,
                sId.viaPort, sId.contactPort, sId.imei);
        JoanSipBuilder.Dialog dlg = new JoanSipBuilder.Dialog();
        boolean secAgree = true;
        String msg = JoanSipBuilder.buildInvite(id, dlg, dest, sServiceRoute,
                sSecVerify, RTP_PORT, sPani, secAgree);
        if (msg == null) {
            return "ERR build invite";
        }
        sInviteAcks.begin(dlg.callId, dlg.cseq);
        InviteWait wait = new InviteWait(dlg.callId, dlg.cseq);
        sInitialInviteWait = wait;
        byte[] pkt = msg.getBytes(StandardCharsets.US_ASCII);
        JoanTrace.note("app invite built bytes=" + pkt.length);
        try {
            send(sSockC, sPcscf, sPcscfPortS, pkt);
        } catch (Exception e) {
            clearInitialInviteWait(wait);
            return "ERR invite send";
        }
        long deadline = System.currentTimeMillis() + 30000;
        String toHdr = "";
        String fromHdr = "";
        String target = dest;
        String route = sServiceRoute;
        while (System.currentTimeMillis() < deadline) {
            String rx = wait.replies.poll();
            if (rx == null) {
                rx = recvEither((int) Math.min(400,
                        deadline - System.currentTimeMillis()));
            }
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
                /* The answer names the codec that was actually selected.
                 * We only speak PCMU; streaming u-law into anything else
                 * is noise in both directions and reports no error. ACK
                 * first so the dialog is well formed, then hang it up. */
                String enc = media == null ? "" : media.codecName;
                boolean amrWb = "AMR-WB".equalsIgnoreCase(enc);
                boolean amrNb = "AMR".equalsIgnoreCase(enc);
                if (media != null && media.payloadType != 0
                        && !amrWb && !amrNb) {
                    JoanTrace.note("app invite answered pt="
                            + media.payloadType + " (" + enc
                            + "); not implemented");
                    sendAck2xx(id, dlg, target, route, toHdr, fromHdr,
                            dlg.cseq);
                    synchronized (LOCK) {
                        sDlg = dlg;
                        sTarget = target;
                        sRoute = route;
                        sToHdr = toHdr;
                        sFromHdr = fromHdr;
                        sCall = true;
                    }
                    clearInitialInviteWait(wait);
                    hangup();
                    return "ERR unsupported codec " + media.payloadType;
                }
                if (!sendAck2xx(id, dlg, target, route, toHdr, fromHdr,
                        dlg.cseq)) {
                    clearInitialInviteWait(wait);
                    return "ERR ack send";
                }
                synchronized (LOCK) {
                    if (addingSecond) {
                        sParked = snapLocked();
                    }
                    sDlg = dlg;
                    sDest = dest;
                    sTarget = target;
                    sRoute = route;
                    sToHdr = toHdr;
                    sFromHdr = fromHdr;
                    sCall = true;
                    sLiveHeld = false;
                    if (media != null) {
                        try {
                            sMediaIp = InetAddress.getByName(media.ip);
                            sMediaPort = media.port;
                            sMediaRtcpPort = media.rtcpPort;
                            sMediaMux = media.mux;
                            sMediaPt = media.payloadType;
                            sMediaAmrWb = amrWb ? Boolean.TRUE
                                    : (amrNb ? Boolean.FALSE : null);
                            JoanTrace.note("app invite codec="
                                    + (enc.isEmpty() ? "PCMU" : enc)
                                    + " pt=" + media.payloadType);
                        } catch (Exception e) {
                            sMediaIp = null;
                        }
                    }
                }
                JoanTrace.note("app invite 200 media="
                        + (sMediaIp != null ? "yes" : "no")
                        + " mux=" + sMediaMux);
                clearInitialInviteWait(wait);
                return "OK";
            }
            if (p.status == 420 && secAgree) {
                /* Bad Extension. Some proxy on the path did not understand
                 * an option tag; sec-agree is the only one we send, and it
                 * is hop-by-hop and should have been stripped by the
                 * P-CSCF. Retry once without it as a fresh transaction
                 * rather than failing the call. */
                String finalTo = nullToEmpty(JoanSipBuilder.header(rx, "To"));
                String finalFrom = nullToEmpty(JoanSipBuilder.header(rx, "From"));
                String contact = JoanSipBuilder.header(rx, "Contact");
                String finalTarget = contact == null ? dest
                        : JoanSipBuilder.contactUri(contact);
                String rr = JoanSipBuilder.header(rx, "Record-Route");
                String finalRoute = rr == null || rr.isEmpty() ? sServiceRoute : rr;
                sendAckNon2xx(id, dlg, finalTarget, finalRoute, finalTo,
                        finalFrom, dlg.cseq, dlg.branch);
                secAgree = false;
                JoanTrace.note("app invite 420; retrying without sec-agree");
                clearInitialInviteWait(wait);
                dlg = new JoanSipBuilder.Dialog();
                String retry = JoanSipBuilder.buildInvite(id, dlg, dest,
                        sServiceRoute, sSecVerify, RTP_PORT, sPani, false);
                if (retry == null) {
                    return "ERR build invite";
                }
                sInviteAcks.begin(dlg.callId, dlg.cseq);
                wait = new InviteWait(dlg.callId, dlg.cseq);
                sInitialInviteWait = wait;
                try {
                    send(sSockC, sPcscf, sPcscfPortS,
                            retry.getBytes(StandardCharsets.US_ASCII));
                } catch (Exception e) {
                    clearInitialInviteWait(wait);
                    return "ERR invite send";
                }
                deadline = System.currentTimeMillis() + 30000;
                continue;
            }
            if (p.status >= 300) {
                String finalTo = nullToEmpty(JoanSipBuilder.header(rx, "To"));
                String finalFrom = nullToEmpty(JoanSipBuilder.header(rx, "From"));
                String contact = JoanSipBuilder.header(rx, "Contact");
                String finalTarget = contact == null ? dest
                        : JoanSipBuilder.contactUri(contact);
                String rr = JoanSipBuilder.header(rx, "Record-Route");
                String finalRoute = rr == null || rr.isEmpty() ? sServiceRoute : rr;
                sendAckNon2xx(id, dlg, finalTarget, finalRoute, finalTo,
                        finalFrom, dlg.cseq, dlg.branch);
                clearInitialInviteWait(wait);
                return "ERR invite " + p.status;
            }
        }
        clearInitialInviteWait(wait);
        return "ERR invite timeout";
    }

    static void hangup() {
        hangup(currentCallId());
    }

    static void hangup(String sipCallId) {
        JoanSipBuilder.Dialog dlg;
        JoanSipBuilder.Id id;
        String target, route, toHdr, fromHdr;
        boolean parkedBye = false;
        synchronized (LOCK) {
            if (sipCallId != null && sParked != null && sParked.dlg != null
                    && sipCallId.equals(sParked.dlg.callId)) {
                dlg = sParked.dlg;
                target = sParked.target != null && !sParked.target.isEmpty()
                        ? sParked.target : sParked.dest;
                route = sParked.route;
                toHdr = sParked.toHdr;
                fromHdr = sParked.fromHdr;
                sParked = null;
                parkedBye = true;
            } else {
                if (!sCall || sId == null) {
                    sCall = false;
                    return;
                }
                dlg = sDlg;
                if (dlg == null) {
                    sCall = false;
                    return;
                }
                if (sipCallId != null && dlg.callId != null
                        && !sipCallId.equals(dlg.callId)) {
                    return;
                }
                target = sTarget != null && !sTarget.isEmpty() ? sTarget : sDest;
                route = sRoute;
                toHdr = sToHdr;
                fromHdr = sFromHdr;
                sCall = false;
                sLiveHeld = false;
                if (sParked != null) {
                    loadLocked(sParked);
                    sParked = null;
                    sCall = true;
                    sLiveHeld = true;
                }
            }
            id = new JoanSipBuilder.Id(sId.impi, sPublicId, sId.realm,
                    sId.localIp, sId.viaPort, sId.contactPort, sId.imei);
        }
        if (target == null || target.isEmpty()) {
            JoanTrace.note("app BYE skipped no target");
            return;
        }
        String bye = JoanSipBuilder.buildBye(id, dlg, target, route,
                sSecVerify, toHdr, fromHdr);
        try {
            sendReply(bye.getBytes(StandardCharsets.US_ASCII));
            JoanTrace.note(parkedBye ? "app BYE sent parked" : "app BYE sent");
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
        if (sCall) {
            /* The framework holds the active call itself while accepting
             * the waiting one (switchWaitingOrHoldingAndActive). If that
             * hold already ran, a second re-INVITE here would complete a
             * handshake the tracker never started -- it desyncs its state
             * machine and every later unhold dies with "Call update is in
             * progress". Only hold if nobody else did. */
            if (!liveHeld()) {
                String h = hold(currentCallId());
                if (h == null) {
                    h = "ERR hold first";
                }
                if (h == null || !h.startsWith("OK")) {
                    JoanTrace.note("app answer: hold first call failed: " + h);
                }
            } else {
                JoanTrace.note("app answer: first call already held");
            }
            synchronized (LOCK) {
                sParked = snapLocked();
            }
        }
        JoanSipBuilder.Id id = new JoanSipBuilder.Id(
                sId.impi, sPublicId, sId.realm, sId.localIp,
                sId.viaPort, sId.contactPort, sId.imei);
        String sdp = JoanSipBuilder.sdpAnswer(sId.localIp, RTP_PORT, invite);
        String tag = sRingingToTag;
        if (tag == null || tag.isEmpty()) {
            tag = sOurToTag;
        }
        if (tag == null || tag.isEmpty()) {
            tag = String.format("%012x",
                    new java.security.SecureRandom().nextLong() & 0xffffffffffffL);
        }
        sOurToTag = tag;
        String resp = buildResponse(invite, 200, "OK", id, tag, sdp);
        try {
            sendReply(resp.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            return "ERR answer send";
        }
        JoanSipBuilder.Media media = JoanSipBuilder.parseSdp(invite);
        synchronized (LOCK) {
            sCall = true;
            sLiveHeld = false;
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
                    sMediaRtcpPort = media.rtcpPort;
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
        String tag = sRingingToTag != null ? sRingingToTag
                : (sOurToTag != null ? sOurToTag : "rej");
        String resp = buildResponse(invite, code,
                code == 603 ? "Decline" : "Busy Here", sId, tag, null);
        try {
            sendReply(resp.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception ignored) {
            return "ERR reject send";
        }
        return "OK";
    }

    static String hold(String sipCallId) {
        synchronized (LOCK) {
            if (!sCall || sDlg == null) {
                return "ERR no call";
            }
            if (sipCallId != null && sDlg.callId != null
                    && !sipCallId.equals(sDlg.callId)) {
                if (sParked != null && sParked.dlg != null
                        && sipCallId.equals(sParked.dlg.callId)) {
                    return "OK";
                }
                return "ERR not live";
            }
            if (sLiveHeld) {
                return "OK";
            }
        }
        String r = reInviteLive(true);
        if (r != null && r.startsWith("OK")) {
            JoanMedia.stop();
            sLiveHeld = true;
            JoanTrace.note("app hold");
        }
        return r;
    }

    static String resume(String sipCallId) {
        boolean swap;
        synchronized (LOCK) {
            if (!sCall || sDlg == null) {
                return "ERR no call";
            }
            swap = sipCallId != null && sParked != null && sParked.dlg != null
                    && sipCallId.equals(sParked.dlg.callId);
            if (!swap && sipCallId != null && sDlg.callId != null
                    && !sipCallId.equals(sDlg.callId)) {
                return "ERR not live";
            }
        }
        if (swap) {
            String h = hold(sDlg.callId);
            if (h == null || !h.startsWith("OK")) {
                return h == null ? "ERR hold" : h;
            }
            synchronized (LOCK) {
                Leg was = snapLocked();
                loadLocked(sParked);
                sParked = was;
                sLiveHeld = true;
            }
        }
        String r = reInviteLive(false);
        if (r != null && r.startsWith("OK")) {
            sLiveHeld = false;
            JoanTrace.note("app resume");
        }
        return r;
    }

    private static Leg snapLocked() {
        Leg l = new Leg();
        l.dlg = sDlg;
        l.dest = sDest;
        l.target = sTarget;
        l.route = sRoute;
        l.toHdr = sToHdr;
        l.fromHdr = sFromHdr;
        l.ourToTag = sOurToTag;
        l.mediaIp = sMediaIp;
        l.mediaPort = sMediaPort;
        l.mediaRtcpPort = sMediaRtcpPort;
        l.mediaPt = sMediaPt;
        l.mediaAmrWb = sMediaAmrWb;
        l.mux = sMediaMux;
        l.held = true;
        return l;
    }

    private static void loadLocked(Leg l) {
        sDlg = l.dlg;
        sDest = l.dest;
        sTarget = l.target;
        sRoute = l.route;
        sToHdr = l.toHdr;
        sFromHdr = l.fromHdr;
        sOurToTag = l.ourToTag;
        sMediaIp = l.mediaIp;
        sMediaPort = l.mediaPort;
        sMediaRtcpPort = l.mediaRtcpPort;
        sMediaPt = l.mediaPt;
        sMediaAmrWb = l.mediaAmrWb;
        sMediaMux = l.mux;
        sLiveHeld = l.held;
    }

    private static String reInviteLive(boolean held) {
        JoanSipBuilder.Dialog dlg;
        JoanSipBuilder.Id id;
        String target, route, toHdr, fromHdr;
        synchronized (LOCK) {
            if (sId == null || sDlg == null) {
                return "ERR no dialog";
            }
            dlg = sDlg;
            /* buildReInvite() owns the increment: it does dlg.cseq++.
             * Incrementing here too sent +2 steps (2,4,6,8...), which a
             * strict P-CSCF drops silently -- resume then times out.
             * RFC 3261 12.2.2: exactly +1 per in-dialog request. */
            id = new JoanSipBuilder.Id(sId.impi, sPublicId, sId.realm,
                    sId.localIp, sId.viaPort, sId.contactPort, sId.imei);
            target = sTarget != null && !sTarget.isEmpty() ? sTarget : sDest;
            route = sRoute;
            toHdr = sToHdr;
            fromHdr = sFromHdr;
        }
        if (target == null || target.isEmpty()) {
            return "ERR no target";
        }
        String sdp = JoanSipBuilder.sdpHold(id.localIp, RTP_PORT, held);
        String inv = JoanSipBuilder.buildReInvite(id, dlg, target, route,
                sSecVerify, toHdr, fromHdr, sdp);
        int inviteCseq = dlg.cseq;
        String inviteBranch = dlg.branch;
        sInviteAcks.begin(dlg.callId, inviteCseq);
        InviteWait wait = new InviteWait(dlg.callId, inviteCseq);
        sReinviteWait = wait;
        JoanTrace.note("app reinvite send held=" + held
                + " cseq=" + inviteCseq + " cid=" + dlg.callId);
        try {
            sendReply(inv.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            clearReinviteWait(wait);
            JoanTrace.note("app reinvite ERR send");
            return "ERR reinvite send";
        }
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            /* The reply usually lands on the accepted TCP socket, which the
             * listen thread owns; it hands over only the matching Call-ID +
             * INVITE CSeq instead of racing us to the ACK. */
            String handed = wait.replies.poll();
            if (handed != null) {
                JoanSipBuilder.Reply hp = JoanSipBuilder.parseReply(handed);
                String hcid = JoanSipBuilder.header(handed, "Call-ID");
                int hcseq = JoanSipBuilder.cseqForMethod(handed, "INVITE");
                boolean mine = wait.matches(hcid, hcseq);
                if (mine && hp != null && hp.status >= 200
                        && hp.status < 300) {
                    if (!sendAck2xx(id, dlg, target, route, toHdr, fromHdr,
                            inviteCseq)) {
                        clearReinviteWait(wait);
                        return "ERR reinvite ack send";
                    }
                    clearReinviteWait(wait);
                    return "OK";
                }
                if (mine && hp != null && hp.status >= 300) {
                    /* A non-2xx ACK is part of this INVITE transaction and
                     * therefore uses inviteBranch, not a fresh branch. */
                    sendAckNon2xx(id, dlg, target, route, toHdr, fromHdr,
                            inviteCseq, inviteBranch);
                    clearReinviteWait(wait);
                    JoanTrace.note("app reinvite ERR " + hp.status);
                    return "ERR reinvite " + hp.status;
                }
                if (hp != null && hp.status >= 200) {
                    reAckFinal(hcid, hcseq, hp.status);
                }
                continue;
            }
            String rx = recvEither((int) Math.min(400,
                    deadline - System.currentTimeMillis()));
            if (rx == null) {
                continue;
            }
            if (!JoanSipBuilder.requestMethod(rx).isEmpty()) {
                handleInbound(rx);
                continue;
            }
            JoanSipBuilder.Reply p = JoanSipBuilder.parseReply(rx);
            if (p == null) {
                continue;
            }
            String cid = JoanSipBuilder.header(rx, "Call-ID");
            int cseq = JoanSipBuilder.cseqForMethod(rx, "INVITE");
            if (!wait.matches(cid, cseq)) {
                if (p.status >= 200) {
                    reAckFinal(cid, cseq, p.status);
                }
                continue;
            }
            if (p.status >= 200 && p.status < 300) {
                if (!sendAck2xx(id, dlg, target, route, toHdr, fromHdr,
                        inviteCseq)) {
                    clearReinviteWait(wait);
                    return "ERR reinvite ack send";
                }
                clearReinviteWait(wait);
                return "OK";
            }
            if (p.status >= 300) {
                sendAckNon2xx(id, dlg, target, route, toHdr, fromHdr,
                        inviteCseq, inviteBranch);
                clearReinviteWait(wait);
                JoanTrace.note("app reinvite ERR " + p.status);
                return "ERR reinvite " + p.status;
            }
        }
        clearReinviteWait(wait);
        JoanTrace.note("app reinvite ERR timeout");
        return "ERR reinvite timeout";
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

    private static boolean sendAck2xx(JoanSipBuilder.Id id,
                                      JoanSipBuilder.Dialog dlg,
                                      String target, String route,
                                      String toHdr, String fromHdr,
                                      int inviteCseq) {
        try {
            String ack = JoanSipBuilder.buildAck2xx(id, dlg, target, route,
                    sSecVerify, toHdr, fromHdr, inviteCseq);
            sInviteAcks.remember2xx(dlg.callId, inviteCseq, ack);
            sendReply(ack.getBytes(StandardCharsets.US_ASCII));
            return true;
        } catch (Exception e) {
            JoanTrace.note("ACK 2xx send " + e.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean sendAckNon2xx(JoanSipBuilder.Id id,
                                         JoanSipBuilder.Dialog dlg,
                                         String target, String route,
                                         String toHdr, String fromHdr,
                                         int inviteCseq, String inviteBranch) {
        try {
            String ack = JoanSipBuilder.buildAckNon2xx(id, dlg, target, route,
                    sSecVerify, toHdr, fromHdr, inviteCseq, inviteBranch);
            if (ack == null) {
                return false;
            }
            sInviteAcks.rememberNon2xx(dlg.callId, inviteCseq, ack);
            sendReply(ack.getBytes(StandardCharsets.US_ASCII));
            return true;
        } catch (Exception e) {
            JoanTrace.note("ACK non2xx send " + e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Re-send the exact archived ACK for a repeated final response. Never
     * reconstruct it from a mutable dialog: a newer hold/resume may already
     * have changed that dialog's CSeq and latest INVITE branch.
     */
    private static boolean reAckFinal(String callId, int inviteCseq,
                                      int status) {
        if (status < 200) {
            return false;
        }
        String ack = status < 300
                ? sInviteAcks.ack2xx(callId, inviteCseq)
                : sInviteAcks.ackNon2xx(callId, inviteCseq);
        if (ack == null) {
            JoanTrace.note("ACK archive miss status=" + status
                    + " cseq=" + inviteCseq);
            return false;
        }
        try {
            sendReply(ack.getBytes(StandardCharsets.US_ASCII));
            JoanTrace.note("re-ACK final status=" + status
                    + " cseq=" + inviteCseq);
            return true;
        } catch (Exception e) {
            JoanTrace.note("re-ACK send " + e.getClass().getSimpleName());
            return false;
        }
    }

    private static void clearInitialInviteWait(InviteWait wait) {
        if (sInitialInviteWait == wait) {
            sInitialInviteWait = null;
        }
    }

    private static void clearReinviteWait(InviteWait wait) {
        if (sReinviteWait == wait) {
            sReinviteWait = null;
        }
    }

    private static void handleInbound(String rx) {
        String method = JoanSipBuilder.requestMethod(rx);
        if (method.isEmpty()) {
            JoanSipBuilder.Reply p = JoanSipBuilder.parseReply(rx);
            if (p == null) {
                return;
            }
            String cid = JoanSipBuilder.header(rx, "Call-ID");
            int cseq = JoanSipBuilder.cseqForMethod(rx, "INVITE");
            /* An already-completed transaction wins over an active waiter:
             * this is a retransmitted older final, not a reply to the newer
             * hold/resume that happens to share the dialog Call-ID. */
            if (p.status >= 200 && reAckFinal(cid, cseq, p.status)) {
                return;
            }
            InviteWait reinvite = sReinviteWait;
            if (reinvite != null && reinvite.matches(cid, cseq)) {
                JoanTrace.note("reply routed reinvite status=" + p.status
                        + " cseq=" + cseq + " via="
                        + (sReplyTcp ? "tcp" : "udp"));
                reinvite.replies.offer(rx);
                return;
            }
            InviteWait initial = sInitialInviteWait;
            if (initial != null && initial.matches(cid, cseq)) {
                JoanTrace.note("reply routed invite status=" + p.status
                        + " cseq=" + cseq + " via="
                        + (sReplyTcp ? "tcp" : "udp"));
                initial.replies.offer(rx);
                return;
            }
            if (p.status >= 200) {
                /* Do not manufacture an ACK for an unknown transaction.
                 * Guessing with sDlg used to send a newer CSeq/branch for a
                 * stale response and could prolong the Timer G storm. */
                JoanTrace.note("unmatched final status=" + p.status
                        + " cseq=" + cseq);
            }
            return;
        }
        if ("BYE".equals(method)) {
            String cid = JoanSipBuilder.header(rx, "Call-ID");
            JoanTrace.note("app inbound BYE");
            String tag = sOurToTag != null ? sOurToTag : "x";
            synchronized (LOCK) {
                if (sParked != null && sParked.dlg != null
                        && cid != null && cid.equals(sParked.dlg.callId)) {
                    tag = sParked.ourToTag != null ? sParked.ourToTag : tag;
                    sParked = null;
                    try {
                        sendReply(buildResponse(rx, 200, "OK", sId, tag, null)
                                .getBytes(StandardCharsets.US_ASCII));
                    } catch (Exception ignored) {
                        // ignore
                    }
                    JoanMmTelFeature.onDialogEnded(cid);
                    return;
                }
            }
            try {
                sendReply(buildResponse(rx, 200, "OK", sId, tag, null)
                        .getBytes(StandardCharsets.US_ASCII));
            } catch (Exception ignored) {
                // ignore
            }
            sCall = false;
            sLiveHeld = false;
            JoanMedia.stop();
            synchronized (LOCK) {
                if (sParked != null) {
                    loadLocked(sParked);
                    sParked = null;
                    sCall = true;
                    sLiveHeld = true;
                }
            }
            if (cid != null) {
                JoanMmTelFeature.onDialogEnded(cid);
            } else {
                JoanMmTelFeature.onCallEndedRemotely();
            }
            return;
        }
        if ("CANCEL".equals(method)) {
            handleCancel(rx);
            return;
        }
        if ("OPTIONS".equals(method)) {
            /* Cores use OPTIONS as a liveness probe. Silence can get the
             * binding torn down, and we advertise OPTIONS in Allow, so
             * answer it and say what we accept. */
            try {
                sendReply(buildResponse(rx, 200, "OK", sId,
                        sOurToTag != null ? sOurToTag : "opt", null,
                        "Allow: " + JoanSipBuilder.ALLOW + "\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
            } catch (Exception ignored) {
                // ignore
            }
            return;
        }
        if (!"INVITE".equals(method)) {
            /* Anything else is dropped without a reply. That is worth
             * saying out loud: the REGISTER Contact advertises
             * +g.3gpp.smsip and Allow lists MESSAGE, so a core is entitled
             * to deliver SMS here as a SIP MESSAGE -- which would land
             * exactly here and vanish. Log the method, never the message:
             * a MESSAGE body is the text of someone's SMS. */
            if (!"ACK".equals(method)) {
                /* ACK needs no response and ignoring it is correct;
                 * calling that "unhandled" in the log is misleading. */
                JoanTrace.note("app inbound unhandled method=" + method);
            }
            return;
        }
        if (sHeldInvite != null || (sCall && sParked != null)) {
            /* A retransmitted INVITE is not a second call. Over UDP the
             * P-CSCF resends on a short timer; if our 180 was delayed the
             * copy lands here, and 486 to the same Call-ID is read by the
             * network as a rejection: the caller falls to voicemail while
             * the user thinks they answered. Resend the provisional reply
             * for a retransmit, resend 200 for an established dialog. */
            String cid = JoanSipBuilder.header(rx, "Call-ID");
            String held = sHeldInvite;
            if (cid != null && held != null
                    && cid.equals(JoanSipBuilder.header(held, "Call-ID"))) {
                String tag = sRingingToTag != null ? sRingingToTag : "ring";
                try {
                    sendReply(buildResponse(rx, 100, "Trying", sId, null, null)
                            .getBytes(StandardCharsets.US_ASCII));
                    sendReply(buildResponse(rx, 180, "Ringing", sId, tag, null)
                            .getBytes(StandardCharsets.US_ASCII));
                } catch (Exception ignored) {
                    // ignore
                }
                JoanTrace.note("app inbound INVITE retransmit; 180 resent");
                return;
            }
            if (cid != null && sDlg != null && sCall
                    && cid.equals(sDlg.callId)) {
                String tag = sOurToTag != null ? sOurToTag : "dlg";
                String sdp = sId != null
                        ? JoanSipBuilder.sdpAnswer(sId.localIp, RTP_PORT, rx)
                        : null;
                try {
                    sendReply(buildResponse(rx, 200, "OK", sId, tag, sdp)
                            .getBytes(StandardCharsets.US_ASCII));
                } catch (Exception ignored) {
                    // ignore
                }
                JoanTrace.note("app inbound INVITE retransmit; 200 resent");
                return;
            }
            try {
                sendReply(buildResponse(rx, 486, "Busy Here", sId, "busy", null)
                        .getBytes(StandardCharsets.US_ASCII));
            } catch (Exception ignored) {
                // ignore
            }
            JoanTrace.note("app inbound INVITE busy");
            return;
        }
        JoanSipBuilder.Media offer = JoanSipBuilder.parseSdp(rx);
        if (offer != null && !offer.offersPcmu) {
            /* sdpAnswer() would answer PCMU regardless of what was
             * offered. Better to decline than to ring the user for a call
             * that cannot carry audio. */
            JoanTrace.note("app inbound INVITE offers no PCMU; 488");
            try {
                sendReply(buildResponse(rx, 488, "Not Acceptable Here",
                        sId, "nocodec", null)
                        .getBytes(StandardCharsets.US_ASCII));
            } catch (Exception ignored) {
                // ignore
            }
            return;
        }
        sRingingToTag = String.format("%012x",
                new java.security.SecureRandom().nextLong() & 0xffffffffffffL);
        if (!sCall) {
            sOurToTag = sRingingToTag;
        }
        sHeldInvite = rx;
        try {
            sendReply(buildResponse(rx, 100, "Trying", sId, null, null)
                    .getBytes(StandardCharsets.US_ASCII));
            sendReply(buildResponse(rx, 180, "Ringing", sId, sRingingToTag, null)
                    .getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            JoanTrace.note("app 180 send fail");
        }
        JoanSipBuilder.Cli cli = JoanSipBuilder.callingIdentity(rx);
        JoanTrace.note("app inbound INVITE tcp=" + sReplyTcp
                + " waiting=" + sCall
                + " number=" + (cli.withheld || cli.uri.isEmpty() ? "no" : "yes")
                + " name=" + (cli.name.isEmpty() ? "no" : "yes"));
        if (sApp != null) {
            JoanMmTelFeature.onIncomingCall(sApp, cli.uri, cli.name,
                    JoanSipBuilder.header(rx, "Call-ID"));
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
        String tag = sRingingToTag != null ? sRingingToTag
                : (sOurToTag != null ? sOurToTag : "x");
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
        if (!sCall) {
            sOurToTag = null;
        }
        sRingingToTag = null;
        if (callId != null) {
            JoanMmTelFeature.onDialogEnded(callId);
        } else {
            JoanMmTelFeature.onCallEndedRemotely();
        }
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
            JoanSipBuilder.sUseTcp = true;
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
                JoanSipBuilder.sUseTcp = false;
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
        return buildResponse(req, code, reason, id, toTag, sdp, null);
    }

    private static String buildResponse(String req, int code, String reason,
                                        JoanSipBuilder.Id id, String toTag,
                                        String sdp, String extraHeaders) {
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
        if (extraHeaders != null) {
            a.append(extraHeaders);
        }
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
        if (sTcpClient != null && !sTcpClient.isClosed()) {
            OutputStream os = sTcpClient.getOutputStream();
            os.write(pkt);
            os.flush();
            return;
        }
        s.send(new DatagramPacket(pkt, pkt.length, dest, port));
    }

    private static String recvEither(int timeoutMs) {
        if (timeoutMs <= 0) {
            return null;
        }
        String tcp = recvTcpClient(Math.min(200, timeoutMs));
        if (tcp != null) {
            return tcp;
        }
        byte[] buf = new byte[4096];
        String a = JoanAppRegister.tryRecv(sSockC, buf, Math.min(200, timeoutMs));
        if (a != null) {
            return a;
        }
        return JoanAppRegister.tryRecv(sSockS, buf, Math.min(200, timeoutMs));
    }

    private static String recvTcpClient(int timeoutMs) {
        if (sTcpClient == null || sTcpClient.isClosed()) {
            return null;
        }
        try {
            sTcpClient.setSoTimeout(Math.max(1, timeoutMs));
            InputStream is = sTcpClient.getInputStream();
            byte[] buf = new byte[4096];
            int n = is.read(buf);
            if (n <= 0) {
                return null;
            }
            sTcpClientAcc.append(new String(buf, 0, n, StandardCharsets.US_ASCII));
            return JoanSipBuilder.extractOne(sTcpClientAcc);
        } catch (SocketTimeoutException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
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
        sLiveHeld = false;
        sParked = null;
        sInitialInviteWait = null;
        sReinviteWait = null;
        sInviteAcks.clear();
        sExpiresSec = 0;
        sRegisteredAtMs = 0;
        closeFd(sTcpPeer);
        closeFd(sTcpS);
        closeFd(sTcpC);
        sTcpPeer = sTcpS = sTcpC = null;
        sReplyTcp = false;
        if (sTcpClient != null) {
            try {
                sTcpClient.close();
            } catch (Exception ignored) {
                // ignore
            }
            sTcpClient = null;
        }
        sTcpClientAcc.setLength(0);
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
