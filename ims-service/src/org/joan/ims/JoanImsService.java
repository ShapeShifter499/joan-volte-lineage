package org.joan.ims;

import android.util.Log;

/**
 * Framework ImsService: the binder entry telephony binds to (manifest
 * action android.telephony.ims.ImsService). Feature creation starts the
 * ctl driver; registration state flows through JoanRegistration.
 */
public class JoanImsService extends android.telephony.ims.ImsService {
    private static final String TAG = "JoanIms";

    @Override
    public android.telephony.ims.feature.MmTelFeature createMmTelFeatureForSubscription(
            int slotId, int subscriptionId) {
        Log.i(TAG, "createMmTelFeature slot=" + slotId + " sub="
                + subscriptionId);
        return new JoanMmTelFeature(getApplicationContext());
    }

    @Override
    public android.telephony.ims.stub.ImsRegistrationImplBase getRegistration(
            int slotId) {
        return JoanRegistration.get();
    }
}
