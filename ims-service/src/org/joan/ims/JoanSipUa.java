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
     * a safe transaction boundary. Multiple INVITE transactions can also be
     * legitimately in flight at once (optimistic swap hold + parked-leg
     * resume), so the waiters live in a map keyed by Call-ID#CSeq.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String,
            InviteWait> sInviteWaits = new java.util.concurrent.ConcurrentHashMap<>();
    private static final JoanSipBuilder.InviteAckArchive sInviteAcks =
            new JoanSipBuilder.InviteAckArchive();
    /** Initial-INVITE wait, overridable offline (bench keeps 30 s). */
    static volatile long sInviteTimeoutMs = 30_000L;
    /** Waits for non-INVITE finals (REFER/SUBSCRIBE), keyed cid#cseq. */
    private static final java.util.concurrent.ConcurrentHashMap<String,
            InviteWait> sReferWaits = new java.util.concurrent.ConcurrentHashMap<>();

    /** One owner per dialog, not one global slot (RFC 3261 14.1). */
    private static final class InviteFlight {
        final boolean held;
        final java.util.concurrent.CompletableFuture<String> result =
                new java.util.concurrent.CompletableFuture<>();
        InviteFlight(boolean held) { this.held = held; }
    }
    private static final java.util.concurrent.ConcurrentHashMap<String,
            InviteFlight> sInviteFlights = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean inFlightOn(String callId) {
        return callId != null && sInviteFlights.containsKey(callId);
    }

    private static String pollReply(InviteWait wait, long timeoutMs) {
        try {
            return wait.replies.poll(Math.max(1, timeoutMs),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static class Leg {
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

        String key() {
            return callId + "#" + cseq;
        }

        boolean matches(String otherCallId, int otherCseq) {
            return callId != null && callId.equals(otherCallId)
                    && cseq == otherCseq;
        }
    }

    private static void registerInviteWait(InviteWait w) {
        sInviteWaits.put(w.key(), w);
    }

    private static void clearInviteWait(InviteWait w) {
        sInviteWaits.remove(w.key(), w);
    }

    static boolean swapInProgress() {
        return sParked != null;
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
        boolean liveCalls = sCall || sParked != null || sHeldInvite != null;
        synchronized (LOCK) {
            if (liveCalls) {
                /* A successful refresh re-plumbs sockets and SAs, but the
                 * dialogs survive RFC 3261 12: tearing them down here would
                 * hang up a live call every ~29 minutes. Keep call state;
                 * release transport/security for replacement below. */
                JoanTrace.note("app adopt: refresh with live call(s); "
                        + "dialogs preserved");
            }
            releaseLocked(!liveCalls);
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
        return invite(dest, false);
    }

    /**
     * @param conferenceFocus true while merging: the INVITE creates the
     * focus dialog, a THIRD dialog beside the two held legs, so the
     * two-call admission guard must not apply to it (that guard is what
     * made merge() refuse the state it itself requires).
     */
    static String invite(String dest, boolean conferenceFocus) {
        if (!sReg) {
            return "ERR call before register";
        }
        if (sPublicId == null || sPublicId.isEmpty()) {
            return "ERR no public identity";
        }
        boolean addingSecond;
        synchronized (LOCK) {
            if (conferenceFocus) {
                addingSecond = false;
            } else {
                if (sCall && sParked != null) {
                    return "ERR two calls";
                }
                if (sCall && !sLiveHeld) {
                    return "ERR hold first";
                }
                addingSecond = sCall && sLiveHeld;
            }
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
        sInviteAcks.begin(dlg.callId, dlg.cseq, dlg, "", "", dest,
                sServiceRoute);
        InviteWait wait = new InviteWait(dlg.callId, dlg.cseq);
        registerInviteWait(wait);
        byte[] pkt = msg.getBytes(StandardCharsets.US_ASCII);
        JoanTrace.note("app invite built bytes=" + pkt.length);
        try {
            send(sSockC, sPcscf, sPcscfPortS, pkt);
        } catch (Exception e) {
            clearInviteWait(wait);
            return "ERR invite send";
        }
        long deadline = System.currentTimeMillis() + sInviteTimeoutMs;
        String toHdr = "";
        String fromHdr = "";
        String target = dest;
        String route = sServiceRoute;
        while (System.currentTimeMillis() < deadline) {
            String rx = pollReply(wait, Math.min(400,
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
                            wait.cseq);
                    synchronized (LOCK) {
                        sDlg = dlg;
                        sTarget = target;
                        sRoute = route;
                        sToHdr = toHdr;
                        sFromHdr = fromHdr;
                        sCall = true;
                    }
                    clearInviteWait(wait);
                    hangup();
                    return "ERR unsupported codec " + media.payloadType;
                }
                if (!sendAck2xx(id, dlg, target, route, toHdr, fromHdr,
                        dlg.cseq)) {
                    clearInviteWait(wait);
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
                clearInviteWait(wait);
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
                        finalFrom, wait.cseq, dlg.branch);
                secAgree = false;
                JoanTrace.note("app invite 420; retrying without sec-agree");
                clearInviteWait(wait);
                dlg = new JoanSipBuilder.Dialog();
                String retry = JoanSipBuilder.buildInvite(id, dlg, dest,
                        sServiceRoute, sSecVerify, RTP_PORT, sPani, false);
                if (retry == null) {
                    return "ERR build invite";
                }
                sInviteAcks.begin(dlg.callId, dlg.cseq, dlg, "", "",
                        dest, sServiceRoute);
                wait = new InviteWait(dlg.callId, dlg.cseq);
                registerInviteWait(wait);
                try {
                    send(sSockC, sPcscf, sPcscfPortS,
                            retry.getBytes(StandardCharsets.US_ASCII));
                } catch (Exception e) {
                    clearInviteWait(wait);
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
                        finalFrom, wait.cseq, dlg.branch);
                clearInviteWait(wait);
                return "ERR invite " + p.status;
            }
        }
        clearInviteWait(wait);
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
            if (!liveHeld() && !inFlightOn(currentCallId())) {
                String h = hold(currentCallId());
                if (h == null) {
                    h = "ERR hold first";
                }
                if (h == null || !h.startsWith("OK")) {
                    JoanTrace.note("app answer: hold first call failed: " + h);
                }
            } else if (!liveHeld()) {
                JoanTrace.note("app answer: hold already in flight (skip)");
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

    /**
     * Conference merge, following the stock LG model (extract-only RE):
     * the network hosts the bridge. We INVITE the carrier's conference
     * focus (tConfURI, or the 3GPP factory URI when the profile leaves
     * it empty), then REFER each held leg into the focus with Replaces,
     * then SUBSCRIBE the conference event package so NOTIFYs track the
     * participants. Nothing here mixes audio locally.
     *
     * Sequence (StateIDLE_MergeConf + ConferenceRefer + SubscribeConferenceState):
     *   1. hold both legs (they arrive held from the framework)
     *   2. INVITE focus -> 200 (the conference exists)
     *   3. REFER leg A: Refer-To: <focus;method=INVITE?Replaces=...>
     *   4. REFER leg B likewise
     *   5. SUBSCRIBE conference-info on the focus dialog
     *
     * The REFERs are fire-and-verify: a 202 from the far end starts the
     * transfer; the focus sends NOTIFYs (or re-INVITEs) that end the
     * replaced dialogs. We do not block on the transfer completing.
     */
    static String merge(Context ctx, String mcc, String mnc) {
        if (!sReg) {
            return "ERR conference before register";
        }
        JoanCarrierProfile prof = JoanCarrierProfile.forNetwork(ctx, mcc, mnc);
        return merge(prof);
    }

    private static volatile boolean sMergeBusy;

    static String merge(JoanCarrierProfile prof) {
        if (sMergeBusy) {
            return "ERR merge in progress";
        }
        sMergeBusy = true;
        try {
            return mergeLocked(prof);
        } finally {
            sMergeBusy = false;
        }
    }

    private static String mergeLocked(JoanCarrierProfile prof) {
        Leg live;
        Leg parked;
        synchronized (LOCK) {
            if (!sCall || sDlg == null) {
                return "ERR no call to merge";
            }
            if (sParked == null) {
                return "ERR merge needs two calls";
            }
            live = snapLocked();
            parked = sParked;
        }
        if (!live.held) {
            /* AOSP ImsCall.merge(): when skipHoldBeforeMerge is false the
             * framework issues hold() and retries merge on hold success;
             * when true (or the race window) the active leg may still be
             * ACTIVE. The merge itself must not depend on that timing:
             * hold the live leg here, without notifying the framework a
             * second time (it already believes the hold state). */
            String r = hold(currentCallId());
            if (r == null || !r.startsWith("OK")) {
                JoanTrace.note("conf merge hold failed: " + r);
                return "ERR hold " + r;
            }
            synchronized (LOCK) {
                live = snapLocked();
                parked = sParked;
            }
        }
        JoanSipBuilder.Id id = new JoanSipBuilder.Id(
                sId.impi, sPublicId, sId.realm, sId.localIp,
                sId.viaPort, sId.contactPort, sId.imei);
        String focus = prof.confUri;
        if (focus == null || focus.isEmpty()) {
            return "ERR no conference focus uri";
        }
        JoanTrace.note("conf merge focus=" + focus
                + (prof.srcKey != null ? " (" + prof.srcKey + ")" : ""));

        /* 1. Create the conference by INVITing the focus as a THIRD,
         * separately owned leg. The original two dialogs stay untouched:
         * the far ends are REFER-red into the focus (RFC 4579 5.10), and
         * only the transfer notifications retire them. */
        FocusLeg focusLeg = inviteFocus(focus);
        if (focusLeg == null) {
            JoanTrace.note("conf merge focus invite failed");
            String rr = resume(currentCallId());
            JoanTrace.note("conf focus refusal resume: " + rr);
            return "ERR focus invite";
        }
        /* 2. REFER each original leg's participant into the focus. RFC
         * 4579 5.10: the REFER goes TO the focus; its Refer-To names the
         * remote participant with an escaped Replaces naming the ORIGINAL
         * point-to-point dialog, so the focus replaces that dialog. A 202
         * only means "request accepted": success is the refer-subscription
         * NOTIFY's final sipfrag (RFC 3515 2.4.8/2.4.9). */
        int confirmed = 0;
        boolean anyIrreversible = false;
        java.util.List<Leg> legs = new java.util.ArrayList<>(2);
        legs.add(parked);
        legs.add(live);
        String[] results = new String[2];
        for (int i = 0; i < legs.size(); i++) {
            Leg leg = legs.get(i);
            String referResult = referLegIntoFocus(id, focusLeg, leg,
                    prof.referSub);
            results[i] = referResult;
            JoanTrace.note("conf refer cid=" + leg.dlg.callId
                    + " -> " + referResult);
            if ("CONFIRMED".equals(referResult)) {
                confirmed++;
                anyIrreversible = true;
            } else if ("ACCEPTED".equals(referResult)) {
                /* 202 seen, final result unknown: NOT success. */
                anyIrreversible = true;
            }
        }
        if (confirmed == 0 && anyIrreversible) {
            retireFocus(focusLeg);
            return "ERR refer unconfirmed";
        }
        if (confirmed == 0) {
            retireFocus(focusLeg);
            String rr = resume(currentCallId());
            JoanTrace.note("conf refer refusal resume: " + rr);
            return "ERR refer refused";
        }
        /* 3. Subscribe the conference event package on the focus dialog
         * (stock Conference::SubscribeConferenceState). */
        if (prof.confSub) {
            String sub = JoanSipBuilder.buildConfSubscribe(id,
                    focusLeg.dlg, focusLeg.target != null
                    && !focusLeg.target.isEmpty()
                    ? focusLeg.target : focus,
                    focusLeg.route, sSecVerify, 21600);
            int subCseq = JoanSipBuilder.cseqForMethod(sub, "SUBSCRIBE");
            NonInviteWait subWait = new NonInviteWait(
                    focusLeg.dlg.callId, subCseq, "SUBSCRIBE",
                    JoanSipBuilder.branchOf(sub), null);
            sNonInviteWaits.put(subWait.callId + "#SUBSCRIBE#"
                    + subWait.cseq, subWait);
            try {
                sendReply(sub.getBytes(StandardCharsets.US_ASCII));
                String subFinal = waitNonInviteFinal(subWait, 8000);
                JoanTrace.note("conf SUBSCRIBE final=" + subFinal);
            } catch (Exception e) {
                JoanTrace.note("conf SUBSCRIBE send fail");
            } finally {
                sNonInviteWaits.remove(subWait.callId + "#SUBSCRIBE#"
                        + subWait.cseq, subWait);
            }
        }
        synchronized (LOCK) {
            if ("CONFIRMED".equals(results[0])) {
                sMergedDialogIds.add(parked.dlg.callId);
                retireDialogLocked(parked.dlg.callId);
            }
            if ("CONFIRMED".equals(results[1])) {
                sMergedDialogIds.add(live.dlg.callId);
                retireDialogLocked(live.dlg.callId);
            }
            Leg survivor = null;
            if (confirmed == 1) {
                if (dialogAliveLocked(live.dlg.callId)) {
                    survivor = (sDlg != null
                            && live.dlg.callId.equals(sDlg.callId))
                            ? snapLocked() : live;
                } else if (dialogAliveLocked(parked.dlg.callId)) {
                    survivor = parked;
                }
            }
            loadLocked(focusLeg);
            sCall = true;
            sParked = confirmed == 2 ? null : survivor;
        }
        sConfFocusCallId = focusLeg.dlg.callId;
        JoanTrace.note("conf merge " + (confirmed == 2 ? "OK"
                : "OK partial") + " confirmed=" + confirmed);
        return confirmed == 2 ? "OK" : "OK partial confirmed=" + confirmed;
    }

    /** A merge transaction in progress, holding the focus dialog open. */
    private static final class FocusLeg extends Leg {
        boolean subscribed;
    }

    private static boolean liveHeldOf(Leg l) {
        return l != null && l.held;
    }

    private static boolean dialogAliveLocked(String cid) {
        if (cid == null) {
            return false;
        }
        if (sDlg != null && cid.equals(sDlg.callId)) {
            return sCall;
        }
        return sParked != null && sParked.dlg != null
                && cid.equals(sParked.dlg.callId);
    }

    private static void retireDialogLocked(String cid) {
        if (cid == null) {
            return;
        }
        if (sDlg != null && cid.equals(sDlg.callId)) {
            sCall = false;
            sDlg = null;
        }
        if (sParked != null && sParked.dlg != null
                && cid.equals(sParked.dlg.callId)) {
            sParked = null;
        }
    }

    private static void retireFocus(FocusLeg focusLeg) {
        try {
            String bye = JoanSipBuilder.buildBye(idSnapshot(), focusLeg.dlg,
                    focusLeg.target, focusLeg.route, sSecVerify,
                    focusLeg.toHdr, focusLeg.fromHdr);
            sendReply(bye.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            JoanTrace.note("conf focus retire BYE fail");
        }
    }

    private static JoanSipBuilder.Id idSnapshot() {
        return new JoanSipBuilder.Id(sId.impi, sPublicId, sId.realm,
                sId.localIp, sId.viaPort, sId.contactPort, sId.imei);
    }

    /**
     * INVITE the conference focus without disturbing the two live dialogs:
     * the focus dialog lives in its own Leg until the merge commits.
     */
    private static FocusLeg inviteFocus(String focus) {
        if (!sReg) {
            return null;
        }
        JoanSipBuilder.Id id = idSnapshot();
        JoanSipBuilder.Dialog dlg = new JoanSipBuilder.Dialog();
        String msg = JoanSipBuilder.buildInvite(id, dlg, focus,
                sServiceRoute, sSecVerify, RTP_PORT, sPani, true);
        if (msg == null) {
            return null;
        }
        sInviteAcks.begin(dlg.callId, dlg.cseq, dlg, "", "", focus,
                sServiceRoute);
        InviteWait wait = new InviteWait(dlg.callId, dlg.cseq);
        registerInviteWait(wait);
        try {
            send(sSockC, sPcscf, sPcscfPortS,
                    msg.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            clearInviteWait(wait);
            return null;
        }
        long deadline = System.currentTimeMillis() + sInviteTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String rx = pollReply(wait, Math.min(400,
                    deadline - System.currentTimeMillis()));
            if (rx == null) {
                continue;
            }
            JoanSipBuilder.Reply p = JoanSipBuilder.parseReply(rx);
            if (p == null) {
                continue;
            }
            if (p.status >= 100 && p.status < 200) {
                continue;
            }
            if (p.status >= 200 && p.status < 300) {
                sendAck2xx(id, dlg, targetOf(rx, focus), routeOf(rx),
                        nullToEmpty(JoanSipBuilder.header(rx, "To")),
                        nullToEmpty(JoanSipBuilder.header(rx, "From")),
                        dlg.cseq);
                FocusLeg fl = new FocusLeg();
                fl.dlg = dlg;
                fl.target = targetOf(rx, focus);
                fl.route = routeOf(rx);
                fl.toHdr = nullToEmpty(JoanSipBuilder.header(rx, "To"));
                fl.fromHdr = nullToEmpty(
                        JoanSipBuilder.header(rx, "From"));
                fl.held = false;
                clearInviteWait(wait);
                return fl;
            }
            sendAckNon2xx(id, dlg, focus, sServiceRoute,
                    nullToEmpty(JoanSipBuilder.header(rx, "To")),
                    nullToEmpty(JoanSipBuilder.header(rx, "From")),
                    dlg.cseq, dlg.branch);
            clearInviteWait(wait);
            return null;
        }
        clearInviteWait(wait);
        return null;
    }

    private static String targetOf(String rx, String fallback) {
        String c = JoanSipBuilder.header(rx, "Contact");
        String t = c != null ? JoanSipBuilder.contactUri(c) : fallback;
        if (t != null && t.toLowerCase(java.util.Locale.ROOT)
                .endsWith(";isfocus")) {
            /* The isfocus marker is a Contact parameter naming the focus
             * role; it is not part of the URI requests are sent to. */
            t = t.substring(0, t.length() - ";isfocus".length());
        }
        return t;
    }

    private static String routeOf(String rx) {
        String rr = JoanSipBuilder.header(rx, "Record-Route");
        return rr != null && !rr.isEmpty() ? rr : sServiceRoute;
    }

    /** Non-INVITE transaction waiters, keyed cid#cseq. */
    private static final java.util.concurrent.ConcurrentHashMap<String,
            NonInviteWait> sNonInviteWaits =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final class NonInviteWait {
        final String callId;
        final int cseq;
        final java.util.concurrent.LinkedBlockingQueue<String> replies =
                new java.util.concurrent.LinkedBlockingQueue<>();
        final String method;
        final String branch;
        final String referToUri;
        boolean notifyFinal;
        boolean notifyFailure;
        NonInviteWait(String callId, int cseq, String method,
                      String branch, String referToUri) {
            this.callId = callId;
            this.cseq = cseq;
            this.method = method;
            this.branch = branch;
            this.referToUri = referToUri;
        }
    }



    /**
     * REFER one original leg's participant into the focus and confirm the
     * transfer via the implicit subscription (RFC 3515): a 202 is only
     * acceptance; the NOTIFY carrying a final sipfrag is the result.
     */
    private static String referLegIntoFocus(JoanSipBuilder.Id id,
                                            FocusLeg focusLeg, Leg leg,
                                            boolean referSub) {
        /* RFC 3891: Replaces names the replaced dialog by its answerer's
         * tag (to-tag) and initiator's tag (from-tag), as those tags
         * appear in the dialog being replaced. */
        boolean weAnswered = leg.ourToTag != null
                && !leg.ourToTag.isEmpty()
                && leg.ourToTag.equals(leg.dlg.fromTag);
        String remoteTag = toTagOf(leg.toHdr);
        String toTag = weAnswered ? leg.ourToTag : remoteTag;
        String fromTag = weAnswered ? remoteTag : leg.dlg.fromTag;
        if (toTag.isEmpty() || fromTag.isEmpty()) {
            return "NO_TAGS";
        }
        String replaces = leg.dlg.callId
                + ";to-tag=" + toTag
                + ";from-tag=" + fromTag;
        /* RFC 4579 5.10 / RFC 3891: Replaces is a header parameter escaped
         * inside the Refer-To URI. The participant's URI carries the
         * escaped Replaces naming the ORIGINAL dialog to be replaced. */
        String target = leg.target != null && !leg.target.isEmpty()
                ? leg.target : leg.dest;
        String referTo = target + "?Replaces="
                + replaces.replace("%", "%25")
                         .replace(" ", "%20")
                         .replace(";", "%3B")
                         .replace("=", "%3D");
        String refer = JoanSipBuilder.buildReferConf(id, focusLeg.dlg,
                focusLeg.target != null && !focusLeg.target.isEmpty()
                        ? focusLeg.target : focusLeg.dest,
                focusLeg.route, sSecVerify, focusLeg.toHdr,
                focusLeg.fromHdr, referTo, aorOf(id), referSub);
        int referCseq = JoanSipBuilder.cseqForMethod(refer, "REFER");
        String branch = JoanSipBuilder.branchOf(refer);
        NonInviteWait w = new NonInviteWait(focusLeg.dlg.callId,
                referCseq, "REFER", branch, referTo);
        sNonInviteWaits.put(w.callId + "#REFER#" + w.cseq, w);
        try {
            sendReply(refer.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            sNonInviteWaits.remove(w.callId + "#REFER#" + w.cseq, w);
            return "SEND_FAIL";
        }
        try {
            long deadline = System.currentTimeMillis() + 8000;
            boolean accepted = false;
            while (System.currentTimeMillis() < deadline) {
                String rx;
                try {
                    rx = w.replies.poll(400,
                            java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (rx == null) {
                    if (w.notifyFinal) {
                        return "CONFIRMED";
                    }
                    if (w.notifyFailure) {
                        return "NOTIFY_FAIL";
                    }
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
                if (p.status == 100) {
                    continue;
                }
                if (p.status >= 200 && p.status < 300) {
                    accepted = true;
                    if (!referSub) {
                        return "CONFIRMED";
                    }
                    continue;  /* wait for the NOTIFY final sipfrag */
                }
                if (p.status >= 300) {
                    return "REFUSED " + p.status;
                }
            }
            return accepted ? "ACCEPTED" : "TIMEOUT";
        } finally {
            sNonInviteWaits.remove(w.callId + "#REFER#" + w.cseq, w);
        }
    }

    /**
     * Wait for a non-INVITE final, dispatching NOTIFYs and stray requests
     * through the normal inbound path. Used by SUBSCRIBE etc.
     */
    private static String waitNonInviteFinal(NonInviteWait w,
                                             int timeoutMs) {
        if (w == null) {
            return null;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String rx;
            try {
                rx = w.replies.poll(400,
                        java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
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
            if (p.status >= 200) {
                return rx;
            }
        }
        return null;
    }

    private static volatile String sConfFocusCallId;
    private static final java.util.List<String> sMergedDialogIds =
            java.util.Collections.synchronizedList(
                    new java.util.ArrayList<String>());

    static String conferenceFocusCallId() {
        return sConfFocusCallId;
    }

    /** Call-IDs of legs that completed transfer into the focus. */
    static String[] mergedDialogIds() {
        synchronized (sMergedDialogIds) {
            String[] out = sMergedDialogIds.toArray(new String[0]);
            sMergedDialogIds.clear();
            return out;
        }
    }

    /** Refer-To target: focus URI with a Replaces parameter naming the leg. */
    private static String focusReferTo(String focusTarget, String callId,
                                       String fromTag, String toTag) {
        String replaces = callId + ";to-tag=" + toTag
                + ";from-tag=" + fromTag;
        String uri = focusTarget;
        boolean hasQ = uri.indexOf('?') >= 0;
        String esc = replaces.replace("%", "%25").replace(" ", "%20");
        /* RFC 3891: Replaces is a header parameter escaped inside the
         * Refer-To URI. A URI that already carries a query gets the
         * escaped header appended with ';', otherwise '?'. */
        return uri + (hasQ ? ";" : "?") + "Replaces=\"" + esc + "\"";
    }

    private static String toTagOf(String toHdr) {
        if (toHdr == null) {
            return "";
        }
        int i = toHdr.indexOf("tag=");
        if (i < 0) {
            return "";
        }
        int v = i + 4;
        int e = v;
        while (e < toHdr.length() && toHdr.charAt(e) != ';'
                && toHdr.charAt(e) != '>' && toHdr.charAt(e) != ' ') {
            e++;
        }
        return toHdr.substring(v, e);
    }

    private static String aorOf(JoanSipBuilder.Id id) {
        return JoanSipBuilder.aorOfPublic(id);
    }

    static String hold(String sipCallId) {
        final String heldCid;
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
            heldCid = sDlg.callId;
        }
        String r = reInviteLive(true, heldCid);
        if (r != null && r.startsWith("OK")) {
            synchronized (LOCK) {
                /* If the swap already moved on (parked leg resumed), the
                 * held leg is the parked one: leave the live media alone.
                 * Only when this dialog is still live do we own media. */
                if (sDlg != null && sDlg.callId != null
                        && sDlg.callId.equals(heldCid)) {
                    JoanMedia.stop();
                    sLiveHeld = true;
                }
            }
            JoanTrace.note("app hold cid=" + heldCid);
        } else {
            synchronized (LOCK) {
                /* If the failed hold belongs to the now-parked leg, its
                 * snapshot says held=true (snapLocked is optimistic); put
                 * the truth back so a later resume holds it properly. */
                if (sParked != null && sParked.dlg != null
                        && heldCid != null
                        && heldCid.equals(sParked.dlg.callId)) {
                    sParked.held = false;
                }
            }
            JoanTrace.note("app hold FAIL cid=" + heldCid + " " + r);
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
            String live = currentCallId();
            String h = hold(live); // awaits confirmed success, including a duplicate hold
            if (h == null || !h.startsWith("OK")) return h;
            synchronized (LOCK) {
                if (!sCall || sParked == null || sParked.dlg == null
                        || !sipCallId.equals(sParked.dlg.callId)
                        || !live.equals(currentCallId()) || !sLiveHeld) {
                    return "ERR dialogs changed during swap";
                }
                Leg was = snapLocked();
                loadLocked(sParked);
                sParked = was;
            }
        }
        String r = reInviteLive(false, sipCallId);
        if ("INFLIGHT".equals(r)) {
            /* A transaction on the same dialog is still pending; refuse
             * rather than race it. The framework can retry resume. */
            JoanTrace.note("app resume refused (in flight)");
            return "ERR reinvite in flight";
        }
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
        l.held = sLiveHeld;
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
        return reInviteLive(held, null);
    }

    /**
     * re-INVITE a specific leg (live or parked). With the optimistic swap
     * hold the framework can move the live/parked bookkeeping while our
     * hold thread is still starting up; targeting by Call-ID means the
     * hold lands on the leg it was asked for, not whichever leg happens
     * to be live when we finally read the dialog state.
     */
    private static String reInviteLive(boolean held, String forCallId) {
        JoanSipBuilder.Dialog dlg;
        String target, route, toHdr, fromHdr;
        synchronized (LOCK) {
            if (sId == null) {
                return "ERR no dialog";
            }
            if (forCallId != null && sParked != null && sParked.dlg != null
                    && forCallId.equals(sParked.dlg.callId)) {
                dlg = sParked.dlg;
                target = sParked.target != null && !sParked.target.isEmpty()
                        ? sParked.target : sParked.dest;
                route = sParked.route;
                toHdr = sParked.toHdr;
                fromHdr = sParked.fromHdr;
            } else {
                if (sDlg == null) {
                    return "ERR no dialog";
                }
                if (forCallId != null && sDlg.callId != null
                        && !forCallId.equals(sDlg.callId)) {
                    return "ERR leg not found";
                }
                dlg = sDlg;
                target = sTarget != null && !sTarget.isEmpty()
                        ? sTarget : sDest;
                route = sRoute;
                toHdr = sToHdr;
                fromHdr = sFromHdr;
            }
        }
        if (target == null || target.isEmpty()) {
            return "ERR no target";
        }
        InviteFlight claim = new InviteFlight(held);
        InviteFlight owner = sInviteFlights.putIfAbsent(dlg.callId, claim);
        if (owner != null) {
            // A repeated hold may join that SAME hold, never an outstanding resume.
            if (!held || !owner.held) return "INFLIGHT";
            try { return owner.result.get(30, java.util.concurrent.TimeUnit.SECONDS); }
            catch (Exception e) { return "ERR pending hold failed"; }
        }
        String result = "ERR reinvite aborted";
        try {
            JoanSipBuilder.Id id;
            String inv;
            synchronized (LOCK) {
                if (sId == null || !sReg || !dialogAlive(dlg.callId)) {
                    return "ERR dialog ended";
                }
                id = new JoanSipBuilder.Id(sId.impi, sPublicId,
                        sId.realm, sId.localIp, sId.viaPort, sId.contactPort, sId.imei);
                inv = JoanSipBuilder.buildReInvite(id, dlg, target, route,
                        sSecVerify, toHdr, fromHdr,
                        JoanSipBuilder.sdpHold(id.localIp, RTP_PORT, held));
            }
            result = driveReinvite(id, inv, dlg, target, route, toHdr, fromHdr, held);
            return result;
        } finally {
            claim.result.complete(result);
            sInviteFlights.remove(dlg.callId, claim);
        }
    }

    private static String driveReinvite(JoanSipBuilder.Id id, String inv,
                                        JoanSipBuilder.Dialog dlg,
                                        String target, String route,
                                        String toHdr, String fromHdr,
                                        boolean held) {
        int inviteCseq = dlg.cseq;
        String inviteBranch = dlg.branch;
        sInviteAcks.begin(dlg.callId, inviteCseq, dlg, toHdr, fromHdr,
                target, route);
        InviteWait wait = new InviteWait(dlg.callId, inviteCseq);
        registerInviteWait(wait);
        JoanTrace.note("app reinvite send held=" + held
                + " cseq=" + inviteCseq + " cid=" + dlg.callId);
        try {
            try {
                sendReply(inv.getBytes(StandardCharsets.US_ASCII));
            } catch (Exception e) {
                JoanTrace.note("app reinvite ERR send");
                return "ERR reinvite send";
            }
            return awaitReinviteFinal(id, dlg, target, route, toHdr,
                    fromHdr, held, wait, inviteCseq, inviteBranch);
        } finally {
            clearInviteWait(wait);
        }
    }

    private static String awaitReinviteFinal(JoanSipBuilder.Id id,
                                             JoanSipBuilder.Dialog dlg,
                                             String target, String route,
                                             String toHdr, String fromHdr,
                                             boolean held, InviteWait wait,
                                             int inviteCseq,
                                             String inviteBranch) {
        long deadline = System.currentTimeMillis() + 25000;
        while (sReg && dialogAlive(dlg.callId) && System.currentTimeMillis() < deadline) {
            String rx = pollReply(wait, Math.min(400, deadline - System.currentTimeMillis()));
            if (rx == null) continue;
            JoanSipBuilder.Reply p = JoanSipBuilder.parseReply(rx);
            if (p == null || p.status < 200) continue;
            if (p.status < 300) {
                String contact = JoanSipBuilder.header(rx, "Contact");
                if (contact != null) target = JoanSipBuilder.contactUri(contact);
                if (!sendAck2xx(id, dlg, target, route, toHdr, fromHdr, inviteCseq))
                    return "ERR reinvite ack send";
                return "OK";
            }
            sendAckNon2xx(id, dlg, target, route, toHdr, fromHdr, inviteCseq, inviteBranch);
            JoanTrace.note("app reinvite ERR " + p.status);
            return "ERR reinvite " + p.status;
        }
        JoanTrace.note("app reinvite ERR timeout or ended");
        return "ERR reinvite timeout or ended";
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
        if (status < 200 || inviteCseq <= 0) {
            /* Non-INVITE finals (e.g. a BYE 200 parses to cseq=-1) are
             * not INVITE transactions; nothing to re-ACK, and logging
             * them as archive misses is noise. */
            return false;
        }
        String ack = status < 300
                ? sInviteAcks.ack2xx(callId, inviteCseq)
                : sInviteAcks.ackNon2xx(callId, inviteCseq);
        if (ack == null) {
            JoanTrace.note("ACK archive miss status=" + status
                    + " cseq=" + inviteCseq + " cid=" + callId);
            return false;
        }
        try {
            sendReply(ack.getBytes(StandardCharsets.US_ASCII));
            JoanTrace.note("re-ACK final status=" + status
                    + " cseq=" + inviteCseq + " cid=" + callId);
            return true;
        } catch (Exception e) {
            JoanTrace.note("re-ACK send " + e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * A final this transaction's loop does not own: hand it to another
     * active waiter (a concurrent optimistic swap transaction) if one
     * exists, otherwise ACK it from the send-time snapshot so the
     * retransmission storm dies at the source.
     */
    private static void routeOrAckLate(String callId, int cseq, int status,
                                       String rx) {
        InviteWait other = sInviteWaits.get(callId + "#" + cseq);
        if (other != null) {
            JoanTrace.note("reply cross-routed status=" + status
                    + " cseq=" + cseq + " cid=" + callId);
            other.replies.offer(rx);
            return;
        }
        ackLateFinal(callId, cseq, status, rx);
    }

    /**
     * A final response for an INVITE WE SENT, arriving after its waiting
     * transaction already gave up (slow SBC chains answer in 15+ s). ACK it
     * from the send-time snapshot so the far end stops retransmitting the
     * final — an unACKed 200 storm is what wedges the network for every
     * later re-INVITE. Truly unknown transactions are still never ACKed.
     */
    private static boolean ackLateFinal(String callId, int inviteCseq,
                                        int status, String rx) {
        if (status < 200 || inviteCseq <= 0) {
            return false;
        }
        String ack = status < 300
                ? sInviteAcks.ackLate2xx(sId, callId, inviteCseq, sSecVerify,
                        rx)
                : sInviteAcks.ackLateNon2xx(sId, callId, inviteCseq,
                        sSecVerify, rx);
        if (ack == null) {
            return false;
        }
        try {
            sendReply(ack.getBytes(StandardCharsets.US_ASCII));
            JoanTrace.note("late ACK final status=" + status
                    + " cseq=" + inviteCseq + " cid=" + callId);
            return true;
        } catch (Exception e) {
            JoanTrace.note("late ACK send " + e.getClass().getSimpleName());
            return false;
        }
    }


    private static void handleInbound(String rx) {
        String method = JoanSipBuilder.requestMethod(rx);
        if (!method.isEmpty() && !"INVITE".equals(method)
                && !"ACK".equals(method) && !"BYE".equals(method)
                && !"CANCEL".equals(method)) {
            /* Non-INVITE finals for REFER/SUBSCRIBE route to their owning
             * transaction waiters; NOTIFYs update refer subscriptions and
             * are answered. Anything else falls through to the existing
             * per-method handlers below. */
            String cid = JoanSipBuilder.header(rx, "Call-ID");
            int cseq = JoanSipBuilder.cseqForMethod(rx, method);
            String branch = JoanSipBuilder.branchOf(rx);
            if (!"NOTIFY".equals(method)) {
                for (NonInviteWait w : sNonInviteWaits.values()) {
                    if (w.callId.equals(cid) && w.cseq == cseq) {
                        w.replies.offer(rx);
                        return;
                    }
                }
            } else {
                String event = JoanSipBuilder.header(rx, "Event");
                String subState = JoanSipBuilder.header(rx,
                        "Subscription-State");
                String body = JoanSipBuilder.bodyOf(rx);
                int sipfragStatus = sipfragFinalStatus(body);
                for (NonInviteWait w : sNonInviteWaits.values()) {
                    if (!w.callId.equals(cid)) {
                        continue;
                    }
                    if (!"REFER".equals(w.method)) {
                        continue;
                    }
                    String id = referEventId(event);
                    if (id == null || Integer.parseInt(id) != w.cseq) {
                        continue;
                    }
                    /* Answer the NOTIFY so the notifier stops retrying. */
                    try {
                        sendReply(buildResponse(rx, 200, "OK", sId,
                                JoanSipBuilder.tagOf(h_of(rx)), null)
                                .getBytes(StandardCharsets.US_ASCII));
                    } catch (Exception ignored) {
                        // ignore
                    }
                    if (sipfragStatus > 0 && subState != null
                            && subState.toLowerCase(
                            java.util.Locale.ROOT).startsWith("terminated")) {
                        w.notifyFinal = sipfragStatus < 300;
                        w.notifyFailure = sipfragStatus >= 300;
                    }
                    return;
                }
                /* Conference event package NOTIFYs keep their existing
                 * handler below. */
            }
        }
        if (method.isEmpty()) {
            JoanSipBuilder.Reply p = JoanSipBuilder.parseReply(rx);
            if (p == null) {
                return;
            }
            String cid = JoanSipBuilder.header(rx, "Call-ID");
            int cseq = JoanSipBuilder.cseqForMethod(rx, "INVITE");
            /* An already-completed transaction wins over an active waiter:
             * this is a retransmitted older final, not a reply to the newer
             * hold/resume that happens to share the dialog Call-ID. A late
             * first final for a known INVITE is ACKed from its snapshot so
             * the retransmission storm dies instead of wedging the SBC. */
            if (p.status >= 200 && reAckFinal(cid, cseq, p.status)) {
                return;
            }
            String cseqHdr = JoanSipBuilder.header(rx, "CSeq");
            if (cseqHdr != null) {
                int sp = cseqHdr.lastIndexOf(' ');
                if (sp > 0) {
                    String replyMethod = cseqHdr.substring(sp + 1).trim();
                    if (!"INVITE".equals(replyMethod) && !"REGISTER"
                            .equals(replyMethod)) {
                        try {
                            int n = Integer.parseInt(
                                    cseqHdr.substring(0, sp).trim());
                            NonInviteWait nw = sNonInviteWaits.get(
                                    cid + "#" + replyMethod + "#" + n);
                            if (nw != null) {
                                nw.replies.offer(rx);
                                return;
                            }
                        } catch (NumberFormatException ignored) {
                            // fall through to INVITE handling
                        }
                    }
                }
            }
            InviteWait w = sInviteWaits.get(cid + "#" + cseq);
            if (w != null && w.matches(cid, cseq)) {
                JoanTrace.note("reply routed status=" + p.status
                        + " cseq=" + cseq + " via="
                        + (sReplyTcp ? "tcp" : "udp"));
                w.replies.offer(rx);
                return;
            }
            // A send-time snapshot is not a completed transaction. The
            // first final belongs to its waiter (pjsip sip_inv.c late ACK
            // guard); only a final without an active owner takes this path.
            if (p.status >= 200 && ackLateFinal(cid, cseq, p.status, rx)) {
                return;
            }
            if (p.status >= 200 && cseq > 0) {
                /* Do not manufacture an ACK for a transaction we never
                 * sent (unknown Call-ID/CSeq pair). Known-but-late finals
                 * were handled above from their send-time snapshot.
                 * cseq<=0 finals (e.g. BYE 200s) are not INVITE
                 * transactions at all and are silently ignored. */
                JoanTrace.note("unmatched final status=" + p.status
                        + " cseq=" + cseq + " cid=" + cid);
            }
            return;
        }
        if ("BYE".equals(method)) {
            String cid = JoanSipBuilder.header(rx, "Call-ID");
            String remoteTag = JoanSipBuilder.tagOf(
                    JoanSipBuilder.header(rx, "From"));
            JoanTrace.note("app inbound BYE");
            synchronized (LOCK) {
                if (sParked != null && sParked.dlg != null
                        && cid != null && cid.equals(sParked.dlg.callId)) {
                    String tag = sParked.ourToTag != null
                            ? sParked.ourToTag : "x";
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
                boolean liveMatch = sCall && sDlg != null && cid != null
                        && cid.equals(sDlg.callId)
                        && (remoteTag.isEmpty() || remoteTag.equals(
                                JoanSipBuilder.tagOf(sToHdr)));
                if (!liveMatch) {
                    /* A BYE that names no dialog we own must not tear down
                     * whichever call happens to be live. RFC 3261 15.1.2:
                     * answer 481 and leave every call alone. */
                    try {
                        sendReply(buildResponse(rx, 481,
                                "Call/Transaction Does Not Exist", sId,
                                "stale", null)
                                .getBytes(StandardCharsets.US_ASCII));
                    } catch (Exception ignored) {
                        // ignore
                    }
                    JoanTrace.note("app BYE for unknown dialog ignored");
                    return;
                }
            }
            try {
                sendReply(buildResponse(rx, 200, "OK", sId,
                        sOurToTag != null ? sOurToTag : "x", null)
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
        if ("NOTIFY".equals(method)) {
            /* Conference event package NOTIFYs (and any dialog-scoped
             * NOTIFY): always answer 200 so the notifier does not retry,
             * and hand the body to the conference-info parser when it
             * is the conference package. */
            String event = JoanSipBuilder.header(rx, "Event");
            try {
                sendReply(buildResponse(rx, 200, "OK", sId,
                        sOurToTag != null ? sOurToTag : "ntf", null)
                        .getBytes(StandardCharsets.US_ASCII));
            } catch (Exception ignored) {
                // ignore
            }
            if (event != null && event.toLowerCase(
                    java.util.Locale.ROOT).startsWith("conference")) {
                java.util.List<String> users =
                        JoanSipBuilder.parseConferenceUsers(rx);
                if (users != null) {
                    JoanTrace.note("conf-info users=" + users.size());
                    JoanMmTelFeature.onConferenceUsers(users);
                }
            }
            return;
        }
        if ("REFER".equals(method)) {
            /* We are the referee of a transfer (e.g. the network asking
             * us to join a conference). Answering 202 is allowed when we
             * take no automatic action; the user decides. */
            /* We are not a working referee: a 202 would create an implicit
             * refer subscription we never drive (RFC 3515). Decline until
             * inbound transfer support exists. */
            try {
                sendReply(buildResponse(rx, 603, "Decline", sId,
                        sOurToTag != null ? sOurToTag : "ref", null)
                        .getBytes(StandardCharsets.US_ASCII));
            } catch (Exception ignored) {
                // ignore
            }
            JoanTrace.note("app inbound REFER (answered 603)");
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
        /* INVITE from here on. */
        String invCid = JoanSipBuilder.header(rx, "Call-ID");
        int invCseq = JoanSipBuilder.cseqForMethod(rx, "INVITE");
        String heldNow = sHeldInvite;
        if (heldNow != null && invCid != null
                && invCid.equals(JoanSipBuilder.header(heldNow, "Call-ID"))) {
            /* Retransmitted initial INVITE while we still ring: resend the
             * provisionals, never a 486 (the voicemail bug). */
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
        if (sCall && sDlg != null && invCid != null
                && invCid.equals(sDlg.callId)) {
            /* Same dialog as the live call: either a retransmission of the
             * answered INVITE or an in-dialog re-INVITE (remote hold etc.).
             * Neither is a new call. */
            if (invCseq > 0 && invCseq <= sDlg.cseq) {
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
            if (inFlightOn(invCid)) {
                /* Glare with our own outstanding re-INVITE: RFC 3261 14.1
                 * says 491, not a silent drop and not a second call. */
                try {
                    sendReply(buildResponse(rx, 491, "Request Pending",
                            sId, sOurToTag, null)
                            .getBytes(StandardCharsets.US_ASCII));
                } catch (Exception ignored) {
                    // ignore
                }
                JoanTrace.note("app inbound re-INVITE glare; 491");
                return;
            }
            /* In-dialog re-INVITE with a new offer: not implemented. Say so
             * honestly instead of ringing a second call. */
            try {
                sendReply(buildResponse(rx, 488, "Not Acceptable Here",
                        sId, sOurToTag, null,
                        "Reason: SIP;cause=488;text=\"re-INVITE unsupported\"")
                        .getBytes(StandardCharsets.US_ASCII));
            } catch (Exception ignored) {
                // ignore
            }
            JoanTrace.note("app inbound in-dialog re-INVITE declined 488");
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

    private static boolean referWaitNeeded(String method) {
        return "REFER".equals(method) || "SUBSCRIBE".equals(method);
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
        String buffered = JoanSipBuilder.extractOne(sTcpAcc);
        if (buffered != null) return buffered;
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
        String buffered = JoanSipBuilder.extractOne(sTcpClientAcc);
        if (buffered != null) return buffered;
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

    private static String h_of(String msg) {
        return JoanSipBuilder.header(msg, "To");
    }

    /** Body after the first CRLFCRLF, or "". */
    private static String bodyOfMsg(String msg) {
        int i = msg.indexOf("\r\n\r\n");
        return i < 0 ? "" : msg.substring(i + 4);
    }

    /** Final status inside a message/sipfrag body, or -1. */
    private static int sipfragFinalStatus(String body) {
        if (body == null || !body.startsWith("SIP/2.0 ")) {
            return -1;
        }
        try {
            return Integer.parseInt(body.substring(8, 11).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** id= value of an "Event: refer;id=N" header, or null. */
    private static String referEventId(String event) {
        if (event == null || !event.toLowerCase(java.util.Locale.ROOT)
                .startsWith("refer")) {
            return null;
        }
        int i = event.indexOf("id=");
        if (i < 0) {
            return null;
        }
        int v = i + 3;
        int e = v;
        while (e < event.length() && Character.isDigit(event.charAt(e))) {
            e++;
        }
        return e > v ? event.substring(v, e) : null;
    }

    private static void releaseLocked() {
        releaseLocked(true);
    }

    /** @param clearDialogs false while a refresh keeps live dialogs. */
    private static void releaseLocked(boolean clearDialogs) {
        sReg = false;
        if (!clearDialogs) {
            /* Keep dialogs and call state; close only transports/SA so the
             * adopt path can install the fresh binding around them. */
            closeFd(sTcpPeer);
            closeFd(sTcpS);
            closeFd(sTcpC);
            sTcpPeer = sTcpS = sTcpC = null;
            sReplyTcp = false;
            if (sTcpClient != null) {
                try { sTcpClient.close(); } catch (Exception ignored) { }
                sTcpClient = null;
            }
            sTcpClientAcc.setLength(0);
            sInviteWaits.clear();
            sNonInviteWaits.clear();
            for (InviteFlight f : sInviteFlights.values()) {
                f.result.complete("ERR binding released");
            }
            sInviteFlights.clear();
            return;
        }
        sCall = false;
        sLiveHeld = false;
        sParked = null;
        sInviteWaits.clear();
        for (InviteFlight f : sInviteFlights.values()) f.result.complete("ERR binding released");
        sInviteFlights.clear();
        sInviteAcks.clear();
        sNonInviteWaits.clear();
        sMergedDialogIds.clear();
        sHeldInvite = null;
        sRingingToTag = null;
        sOurToTag = null;
        sConfFocusCallId = null;
        sDlg = null;
        sDest = null;
        sTarget = null;
        sRoute = null;
        sToHdr = null;
        sFromHdr = null;
        sMediaIp = null;
        sMediaPort = 0;
        sMediaRtcpPort = 0;
        sMediaPt = 0;
        sMediaAmrWb = null;
        sMediaMux = false;
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
