package org.joan.ims;

import android.content.Context;
import android.os.Bundle;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.feature.ImsFeature;
import android.telephony.ims.feature.MmTelFeature;
import android.telephony.ims.stub.ImsCallSessionImplBase;
import android.util.Log;

/**
 * MmTel feature: the capability surface Dialer and telephony query, plus
 * call sessions in both directions.
 *
 * Inbound calls arrive as a pushed event from the daemon (see JoanEvents),
 * which holds the INVITE at 180 Ringing until the user answers or declines.
 */
public class JoanMmTelFeature extends MmTelFeature {
    private static final String TAG = "JoanIms";

    private final Context app;

    public JoanMmTelFeature(Context app) {
        super(app.getMainExecutor());
        this.app = app.getApplicationContext();
        /* Publish and attach here rather than in a callback: which of
         * onFeatureReady / changeEnabledCapabilities the framework calls,
         * and when, varies. An inbound call that arrives before we are
         * attached cannot ring, so attach as early as we exist. */
        sInstance = this;
        JoanEvents.start(this.app);
        // Framework should call onFeatureReady(); also post READY in case
        // the listener attach races FeatureConnector's first status read.
        app.getMainExecutor().execute(this::markReady);
    }

    @Override
    public void changeEnabledCapabilities(
            CapabilityChangeRequest request, CapabilityCallbackProxy c) {
        Log.i(TAG, "changeEnabledCapabilities");
        sInstance = this;
        JoanDriver.start(app);
        JoanEvents.start(app);
        markReady();
    }

    @Override
    public void onFeatureReady() {
        Log.i(TAG, "onFeatureReady");
        JoanTrace.note("onFeatureReady");
        sInstance = this;
        JoanDriver.start(app);
        JoanEvents.start(app);
        markReady();
    }

    /* The live feature, so a pushed event can reach Telecom. There is one
     * MmTelFeature per subscription and joan is single-SIM. */
    private static volatile JoanMmTelFeature sInstance;
    private static volatile JoanCallSession sIncoming;

    /**
     * An inbound INVITE is being held by the daemon while we ring.
     * Build a session for it and hand it to Telecom.
     */
    static void onIncomingCall(Context ctx, String callerUri,
                               String callerName) {
        JoanMmTelFeature f = sInstance;
        if (f == null) {
            Log.w(TAG, "incoming call but no MmTelFeature; cannot ring");
            JoanTrace.note("incoming with no feature");
            new Thread(() -> JoanCtl.txn("REJECT 486"),
                    "joan-ims-noferature-reject").start();
            return;
        }
        try {
            ImsCallProfile p = new ImsCallProfile(
                    ImsCallProfile.SERVICE_TYPE_NORMAL,
                    ImsCallProfile.CALL_TYPE_VOICE);
            applyCallerId(p, callerUri, callerName);
            JoanCallSession s = JoanCallSession.incoming(ctx, f, p);
            sIncoming = s;
            Bundle extras = new Bundle();
            f.notifyIncoming(s, extras);
            Log.i(TAG, "notifyIncomingCall delivered");
            JoanTrace.note("incoming call -> dialer");
        } catch (Throwable t) {
            Log.w(TAG, "notifyIncomingCall failed", t);
            JoanTrace.note("incoming failed " + t.getClass().getSimpleName());
            new Thread(() -> JoanCtl.txn("REJECT 486"),
                    "joan-ims-fail-reject").start();
        }
    }

    /* Telecom reads the calling party from the profile extras. Without
     * these the dialer has nothing to show and the call reads "unknown".
     * The names are the framework's own ImsCallProfile keys; they are
     * spelled out rather than referenced so this compiles against the
     * trimmed stub. */
    private static final String EXTRA_OI = "oi";     /* originating number */
    private static final String EXTRA_CNA = "cna";   /* originating name */
    private static final String EXTRA_OIR = "oir";   /* presentation */
    private static final int OIR_PRESENTATION_NOT_RESTRICTED = 2;
    private static final int OIR_PRESENTATION_RESTRICTED = 1;

    private static void applyCallerId(ImsCallProfile p, String uri,
                                      String name) {
        String number = dialableFrom(uri);
        String cna = (name == null) ? "" : name.trim();
        if (cna.length() > 80) {
            cna = cna.substring(0, 80);
        }
        if (number == null || number.isEmpty()) {
            /* Withheld, or unparseable. Say so explicitly rather than
             * leaving the field unset. A name without a number is still
             * worth showing if the network supplied one. */
            p.setCallExtraInt(EXTRA_OIR, OIR_PRESENTATION_RESTRICTED);
            if (!cna.isEmpty()) {
                p.setCallExtra(EXTRA_CNA, cna);
            }
            return;
        }
        p.setCallExtra(EXTRA_OI, number);
        p.setCallExtra(EXTRA_CNA, cna);
        p.setCallExtraInt(EXTRA_OIR, OIR_PRESENTATION_NOT_RESTRICTED);
    }

    /**
     * "tel:+15551234567" or "sip:+15551234567@ims.mnc260..." -> the number.
     * Anything else is passed through so an alphanumeric caller still
     * shows something rather than nothing.
     */
    private static String dialableFrom(String uri) {
        if (uri == null) {
            return null;
        }
        String u = uri.trim();
        if (u.isEmpty()) {
            return null;
        }
        if (u.startsWith("tel:")) {
            u = u.substring(4);
        } else if (u.startsWith("sip:") || u.startsWith("sips:")) {
            u = u.substring(u.indexOf(':') + 1);
        }
        int at = u.indexOf('@');
        if (at > 0) {
            u = u.substring(0, at);
        }
        int semi = u.indexOf(';');
        if (semi > 0) {
            u = u.substring(0, semi);
        }
        return u.isEmpty() ? null : u;
    }

    /** Caller gave up, or the far end hung up. */
    static void onCallEndedRemotely() {
        JoanCallSession s = sIncoming;
        sIncoming = null;
        if (s != null) {
            s.onRemoteEnded();
        }
    }

    /**
     * notifyIncomingCall has gained a call-id parameter in some releases.
     * Bind it reflectively so the app works either way rather than dying
     * with NoSuchMethodError on a device whose framework differs from the
     * stub this was compiled against.
     */
    private void notifyIncoming(JoanCallSession s, Bundle extras)
            throws Exception {
        try {
            java.lang.reflect.Method m = getClass().getMethod(
                    "notifyIncomingCall", ImsCallSessionImplBase.class,
                    String.class, Bundle.class);
            m.invoke(this, s, s.getCallId(), extras);
            return;
        } catch (NoSuchMethodException ignored) {
            // older shape below
        }
        java.lang.reflect.Method m = getClass().getMethod(
                "notifyIncomingCall", ImsCallSessionImplBase.class,
                Bundle.class);
        m.invoke(this, s, extras);
    }

    @Override
    public boolean queryCapabilityConfiguration(int capability, int radioTech) {
        return capability == MmTelCapabilities.CAPABILITY_TYPE_VOICE;
    }

    @Override
    public ImsCallProfile createCallProfile(int callSessionType, int callType) {
        return new ImsCallProfile(callSessionType, callType);
    }

    @Override
    public ImsCallSessionImplBase createCallSession(ImsCallProfile profile) {
        Log.i(TAG, "createCallSession");
        return new JoanCallSession(app, this, profile);
    }

    /**
     * AOSP MmTelFeature#setCallAudioHandler: AUDIO_HANDLER_ANDROID
     * makes Telephony Connection audioModeIsVoip=true, which Telecom
     * turns into MODE_IN_COMMUNICATION instead of MODE_IN_CALL (radio
     * mixer). Call after the session is ACTIVE so ImsPhoneCallTracker
     * can find the Connection.
     */
    void useAndroidAudioHandler() {
        try {
            setCallAudioHandler(AUDIO_HANDLER_ANDROID);
            JoanTrace.note("audio handler ANDROID");
            Log.i(TAG, "setCallAudioHandler ANDROID");
        } catch (Throwable t) {
            JoanTrace.note("audio handler " + t.getClass().getSimpleName());
            Log.w(TAG, "setCallAudioHandler", t);
        }
    }

    @Override
    public int shouldProcessCall(String[] numbers) {
        if (JoanRegistration.isRegistered()) {
            return PROCESS_CALL_IMS;
        }
        return PROCESS_CALL_CSFB;
    }

    private void markReady() {
        try {
            setFeatureState(ImsFeature.STATE_READY);
        } catch (Throwable t) {
            Log.w(TAG, "setFeatureState failed "
                    + t.getClass().getSimpleName());
        }
        try {
            notifyCapabilitiesStatusChanged(new MmTelCapabilities(
                    MmTelCapabilities.CAPABILITY_TYPE_VOICE));
        } catch (Throwable t) {
            Log.w(TAG, "cap notify failed "
                    + t.getClass().getSimpleName());
        }
    }
}
