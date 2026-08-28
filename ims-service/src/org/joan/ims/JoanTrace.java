package org.joan.ims;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Non-secret local trace for bring-up when logcat is flooded. */
final class JoanTrace {
    private static final String TAG = "JoanIms";
    private static final Object LOCK = new Object();
    private static File sFile;
    private static volatile String sLastAkaStage = "";

    private JoanTrace() {}

    static void init(Context ctx) {
        synchronized (LOCK) {
            if (sFile != null) {
                return;
            }
            try {
                Context de = ctx.createDeviceProtectedStorageContext();
                sFile = new File(de.getFilesDir(), "joan-trace.log");
                note("trace init");
            } catch (Throwable t) {
                Log.w(TAG, "trace init failed: " + t.getClass().getSimpleName());
            }
        }
    }

    static void note(String msg) {
        Log.i(TAG, msg);
        synchronized (LOCK) {
            if (sFile == null) {
                return;
            }
            try {
                String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",
                        Locale.US).format(new Date());
                FileWriter fw = new FileWriter(sFile, true);
                fw.write(ts + " " + msg + "\n");
                fw.close();
            } catch (Throwable t) {
                Log.w(TAG, "trace write failed: " + t.getClass().getSimpleName());
            }
        }
    }

    static String akaStage() {
        return sLastAkaStage;
    }

    static void akaStage(String stage) {
        sLastAkaStage = stage == null ? "" : stage;
        note("AKA/REG stage: " + sLastAkaStage);
    }
}
