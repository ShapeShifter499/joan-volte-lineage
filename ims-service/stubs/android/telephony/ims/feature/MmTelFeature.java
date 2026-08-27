/* Compile-time stub mirroring AOSP MmTelFeature surface (2026-08-27).
 * Runtime class comes from the device framework.
 * NOT packed into the APK. */
package android.telephony.ims.feature;

public abstract class MmTelFeature extends ImsFeature {
    public static class MmTelCapabilities {
        public static final int CAPABILITY_TYPE_VOICE = 1 << 0;
        public static final int CAPABILITY_TYPE_VIDEO = 1 << 1;
        public static final int CAPABILITY_TYPE_UT = 1 << 2;
        public static final int CAPABILITY_TYPE_SMS = 1 << 3;

        protected final int mCapabilities;

        public MmTelCapabilities() {
            mCapabilities = 0;
        }

        public MmTelCapabilities(int capabilities) {
            mCapabilities = capabilities;
        }
    }

    /** From ImsFeature (stubbed shape here): request from framework. */
    @Override
    public void changeEnabledCapabilities(
            CapabilityChangeRequest request, CapabilityCallbackProxy c) {}

    public void onFeatureReady() {}

    public void onFeatureRemoved() {}

    /** Framework-facing status update (mirrors final wrapper). */
    protected final void notifyCapabilitiesStatusChanged(
            MmTelCapabilities c) {
        // no-op in compile stub
    }

    public MmTelFeature() {}
}
