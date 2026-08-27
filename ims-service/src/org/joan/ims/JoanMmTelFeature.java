package org.joan.ims;

import android.content.Context;
import android.telephony.ims.feature.ImsFeature;
import android.telephony.ims.feature.MmTelFeature;
import android.util.Log;

/**
 * MmTel feature: the capability surface Dialer/telephony queries.
 * Starts the ctl driver at bind; voice capability lights up once the
 * native UA reaches REGISTERED. Call-session hooks (MO/MT INVITE) land
 * here next.
 */
public class JoanMmTelFeature extends MmTelFeature {
    private static final String TAG = "JoanIms";

    private final Context app;

    public JoanMmTelFeature(Context app) {
        this.app = app.getApplicationContext();
    }

    @Override
    public void changeEnabledCapabilities(
            CapabilityChangeRequest request, CapabilityCallbackProxy c) {
        Log.i(TAG, "changeEnabledCapabilities");
        JoanDriver.start(app);
        // Acknowledge VOICE enable requests; status change follows
        // from the driver once REGISTER completes.
    }

    @Override
    public void onFeatureReady() {
        Log.i(TAG, "onFeatureReady");
        JoanDriver.start(app);
        setFeatureState(ImsFeature.STATE_READY);
        notifyRegisteredCapability();
    }

    private void notifyRegisteredCapability() {
        try {
            notifyCapabilitiesStatusChanged(new MmTelCapabilities(
                    MmTelCapabilities.CAPABILITY_TYPE_VOICE));
        } catch (Throwable t) {
            Log.w(TAG, "cap notify failed "
                    + t.getClass().getSimpleName());
        }
    }
}
