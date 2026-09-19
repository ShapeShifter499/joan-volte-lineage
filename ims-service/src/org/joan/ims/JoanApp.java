package org.joan.ims;

import android.app.Application;
import android.util.Log;

/** Process entrypoint for the persistent system app. Starts the IMS
 * registration driver as soon as the package process comes up; the
 * framework ImsService/MmTelFeature binder path can then observe state
 * through JoanRegistration when it asks.
 */
public class JoanApp extends Application {
    private static final String TAG = "JoanIms";

    @Override
    public void onCreate() {
        super.onCreate();
        JoanTrace.init(getApplicationContext());
        JoanTrace.note("Application onCreate; starting driver");
        /* What we offer has to be what this ROM can run, not what the
         * source implements: a ROM update can drop or add a platform
         * codec without this app changing at all. */
        java.util.Set<String> amr = JoanAmrCodec.availableAmr();
        JoanSipBuilder.restrictProfile(amr);
        /* Our own versionName, for the User-Agent. Read here because
         * JoanSipBuilder compiles without android.jar, and read through
         * JoanTrace because PackageManager's record lags a system-app
         * replacement -- reading it directly is what made an alpha55
         * handset introduce itself to a carrier as alpha49. */
        try {
            String v = JoanTrace.readVersionName(getApplicationContext());
            if (v != null) {
                JoanSipBuilder.setUserAgentVersion(v);
            }
        } catch (Throwable t) {
            /* No version is not a reason to fail startup. */
        }
        JoanTrace.note("codec profile: " + JoanSipBuilder.profileSummary());
        JoanDriver.start(getApplicationContext());
    }
}
