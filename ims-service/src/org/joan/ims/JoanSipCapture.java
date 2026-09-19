package org.joan.ims;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The last REGISTER exchange, in full, for a tester to send back.
 *
 * The summary row in JoanStateProvider says things like "reg2=500" and
 * lists header names. That was enough while the questions were about
 * whether a message was sent at all; it is not enough now. Digi.Mobil RO
 * answers a protected REGISTER with 500 Server Internal Error, CMCC with
 * 404, and neither can be explained from a list of header names -- the
 * answer is in the values, and every one of those handsets belongs to
 * somebody else and is not rooted.
 *
 * So this keeps the messages themselves, redacted of authentication
 * material by JoanSipRedact, in a file the state provider will serve over
 * `adb shell content read`. It is deliberately a separate file from
 * joan-trace.log: the trace is a running narrative worth keeping across
 * cycles, this is one exchange kept whole, and a tester should be able to
 * send either without sending the other.
 */
final class JoanSipCapture {
    private static final String TAG = "JoanIms";
    private static final Object LOCK = new Object();
    private static File sFile;
    private static final List<String> sEntries = new ArrayList<>();

    /** One message. Past this a capture is not evidence, it is a hazard. */
    private static final int MAX_MESSAGE = 32 * 1024;
    /** The whole file. Four messages of a REGISTER cycle fit far inside. */
    private static final int MAX_ENTRIES = 8;

    private JoanSipCapture() {}

    static void init(Context ctx) {
        synchronized (LOCK) {
            if (sFile != null) {
                return;
            }
            try {
                Context de = ctx.createDeviceProtectedStorageContext();
                sFile = new File(de.getFilesDir(), "joan-capture.log");
            } catch (Throwable t) {
                Log.w(TAG, "capture init failed: " + t.getClass().getSimpleName());
            }
        }
    }

    /** The file, for the provider. Null before init(). */
    static File file(Context ctx, boolean unusedRotated) {
        init(ctx);
        synchronized (LOCK) {
            return sFile;
        }
    }

    /**
     * Record one message. A label beginning "REG1 request" starts a new
     * cycle, which is how the file stays one exchange rather than a log:
     * the first message of a registration attempt is the only unambiguous
     * boundary available without threading state through four call sites.
     */
    static void record(String label, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        // Describe the line endings BEFORE anything touches them: this
        // file goes out over a PTY that rewrites LF as CRLF, and the
        // original terminators are evidence in their own right.
        String facts = JoanSipRedact.lineEndings(text);
        String body = text.length() > MAX_MESSAGE
                ? text.substring(0, MAX_MESSAGE) + "\n<truncated at "
                        + MAX_MESSAGE + " of " + text.length() + " bytes>\n"
                : text;
        String entry = "==== " + label + " ====\n"
                + "---- as sent: " + facts + "\n"
                + JoanSipRedact.normalize(JoanSipRedact.redact(body));
        synchronized (LOCK) {
            if (label.startsWith("REG1 request")) {
                sEntries.clear();
            }
            if (sEntries.size() >= MAX_ENTRIES) {
                sEntries.remove(0);
            }
            sEntries.add(entry);
            write();
        }
    }

    /** Caller holds LOCK. */
    private static void write() {
        if (sFile == null) {
            return;
        }
        try {
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",
                    Locale.US).format(new Date());
            FileWriter fw = new FileWriter(sFile, false);
            fw.write("# joan SIP capture  build=" + JoanTrace.build()
                    + "  written " + ts + "\n"
                    + "#\n"
                    + "# The REGISTER exchange as this handset sent and received it.\n"
                    + "#\n"
                    + "# REDACTED: nonce, cnonce, response, rspauth, nextnonce, auts\n"
                    + "#   (lengths kept, values gone).\n"
                    + "# NOT REDACTED: your IMS identities (IMPI/IMPU), Call-ID, and\n"
                    + "#   your P-CSCF address. They are what the open questions are\n"
                    + "#   about, so they are kept on purpose -- read this file before\n"
                    + "#   you send it to anyone.\n"
                    + "#\n"
                    + "# LINE ENDINGS: the messages below are written with one LF per\n"
                    + "#   line so this file reads the same after adb's PTY, a copy and\n"
                    + "#   a paste. What was actually on the wire is on each message's\n"
                    + "#   'as sent' line: crlf= is what SIP requires, and lf= or cr=\n"
                    + "#   above zero is a fault worth reporting, not a transfer\n"
                    + "#   artefact. sha256 is over the message as sent, before\n"
                    + "#   redaction, so two captures can be compared to each other.\n"
                    + "#\n");
            for (String e : sEntries) {
                fw.write("\n");
                fw.write(e);
                if (!e.endsWith("\n")) {
                    fw.write("\n");
                }
            }
            fw.close();
        } catch (Throwable t) {
            Log.w(TAG, "capture write failed: " + t.getClass().getSimpleName());
        }
    }
}
