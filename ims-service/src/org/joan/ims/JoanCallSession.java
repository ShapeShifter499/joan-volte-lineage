package org.joan.ims;

import android.content.Context;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.ImsCallSessionListener;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsStreamMediaProfile;
import android.telephony.ims.stub.ImsCallSessionImplBase;
import android.util.Log;

/**
 * One IMS call session. Callback sequence matches CAF
 * {@code org.codeaurora.ims.ImsCallSessionImpl}:
 *
 *   DIALING  -> callSessionProgressing(empty ImsStreamMediaProfile)
 *   ACTIVE   -> callSessionStarted(profile)
 *   END      -> callSessionTerminated
 *
 * Audio is not injected here. CAF leaves media to the modem; AOSP
 * ImsService docs leave in-call audio to the HAL after STARTED.
 */
public class JoanCallSession extends ImsCallSessionImplBase {
    private static final String TAG = "JoanIms";
    private final Context app;
    private final ImsCallProfile profile;
    private volatile ImsCallSessionListener listener;
    private volatile int state = STATE_IDLE;
    private final String callId;
    private volatile boolean watchHangup;

    JoanCallSession(Context app, ImsCallProfile profile) {
        this.app = app.getApplicationContext();
        this.profile = profile;
        this.callId = "joan-" + Long.toHexString(System.nanoTime());
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
            String resp = JoanCtl.txn("CALL " + uri);
            if (resp != null && resp.startsWith("OK")) {
                state = STATE_ESTABLISHED;
                notifyStarted(used);
                JoanMedia.start(this.app);
                watchRemoteHangup();
            } else {
                failStart(resp == null ? "ctl failed" : "call failed");
            }
        }, "joan-ims-call").start();
    }

    @Override
    public void accept(int callType, ImsStreamMediaProfile media) {
        Log.i(TAG, "call session accept");
        state = STATE_ESTABLISHED;
        notifyStarted(profile);
    }

    @Override
    public void reject(int reason) {
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
        new Thread(() -> JoanCtl.txn("HANGUP"), "joan-ims-hangup").start();
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
                    String st = JoanCtl.txn("STATUS");
                    if (st == null) {
                        continue;
                    }
                    if (st.contains("CALL=1")) {
                        seenUp = true;
                        continue;
                    }
                    if (seenUp) {
                        JoanTrace.note("remote hangup STATUS without CALL=1");
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
