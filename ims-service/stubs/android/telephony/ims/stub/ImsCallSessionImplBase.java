/* Compile-time stub mirroring AOSP ImsCallSessionImplBase. NOT packed. */
package android.telephony.ims.stub;

import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.ImsCallSessionListener;
import android.telephony.ims.ImsStreamMediaProfile;

import java.util.concurrent.Executor;

public class ImsCallSessionImplBase {
    /* Access Network Bitrate Recommendation, delivered by the framework
     * from the radio (Android 14+, present on this device's Android 15
     * framework -- verified by dex symbol). Declared here only so the
     * override compiles; the real base class provides the behaviour, and
     * on a framework without it the override is simply never called. */
    public void callSessionNotifyAnbr(int mediaType, int direction,
                                      int bitsPerSecond) {}

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

    public void hold(ImsStreamMediaProfile profile) {}

    public void resume(ImsStreamMediaProfile profile) {}

    /* LOS 22.2 / AOSP binder surface: merge() takes NO argument. */
    public void merge() {}
    public boolean isMultiparty() { return false; }

    public void close() {}

    public int getState() { return STATE_IDLE; }
}
