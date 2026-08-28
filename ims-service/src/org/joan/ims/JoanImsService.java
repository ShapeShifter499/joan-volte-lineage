package org.joan.ims;

import android.util.Log;

/**
 * Framework ImsService: the binder entry telephony binds to (manifest
 * action android.telephony.ims.ImsService). Binding starts the ctl driver;
 * registration state flows through JoanRegistration.
 */
public class JoanImsService extends android.telephony.ims.ImsService {
    private static final String TAG = "JoanIms";

    @Override
    public void onCreate() {
        super.onCreate();
        JoanTrace.init(getApplicationContext());
        JoanTrace.note("ImsService onCreate; starting driver");
        JoanDriver.start(getApplicationContext());
    }

    @Override
    public android.telephony.ims.feature.MmTelFeature createMmTelFeatureForSubscription(
            int slotId, int subscriptionId) {
        Log.i(TAG, "createMmTelFeature slot=" + slotId + " sub="
                + subscriptionId);
        JoanDriver.start(getApplicationContext());
        return new JoanMmTelFeature(getApplicationContext());
    }

    @Override
    public android.telephony.ims.feature.MmTelFeature createMmTelFeature(
            int slotId) {
        Log.i(TAG, "createMmTelFeature legacy slot=" + slotId);
        JoanDriver.start(getApplicationContext());
        return new JoanMmTelFeature(getApplicationContext());
    }

    @Override
    public android.telephony.ims.stub.ImsRegistrationImplBase getRegistration(
            int slotId) {
        Log.i(TAG, "getRegistration slot=" + slotId);
        return JoanRegistration.get();
    }
}
