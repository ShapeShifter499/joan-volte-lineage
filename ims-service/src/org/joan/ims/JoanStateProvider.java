package org.joan.ims;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Non-secret diagnostics/start provider. Querying this provider forces Android
 * to instantiate package code, starts the guarded driver, and returns only
 * coarse state. It never exposes IMS identity, nonce, RES, CK, IK, local IP,
 * or P-CSCF.
 *
 * openFile additionally serves the bring-up trace, which records outcomes
 * and field lengths rather than values for the same reason. Both paths are
 * gated by enforceCaller: exported for the shell, not for installed apps.
 */
public class JoanStateProvider extends ContentProvider {
    static final String AUTHORITY = "org.joan.ims.state";

    @Override
    public boolean onCreate() {
        Context ctx = getContext();
        if (ctx != null) {
            JoanTrace.init(ctx);
            JoanTrace.note("StateProvider onCreate; starting driver");
            JoanDriver.start(ctx);
        }
        return true;
    }

    private static volatile String sProbeResult = "";
    private static volatile boolean sProbeRunning = false;
    private static volatile String sAppRegResult = "";
    private static volatile boolean sAppRegRunning = false;

    /**
     * The provider stays exported so `adb shell content query` still works
     * for bring-up, but only the platform, root and the shell may call it.
     *
     * The rows themselves are coarse by design. The side effects are not:
     * the akaprobe and appregister paths drive ISIM AUTHENTICATE and
     * run a whole REGISTER cycle against the SIM. Exported with no
     * permission, any installed app could spin SIM authentication and IPsec
     * setup at will, and read the subscription debug row while doing it.
     */
    private static void enforceCaller() {
        int uid = Binder.getCallingUid();
        if (uid == Process.SYSTEM_UID || uid == Process.SHELL_UID
                || uid == 0 || uid == Process.myUid()) {
            return;
        }
        throw new SecurityException("org.joan.ims.state is not for uid " + uid);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        enforceCaller();
        Context ctx = getContext();
        boolean probe = uri != null && (
                "akaprobe".equals(uri.getLastPathSegment())
                        || "appregister".equals(uri.getLastPathSegment()));
        if (ctx != null) {
            JoanTrace.init(ctx);
            if (!probe) {
                JoanDriver.start(ctx);
            }
        }
        MatrixCursor c = new MatrixCursor(new String[] { "key", "value" });
        if (uri != null && "akaprobe".equals(uri.getLastPathSegment())) {
            c.addRow(new Object[] { "aka_probe_running",
                    String.valueOf(sProbeRunning) });
            c.addRow(new Object[] { "aka_probe_result", sProbeResult });
            if (!sProbeRunning && ctx != null) {
                sProbeRunning = true;
                final Context app = ctx.getApplicationContext();
                new Thread(() -> {
                    try {
                        sProbeResult = JoanAka.runAkaProbe(app);
                    } catch (Throwable t) {
                        sProbeResult = "probe error "
                                + t.getClass().getSimpleName();
                    } finally {
                        sProbeRunning = false;
                    }
                }, "joan-aka-probe").start();
            }
            return c;
        }
        if (uri != null && "appregister".equals(uri.getLastPathSegment())) {
            /* REGISTER 200 from the app over IpSecTransform. Refuses
             * if the native daemon is still answering STATUS. */
            c.addRow(new Object[] { "appregister_running",
                    String.valueOf(sAppRegRunning) });
            c.addRow(new Object[] { "appregister_result", sAppRegResult });
            if (!sAppRegRunning && ctx != null) {
                sAppRegRunning = true;
                final Context app = ctx.getApplicationContext();
                new Thread(() -> {
                    try {
                        sAppRegResult = JoanAppRegister.run(app);
                    } catch (Throwable t) {
                        sAppRegResult = "appregister error "
                                + t.getClass().getSimpleName();
                    } finally {
                        sAppRegRunning = false;
                    }
                }, "joan-app-register").start();
            }
            return c;
        }
        c.addRow(new Object[] { "build", buildLabel(ctx) });
        c.addRow(new Object[] { "driver_started", String.valueOf(JoanDriver.isRunning()) });
        c.addRow(new Object[] { "registered", String.valueOf(JoanRegistration.isRegistered()) });
        c.addRow(new Object[] { "ims_requested", String.valueOf(JoanDriver.imsRequested()) });
        c.addRow(new Object[] { "last_state", JoanDriver.lastState() });
        c.addRow(new Object[] { "aka_stage", JoanTrace.akaStage() });
        c.addRow(new Object[] { "last_register", JoanDriver.lastRegister() });
        c.addRow(new Object[] { "ims_diag_listener", JoanImsDiagnostics.listener() });
        c.addRow(new Object[] { "ims_diag_data", JoanImsDiagnostics.data() });
        c.addRow(new Object[] { "ims_diag_network", JoanImsDiagnostics.network() });
        c.addRow(new Object[] { "ims_diag_ages", JoanImsDiagnostics.ages() });
        c.addRow(new Object[] { "last_dial", JoanTrace.lastDial() });
        c.addRow(new Object[] { "volte_gate", JoanVolteCarrierGate.last() });
        c.addRow(new Object[] { "sub_debug", JoanDriver.subscriptionDebug(ctx) });
        c.addRow(new Object[] { "pani_cell", JoanAppRegister.paniCellStatus() });
        c.addRow(new Object[] { "capture", captureRow(ctx) });
        return c;
    }

    /**
     * The version actually installed, read from the package rather than a
     * string constant. The constant went stale immediately and a bug
     * report then could not say which build it came from.
     */
    /**
     * Which build this is, from the apk on disk.
     *
     * <p>This used to be a second copy of JoanTrace.readBuild, carrying
     * its own comment about why PackageManager's record cannot be
     * trusted. Two copies of one rule is how the User-Agent came to
     * disagree with this row on the same handset, so there is now one
     * resolver and everything asks it.
     */
    private static String buildLabel(Context ctx) {
        return JoanTrace.readBuild(ctx);
    }

    /**
     * Whether a full REGISTER capture is waiting, and the command that
     * fetches it.
     *
     * This row exists because of how testers actually behave. Asked twice
     * for joan-capture.log, the Digi.Mobil RO lane sent the state rows
     * both times -- which is not a failure to follow instructions, it is
     * a signal: the state query is the thing people run, so the pointer
     * to everything else belongs in its output rather than in a README
     * nobody opened. A status code with no message behind it costs a
     * round trip through a chat window and a carrier's patience.
     */
    private static String captureRow(Context ctx) {
        File f = JoanSipCapture.file(ctx, false);
        if (f == null || !f.exists()) {
            return "none yet; written when a REGISTER is attempted";
        }
        return "present bytes=" + f.length()
                + " read=\"adb shell content read --uri content://"
                + AUTHORITY + "/capture\"";
    }

    /**
     * Serve the trace to `adb shell content read`.
     *
     * The trace lives in device-protected storage under the app's own uid,
     * which a tester cannot reach without root -- and a tester who cannot
     * send the trace is a lane that cannot be diagnosed. Every remaining
     * question in this project has to be answered from someone else's
     * handset, so "pull the file as root" was not a workable ask.
     *
     *   adb shell content read --uri content://org.joan.ims.state/trace
     *   adb shell content read --uri content://org.joan.ims.state/trace.1
     *   adb shell content read --uri content://org.joan.ims.state/capture
     *
     * The capture is the last REGISTER exchange in full, redacted of
     * authentication material; the trace is the running narrative. They are
     * separate files so a tester can send one without the other.
     *
     * enforceCaller keeps this to the platform, root and the shell, so
     * opening the file up to adb does not open it to installed apps. It is
     * read-only: this provider hands out no writable descriptor.
     */
    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        enforceCaller();
        if (!"r".equals(mode)) {
            throw new SecurityException("the trace is read-only");
        }
        Context ctx = getContext();
        if (ctx == null) {
            throw new FileNotFoundException("provider has no context");
        }
        String seg = uri == null ? null : uri.getLastPathSegment();
        boolean rotated = "trace.1".equals(seg);
        boolean capture = "capture".equals(seg);
        if (!rotated && !capture && !"trace".equals(seg)) {
            throw new FileNotFoundException("no such file: " + seg);
        }
        File f = capture ? JoanSipCapture.file(ctx, false)
                : JoanTrace.file(ctx, rotated);
        if (f == null || !f.exists()) {
            // Say which one is missing, and why it might legitimately not
            // be there. The rotated trace appears only once the live one
            // passes its size limit, and the capture only once a REGISTER
            // has been attempted; a tester reading "not found" on a fresh
            // install should not read that as a broken install.
            String what = capture
                    ? "no capture yet; it is written when a REGISTER is attempted"
                    : rotated
                            ? "no rotated trace yet; the live trace has not filled up"
                            : "no trace yet";
            throw new FileNotFoundException(
                    what + "; query the state uri first to start the driver");
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd.org.joan.ims.state";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        return 0;
    }
}
