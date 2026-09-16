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
    /** Exact 200 OK for the INVITE that established the live dialog. */
    private static volatile String sInvite200;
    private static volatile String sOurToTag;
    private static volatile String sRingingToTag;
    private static volatile InetAddress sMediaIp;
    private static volatile int sMediaPort;
    private static volatile int sMediaRtcpPort;
    /** Negotiated payload type, and TRUE/FALSE for AMR-WB/NB, null=PCMU. */
    private static volatile int sMediaPt;
    /** Encoder bitrate from the negotiated AMR mode-set; 0 = codec default. */
    private static volatile int sMediaAmrBitrate;
    /** Framing the peer negotiated; false means bandwidth-efficient. */
    private static volatile boolean sMediaAmrOct;
    /** Highest mode the negotiated mode-set allows; -1 when unrestricted. */
    private static volatile int sMediaAmrMaxMode = -1;
    /**
     * Payload type both ends agreed for RFC 4733 telephone-event, or 0.
     *
     * <p>Zero is not a valid dynamic payload type, so it doubles as "the
     * peer did not offer one" -- in which case there is no way to send a
     * digit this call and sendDtmf must say so rather than put tones into
     * a speech codec that will mangle them.
     */
    private static volatile int sMediaTePt;
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
        /* A parked leg carried only the payload type, so resuming one
         * restored AMR at the codec default bitrate, octet-aligned even
         * when the call had negotiated bandwidth-efficient, and with no
         * mode-set ceiling. Snapshot every negotiated parameter. */
        int mediaAmrBitrate, mediaAmrMaxMode, mediaTePt;
        boolean mediaAmrOct;
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
    /* ------------------------------------------------------------------
     * Session timers, RFC 4028. The policy is in JoanSessionTimer; this
     * is the state it drives and the one thread that watches the clock.
     *
     * Without this a dialog has no keepalive: if the far end disappears
     * without a BYE -- core restarted, proxy dropped the dialog, radio
     * gone for good -- nothing says so, and the call stays on screen with
     * no session behind it and no way to place another.
     * ------------------------------------------------------------------ */

    /** Interval both ends settled on, in seconds; 0 = untimed session. */
    private static volatile int sSeAgreedSec;
    /** Which side owes the refreshes. */
    private static volatile int sSeRefresher = JoanSessionTimer.REFRESHER_UNKNOWN;
    /** True when this side sent the INVITE. */
    private static volatile boolean sSeWeAreUac;
    /** The peer's Allow, so a refresh uses a method it accepts. */
    private static volatile String sSePeerAllow;
    /** Epoch ms at which to send our refresh; 0 = nothing scheduled. */
    private static volatile long sSeRefreshAt;
    /** Epoch ms at which the session is over if nothing refreshed it. */
    private static volatile long sSeExpiresAt;
    private static volatile Thread sSeThread;
    /** Call-ID the armed timer belongs to, so a stale tick cannot fire. */
    private static volatile String sSeCallId;

    static int sessionExpiresAgreed() {
        return sSeAgreedSec;
    }

    /**
     * Arm the timers from a message that carried the negotiated interval.
     *
     * @param msg       the 2xx we received (UAC) or the INVITE we answered
     *                  (UAS)
     * @param weAreUac  true when we sent the INVITE
     * @param peerAllow the peer's Allow header, or null
     */
    private static void sessionTimerArm(String msg, boolean weAreUac,
                                        String peerAllow, String callId) {
        int sec = JoanSessionTimer.parseExpires(
                JoanSipBuilder.header(msg, "Session-Expires"));
        if (sec <= 0) {
            /* The peer did not agree to a timed session. Refreshing one it
             * never agreed to is worse than not having the safety net. */
            sessionTimerStop("peer did not time the session");
            return;
        }
        int refresher = JoanSessionTimer.parseRefresher(
                JoanSipBuilder.header(msg, "Session-Expires"));
        if (refresher == JoanSessionTimer.REFRESHER_UNKNOWN) {
            refresher = weAreUac ? JoanSessionTimer.REFRESHER_UAC
                    : JoanSessionTimer.REFRESHER_UAS;
        }
        sSeAgreedSec = sec;
        sSeRefresher = refresher;
        sSeWeAreUac = weAreUac;
        sSePeerAllow = peerAllow;
        sSeCallId = callId;
        sessionTimerRearm("negotiated");
        sessionTimerStartThread();
    }

    /** Push both deadlines out; called whenever a refresh succeeds. */
    private static void sessionTimerRearm(String why) {
        int sec = sSeAgreedSec;
        if (sec <= 0) {
            return;
        }
        boolean we = JoanSessionTimer.weRefresh(sSeRefresher, sSeWeAreUac);
        long now = System.currentTimeMillis();
        sSeRefreshAt = now + JoanSessionTimer.refreshDueMs(sec, we);
        sSeExpiresAt = now + JoanSessionTimer.expiryDueMs(sec);
        JoanTrace.note("session timer " + why + " interval=" + sec
                + "s refresher=" + (we ? "us" : "peer")
                + " refresh_in=" + (JoanSessionTimer.refreshDueMs(sec, we) / 1000)
                + "s");
    }

    private static void sessionTimerStop(String why) {
        if (sSeAgreedSec != 0 || sSeRefreshAt != 0) {
            JoanTrace.note("session timer off: " + why);
        }
        sSeAgreedSec = 0;
        sSeRefresher = JoanSessionTimer.REFRESHER_UNKNOWN;
        sSeRefreshAt = 0;
        sSeExpiresAt = 0;
        sSeCallId = null;
        sSePeerAllow = null;
    }

    private static void sessionTimerStartThread() {
        Thread t = sSeThread;
        if (t != null && t.isAlive()) {
            return;
        }
        t = new Thread(JoanSipUa::sessionTimerLoop, "joan-sip-setimer");
        t.setDaemon(true);
        sSeThread = t;
        t.start();
    }

    /**
     * One second of granularity is plenty for intervals measured in
     * minutes, and polling keeps the deadline logic in one place rather
     * than spread over every path that could reschedule it.
     */
    private static void sessionTimerLoop() {
        while (sReg) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
            int sec = sSeAgreedSec;
            String cid = sSeCallId;
            if (sec <= 0 || cid == null) {
                continue;
            }
            if (!sCall || !dialogAlive(cid)) {
                sessionTimerStop("call ended");
                continue;
            }
            long now = System.currentTimeMillis();
            if (JoanSessionTimer.due(sSeExpiresAt, now)) {
                /* RFC 4028 s7: the session is over. Not a timeout to retry
                 * through -- there is nothing on the other end to retry
                 * at. Ending the call is what makes the failure visible
                 * instead of leaving a dead call on screen. */
                JoanTrace.note("session timer expired after " + sec
                        + "s with no refresh; ending call");
                sessionTimerStop("expired");
                /* Off-thread: hangup() sends a BYE and takes LOCK, and
                 * this loop must stay free to notice the next tick.
                 * JoanCallSession's watcher sees the call go inactive and
                 * tells the framework, the same path a remote BYE takes. */
                final String dead = cid;
                new Thread(() -> hangup(dead), "joan-sip-se-bye").start();
                continue;
            }
            if (JoanSessionTimer.due(sSeRefreshAt, now)) {
                /* Clear the deadline first: the refresh can take seconds,
                 * and a second tick meanwhile would send it twice. */
                sSeRefreshAt = 0;
                sessionTimerRefresh(cid);
            }
        }
    }

    /**
     * Refresh the session with an in-dialog UPDATE.
     *
     * @return "OK" on a 2xx, or an error string. A 422 is surfaced with
     *         its Min-SE so the caller can raise the interval rather than
     *         retrying the value the peer just refused.
     */
    private static String sendSessionUpdate(String callId) {
        JoanSipBuilder.Id id;
        JoanSipBuilder.Dialog dlg;
        String target, route, toHdr, fromHdr;
        int cseq;
        synchronized (LOCK) {
            if (sId == null || sDlg == null || !sCall) {
                return "ERR no dialog";
            }
            if (callId != null && sDlg.callId != null
                    && !callId.equals(sDlg.callId)) {
                return "ERR leg not found";
            }
            id = idSnapshot();
            dlg = sDlg;
            target = sTarget != null && !sTarget.isEmpty() ? sTarget : sDest;
            route = sRoute;
            toHdr = sToHdr;
            fromHdr = sFromHdr;
            cseq = ++sDlg.cseq;
        }
        if (target == null || target.isEmpty()) {
            return "ERR no target";
        }
        String extra = JoanSipBuilder.sessionTimerRefreshHeaders(
                sSeAgreedSec, sSeRefresher)
                + "Allow: " + JoanSipBuilder.ALLOW + "\r\n";
        String msg = JoanSipBuilder.buildUpdate(id, dlg, target, route,
                sSecVerify, toHdr, fromHdr, cseq, extra);
        if (msg == null) {
            return "ERR update build";
        }
        NonInviteWait w = new NonInviteWait(dlg.callId, cseq, "UPDATE",
                dlg.branch, null, JoanSipBuilder.tagOf(fromHdr),
                JoanSipBuilder.tagOf(toHdr));
        sNonInviteWaits.put(w.callId + "#UPDATE#" + w.cseq, w);
        try {
            sendReply(msg.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            sNonInviteWaits.remove(w.callId + "#UPDATE#" + w.cseq, w);
            return "ERR update send";
        }
        try {
            String rx = waitNonInviteFinal(w, 32000);
            if (rx == null) {
                return "ERR update timeout";
            }
            JoanSipBuilder.Reply p = JoanSipBuilder.parseReply(rx);
            if (p == null) {
                return "ERR update reply";
            }
            if (p.status >= 200 && p.status < 300) {
                /* The peer may lower the interval in its answer; take the
                 * value it actually agreed to, not the one we asked for. */
                int agreed = JoanSessionTimer.parseExpires(
                        JoanSipBuilder.header(rx, "Session-Expires"));
                if (agreed > 0 && agreed != sSeAgreedSec) {
                    JoanTrace.note("session timer peer lowered interval "
                            + sSeAgreedSec + "s -> " + agreed + "s");
                    sSeAgreedSec = agreed;
                }
                return "OK";
            }
            if (p.status == 422) {
                int peerMin = JoanSessionTimer.parseMinSe(
                        JoanSipBuilder.header(rx, "Min-SE"));
                int retry = JoanSessionTimer.retryExpiresAfter422(
                        peerMin, SE_MAX_SEC);
                JoanTrace.note("session timer 422 min_se=" + peerMin
                        + " retry=" + retry);
                if (retry > 0) {
                    sSeAgreedSec = retry;
                    return "ERR update 422 retry";
                }
                return "ERR update 422";
            }
            return "ERR update " + p.status;
        } finally {
            sNonInviteWaits.remove(w.callId + "#UPDATE#" + w.cseq, w);
        }
    }

    /** Longest session interval we will agree to hold. */
    private static final int SE_MAX_SEC = 7200;

    /**
     * Handle a refresh the peer sent us, and produce the Session-Expires
     * to echo in our 200.
     *
     * <p>Only the expiry deadline moves. The refresher does not change on
     * a refresh: RFC 4028 keeps the role from the initial negotiation, and
     * treating every inbound refresh as a handover would have both sides
     * stop refreshing the moment they disagreed about whose turn it was.
     */
    private static String sessionTimerOnInboundRefresh(String req,
                                                       String what) {
        int sec = JoanSessionTimer.parseExpires(
                JoanSipBuilder.header(req, "Session-Expires"));
        if (sec <= 0) {
            /* Not a session refresh, just an in-dialog request. */
            return "";
        }
        int tooSmall = JoanSessionTimer.rejectBelowMinSe(
                sec, JoanSipBuilder.sessionMinSeSec());
        if (tooSmall > 0) {
            /* Answering 200 to an interval below our Min-SE would commit
             * us to a session we said we would not hold. The peer is told
             * the value we accept, in a header it already understands. */
            JoanTrace.note("session timer inbound " + what + " interval "
                    + sec + "s below min_se " + tooSmall + "s");
            return "Min-SE: " + tooSmall + "\r\n";
        }
        if (sec > SE_MAX_SEC) {
            sec = SE_MAX_SEC;
        }
        int refresher = sSeRefresher != JoanSessionTimer.REFRESHER_UNKNOWN
                ? sSeRefresher
                : JoanSessionTimer.parseRefresher(
                        JoanSipBuilder.header(req, "Session-Expires"));
        sSeAgreedSec = sec;
        sSeRefresher = refresher;
        if (sSeCallId == null) {
            sSeCallId = JoanSipBuilder.header(req, "Call-ID");
        }
        sessionTimerRearm("refreshed by peer " + what);
        sessionTimerStartThread();
        return JoanSipBuilder.sessionTimerAnswerHeaders(sec, refresher);
    }

    /**
     * Whether a re-INVITE leaves the media we already negotiated intact.
     *
     * <p>A refresh normally re-offers the same thing, and answering 200 to
     * one that does is safe. An offer that no longer carries the payload
     * type this call is running is a real media change, and pretending to
     * accept it would leave both ends sending codecs the other cannot
     * decode -- a call that stays up and goes silent, which is worse than
     * an honest 488.
     */
    private static boolean reInviteKeepsNegotiatedMedia(String rx) {
        JoanSipBuilder.Media o = JoanSipBuilder.parseSdp(rx);
        if (o == null) {
            return true; // no offer at all: nothing to disagree about
        }
        int pt = sMediaPt;
        for (JoanSipBuilder.Codec c : o.codecs) {
            if (c.pt == pt) {
                return true;
            }
        }
        JoanTrace.note("app inbound re-INVITE drops negotiated pt=" + pt
                + "; not treating it as a session refresh");
        return false;
    }

    /**
     * The peer held or resumed us. Answer 200 with the mirrored
     * direction, move the media, and tell the framework so the UI says
     * "on hold" rather than showing a connected call with no audio.
     */
    private static void handlePeerDirectionChange(String rx,
                                                  JoanSipBuilder.Media offer) {
        boolean held = JoanSipBuilder.isHeldByPeer(offer.direction);
        String ours = JoanSipBuilder.mirrorDirection(offer.direction);
        JoanTrace.note("app inbound re-INVITE peer " + (held ? "hold" : "resume")
                + " their=" + offer.direction + " ours=" + ours);
        String sdp = JoanSipBuilder.sdpAnswer(sId.localIp, RTP_PORT, offer,
                JoanSipBuilder.selectAnswerCodec(offer));
        String seOut = sessionTimerOnInboundRefresh(rx, "re-INVITE");
        try {
            sendReply(buildResponse(rx, 200, "OK", sId, sOurToTag, sdp,
                    seOut.isEmpty() ? null : seOut)
                    .getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            JoanTrace.note("app peer hold answer send fail");
            return;
        }
        sLiveHeld = held;
        if (held) {
            /* Keep the socket and the dialog; only the audio stops. The
             * peer is still entitled to send us media in the sendonly
             * case, but there is nothing to play it into while the
             * framework shows the call held. */
            JoanMedia.stop();
        } else if (sMediaIp != null) {
            startMediaForActiveCall();
        }
        JoanMmTelFeature.onPeerHoldChanged(
                JoanSipBuilder.header(rx, "Call-ID"), held);
    }

    /** Restart RTP on the currently negotiated parameters. */
    private static boolean startMediaForActiveCall() {
        return JoanMedia.startRtp(sApp, sNet, sLocal, sMediaIp, sMediaPort,
                sMediaRtcpPort, sMediaMux, sMediaPt, sMediaAmrWb,
                sMediaAmrBitrate, sMediaAmrOct, sMediaAmrMaxMode, sMediaTePt);
    }

    /** Whether the reg-event subscription is already in place. */
    private static volatile boolean sRegEventSubscribed;

    /**
     * Subscribe to our own registration event package, once per binding.
     *
     * <p>Off-thread and best-effort. A core that does not offer the
     * package answers 489 Bad Event or 404, and that is not a reason to
     * fail a registration that otherwise worked -- the refresh timer
     * remains the backstop it has always been. Failing loudly here would
     * turn a missing optional feature into a handset that will not
     * register.
     */
    private static void subscribeRegEvent() {
        if (sRegEventSubscribed || sPublicId == null || sPublicId.isEmpty()) {
            return;
        }
        sRegEventSubscribed = true;
        new Thread(() -> {
            try {
                JoanSipBuilder.Id id = idSnapshot();
                JoanSipBuilder.Dialog dlg = new JoanSipBuilder.Dialog();
                dlg.cseq = 0;
                String msg = JoanSipBuilder.buildRegEventSubscribe(id, dlg,
                        JoanSipBuilder.aorOf(sPublicId), sServiceRoute,
                        sSecVerify, sExpiresSec > 0 ? sExpiresSec : 600000);
                if (msg == null) {
                    return;
                }
                NonInviteWait w = new NonInviteWait(dlg.callId, dlg.cseq,
                        "SUBSCRIBE", dlg.branch, null, dlg.fromTag, "");
                sNonInviteWaits.put(w.callId + "#SUBSCRIBE#" + w.cseq, w);
                try {
                    sendReply(msg.getBytes(StandardCharsets.US_ASCII));
                    String rx = waitNonInviteFinal(w, 10000);
                    JoanSipBuilder.Reply p = rx == null ? null
                            : JoanSipBuilder.parseReply(rx);
                    JoanTrace.note("reg-event subscribe "
                            + (p == null ? "no reply" : String.valueOf(p.status)));
                } finally {
                    sNonInviteWaits.remove(w.callId + "#SUBSCRIBE#" + w.cseq, w);
                }
            } catch (Throwable t) {
                JoanTrace.note("reg-event subscribe "
                        + t.getClass().getSimpleName());
            }
        }, "joan-sip-regevent").start();
    }

    /** Our own contact URI, for matching ourselves in a reginfo body. */
    private static String ourContactUri() {
        JoanSipBuilder.Id id = sId;
        if (id == null || id.localIp == null) {
            return null;
        }
        return "sip:joan@" + JoanSipBuilder.bracket(id.localIp)
                + ":" + id.contactPort;
    }

    /**
     * A reg-event NOTIFY told us something about our own binding.
     *
     * <p>A terminated binding is acted on immediately rather than waiting
     * for the refresh to fail: between the network dropping us and the
     * next REGISTER the handset shows itself registered and takes no
     * calls, which is the failure this subscription exists to shorten.
     */
    private static void handleRegEventNotify(String rx) {
        String body = JoanSipBuilder.bodyOf(rx);
        int state = JoanRegInfo.parse(body, ourContactUri());
        if (state == JoanRegInfo.STATE_UNKNOWN
                || state == JoanRegInfo.STATE_ACTIVE) {
            JoanTrace.note("reg-event notify state="
                    + (state == JoanRegInfo.STATE_ACTIVE ? "active" : "unknown"));
            return;
        }
        boolean retry = state == JoanRegInfo.STATE_TERMINATED_REREGISTER;
        JoanTrace.note("reg-event notify: binding terminated"
                + (retry ? "; re-registering" : "; network refused us"));
        if (sCall) {
            /* Same restraint as an IMS bearer loss during a call: the
             * dialog is still up and releasing the UA would take it with
             * us. The call ending re-runs this. */
            JoanTrace.note("reg-event: call active; deferring release");
            return;
        }
        release();
        if (retry) {
            JoanDriver.poke(JoanAppRegister.JoanRegLifecycle
                    .POKE_IMS_AVAILABLE);
        }
    }

    private static void sessionTimerRefresh(String callId) {
        boolean useUpdate = JoanSessionTimer.refreshWithUpdate(
                JoanSipBuilder.sessionRefreshMethod(), sSePeerAllow);
        JoanTrace.note("session timer refresh via "
                + (useUpdate ? "UPDATE" : "re-INVITE")
                + " cid=" + callId);
        String r = useUpdate ? sendSessionUpdate(callId)
                : reInviteLive(liveHeld(), callId);
        if ("ERR update 422 retry".equals(r)) {
            /* sSeAgreedSec was raised to the peer's Min-SE; one immediate
             * retry at the value it demanded, then treat it as a failure
             * rather than looping against a peer that keeps refusing. */
            r = sendSessionUpdate(callId);
        }
        if (r != null && r.startsWith("OK")) {
            sessionTimerRearm("refreshed");
            return;
        }
        JoanTrace.note("session timer refresh failed: " + r);
        if (useUpdate) {
            /* A peer that advertised UPDATE and then refused it still has
             * to be refreshed somehow; fall back once rather than letting
             * the session run out on a method argument. */
            String r2 = reInviteLive(liveHeld(), callId);
            if (r2 != null && r2.startsWith("OK")) {
                sessionTimerRearm("refreshed by re-INVITE fallback");
                return;
            }
            JoanTrace.note("session timer re-INVITE fallback failed: " + r2);
        }
        /* Leave sSeExpiresAt alone. If the peer is simply slow, an inbound
         * refresh still rearms us; if it is gone, expiry ends the call. */
    }

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

    static int mediaAmrBitrate() {
        return sMediaAmrBitrate;
    }

    static boolean mediaAmrOctetAligned() {
        return sMediaAmrOct;
    }

    static int mediaAmrMaxMode() {
        return sMediaAmrMaxMode;
    }

    static int mediaTePt() {
        return sMediaTePt;
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
        InetAddress wasLocal = sLocal;
        String wasCallId = currentCallId();
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
            if (!liveCalls) {
                sCall = false;
            }
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
            subscribeRegEvent();
        } else {
            JoanTrace.note("app UA REGISTER 200 but no public identity");
        }
        if (sReg && liveCalls && wasLocal != null
                && !wasLocal.equals(local)) {
            /* The registration was re-plumbed onto a different local
             * address while a call was up -- a VoWiFi/VoLTE swap, or a
             * PDN that came back with a new address. The dialog survived
             * (RFC 3261 s12), but everything bound to the old address did
             * not: the RTP socket cannot send from an address the
             * interface no longer has, and the peer is still sending to
             * it. Left alone this is a call that stays on screen, stays
             * silent, and never ends. */
            migrateCallToNewAddress(wasLocal, local, wasCallId);
        }
    }

    /**
     * Move a live call onto the address the registration just moved to.
     *
     * <p>Off-thread: this sends a re-INVITE and waits for its final
     * response, and adopt() is called from the registration path.
     *
     * <p>Every failure here ends the call. That is the point -- the
     * alternative is a call that looks connected and carries nothing,
     * which is the state this whole path exists to get out of.
     */
    private static void migrateCallToNewAddress(final InetAddress from,
                                                final InetAddress to,
                                                final String callId) {
        JoanTrace.note("app local address changed during a call; migrating"
                + " fam=" + (to instanceof Inet6Address ? "v6" : "v4")
                + " cid=" + callId);
        new Thread(() -> {
            try {
                if (callId == null || !dialogAlive(callId)) {
                    JoanTrace.note("app migrate: dialog already gone");
                    return;
                }
                /* Rebind the media first. The re-INVITE tells the peer to
                 * send here, and we want to be listening before it does
                 * rather than dropping the first seconds of audio. */
                boolean media = startMediaForActiveCall();
                if (!media) {
                    JoanTrace.note("app migrate: media would not restart;"
                            + " ending call");
                    hangup(callId);
                    return;
                }
                /* A re-INVITE is a target refresh: its Contact and its SDP
                 * both carry the new address, which is what moves the
                 * peer's signalling and its media together. */
                String r = reInviteLive(liveHeld(), callId);
                if (r != null && r.startsWith("OK")) {
                    JoanTrace.note("app migrate: call moved to the new"
                            + " address");
                    return;
                }
                JoanTrace.note("app migrate: re-INVITE failed (" + r
                        + "); ending call");
                hangup(callId);
            } catch (Throwable t) {
                JoanTrace.note("app migrate " + t.getClass().getSimpleName()
                        + "; ending call");
                try {
                    hangup(callId);
                } catch (Throwable ignored) {
                    // nothing left to do
                }
            }
        }, "joan-sip-migrate").start();
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
        /* One 422 retry only. A core that answers the value it just
         * demanded with another 422 is not going to agree to anything,
         * and retrying forever would hold the dial screen open. */
        boolean seRetried = false;
        /* One redirect only. A core that 302s us back into another 302 is
         * a routing loop, and RFC 3261 s8.1.3.4 leaves the depth to the
         * UAC; one hop covers number portability without risking it. */
        boolean seRedirected = false;
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
                JoanSipBuilder.Codec answered =
                        media == null ? null : media.codec(media.payloadType);
                JoanSipBuilder.Capability answeredCap =
                        JoanSipBuilder.capabilityFor(answered);
                if (media != null && media.payloadType != 0
                        && answeredCap == null) {
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
                        wait.cseq)) {
                    clearInviteWait(wait);
                    return "ERR ack send";
                }
                synchronized (LOCK) {
                    if (addingSecond) {
                        sParked = snapLocked();
                    }
                    dlg.remoteTag = JoanSipBuilder.tagOf(toHdr);
                    dlg.remoteCseq = 0;
                    sDlg = dlg;
                    sDest = dest;
                    sTarget = target;
                    sRoute = route;
                    sToHdr = toHdr;
                    sFromHdr = fromHdr;
                    sOurToTag = JoanSipBuilder.tagOf(fromHdr);
                    sCall = true;
                    sLiveHeld = false;
                    if (media != null) {
                        try {
                            sMediaIp = InetAddress.getByName(media.ip);
                            sMediaPort = media.port;
                            sMediaRtcpPort = media.rtcpPort;
                            sMediaMux = media.mux;
                            sMediaPt = media.payloadType;
                            sMediaAmrWb = JoanSipBuilder.amrWideband(answered);
                            sMediaAmrBitrate = JoanSipBuilder.amrBitrate(answered);
                            sMediaAmrOct = JoanSipBuilder.amrOctetAligned(answered);
                            sMediaAmrMaxMode = answered == null
                                    ? -1 : answered.maxAmrMode();
                            JoanSipBuilder.Codec te =
                                    JoanSipBuilder.telephoneEventFor(
                                            media, answered);
                            sMediaTePt = te == null ? 0 : te.pt;
                            JoanTrace.note("app invite codec="
                                    + (enc.isEmpty() ? "PCMU" : enc)
                                    + " pt=" + media.payloadType
                                    + " fmtp=\"" + (answered == null
                                            ? "" : answered.fmtp) + "\""
                                    + " bitrate=" + sMediaAmrBitrate
                                    + " te_pt=" + sMediaTePt);
                        } catch (Exception e) {
                            sMediaIp = null;
                        }
                    }
                }
                sessionTimerArm(rx, true,
                        JoanSipBuilder.header(rx, "Allow"),
                        dlg == null ? null : dlg.callId);
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
            if (p.status == 422 && !seRetried) {
                /* Session Interval Too Small, RFC 4028 s6. The core will
                 * not hold a session as short as we asked for and names
                 * the shortest it will. Retrying at that value is the
                 * whole point of the response; failing the call instead
                 * would mean a carrier with a Min-SE above our
                 * Session-Expires could never place a call at all. */
                int peerMin = JoanSessionTimer.parseMinSe(
                        JoanSipBuilder.header(rx, "Min-SE"));
                int raised = JoanSessionTimer.retryExpiresAfter422(
                        peerMin, SE_MAX_SEC);
                JoanTrace.note("app invite 422 min_se=" + peerMin
                        + " retry_at=" + raised);
                String finalTo = nullToEmpty(JoanSipBuilder.header(rx, "To"));
                String finalFrom = nullToEmpty(JoanSipBuilder.header(rx, "From"));
                String contact = JoanSipBuilder.header(rx, "Contact");
                String finalTarget = contact == null ? dest
                        : JoanSipBuilder.contactUri(contact);
                String rr = JoanSipBuilder.header(rx, "Record-Route");
                String finalRoute = rr == null || rr.isEmpty()
                        ? sServiceRoute : rr;
                sendAckNon2xx(id, dlg, finalTarget, finalRoute, finalTo,
                        finalFrom, wait.cseq, dlg.branch);
                clearInviteWait(wait);
                if (raised <= 0) {
                    return "ERR invite 422";
                }
                /* Only the offer changes; everything else about the
                 * retry is the 420 path's shape -- a fresh dialog and a
                 * fresh transaction, because the old one is finished. */
                seRetried = true;
                JoanSipBuilder.setSessionTimer(raised, raised,
                        JoanSipBuilder.sessionRefresher());
                dlg = new JoanSipBuilder.Dialog();
                String retry422 = JoanSipBuilder.buildInvite(id, dlg, dest,
                        sServiceRoute, sSecVerify, RTP_PORT, sPani, secAgree);
                if (retry422 == null) {
                    return "ERR build invite";
                }
                sInviteAcks.begin(dlg.callId, dlg.cseq, dlg, "", "",
                        dest, sServiceRoute);
                wait = new InviteWait(dlg.callId, dlg.cseq);
                registerInviteWait(wait);
                try {
                    send(sSockC, sPcscf, sPcscfPortS,
                            retry422.getBytes(StandardCharsets.US_ASCII));
                } catch (Exception e) {
                    clearInviteWait(wait);
                    return "ERR invite send";
                }
                deadline = System.currentTimeMillis() + 30000;
                continue;
            }
            if (p.status >= 300 && p.status < 400 && !seRedirected) {
                /* RFC 3261 s8.1.3.4: a redirect names where to go in
                 * Contact, and a UAC that treats it as a failure simply
                 * does not reach the callee. Cores use 302 for number
                 * portability and for routing a call to a different
                 * S-CSCF, so failing here is a call that could have
                 * connected and did not. */
                String redirTo = nullToEmpty(JoanSipBuilder.header(rx, "To"));
                String redirFrom = nullToEmpty(JoanSipBuilder.header(rx, "From"));
                String redirContact = JoanSipBuilder.header(rx, "Contact");
                String next = redirContact == null ? null
                        : JoanSipBuilder.contactUri(redirContact);
                sendAckNon2xx(id, dlg, next == null ? dest : next,
                        sServiceRoute, redirTo, redirFrom, wait.cseq,
                        dlg.branch);
                clearInviteWait(wait);
                if (next == null || next.isEmpty() || next.equals(dest)) {
                    /* No Contact, or one pointing back where we already
                     * are. Following that is a loop, not a redirect. */
                    JoanTrace.note("app invite " + p.status
                            + " with no usable Contact; not following");
                    return "ERR invite " + p.status;
                }
                JoanTrace.note("app invite " + p.status + " redirect; retrying");
                seRedirected = true;
                dest = next;
                dlg = new JoanSipBuilder.Dialog();
                String redir = JoanSipBuilder.buildInvite(id, dlg, dest,
                        sServiceRoute, sSecVerify, RTP_PORT, sPani, secAgree);
                if (redir == null) {
                    return "ERR build invite";
                }
                sInviteAcks.begin(dlg.callId, dlg.cseq, dlg, "", "",
                        dest, sServiceRoute);
                wait = new InviteWait(dlg.callId, dlg.cseq);
                registerInviteWait(wait);
                try {
                    send(sSockC, sPcscf, sPcscfPortS,
                            redir.getBytes(StandardCharsets.US_ASCII));
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
                if (p.status == 503) {
                    /* RFC 3261 s21.5.4: 503 is this server being
                     * unavailable, not the call being refused. Retry-After
                     * says for how long. Reporting it as a plain failure
                     * hides a transient from the user and from the log. */
                    int after = JoanSessionTimer.parseExpires(
                            JoanSipBuilder.header(rx, "Retry-After"));
                    JoanTrace.note("app invite 503 service unavailable"
                            + (after > 0 ? " retry_after=" + after + "s"
                                    : " (no Retry-After)"));
                    return "ERR invite 503"
                            + (after > 0 ? " retry_after=" + after : "");
                }
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
        JoanSipBuilder.Media inOffer = JoanSipBuilder.parseSdp(invite);
        JoanSipBuilder.Codec chosen = JoanSipBuilder.selectAnswerCodec(inOffer);
        String sdp = JoanSipBuilder.sdpAnswer(sId.localIp, RTP_PORT,
                inOffer, chosen);
        String tag = sRingingToTag;
        if (tag == null || tag.isEmpty()) {
            tag = sOurToTag;
        }
        if (tag == null || tag.isEmpty()) {
            tag = String.format("%012x",
                    new java.security.SecureRandom().nextLong() & 0xffffffffffffL);
        }
        sOurToTag = tag;
        /* RFC 4028 s8.2: the UAS settles the interval and says who
         * refreshes. A peer that asked for nothing gets nothing back --
         * putting a Session-Expires in a 2xx the caller never asked for
         * commits it to refreshes it does not know it owes. */
        int seAsked = JoanSessionTimer.parseExpires(
                JoanSipBuilder.header(invite, "Session-Expires"));
        boolean seWanted = seAsked > 0
                && JoanSessionTimer.peerSupportsTimer(
                        JoanSipBuilder.header(invite, "Supported"),
                        JoanSipBuilder.header(invite, "Require"));
        int seAgreed = 0;
        int seRefresher = JoanSessionTimer.REFRESHER_UNKNOWN;
        String seHeaders = "";
        if (seWanted && JoanSipBuilder.sessionExpiresSec() > 0) {
            int tooSmall = JoanSessionTimer.rejectBelowMinSe(
                    seAsked, JoanSipBuilder.sessionMinSeSec());
            if (tooSmall > 0) {
                /* Below our Min-SE. 422 names the value we will accept,
                 * which is the only answer that lets the caller retry
                 * into something that works. */
                JoanTrace.note("app ANSWER 422 session-interval-too-small"
                        + " asked=" + seAsked + " min_se=" + tooSmall);
                try {
                    sendReply(buildResponse(invite, 422,
                            "Session Interval Too Small", id, tag, null,
                            "Min-SE: " + tooSmall + "\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                } catch (Exception ignored) {
                    // the caller will time out either way
                }
                return "ERR session interval too small";
            }
            seAgreed = seAsked > SE_MAX_SEC ? SE_MAX_SEC : seAsked;
            seRefresher = JoanSessionTimer.uasRefresher(
                    JoanSessionTimer.parseRefresher(
                            JoanSipBuilder.header(invite, "Session-Expires")),
                    JoanSipBuilder.sessionRefresher());
            seHeaders = JoanSipBuilder.sessionTimerAnswerHeaders(
                    seAgreed, seRefresher);
        }
        String resp = buildResponse(invite, 200, "OK", id, tag, sdp,
                seHeaders.isEmpty() ? null : seHeaders);
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
            dlg.remoteTag = JoanSipBuilder.tagOf(
                    JoanSipBuilder.header(invite, "From"));
            dlg.remoteCseq = JoanSipBuilder.cseqForMethod(invite, "INVITE");
            sDlg = dlg;
            sInvite200 = resp;
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
            /* The inbound path never recorded a codec, so every answered
             * call ran as PCMU whatever was negotiated. It now carries the
             * selection, the same way the outbound path carries what the
             * answer chose. */
            sMediaPt = chosen == null ? 0 : chosen.pt;
            sMediaAmrWb = JoanSipBuilder.amrWideband(chosen);
            sMediaAmrBitrate = JoanSipBuilder.amrBitrate(chosen);
            sMediaAmrOct = JoanSipBuilder.amrOctetAligned(chosen);
            sMediaAmrMaxMode = chosen == null ? -1 : chosen.maxAmrMode();
            /* The answer we just built echoed the peer's event type at the
             * chosen codec's clock rate; send digits on that same one. */
            JoanSipBuilder.Codec te =
                    JoanSipBuilder.telephoneEventFor(media, chosen);
            sMediaTePt = te == null ? 0 : te.pt;
        }
        JoanTrace.note("app ANSWER 200 codec="
                + (chosen == null ? "PCMU" : chosen.name)
                + " pt=" + (chosen == null ? 0 : chosen.pt)
                + " fmtp=\"" + (chosen == null ? "" : chosen.fmtp) + "\""
                + " bitrate=" + sMediaAmrBitrate
                + " te_pt=" + sMediaTePt
                + " session_expires=" + seAgreed);
        if (seAgreed > 0) {
            sSeAgreedSec = seAgreed;
            sSeRefresher = seRefresher;
            sSeWeAreUac = false;
            sSePeerAllow = JoanSipBuilder.header(invite, "Allow");
            sSeCallId = JoanSipBuilder.header(invite, "Call-ID");
            sessionTimerRearm("negotiated as UAS");
            sessionTimerStartThread();
        } else {
            sessionTimerStop("inbound call is not timed");
        }
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
                    JoanSipBuilder.branchOf(sub), null,
                    JoanSipBuilder.tagOf(focusLeg.fromHdr),
                    JoanSipBuilder.tagOf(focusLeg.toHdr));
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
        if (cid.equals(sSeCallId)) {
            /* The timer belongs to this dialog. Leaving it armed would
             * have the next tick refresh, or hang up, a call that has
             * already gone. */
            sessionTimerStop("dialog retired");
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
                        wait.cseq);
                FocusLeg fl = new FocusLeg();
                fl.dlg = dlg;
                fl.target = targetOf(rx, focus);
                fl.route = routeOf(rx);
                fl.toHdr = nullToEmpty(JoanSipBuilder.header(rx, "To"));
                fl.fromHdr = nullToEmpty(
                        JoanSipBuilder.header(rx, "From"));
                fl.ourToTag = JoanSipBuilder.tagOf(fl.fromHdr);
                fl.held = false;
                JoanSipBuilder.Media media = JoanSipBuilder.parseSdp(rx);
                if (media != null) {
                    try {
                        fl.mediaIp = InetAddress.getByName(media.ip);
                        fl.mediaPort = media.port;
                        fl.mediaRtcpPort = media.rtcpPort;
                        fl.mediaPt = media.payloadType;
                        fl.mux = media.mux;
                    } catch (Exception ignored) {
                        fl.mediaIp = null;
                    }
                }
                dlg.remoteTag = JoanSipBuilder.tagOf(fl.toHdr);
                dlg.remoteCseq = 0;
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
        final String localTag;
        final String remoteTag;
        boolean notifyFinal;
        boolean notifyFailure;
        NonInviteWait(String callId, int cseq, String method,
                      String branch, String referToUri,
                      String localTag, String remoteTag) {
            this.callId = callId;
            this.cseq = cseq;
            this.method = method;
            this.branch = branch;
            this.referToUri = referToUri;
            this.localTag = localTag == null ? "" : localTag;
            this.remoteTag = remoteTag == null ? "" : remoteTag;
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
                referCseq, "REFER", branch, referTo,
                JoanSipBuilder.tagOf(focusLeg.fromHdr),
                JoanSipBuilder.tagOf(focusLeg.toHdr));
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
        l.mediaAmrBitrate = sMediaAmrBitrate;
        l.mediaAmrOct = sMediaAmrOct;
        l.mediaAmrMaxMode = sMediaAmrMaxMode;
        l.mediaTePt = sMediaTePt;
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
        sMediaAmrBitrate = l.mediaAmrBitrate;
        sMediaAmrOct = l.mediaAmrOct;
        sMediaAmrMaxMode = l.mediaAmrMaxMode;
        sMediaTePt = l.mediaTePt;
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
                    String notifyTo = JoanSipBuilder.tagOf(
                            JoanSipBuilder.header(rx, "To"));
                    String notifyFrom = JoanSipBuilder.tagOf(
                            JoanSipBuilder.header(rx, "From"));
                    if (w.localTag.isEmpty() || w.remoteTag.isEmpty()
                            || !w.localTag.equals(notifyTo)
                            || !w.remoteTag.equals(notifyFrom)) {
                        JoanTrace.note("app REFER NOTIFY ignored: dialog mismatch");
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
            String localTag = JoanSipBuilder.tagOf(
                    JoanSipBuilder.header(rx, "To"));
            JoanTrace.note("app inbound BYE");
            synchronized (LOCK) {
                if (sParked != null && sParked.dlg != null
                        && cid != null && cid.equals(sParked.dlg.callId)
                        && !remoteTag.isEmpty()
                        && remoteTag.equals(JoanSipBuilder.tagOf(sParked.toHdr))
                        && !localTag.isEmpty()
                        && localTag.equals(sParked.ourToTag)) {
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
                        && !remoteTag.isEmpty()
                        && remoteTag.equals(JoanSipBuilder.tagOf(sToHdr))
                        && !localTag.isEmpty()
                        && (localTag.equals(sOurToTag)
                        || localTag.equals(JoanSipBuilder.tagOf(sFromHdr)));
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
            sessionTimerStop("remote BYE");
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
        if ("UPDATE".equals(method)) {
            /* RFC 4028 session refresh, and RFC 3311 more generally. The
             * network is asking whether the session is still wanted; the
             * answer is a 200 with our media, not silence. Unanswered, the
             * refresh fails and the call is torn down -- the one way a
             * healthy long call dies for a protocol reason.
             *
             * An UPDATE that names no dialog we own gets 481 rather than
             * an answer that would confirm a session we are not in. */
            String cid = JoanSipBuilder.header(rx, "Call-ID");
            String se = JoanSipBuilder.header(rx, "Session-Expires");
            synchronized (LOCK) {
                boolean mine = sCall && sDlg != null && cid != null
                        && cid.equals(sDlg.callId);
                String tag = sOurToTag != null && !sOurToTag.isEmpty()
                        ? sOurToTag : "x";
                JoanTrace.note("app inbound UPDATE dialog=" + (mine ? "ours" : "unknown")
                        + (se == null ? "" : " session-expires=\"" + se.trim() + "\""));
                if (!mine) {
                    try {
                        sendReply(buildResponse(rx, 481,
                                "Call/Transaction Does Not Exist", sId, tag, null)
                                .getBytes(StandardCharsets.US_ASCII));
                    } catch (Exception ignored) {
                        // ignore
                    }
                    return;
                }
                /* If it carries an offer, answer with the codec already
                 * negotiated rather than renegotiating mid-call. */
                String sdp = null;
                if (JoanSipBuilder.parseSdp(rx) != null) {
                    JoanSipBuilder.Media o = JoanSipBuilder.parseSdp(rx);
                    sdp = JoanSipBuilder.sdpAnswer(sId.localIp, RTP_PORT, o,
                            JoanSipBuilder.selectAnswerCodec(o));
                }
                /* This UPDATE is the peer's session refresh. Echoing the
                 * interval back is what confirms the session for another
                 * one; answering 200 with no Session-Expires leaves the
                 * peer's own timer running out and the call dropped from
                 * the far side for a reason nothing here would explain. */
                String seOut = sessionTimerOnInboundRefresh(rx, "UPDATE");
                try {
                    sendReply(buildResponse(rx, 200, "OK", sId, tag, sdp,
                            seOut.isEmpty() ? null : seOut)
                            .getBytes(StandardCharsets.US_ASCII));
                } catch (Exception ignored) {
                    // ignore
                }
            }
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
            } else if (event != null && event.toLowerCase(
                    java.util.Locale.ROOT).startsWith("reg")) {
                handleRegEventNotify(rx);
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
            /* Log the method, never the message: the REGISTER Contact
             * advertises +g.3gpp.smsip, so a core may deliver SMS here as
             * a SIP MESSAGE and its body is the text of someone's SMS. */
            if ("PRACK".equals(method)) {
                /* A PRACK only acknowledges a reliable provisional, and
                 * RFC 3262 s3 wants a 200 for it. We never send a 1xx
                 * with Require: 100rel, so this should not arrive -- but
                 * PRACK is in our Allow, and answering 501 to a method we
                 * advertise is the kind of contradiction that makes a
                 * core give up on the dialog. */
                try {
                    sendReply(buildResponse(rx, 200, "OK", sId,
                            sOurToTag != null ? sOurToTag : "prk", null)
                            .getBytes(StandardCharsets.US_ASCII));
                    JoanTrace.note("app inbound PRACK; 200");
                } catch (Exception ignored) {
                    // the sender retransmits
                }
                return;
            }
            if (!"ACK".equals(method)) {
                /* ACK needs no response and ignoring it is correct.
                 * Everything else does: RFC 3261 8.2.1 requires a UAS to
                 * answer a request it cannot handle, and dropping one
                 * silently makes the sender retransmit and then tear the
                 * dialog down. An unanswered in-dialog UPDATE is exactly
                 * how a session refresh becomes a dropped call. */
                JoanTrace.note("app inbound unhandled method=" + method
                        + "; answered 501");
                try {
                    sendReply(buildResponse(rx, 501, "Not Implemented", sId,
                            null, null).getBytes(StandardCharsets.US_ASCII));
                } catch (Exception ignored) {
                    // nothing further to try
                }
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
             * Neither is a new call. Local CSeq is never the remote space. */
            String remoteTag = JoanSipBuilder.tagOf(
                    JoanSipBuilder.header(rx, "From"));
            String localTag = JoanSipBuilder.tagOf(
                    JoanSipBuilder.header(rx, "To"));
            boolean tagsMatch = !remoteTag.isEmpty()
                    && remoteTag.equals(sDlg.remoteTag != null
                    && !sDlg.remoteTag.isEmpty()
                    ? sDlg.remoteTag : JoanSipBuilder.tagOf(sToHdr))
                    && (localTag.isEmpty()
                    || localTag.equals(sOurToTag)
                    || localTag.equals(JoanSipBuilder.tagOf(sFromHdr)));
            if (invCseq > 0 && invCseq == sDlg.remoteCseq && tagsMatch) {
                String cached = sInvite200;
                try {
                    if (cached != null && !cached.isEmpty()) {
                        sendReply(cached.getBytes(StandardCharsets.US_ASCII));
                    } else {
                        String tag = sOurToTag != null ? sOurToTag : "dlg";
                        sendReply(buildResponse(rx, 200, "OK", sId, tag, null)
                                .getBytes(StandardCharsets.US_ASCII));
                    }
                } catch (Exception ignored) {
                    // ignore
                }
                JoanTrace.note("app inbound INVITE retransmit; 200 resent");
                return;
            }
            if (!tagsMatch) {
                try {
                    sendReply(buildResponse(rx, 481,
                            "Call/Transaction Does Not Exist", sId,
                            "stale", null)
                            .getBytes(StandardCharsets.US_ASCII));
                } catch (Exception ignored) {
                    // ignore
                }
                JoanTrace.note("app inbound INVITE dialog-tag mismatch; 481");
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
            /* A re-INVITE carrying Session-Expires is the peer's session
             * refresh, not a media change. RFC 4028 lets either method
             * carry it, and a carrier configured for
             * SESSION_REFRESH_METHOD_INVITE will only ever use this one.
             * Declining it 488 answers the refresh with a failure, and
             * the peer then tears the call down when its own timer runs
             * out -- a long call dying for a protocol reason, which is
             * the exact failure session timers exist to prevent. */
            /* A direction change is the peer holding or resuming us.
             * RFC 3264 s8.4 makes this an ordinary re-INVITE, and it is
             * the one the far end sends when its user presses hold.
             * Declining it 488 refuses a request the peer is entitled to
             * make, and cores differ on whether that ends the call --
             * some drop it, which is a user pressing hold and losing the
             * call. Answer it, mirror the direction, and stop sending
             * RTP into a stream nobody is listening to. */
            JoanSipBuilder.Media reOffer = JoanSipBuilder.parseSdp(rx);
            if (reOffer != null && reInviteKeepsNegotiatedMedia(rx)
                    && !JoanSipBuilder.DIR_SENDRECV.equals(reOffer.direction)
                            != sLiveHeld) {
                handlePeerDirectionChange(rx, reOffer);
                return;
            }
            String refreshSe = JoanSipBuilder.header(rx, "Session-Expires");
            if (refreshSe != null
                    && JoanSessionTimer.parseExpires(refreshSe) > 0
                    && reInviteKeepsNegotiatedMedia(rx)) {
                String seOut = sessionTimerOnInboundRefresh(rx, "re-INVITE");
                String sdpOut = null;
                JoanSipBuilder.Media o = JoanSipBuilder.parseSdp(rx);
                if (o != null) {
                    sdpOut = JoanSipBuilder.sdpAnswer(sId.localIp, RTP_PORT,
                            o, JoanSipBuilder.selectAnswerCodec(o));
                }
                try {
                    sendReply(buildResponse(rx, 200, "OK", sId, sOurToTag,
                            sdpOut, seOut.isEmpty() ? null : seOut)
                            .getBytes(StandardCharsets.US_ASCII));
                    JoanTrace.note("app inbound re-INVITE session refresh; 200");
                } catch (Exception ignored) {
                    // the peer retransmits; nothing to do here
                }
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
        if (sHeldInvite != null) {
            try {
                sendReply(buildResponse(rx, 486, "Busy Here", sId,
                        "busy", null)
                        .getBytes(StandardCharsets.US_ASCII));
            } catch (Exception ignored) {
                // ignore
            }
            JoanTrace.note("app inbound INVITE busy; ringing already held");
            return;
        }
        JoanSipBuilder.Media offer = JoanSipBuilder.parseSdp(rx);
        if (offer != null && JoanSipBuilder.selectAnswerCodec(offer) == null) {
            /* Nothing in the offer we can carry. Better to decline than to
             * ring the user for a call that cannot have audio. This used
             * to fire whenever PCMU was absent, because the answer was
             * PCMU regardless of the offer; it now fires only when AMR-WB,
             * AMR and PCMU are all absent or unusable. */
            JoanTrace.note("app inbound INVITE: no usable codec; 488");
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
                + " name=" + (cli.name.isEmpty() ? "no" : "yes")
                + " offer=" + JoanSipBuilder.codecSummary(offer));
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
        int cancelCseq = JoanSipBuilder.cseqForMethod(rx, "CANCEL");
        int inviteCseq = held != null
                ? JoanSipBuilder.cseqForMethod(held, "INVITE") : -1;
        boolean mine = held != null && callId != null
                && callId.equals(JoanSipBuilder.header(held, "Call-ID"))
                && cancelCseq > 0 && cancelCseq == inviteCseq;
        String tag = sRingingToTag != null ? sRingingToTag
                : (sOurToTag != null ? sOurToTag : "x");
        /* A UAS answers the CANCEL transaction either way. */
        try {
            if (mine) {
                sendReply(buildResponse(rx, 200, "OK", sId, tag, null)
                        .getBytes(StandardCharsets.US_ASCII));
            } else {
                sendReply(buildResponse(rx, 481,
                        "Call/Transaction Does Not Exist", sId, tag, null)
                        .getBytes(StandardCharsets.US_ASCII));
            }
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
            /* Requests built for the UDP client socket are about to leave
             * over TCP instead; their top Via must say so or the P-CSCF
             * answers 400 Bad Request. Responses are left alone. */
            pkt = JoanSipBuilder.retargetRequestViaToTcp(
                    new String(pkt, StandardCharsets.US_ASCII))
                    .getBytes(StandardCharsets.US_ASCII);
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
        java.util.List<String> vias = JoanSipBuilder.headers(req, "Via");
        java.util.List<String> rrs = JoanSipBuilder.headers(req, "Record-Route");
        String from = JoanSipBuilder.header(req, "From");
        String to = JoanSipBuilder.header(req, "To");
        String callId = JoanSipBuilder.header(req, "Call-ID");
        String cseq = JoanSipBuilder.header(req, "CSeq");
        if (to != null && to.indexOf("tag=") < 0 && toTag != null) {
            to = to + ";tag=" + toTag;
        }
        String host = JoanSipBuilder.bracket(id.localIp);
        String contactUser = "joan";
        if (sPublicId != null && !sPublicId.isEmpty()) {
            String aor = sPublicId;
            if (aor.startsWith("sip:")) {
                aor = aor.substring(4);
            } else if (aor.startsWith("tel:")) {
                aor = aor.substring(4);
            }
            int at = aor.indexOf('@');
            contactUser = at >= 0 ? aor.substring(0, at) : aor;
        }
        StringBuilder a = new StringBuilder(800);
        a.append("SIP/2.0 ").append(code).append(' ').append(reason)
                .append("\r\n");
        for (String via : vias) {
            a.append("Via: ").append(via).append("\r\n");
        }
        for (String rr : rrs) {
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
        if (extraHeaders != null && !extraHeaders.isEmpty()) {
            a.append(extraHeaders);
            if (!extraHeaders.endsWith("\r\n")) {
                a.append("\r\n");
            }
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
            closeTransportLocked();
            return;
        }
        sCall = false;
        sLiveHeld = false;
        sRegEventSubscribed = false;
        sessionTimerStop("binding released");
        sParked = null;
        sInviteWaits.clear();
        for (InviteFlight f : sInviteFlights.values()) f.result.complete("ERR binding released");
        sInviteFlights.clear();
        sInviteAcks.clear();
        sNonInviteWaits.clear();
        sMergedDialogIds.clear();
        sHeldInvite = null;
        sInvite200 = null;
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
        sMediaAmrBitrate = 0;
        sMediaAmrOct = false;
        sMediaAmrMaxMode = -1;
        sMediaTePt = 0;
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
        closeTransportLocked();
    }

    /** UDP sockets, IPsec SAs, and the listen thread. Dialogs stay. */
    private static void closeTransportLocked() {
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
        sOutC = sInC = sOutS = sInS = null;
        sIpsec = null;
    }
}
