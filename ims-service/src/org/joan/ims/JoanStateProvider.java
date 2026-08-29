package org.joan.ims;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;

/**
 * Non-secret diagnostics/start provider. Querying this provider forces Android
 * to instantiate package code, starts the guarded driver, and returns only
 * coarse state. It never exposes IMS identity, nonce, RES, CK, IK, local IP,
 * or P-CSCF.
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
    private static volatile String sIpsecResult = "";
    private static volatile boolean sIpsecRunning = false;
    private static volatile String sAppRegResult = "";
    private static volatile boolean sAppRegRunning = false;

    /**
     * The provider stays exported so `adb shell content query` still works
     * for bring-up, but only the platform, root and the shell may call it.
     *
     * The rows themselves are coarse by design. The side effects are not:
     * the akaprobe/ipsecspike/appregister paths drive ISIM AUTHENTICATE,
     * allocate SPIs and run a whole REGISTER cycle. Exported with no
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
                        || "ipsecspike".equals(uri.getLastPathSegment())
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
        if (uri != null && "ipsecspike".equals(uri.getLastPathSegment())) {
            /* Decides whether the native daemon can be removed: see
             * JoanIpsecSpike. Runs off the binder thread. */
            c.addRow(new Object[] { "ipsec_spike_running",
                    String.valueOf(sIpsecRunning) });
            c.addRow(new Object[] { "ipsec_spike_result", sIpsecResult });
            if (!sIpsecRunning && ctx != null) {
                sIpsecRunning = true;
                final Context app = ctx.getApplicationContext();
                new Thread(() -> {
                    try {
                        sIpsecResult = JoanIpsecSpike.run(app);
                    } catch (Throwable t) {
                        sIpsecResult = "spike error "
                                + t.getClass().getSimpleName();
                    } finally {
                        sIpsecRunning = false;
                    }
                }, "joan-ipsec-spike").start();
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
        c.addRow(new Object[] { "build", "2026-08-27-apdu-aka-v14" });
        c.addRow(new Object[] { "driver_started", String.valueOf(JoanDriver.isRunning()) });
        c.addRow(new Object[] { "registered", String.valueOf(JoanRegistration.isRegistered()) });
        c.addRow(new Object[] { "ims_requested", String.valueOf(JoanDriver.imsRequested()) });
        c.addRow(new Object[] { "last_state", JoanDriver.lastState() });
        c.addRow(new Object[] { "aka_stage", JoanTrace.akaStage() });
        c.addRow(new Object[] { "ctl_last", JoanCtl.lastError() });
        c.addRow(new Object[] { "native_status", safeNativeStatus() });
        c.addRow(new Object[] { "sub_debug", JoanDriver.subscriptionDebug(ctx) });
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd.org.joan.ims.state";
    }

    private static String safeNativeStatus() {
        String r = JoanCtl.txn("STATUS");
        if (r == null || r.isEmpty()) {
            return "unavailable";
        }
        // Keep stage/error visible but never expose the selected P-CSCF IP.
        return r.replaceAll("PCSCF=\\S+", "PCSCF=[redacted]");
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
