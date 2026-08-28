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
        JoanDriver.start(getApplicationContext());
    }
}
