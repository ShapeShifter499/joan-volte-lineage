package org.joan.ims;

import android.content.Context;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.feature.ImsFeature;
import android.telephony.ims.feature.MmTelFeature;
import android.telephony.ims.stub.ImsCallSessionImplBase;
import android.util.Log;

/**
 * MmTel feature: capability surface Dialer/telephony queries, plus MO
 * call sessions. Native UA still auto-answers MT until a reverse event
 * channel exists to ring the Dialer.
 */
public class JoanMmTelFeature extends MmTelFeature {
    private static final String TAG = "JoanIms";

    private final Context app;

    public JoanMmTelFeature(Context app) {
        super(app.getMainExecutor());
        this.app = app.getApplicationContext();
        // Framework should call onFeatureReady(); also post READY in case
        // the listener attach races FeatureConnector's first status read.
        app.getMainExecutor().execute(this::markReady);
    }

    @Override
    public void changeEnabledCapabilities(
            CapabilityChangeRequest request, CapabilityCallbackProxy c) {
        Log.i(TAG, "changeEnabledCapabilities");
        JoanDriver.start(app);
        markReady();
    }

    @Override
    public void onFeatureReady() {
        Log.i(TAG, "onFeatureReady");
        JoanTrace.note("onFeatureReady");
        JoanDriver.start(app);
        markReady();
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
        JoanDriver.start(app);
        return new JoanCallSession(profile);
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
