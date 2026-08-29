/* Compile-time stub mirroring AOSP MmTelFeature surface (2026-08-27).
 * Runtime class comes from the device framework.
 * NOT packed into the APK. */
package android.telephony.ims.feature;

import android.os.Bundle;
import java.util.concurrent.Executor;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.stub.ImsCallSessionImplBase;

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

    public boolean queryCapabilityConfiguration(int capability, int radioTech) {
        return false;
    }

    public void onFeatureRemoved() {}

    /** Framework-facing status update (mirrors final wrapper). */
    protected final void notifyCapabilitiesStatusChanged(
            MmTelCapabilities c) {
        // no-op in compile stub
    }


    public static final int PROCESS_CALL_IMS = 0;
    public static final int PROCESS_CALL_CSFB = 1;

    /** AOSP: Android (AP) handles IMS call audio, not the modem. */
    public static final int AUDIO_HANDLER_ANDROID = 0;
    public static final int AUDIO_HANDLER_BASEBAND = 1;

    /**
     * Runtime (framework) tells Telephony/Telecom to use VoIP audio
     * mode when {@code AUDIO_HANDLER_ANDROID}. Compile stub is a no-op.
     */
    public final void setCallAudioHandler(int imsAudioHandler) {}

    public ImsCallProfile createCallProfile(int callSessionType, int callType) {
        return null;
    }

    public ImsCallSessionImplBase createCallSession(ImsCallProfile profile) {
        return null;
    }

    public int shouldProcessCall(String[] numbers) {
        return PROCESS_CALL_CSFB;
    }

    public final void notifyIncomingCall(ImsCallSessionImplBase c, Bundle extras) {}

    public MmTelFeature() {}

    public MmTelFeature(Executor executor) { super(); }
}
