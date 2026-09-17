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
         * JoanSipBuilder compiles without android.jar. */
        try {
            android.content.pm.PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            JoanSipBuilder.setUserAgentVersion(pi.versionName);
        } catch (Throwable t) {
            /* No version is not a reason to fail startup. */
        }
        JoanTrace.note("codec profile: " + JoanSipBuilder.profileSummary());
        JoanDriver.start(getApplicationContext());
    }
}
