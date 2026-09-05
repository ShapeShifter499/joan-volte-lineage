/* Compile-time stub for AOSP ImsCallProfile (SystemApi, not in public SDK).
 * NOT packed into the APK. */
package android.telephony.ims;

public class ImsCallProfile {
    public static final int SERVICE_TYPE_NONE = 0;
    public static final int SERVICE_TYPE_NORMAL = 1;
    public static final int SERVICE_TYPE_EMERGENCY = 2;
    public static final int SERVICE_TYPE_CONFERENCE = 3;
    public static final int CALL_TYPE_VOICE = 2;
    public static final int CALL_TYPE_VT = 4;

    private int serviceType;

    public ImsCallProfile() {}

    public ImsCallProfile(int serviceType, int callType) {
        this.serviceType = serviceType;
    }

    public int getServiceType() {
        return serviceType;
    }

    public void setCallExtra(String name, String value) {}

    /* Caller-ID presentation is carried as an int extra (oir). */
    public void setCallExtraInt(String name, int value) {}

    public String getCallExtra(String name) { return null; }
}
