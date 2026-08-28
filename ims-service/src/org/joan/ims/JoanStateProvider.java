package org.joan.ims;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

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

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        Context ctx = getContext();
        if (ctx != null) {
            JoanTrace.init(ctx);
            JoanDriver.start(ctx);
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
