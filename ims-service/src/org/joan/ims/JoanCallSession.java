package org.joan.ims;

import android.content.Context;
import android.os.Message;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.ImsCallSessionListener;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsStreamMediaProfile;
import android.telephony.ims.stub.ImsCallSessionImplBase;
import android.util.Log;

/**
 * One IMS call session. Callback sequence matches AOSP
 * ImsPhoneCallTracker (SystemApi names, not CAF):
 *
 *   DIALING  -> callSessionInitiating then callSessionProgressing
 *   ACTIVE   -> callSessionInitiated(profile)
 *   END      -> callSessionTerminated
 *
 * After the session exists, MmTelFeature.setCallAudioHandler(ANDROID)
 * tells Telecom this call is AP-owned (MODE_IN_COMMUNICATION).
 * JoanMedia then plays/records on the voice-communication stream.
 */
public class JoanCallSession extends ImsCallSessionImplBase {
    private static final String TAG = "JoanIms";
    private final Context app;
    private final JoanMmTelFeature feature;
    private final ImsCallProfile profile;
    private volatile ImsCallSessionListener listener;
    private volatile int state = STATE_IDLE;
    private final String callId;
    private volatile boolean watchHangup;
    /* An inbound call held at 180. accept() and reject() must drive the UA
     * rather than just telling Telecom, because the INVITE has not been
     * answered yet. */
    private final boolean incoming;
    private volatile String sipCallId;

    JoanCallSession(Context app, JoanMmTelFeature feature, ImsCallProfile profile) {
        this(app, feature, profile, false, null);
    }

    private JoanCallSession(Context app, JoanMmTelFeature feature,
                            ImsCallProfile profile, boolean incoming,
                            String sipCallId) {
        this.app = app.getApplicationContext();
        this.feature = feature;
        this.profile = profile;
        this.incoming = incoming;
        this.sipCallId = sipCallId;
        this.callId = "joan-" + Long.toHexString(System.nanoTime());
    }

    static JoanCallSession incoming(Context app, JoanMmTelFeature feature,
                                    ImsCallProfile profile, String sipCallId) {
        JoanCallSession s = new JoanCallSession(app, feature, profile, true,
                sipCallId);
        s.state = STATE_ESTABLISHING;
        return s;
    }

    /** The caller gave up, or the far end hung up. */
    void onRemoteEnded() {
        watchHangup = false;
        if (sipCallId == null || sipCallId.equals(JoanSipUa.currentCallId())) {
            JoanMedia.stop();
        }
        state = STATE_TERMINATED;
        notifyTerminated(0);
    }

    void onHeldByUa() {
        notifyHeld();
    }

    @Override
    public void setListener(ImsCallSessionListener l) {
        super.setListener(l);
        listener = l;
        Log.i(TAG, "setListener " + (l == null ? "null" : "ok"));
    }

    @Override
    public String getCallId() {
        return callId;
    }

    @Override
    public ImsCallProfile getCallProfile() {
        return profile;
    }

    @Override
    public int getState() {
        return state;
    }

    @Override
    public void start(String callee, ImsCallProfile p) {
        Log.i(TAG, "call session start");
        JoanTrace.lastDial("call session start");
        state = STATE_ESTABLISHING;
        ImsCallProfile used = p != null ? p : profile;
        notifyInitiating(used);
        /* CAF fires Progressing as soon as MO is DIALING, before 200. */
        notifyProgressing();
        /* Connection exists as DIALING; tell Telecom AP owns audio now. */
        feature.useAndroidAudioHandler();
        if (callee == null || callee.isEmpty()) {
            failStart("empty callee");
            return;
        }
        final String uri;
        if (callee.startsWith("sip:") || callee.startsWith("tel:")) {
            uri = callee;
        } else {
            uri = "tel:" + callee;
        }
        new Thread(() -> {
            if (!JoanSipUa.isRegistered()) {
                failStart("not registered");
                return;
            }
            String resp = JoanSipUa.invite(uri);
            if (resp == null || !resp.startsWith("OK")) {
                failStart(resp == null ? "invite failed" : resp);
                return;
            }
            sipCallId = JoanSipUa.currentCallId();
            JoanMmTelFeature.track(sipCallId, this);
            if (!hasNegotiatedMedia()) {
                JoanSipUa.hangup();
                failStart("no negotiated media");
                return;
            }
            /* Order matters: Telecom only switches to
             * MODE_IN_COMMUNICATION once the session is ACTIVE and the
             * audio handler is ANDROID. AudioRecord and AudioTrack have to
             * open after that or they land on the wrong routing. */
            state = STATE_ESTABLISHED;
            notifyStarted(used);
            feature.useAndroidAudioHandler();
            startMedia();
            watchRemoteHangup();
        }, "joan-ims-call").start();
    }

    @Override
    public void accept(int callType, ImsStreamMediaProfile media) {
        Log.i(TAG, "call session accept");
        if (!incoming) {
            state = STATE_ESTABLISHED;
            notifyStarted(profile);
            feature.useAndroidAudioHandler();
            if (hasNegotiatedMedia()) {
                startMedia();
            }
            return;
        }
        /* The INVITE is still unanswered: the 200 OK has to go out before
         * we can claim the call is up. Off the binder thread. */
        new Thread(() -> {
            String resp = JoanSipUa.answer();
            if (resp == null || !resp.startsWith("OK")) {
                Log.w(TAG, "ANSWER refused by app UA");
                JoanTrace.note("incoming answer failed");
                state = STATE_TERMINATED;
                notifyTerminated(0);
                return;
            }
            sipCallId = JoanSipUa.currentCallId();
            JoanMmTelFeature.track(sipCallId, this);
            if (!hasNegotiatedMedia()) {
                JoanSipUa.hangup();
                JoanTrace.note("incoming answer had no media");
                state = STATE_TERMINATED;
                notifyTerminated(0);
                return;
            }
            state = STATE_ESTABLISHED;
            notifyStarted(profile);
            feature.useAndroidAudioHandler();
            startMedia();
            watchRemoteHangup();
            JoanTrace.note("incoming call answered");
        }, "joan-ims-answer").start();
    }

    @Override
    public void reject(int reason) {
        if (incoming) {
            /* 603 Decline says the user refused; 486 would claim we are
             * busy, which sends some callers to a different treatment. */
            state = STATE_TERMINATED;
            new Thread(() -> JoanSipUa.reject(603),
                    "joan-ims-reject").start();
            notifyTerminated(reason);
            return;
        }
        hangupAsync();
        state = STATE_TERMINATED;
        notifyTerminated(reason);
    }

    @Override
    public void terminate(int reason) {
        hangupAsync();
        state = STATE_TERMINATED;
        notifyTerminated(reason);
    }

    @Override
    public void hold(ImsStreamMediaProfile mediaProfile) {
        Log.i(TAG, "call session hold");
        new Thread(() -> {
            boolean optimistic = JoanSipUa.swapInProgress();
            if (optimistic) {
                /* Mid-swap: the framework will resume the parked leg the
                 * moment it hears 'held'. Confirm now; the hold re-INVITE
                 * final lands asynchronously (a failure is reported
                 * late). This halves swap wall-time: hold and resume
                 * re-INVITEs fly concurrently on their two dialogs. */
                notifyHeld();
            }
            String r = JoanSipUa.hold(sipCallId);
            if (r != null && r.startsWith("OK")) {
                if (!optimistic) {
                    notifyHeld();
                }
            } else {
                notifyHoldFailed(r);
            }
        }, "joan-ims-hold").start();
    }

    /**
     * AOSP conference handshake: the framework holds both calls first,
     * then calls merge() on the conference session. Route it to the
     * stock-model conference flow.
     */
    @Override
    public void merge() {
        mergeConference();
    }

    @Override
    public void resume(ImsStreamMediaProfile mediaProfile) {
        Log.i(TAG, "call session resume");
        JoanTrace.note("call session resume invoked");
        new Thread(() -> {
            String r = JoanSipUa.resume(sipCallId);
            if (r == null || !r.startsWith("OK")) {
                JoanTrace.note("resume failed: " + r);
                notifyResumeFailed(r);
                return;
            }
            feature.useAndroidAudioHandler();
            if (hasNegotiatedMedia()) {
                startMedia();
            }
            notifyResumed();
        }, "joan-ims-resume").start();
    }

    /**
     /**
      * Conference merge (AOSP ImsCall.merge contract): a NEW session
      * represents the merged conference. The framework keeps the merged
      * conference session as the live call and expects the two original
      * sessions to terminate as their legs transfer into the focus.
      *
      * Sequence: callSessionMergeStarted(confSession) -> network merge
      * (focus INVITE + REFERs + subscription) -> on success
      * callSessionMergeComplete(confSession); on failure
      * callSessionMergeFailed(reason) and the original sessions stay.
      * The originals that transferred in are terminated via
      * onRemoteEnded() below, which is what ImsPhoneCallTracker uses to
      * recognize the fully merged state (ImsCall.processMergeComplete
      * cases 1-3).
      */
     void mergeConference() {
         Log.i(TAG, "call session merge");
         JoanTrace.note("call session merge invoked");
         JoanCallSession conf = conferenceSession();
         notifyMergeStarted(conf);
         new Thread(() -> {
             String r = JoanSipUa.merge(app,
                     JoanRegistration.mcc(), JoanRegistration.mnc());
             JoanTrace.note("call session merge result=" + r);
             if (r != null && r.startsWith("OK")) {
                 conf.sipCallId = JoanSipUa.conferenceFocusCallId();
                 if (conf.sipCallId != null) {
                     JoanMmTelFeature.track(conf.sipCallId, conf);
                     JoanMmTelFeature.trackConference(conf);
                 }
                 conf.state = STATE_ESTABLISHED;
                 feature.useAndroidAudioHandler();
                 /* The transferred original legs are gone: end them the
                  * way a remote hangup would, so the framework folds the
                  * dialog into the conference session instead of keeping
                  * zombie legs. A surviving leg (partial merge) stays. */
                 for (String transferred :
                         JoanSipUa.mergedDialogIds()) {
                     JoanMmTelFeature.onMergedIntoConference(transferred);
                 }
                 conf.notifyMergeComplete();
             } else {
                 JoanMmTelFeature.trackConference(null);
                 notifyMergeFailed(r);
             }
         }, "joan-ims-merge").start();
     }

     /** Fresh session object for the merged conference (framework
      * transient-conference-session pattern). It performs no dialing:
      * the UA owns the focus dialog. */
     private JoanCallSession conferenceSession() {
         JoanCallSession c = new JoanCallSession(app, feature, profile);
         c.state = STATE_ESTABLISHING;
         c.conference = true;
         return c;
     }

     private volatile boolean conference;

     @Override
     public boolean isMultiparty() {
         return conference;
     }

     private void notifyMergeStarted(JoanCallSession conf) {
         ImsCallSessionListener l = listener;
         if (l == null) {
             return;
         }
         try {
             l.callSessionMergeStarted(conf, profile);
         } catch (Throwable t) {
             Log.w(TAG, "merge start notify " + t.getClass().getSimpleName());
         }
     }

    void onConferenceUsers(java.util.List<String> users) {
        ImsCallSessionListener l = listener;
        if (l == null) {
            return;
        }
        try {
            android.telephony.ims.ImsConferenceState st =
                    new android.telephony.ims.ImsConferenceState();
            for (String u : users) {
                st.mParticipants.put(u, "");
            }
            l.callSessionConferenceStateUpdated(st);
        } catch (Throwable t) {
            Log.w(TAG, "conf state notify "
                    + t.getClass().getSimpleName());
        }
    }

    private void hangupAsync() {
        watchHangup = false;
        boolean live = sipCallId == null
                || sipCallId.equals(JoanSipUa.currentCallId());
        if (live) {
            JoanMedia.stop();
        }
        final String id = sipCallId;
        new Thread(() -> JoanSipUa.hangup(id), "joan-ims-hangup").start();
    }

    private void watchRemoteHangup() {
        watchHangup = true;
        new Thread(() -> {
            try {
                boolean seenUp = false;
                for (int i = 0; i < 3000 && watchHangup; i++) {
                    Thread.sleep(200);
                    if (!watchHangup) {
                        return;
                    }
                    if (sipCallId != null
                            ? JoanSipUa.dialogAlive(sipCallId)
                            : JoanSipUa.callActive()) {
                        seenUp = true;
                        continue;
                    }
                    if (seenUp) {
                        JoanTrace.note("remote hangup: call no longer active");
                        state = STATE_TERMINATED;
                        JoanMedia.stop();
                        notifyTerminated(ImsReasonInfo.CODE_USER_TERMINATED);
                        return;
                    }
                }
            } catch (InterruptedException ignored) {
                // stop watching
            }
        }, "joan-ims-watch").start();
    }

    private void notifyInitiating(ImsCallProfile p) {
        ImsCallSessionListener l = listener;
        if (l == null) {
            return;
        }
        try {
            l.callSessionInitiating(p);
        } catch (Throwable t) {
            Log.w(TAG, "initiating notify " + t.getClass().getSimpleName());
        }
    }

    private void notifyProgressing() {
        ImsCallSessionListener l = listener;
        if (l == null) {
            Log.w(TAG, "progressing skipped: no listener");
            return;
        }
        try {
            l.callSessionProgressing(new ImsStreamMediaProfile(
                    ImsStreamMediaProfile.AUDIO_QUALITY_NONE,
                    ImsStreamMediaProfile.DIRECTION_SEND_RECEIVE,
                    0, ImsStreamMediaProfile.DIRECTION_INVALID));
        } catch (Throwable t) {
            Log.w(TAG, "progressing notify " + t.getClass().getSimpleName());
        }
    }

    private void notifyStarted(ImsCallProfile p) {
        ImsCallSessionListener l = listener;
        if (l == null) {
            Log.w(TAG, "started skipped: no listener");
            return;
        }
        try {
            l.callSessionInitiated(p);
        } catch (Throwable t) {
            Log.w(TAG, "initiated notify " + t.getClass().getSimpleName());
        }
    }

    private void notifyTerminated(int reason) {
        watchHangup = false;
        JoanMmTelFeature.untrack(sipCallId);
        ImsCallSessionListener l = listener;
        if (l == null) {
            return;
        }
        try {
            l.callSessionTerminated(new ImsReasonInfo(reason, 0, "hangup"));
        } catch (Throwable t) {
            Log.w(TAG, "term notify " + t.getClass().getSimpleName());
        }
    }

    private volatile boolean heldNotified;

    private void notifyHeld() {
        if (heldNotified) {
            /* Exactly once per session: ImsPhoneCallTracker treats a second
             * callSessionHeld as a stray event and wedges its handshake --
             * later unholds then fail with "Call update is in progress". */
            return;
        }
        heldNotified = true;
        ImsCallSessionListener l = listener;
        if (l == null) {
            return;
        }
        try {
            l.callSessionHeld(profile);
        } catch (Throwable t) {
            Log.w(TAG, "held notify " + t.getClass().getSimpleName());
        }
    }

    private void notifyHoldFailed(String why) {
        ImsCallSessionListener l = listener;
        if (l == null) {
            return;
        }
        try {
            l.callSessionHoldFailed(new ImsReasonInfo(
                    ImsReasonInfo.CODE_UNSPECIFIED, -1,
                    why != null ? why : "hold failed"));
        } catch (Throwable t) {
            Log.w(TAG, "hold fail notify " + t.getClass().getSimpleName());
        }
    }

    private void notifyResumed() {
        heldNotified = false;
        ImsCallSessionListener l = listener;
        if (l == null) {
            return;
        }
        try {
            l.callSessionResumed(profile);
        } catch (Throwable t) {
            Log.w(TAG, "resumed notify " + t.getClass().getSimpleName());
        }
    }

    private void notifyResumeFailed(String why) {
        ImsCallSessionListener l = listener;
        if (l == null) {
            return;
        }
        try {
            l.callSessionResumeFailed(new ImsReasonInfo(
                    ImsReasonInfo.CODE_UNSPECIFIED, -1,
                    why != null ? why : "resume failed"));
        } catch (Throwable t) {
            Log.w(TAG, "resume fail notify " + t.getClass().getSimpleName());
        }
    }

    private void notifyMergeComplete() {
        ImsCallSessionListener l = listener;
        if (l == null) {
            return;
        }
        try {
            l.callSessionMergeComplete(this);
        } catch (Throwable t) {
            Log.w(TAG, "merge notify " + t.getClass().getSimpleName());
        }
    }

    private void notifyMergeFailed(String why) {
        ImsCallSessionListener l = listener;
        if (l == null) {
            return;
        }
        try {
            l.callSessionMergeFailed(new ImsReasonInfo(
                    ImsReasonInfo.CODE_UNSPECIFIED, -1,
                    why != null ? why : "merge failed"));
        } catch (Throwable t) {
            Log.w(TAG, "merge fail notify " + t.getClass().getSimpleName());
        }
    }

    /**
     * Whether the answer gave us somewhere to send RTP. Checked before the
     * session is declared ACTIVE: JoanMedia.start() used to bridge audio to
     * the native daemon when there was no negotiated media, which produced
     * a call that looked connected and was silent. Failing is better.
     */
    private boolean hasNegotiatedMedia() {
        if (JoanSipUa.mediaIp() == null || JoanSipUa.mediaPort() <= 0) {
            JoanTrace.note("no negotiated media");
            return false;
        }
        return true;
    }

    /**
     * Start RTP, and end the call if the negotiated codec cannot be
     * carried. A call that stays up while carrying a codec nobody agreed
     * to is silent in both directions and reports nothing; ending it is
     * the honest outcome and the one the user can act on.
     */
    private void startMedia() {
        if (JoanMedia.startRtp(app, JoanSipUa.network(), JoanSipUa.localAddr(),
                JoanSipUa.mediaIp(), JoanSipUa.mediaPort(),
                JoanSipUa.mediaRtcpPort(), JoanSipUa.mediaMux(),
                JoanSipUa.mediaPt(), JoanSipUa.mediaAmrWideband(),
                JoanSipUa.mediaAmrBitrate(),
                JoanSipUa.mediaAmrOctetAligned(),
                JoanSipUa.mediaAmrMaxMode(),
                JoanSipUa.mediaTePt())) {
            return;
        }
        JoanTrace.note("media did not start; ending call");
        hangupAsync();
    }

    /**
     * Send one DTMF digit of a fixed length.
     *
     * <p>The framework hands us a character and, for the Message form, a
     * callback it expects when the tone has been queued. Both go through
     * the RTP telephone-event path: there is no in-band option here, and
     * writing tones into an AMR stream is the failure mode RFC 4733 exists
     * to avoid.
     */
    @Override
    public void sendDtmf(char c, Message result) {
        boolean ok = JoanMedia.sendDtmf(c, 0);
        JoanTrace.note("app sendDtmf '" + c + "' " + (ok ? "queued" : "refused"));
        if (result != null) {
            /* The framework only wants to know the request was taken; it
             * has no way to represent "the far end heard it". Answering
             * unconditionally keeps Telephony from waiting on a message
             * that would never arrive when no event type was negotiated. */
            result.sendToTarget();
        }
    }

    @Override
    public void startDtmf(char c) {
        boolean ok = JoanMedia.startDtmf(c);
        JoanTrace.note("app startDtmf '" + c + "' " + (ok ? "held" : "refused"));
    }

    @Override
    public void stopDtmf() {
        JoanMedia.stopDtmf();
    }

    /** ANBR directions, as the framework numbers them. */
    private static final int ANBR_UPLINK = 1;

    /**
     * The radio's bitrate recommendation for this call.
     *
     * <p>This is the adaptation mechanism VoLTE actually uses: the access
     * network tells the handset what it can carry, rather than waiting for
     * packets to be lost and inferred. Only the uplink direction is
     * actionable -- a downlink recommendation concerns what the network
     * sends us, and retuning our encoder for it would be answering the
     * wrong question.
     *
     * <p>The recommendation is mapped to the highest AMR mode that fits
     * inside it and then checked against the negotiated mode-set, which is
     * the same order AOSP applies in NotifyAnbrReceived().
     */
    @Override
    public void callSessionNotifyAnbr(int mediaType, int direction,
                                      int bitsPerSecond) {
        Boolean wb = JoanSipUa.mediaAmrWideband();
        JoanTrace.note("anbr media=" + mediaType + " dir=" + direction
                + " bps=" + bitsPerSecond
                + (wb == null ? " (not AMR; ignored)" : ""));
        if (wb == null || direction != ANBR_UPLINK || bitsPerSecond <= 0) {
            return;
        }
        int mode = JoanAmr.bitrateMode(bitsPerSecond, wb);
        if (mode < 0) {
            JoanTrace.note("anbr " + bitsPerSecond
                    + " bps is below the lowest mode; ignored");
            return;
        }
        JoanMedia.requestMode(mode, "ANBR");
    }

    private void failStart(String why) {
        state = STATE_TERMINATED;
        watchHangup = false;
        JoanMedia.stop();
        String reason = why == null ? "unknown" : why;
        if ("empty callee".equals(reason)
                || "not registered".equals(reason)
                || "invite failed".equals(reason)
                || "no negotiated media".equals(reason)) {
            JoanTrace.lastDial("start failed: " + reason);
        } else if (reason.startsWith("OK")) {
            JoanTrace.lastDial("start failed: unexpected OK");
        } else {
            JoanTrace.lastDial("start failed: invite refused");
        }
        Log.w(TAG, "call start failed: " + why);
        ImsCallSessionListener l = listener;
        if (l == null) {
            return;
        }
        try {
            l.callSessionInitiatingFailed(new ImsReasonInfo(
                    ImsReasonInfo.CODE_UNSPECIFIED, -1, why));
        } catch (Throwable t) {
            Log.w(TAG, "fail notify " + t.getClass().getSimpleName());
        }
    }
}
