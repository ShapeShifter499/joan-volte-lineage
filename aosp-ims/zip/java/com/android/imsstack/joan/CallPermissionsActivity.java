/*
 * Copyright (C) 2026 The joan-volte-lineage authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.imsstack.joan;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/*
 * Asks for the runtime permissions calls need, on the phone, without adb.
 *
 * The zip's default-permissions file grants them on the first boot after a
 * ROM install or update. Flashed onto a ROM that has already booted, nothing
 * grants them, and without RECORD_AUDIO ImsMedia (in this package) cannot
 * open the microphone, and calls carry no audio. While the microphone is
 * missing, this activity is in the app drawer as "Calling permissions"; it
 * asks with Android's own dialogs and leaves the drawer once it is allowed.
 *
 * Nothing needs a restart: audio, the camera and location are checked when
 * a call opens them. IWLAN's IPsec app-op has no dialog; that one stays adb.
 */
public class CallPermissionsActivity extends Activity {
    private static final String TAG = "ImsStackPermissions";

    // The microphone first, the one calls cannot do without. Then video
    // calls, the location emergency calls send, and the phone state.
    // Background location is not asked: the stack's process is persistent,
    // which counts as in use.
    static final String[] WANTED = {
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_PHONE_STATE,
    };
    private static final int REQUEST = 1;

    /**
     * Puts this activity in the app drawer while the microphone is not
     * allowed, and takes it out once it is. Called when the stack starts.
     */
    public static void updateLauncherEntry(Context ctx) {
        boolean missing = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED;
        if (missing) {
            Log.w(TAG, "RECORD_AUDIO not granted: calls cannot open the microphone. Open"
                    + " \"Calling permissions\" in the app drawer, or run grant-permissions.sh");
        }
        showInLauncher(ctx, missing);
    }

    private static void showInLauncher(Context ctx, boolean shown) {
        ComponentName cn = new ComponentName(ctx, CallPermissionsActivity.class);
        // Out of the drawer is the manifest's default (android:enabled="false").
        int state = shown ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        try {
            PackageManager pm = ctx.getPackageManager();
            if (pm.getComponentEnabledSetting(cn) != state) {
                // DONT_KILL_APP: this is the persistent IMS process.
                pm.setComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "app drawer entry: " + e);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            // Recreated while the dialogs are up; their result comes here.
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String p : WANTED) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        if (missing.isEmpty()) {
            done();
            return;
        }
        requestPermissions(missing.toArray(new String[0]), REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        if (requestCode == REQUEST) {
            done();
        }
    }

    private void done() {
        String mic = Manifest.permission.RECORD_AUDIO;
        if (checkSelfPermission(mic) == PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "RECORD_AUDIO granted");
            showInLauncher(this, false);
            Toast.makeText(this, "Calls can use the microphone now.", Toast.LENGTH_LONG).show();
        } else if (!shouldShowRequestPermissionRationale(mic)) {
            // Denied for good ("Don't allow" twice): only Settings can allow it now.
            Toast.makeText(this, "Allow Microphone under Permissions: calls need it.",
                    Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null)));
            } catch (RuntimeException e) {
                Log.w(TAG, "app settings: " + e);
            }
        } else {
            Toast.makeText(this, "Calls need the microphone. Open Calling permissions again"
                    + " to allow it.", Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
