/* Compile-time stub for AOSP ImsCallProfile (SystemApi, not in public SDK).
 * NOT packed into the APK. */
package android.telephony.ims;

public class ImsCallProfile {
    public static final int SERVICE_TYPE_NONE = 0;
    public static final int SERVICE_TYPE_NORMAL = 1;
    public static final int SERVICE_TYPE_EMERGENCY = 2;
    public static final int CALL_TYPE_VOICE = 2;
    public static final int CALL_TYPE_VT = 4;

    public ImsCallProfile() {}

    public ImsCallProfile(int serviceType, int callType) {}

    public void setCallExtra(String name, String value) {}

    public String getCallExtra(String name) { return null; }
}
