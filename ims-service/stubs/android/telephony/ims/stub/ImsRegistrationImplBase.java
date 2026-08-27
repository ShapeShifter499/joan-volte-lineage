/* Compile-time stub mirroring AOSP ImsRegistrationImplBase (2026-08-27).
 * Real: REGISTRATION_TECH_LTE=0; no-arg ctor package-private, so concrete
 * classes MUST use the (Executor) super call. NOT packed into the APK. */
package android.telephony.ims.stub;

import java.util.concurrent.Executor;

public class ImsRegistrationImplBase {
    public static final int REGISTRATION_TECH_NONE = -1;
    public static final int REGISTRATION_TECH_LTE = 0;
    public static final int REGISTRATION_TECH_IWLAN = 1;
    public static final int REGISTRATION_TECH_CROSS_SIM = 2;
    public static final int REGISTRATION_TECH_NR = 3;
    public static final int REGISTRATION_TECH_3G = 4;

    public ImsRegistrationImplBase(Executor executor) {}

    public void onRegistered(int imsRadioTech) {}

    public void onRegistering(int imsRadioTech) {}

    public void onDeregistered(
            android.telephony.ims.ImsReasonInfo info,
            int suggestedAction, android.net.Uri[] uris) {}

    public void onSubscriberAssociatedUriChanged(android.net.Uri[] uris) {}

    public void onTechnologyProcessFailed(
            android.telephony.ims.ImsReasonInfo info, int radioTech) {}
}
