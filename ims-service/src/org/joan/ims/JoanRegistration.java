package org.joan.ims;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Executor;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.stub.ImsRegistrationImplBase;

/**
 * Registration state surfaced to the telephony framework/Dialer.
 * Driven by JoanDriver on each REGISTER outcome. Single instance.
 * Uses the public (Executor) super ctor; REGISTRATION_TECH_LTE=0.
 */
public final class JoanRegistration {
    private static volatile boolean sRegistered;
    private static volatile String sPcscf;
    private static volatile Impl sImpl;
    private static final Executor MAIN = new HandlerExecutor();

    private JoanRegistration() {}

    static synchronized void setRegistered(boolean reg, String pcscf) {
        boolean was = sRegistered;
        sRegistered = reg;
        if (pcscf != null) {
            sPcscf = pcscf;
        }
        Impl impl = sImpl;
        if (impl != null && was != reg) {
            MAIN.execute(() -> impl.pushState(reg));
        }
    }

    static String getPcscf() {
        return sPcscf;
    }

    /** Singleton registration impl handed to the framework. */
    public static synchronized Impl get() {
        if (sImpl == null) {
            sImpl = new Impl();
        }
        return sImpl;
    }

    /** True once the native UA completed REGISTER (2xx). */
    public static boolean isRegistered() {
        return sRegistered;
    }

    private static volatile String sMcc;
    private static volatile String sMnc;

    /** Remember the operator the registration ran against, for the
     * carrier profile (conference focus URI and friends). */
    static void setOperator(String mcc, String mnc) {
        sMcc = mcc;
        sMnc = mnc;
    }

    static String mcc() {
        return sMcc;
    }

    static String mnc() {
        return sMnc;
    }

    public static final class Impl extends ImsRegistrationImplBase {
        private Impl() {
            super(MAIN);
        }

        void pushState(boolean reg) {
            try {
                if (reg) {
                    onRegistered(REGISTRATION_TECH_LTE);
                } else {
                    onDeregistered(new ImsReasonInfo(
                            ImsReasonInfo.CODE_REGISTRATION_ERROR,
                            -1, "joan ua not registered"),
                            0 /* SUGGESTED_ACTION_NONE */,
                            REGISTRATION_TECH_NONE);
                }
            } catch (Throwable t) {
                android.util.Log.w("JoanIms", "reg notify failed "
                        + t.getClass().getSimpleName());
            }
        }
    }
}
