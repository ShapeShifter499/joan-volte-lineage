package org.joan.ims;

import android.content.Context;
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

    JoanCallSession(Context app, JoanMmTelFeature feature, ImsCallProfile profile) {
        this(app, feature, profile, false);
    }

    private JoanCallSession(Context app, JoanMmTelFeature feature,
                            ImsCallProfile profile, boolean incoming) {
        this.app = app.getApplicationContext();
        this.feature = feature;
        this.profile = profile;
        this.incoming = incoming;
        this.callId = "joan-" + Long.toHexString(System.nanoTime());
    }

    static JoanCallSession incoming(Context app, JoanMmTelFeature feature,
                                    ImsCallProfile profile) {
        JoanCallSession s = new JoanCallSession(app, feature, profile, true);
        s.state = STATE_ESTABLISHING;
        return s;
    }

    /** The caller gave up, or the far end hung up. */
    void onRemoteEnded() {
        watchHangup = false;
        JoanMedia.stop();
        state = STATE_TERMINATED;
        notifyTerminated(0);
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
        JoanTrace.note("call session start");
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

    private void hangupAsync() {
        watchHangup = false;
        JoanMedia.stop();
        new Thread(JoanSipUa::hangup, "joan-ims-hangup").start();
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
                    if (JoanSipUa.callActive()) {
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

    private void startMedia() {
        JoanMedia.startRtp(app, JoanSipUa.network(), JoanSipUa.localAddr(),
                JoanSipUa.mediaIp(), JoanSipUa.mediaPort(),
                JoanSipUa.mediaMux());
    }

    private void failStart(String why) {
        state = STATE_TERMINATED;
        watchHangup = false;
        JoanMedia.stop();
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
