/* Compile-time stub for framework call-session callbacks. NOT packed. */
package android.telephony.ims;

public class ImsCallSessionListener {
    public void callSessionInitiating(ImsCallProfile profile) {}
    public void callSessionProgressing(ImsStreamMediaProfile profile) {}
    public void callSessionStarted(ImsCallProfile profile) {}
    public void callSessionStartFailed(ImsReasonInfo reason) {}
    public void callSessionTerminated(ImsReasonInfo reason) {}
}
