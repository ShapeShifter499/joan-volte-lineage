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
package com.android.imsstack;

import android.app.Application;
import android.util.Log;

import com.android.imsstack.joan.CarrierImsGate;
import com.android.telephony.imsmedia.ImsMediaApplication;
import com.android.telephony.imsmedia.WakeLockManager;

/**
 * Application class of the single-APK build used by the flashable zip.
 *
 * Upstream ships ImsMedia as its own app under android.uid.phone, which only
 * the ROM's platform key can join, so the zip folds the ImsMedia service into
 * this package and runs it in its own process, as upstream does. An APK has one
 * Application class: in the ImsStack process this is ImsStackApp, which starts
 * the IMS stack; in the media process it does what ImsMediaApplication does
 * instead, and must not start the stack.
 */
public class ImsStackZipApp extends ImsStackApp {
    static final String MEDIA_PROCESS = "com.android.telephony.imsmedia";

    @Override
    public void onCreate() {
        if (!MEDIA_PROCESS.equals(Application.getProcessName())) {
            super.onCreate();
            // LineageOS on joan ships no carrier config for most carriers,
            // and AOSP defaults carrier_volte_available_bool to false.
            CarrierImsGate.start(this);
            return;
        }
        ImsMediaApplication.setAppContext(getApplicationContext());
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            Log.e("ImsMediaApplication", "UncaughtException. Releasing all wakelocks.", e);
            WakeLockManager wakeLockManager = WakeLockManager.getInstance();
            if (wakeLockManager != null) {
                wakeLockManager.cleanup();
            }
        });
    }
}
