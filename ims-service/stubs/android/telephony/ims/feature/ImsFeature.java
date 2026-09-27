/* Compile-time stub mirroring AOSP ImsFeature (2026-08-27).
 * NOT packed into the APK. */
package android.telephony.ims.feature;

import java.util.concurrent.Executor;

public abstract class ImsFeature {
    public static final int FEATURE_EMERGENCY_MMTEL = 0;
    public static final int FEATURE_MMTEL = 1;
    public static final int FEATURE_RCS = 2;

    public static final int STATE_UNAVAILABLE = 0;
    public static final int STATE_INITIALIZING = 1;
    public static final int STATE_READY = 2;

    public static class Capabilities {
        protected int mCapabilities;

        public Capabilities() {}

        public Capabilities(int caps) {
            mCapabilities = caps;
        }

        public boolean isCapable(int capabilities) {
            return (mCapabilities & capabilities) > 0;
        }
    }

    public static class CapabilityChangeRequest {}

    public static class CapabilityCallbackProxy {
        CapabilityCallbackProxy() {}
    }

    public abstract void changeEnabledCapabilities(
            CapabilityChangeRequest request, CapabilityCallbackProxy c);

    public void setFeatureState(int state) {}

    public int getFeatureState() {
        return STATE_UNAVAILABLE;
    }
}
