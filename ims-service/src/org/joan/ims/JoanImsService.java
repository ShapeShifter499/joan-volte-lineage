package org.joan.ims;

import android.util.Log;

import android.telephony.ims.ImsFeatureConfiguration;
import android.telephony.ims.feature.ImsFeature;

/**
 * Framework ImsService: the binder entry telephony binds to (manifest
 * action android.telephony.ims.ImsService). Binding starts the ctl driver;
 * registration state flows through JoanRegistration.
 *
 * querySupportedImsFeatures() is required on Android 12+: the default
 * implementation returns an empty set, which left MmTel UNAVAILABLE and
 * Dialer on CS even though the native UA was REGISTERED.
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
    public ImsFeatureConfiguration querySupportedImsFeatures() {
        Log.i(TAG, "querySupportedImsFeatures MMTEL");
        JoanTrace.note("querySupportedImsFeatures MMTEL");
        return new ImsFeatureConfiguration.Builder()
                .addFeature(0, ImsFeature.FEATURE_EMERGENCY_MMTEL)
                .addFeature(0, ImsFeature.FEATURE_MMTEL)
                .build();
    }

    @Override
    public android.telephony.ims.feature.MmTelFeature createMmTelFeatureForSubscription(
            int slotId, int subscriptionId) {
        Log.i(TAG, "createMmTelFeature slot=" + slotId + " sub="
                + subscriptionId);
        JoanTrace.note("createMmTelFeature slot=" + slotId + " sub="
                + subscriptionId);
        JoanDriver.start(getApplicationContext());
        return new JoanMmTelFeature(getApplicationContext());
    }

    @Override
    public android.telephony.ims.feature.MmTelFeature createMmTelFeature(
            int slotId) {
        Log.i(TAG, "createMmTelFeature legacy slot=" + slotId);
        JoanTrace.note("createMmTelFeature legacy slot=" + slotId);
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
