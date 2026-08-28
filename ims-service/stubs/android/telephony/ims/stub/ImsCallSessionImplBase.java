/* Compile-time stub mirroring AOSP ImsCallSessionImplBase. NOT packed. */
package android.telephony.ims.stub;

import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.ImsCallSessionListener;
import android.telephony.ims.ImsStreamMediaProfile;

import java.util.concurrent.Executor;

public class ImsCallSessionImplBase {
    public static final int STATE_IDLE = 0;
    public static final int STATE_INITIATED = 1;
    public static final int STATE_NEGOTIATING = 2;
    public static final int STATE_ESTABLISHING = 3;
    public static final int STATE_ESTABLISHED = 4;
    public static final int STATE_TERMINATED = 8;

    public ImsCallSessionImplBase() {}

    public ImsCallSessionImplBase(Executor executor) {}

    public void setListener(ImsCallSessionListener listener) {}

    public void setDefaultExecutor(Executor executor) {}

    public String getCallId() { return null; }

    public ImsCallProfile getCallProfile() { return null; }

    public void start(String callee, ImsCallProfile profile) {}

    public void accept(int callType, ImsStreamMediaProfile profile) {}

    public void reject(int reason) {}

    public void terminate(int reason) {}

    public void close() {}

    public int getState() { return STATE_IDLE; }
}
