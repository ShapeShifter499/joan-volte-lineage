package org.joan.ims;

import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.ImsCallSessionListener;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsStreamMediaProfile;
import android.telephony.ims.stub.ImsCallSessionImplBase;
import android.util.Log;

/**
 * One IMS call session. MO: Dialer calls start() which issues CALL on ctl.
 * MT still auto-answers in the native UA until a reverse event channel exists.
 */
public class JoanCallSession extends ImsCallSessionImplBase {
    private static final String TAG = "JoanIms";
    private final ImsCallProfile profile;
    private volatile ImsCallSessionListener listener;
    private volatile int state = STATE_IDLE;
    private final String callId;

    JoanCallSession(ImsCallProfile profile) {
        this.profile = profile;
        this.callId = "joan-" + Long.toHexString(System.nanoTime());
    }

    @Override
    public void setListener(ImsCallSessionListener l) {
        listener = l;
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
        state = STATE_ESTABLISHING;
        ImsCallSessionListener l = listener;
        if (l != null) {
            try {
                l.callSessionInitiating(p != null ? p : profile);
            } catch (Throwable t) {
                Log.w(TAG, "initiating notify " + t.getClass().getSimpleName());
            }
        }
        final String uri;
        if (callee == null || callee.isEmpty()) {
            failStart("empty callee");
            return;
        }
        if (callee.startsWith("sip:") || callee.startsWith("tel:")) {
            uri = callee;
        } else {
            uri = "tel:" + callee;
        }
        new Thread(() -> {
            String resp = JoanCtl.txn("CALL " + uri);
            ImsCallSessionListener cb = listener;
            if (resp != null && resp.startsWith("OK")) {
                state = STATE_ESTABLISHED;
                if (cb != null) {
                    try {
                        cb.callSessionStarted(p != null ? p : profile);
                    } catch (Throwable t) {
                        Log.w(TAG, "started notify "
                                + t.getClass().getSimpleName());
                    }
                }
            } else {
                failStart(resp == null ? "ctl failed" : "call failed");
            }
        }, "joan-ims-call").start();
    }

    @Override
    public void accept(int callType, ImsStreamMediaProfile media) {
        Log.i(TAG, "call session accept (native auto-answers MT)");
        state = STATE_ESTABLISHED;
    }

    @Override
    public void reject(int reason) {
        JoanCtl.txn("HANGUP");
        state = STATE_TERMINATED;
    }

    @Override
    public void terminate(int reason) {
        JoanCtl.txn("HANGUP");
        state = STATE_TERMINATED;
        ImsCallSessionListener l = listener;
        if (l != null) {
            try {
                l.callSessionTerminated(new ImsReasonInfo(
                        ImsReasonInfo.CODE_USER_TERMINATED, reason, "hangup"));
            } catch (Throwable t) {
                Log.w(TAG, "term notify " + t.getClass().getSimpleName());
            }
        }
    }

    private void failStart(String why) {
        state = STATE_TERMINATED;
        Log.w(TAG, "call start failed: " + why);
        ImsCallSessionListener l = listener;
        if (l != null) {
            try {
                l.callSessionStartFailed(new ImsReasonInfo(
                        ImsReasonInfo.CODE_UNSPECIFIED, -1, why));
            } catch (Throwable t) {
                Log.w(TAG, "fail notify " + t.getClass().getSimpleName());
            }
        }
    }
}
