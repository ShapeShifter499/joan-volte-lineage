/* Compile-time API stubs for classes hidden from public SDK. Device
 * framework provides the real ones at runtime; javac only needs matching
 * shapes. Generated against AOSP main sources fetched 2026-08-27.
 * NEVER packed into the APK (javac -classpath only). */
package android.telephony.ims;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import android.telephony.ims.feature.MmTelFeature;
import android.telephony.ims.stub.ImsRegistrationImplBase;

/** Shape-compatible with AOSP ImsService for compile-time resolution. */
public class ImsService extends Service {
    public static final String SERVICE_INTERFACE =
            "android.telephony.ims.ImsService";

    public MmTelFeature createMmTelFeature(int slotId) {
        return null;
    }

    public MmTelFeature createMmTelFeatureForSubscription(
            int slotId, int subscriptionId) {
        return null;
    }

    public ImsRegistrationImplBase getRegistration(int slotId) {
        return null;
    }

    public void enableIms(int slotId) {}

    public void disableIms(int myslotId) {}

    public IBinder onBind(Intent i) {
        return null;
    }
}
