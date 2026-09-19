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
    private static int sWrites;
    /** Truncate past this; the log is a bring-up aid, not an archive. */
    private static final long MAX_BYTES = 256 * 1024;
    private static volatile String sLastAkaStage = "";
    private static volatile String sLastDial = "";
    private static volatile String sLastBuild = "unknown";
    private static volatile String sLastNetwork = "";
    private static volatile String sLastData = "";
    private static volatile String sLastAttempt = "";

    private JoanTrace() {}

    static void init(Context ctx) {
        // The capture shares this lifecycle deliberately: it is written
        // from the register path, which can run without the provider ever
        // being queried, and a capture file that was never opened is
        // indistinguishable from a registration that never happened.
        JoanSipCapture.init(ctx);
        synchronized (LOCK) {
            if (sFile != null) {
                return;
            }
            try {
                Context de = ctx.createDeviceProtectedStorageContext();
                sFile = new File(de.getFilesDir(), "joan-trace.log");
                sLastBuild = readBuild(ctx);
                note("trace init build=" + sLastBuild);
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
                /* Unbounded growth was a real finding: 393 KB and climbing
                 * on the test handset, in device-protected storage, with
                 * no rotation. Check occasionally rather than every line. */
                remember(msg);
                boolean fresh = false;
                if (++sWrites % 64 == 0 && sFile.length() > MAX_BYTES) {
                    fresh = true;
                }
                if (fresh) {
                    /* Rotate rather than discard. Truncation threw away
                     * everything that led up to whatever is being
                     * investigated -- on 2026-09-16 it ate an inbound call
                     * one second before the outbound call that survived,
                     * while both were being chased. The previous file is
                     * the half a tester most often needs; one extra 256 KB
                     * in device-protected storage is a cheap trade. */
                    File prev = new File(sFile.getParentFile(),
                            sFile.getName() + ".1");
                    if (prev.exists() && !prev.delete()) {
                        Log.w(TAG, "could not remove rotated trace");
                    }
                    if (!sFile.renameTo(prev)) {
                        Log.w(TAG, "could not rotate trace; truncating");
                    }
                }
                FileWriter fw = new FileWriter(sFile, !fresh);
                if (fresh) {
                    fw.write(ts + " trace rotated at " + MAX_BYTES
                            + " bytes build=" + sLastBuild
                            + "; previous in " + sFile.getName() + ".1\n");
                    writeRemembered(fw, ts);
                }
                fw.write(ts + " " + msg + "\n");
                fw.close();
            } catch (Throwable t) {
                Log.w(TAG, "trace write failed: " + t.getClass().getSimpleName());
            }
        }
    }

    /**
     * The trace file, or the rotated one next to it. A tester without root
     * cannot reach device-protected storage, so the provider serves this
     * over `content read` instead; see JoanStateProvider.openFile.
     *
     * Returns null before init() has run, which is the honest answer:
     * there is no file yet, and inventing a path would hand back a name
     * that nothing ever wrote to.
     */
    static File file(Context ctx, boolean rotated) {
        init(ctx);
        synchronized (LOCK) {
            if (sFile == null) {
                return null;
            }
            return rotated
                    ? new File(sFile.getParentFile(), sFile.getName() + ".1")
                    : sFile;
        }
    }

    /** The build string the trace stamps, for anything else that labels a file. */
    static String build() {
        return sLastBuild;
    }

    static String akaStage() {
        return sLastAkaStage;
    }

    static void akaStage(String stage) {
        sLastAkaStage = stage == null ? "" : stage;
        note("AKA/REG stage: " + sLastAkaStage);
    }

    /**
     * Coarse Dialer/Telecom routing. Counts and flags only: never the
     * callee, Call-ID, or SIP URI. Distinguishes "IMS never asked" from
     * "session started then failed".
     */
    static String lastDial() {
        return sLastDial;
    }

    static void lastDial(String stage) {
        sLastDial = stage == null ? "" : stage;
        note("dial: " + sLastDial);
    }

    private static void remember(String msg) {
        if (msg == null) {
            return;
        }
        if (msg.startsWith("IMS network ")) {
            sLastNetwork = msg;
        } else if (msg.startsWith("IMS data_call ")) {
            sLastData = msg;
        } else if (msg.startsWith("IMS attempt ")
                || msg.startsWith("IMS diagnostics ")) {
            sLastAttempt = msg;
        }
    }

    private static void writeRemembered(FileWriter fw, String ts)
            throws java.io.IOException {
        if (sLastNetwork != null && !sLastNetwork.isEmpty()) {
            fw.write(ts + " " + sLastNetwork + "\n");
        }
        if (sLastData != null && !sLastData.isEmpty()) {
            fw.write(ts + " " + sLastData + "\n");
        }
        if (sLastAttempt != null && !sLastAttempt.isEmpty()) {
            fw.write(ts + " " + sLastAttempt + "\n");
        }
    }

    /** Version actually installed; never a stale string constant. */
    static String readBuild(Context ctx) {
        android.content.pm.PackageInfo apk = apkInfo(ctx);
        if (apk != null && apk.versionName != null) {
            return apk.versionName + " (" + apk.versionCode + ")";
        }
        android.content.pm.PackageInfo pm = pmInfo(ctx);
        if (pm != null && pm.versionName != null) {
            return pm.versionName + " (" + pm.versionCode + ") [pm]";
        }
        return "unknown";
    }

    /**
     * Our versionName, for anything that has to state which build it is.
     *
     * <p>Same order as {@link #readBuild} and for the same reason, which
     * this project has now been bitten by twice. Replacing a system app in
     * place leaves PackageManager serving the PREVIOUS record until it
     * rescans, so the apk on disk is the truth and PM is a fallback.
     *
     * <p>The second bite was the User-Agent. It read PM directly, so an
     * alpha55 handset -- running code that only exists in alpha55, and
     * writing a capture file whose own header said alpha55 -- announced
     * itself to Digi.Mobil RO as {@code joan-ims/0.4.0-alpha49}. Anyone
     * using the User-Agent to check what a tester was running would have
     * been told the wrong thing, in the one artefact that reaches the
     * carrier.
     */
    static String readVersionName(Context ctx) {
        android.content.pm.PackageInfo apk = apkInfo(ctx);
        if (apk != null && apk.versionName != null) {
            return apk.versionName;
        }
        android.content.pm.PackageInfo pm = pmInfo(ctx);
        return pm != null ? pm.versionName : null;
    }

    /** The apk on disk: what is actually running. */
    private static android.content.pm.PackageInfo apkInfo(Context ctx) {
        if (ctx == null) {
            return null;
        }
        try {
            String path = ctx.getApplicationInfo().sourceDir;
            return ctx.getPackageManager().getPackageArchiveInfo(path, 0);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** PackageManager's record: may lag a system-app replacement. */
    private static android.content.pm.PackageInfo pmInfo(Context ctx) {
        if (ctx == null) {
            return null;
        }
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
