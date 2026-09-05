/* Compile-time stub for framework call-session callbacks. NOT packed. */
package android.telephony.ims;

import org.joan.ims.JoanConfState;

public class ImsCallSessionListener {
    public void callSessionInitiating(ImsCallProfile profile) {}
    public void callSessionProgressing(ImsStreamMediaProfile profile) {}
    /* AOSP name for ACTIVE. CAF's older IImsCallSession used callSessionStarted. */
    public void callSessionInitiated(ImsCallProfile profile) {}
    public void callSessionInitiatingFailed(ImsReasonInfo reason) {}
    public void callSessionTerminated(ImsReasonInfo reason) {}
    public void callSessionHeld(ImsCallProfile profile) {}
    public void callSessionHoldFailed(ImsReasonInfo reason) {}
    public void callSessionResumed(ImsCallProfile profile) {}
    public void callSessionResumeFailed(ImsReasonInfo reason) {}
    public void callSessionMergeStarted(ImsCallProfile profile,
            JoanConfState conference) {}
    public void callSessionMergeComplete(ImsCallProfile profile,
            JoanConfState conference) {}
    public void callSessionMergeFailed(ImsReasonInfo reason) {}
    public void callSessionConferenceStateUpdated(JoanConfState state) {}
}
