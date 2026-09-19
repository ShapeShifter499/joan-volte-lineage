package org.joan.ims;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The one screen joan has, and it exists because the grant could not be
 * shipped.
 *
 * RECORD_AUDIO and the location permissions are dangerous runtime
 * permissions. privapp-permissions does not cover those, and
 * etc/default-permissions -- which does -- is applied by
 * DefaultPermissionGrantPolicy.grantDefaultPermissions only on first boot
 * and platform upgrades. A package added to /system afterwards, which is
 * exactly what this zip does, is never revisited. That is not a theory:
 * alpha63 shipped the default-permissions file and the tester's state row
 * still read pani_cell=no-permission.
 *
 * So the file stays (it is correct for a ROM build or a fresh device) and
 * the app asks as well. An ImsService has no UI and cannot raise a
 * permission dialog on its own, so this is a launcher entry the tester
 * opens once. No root, no adb, and it survives reboots and reinstalls of
 * the same package.
 *
 * It deliberately shows what is granted rather than only asking: a tester
 * who is told "microphone denied" can act on it, where a silent uplink
 * looks like a carrier fault and has already been reported as one.
 */
public class JoanPermissionActivity extends Activity {

    private static final int REQUEST = 1;

    /** What joan needs, and what each one is actually for. */
    private static final String[][] WANTED = {
        { android.Manifest.permission.RECORD_AUDIO,
          "Microphone -- your voice on a call. Without it the other side "
          + "hears silence while you hear them normally." },
        { android.Manifest.permission.ACCESS_FINE_LOCATION,
          "Location -- the serving cell id in P-Access-Network-Info, which "
          + "the network reads to place the call. Nothing else uses it." },
    };

    private TextView mText;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 64, 48, 48);
        mText = new TextView(this);
        mText.setTextSize(15f);
        mText.setGravity(Gravity.START);
        root.addView(mText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.WHITE);
        sv.addView(root);
        setContentView(sv);

        String[] missing = missing();
        if (missing.length > 0) {
            requestPermissions(missing, REQUEST);
        }
        render();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms,
                                           int[] results) {
        render();
    }

    private String[] missing() {
        int n = 0;
        for (String[] w : WANTED) {
            if (!granted(w[0])) {
                n++;
            }
        }
        String[] out = new String[n];
        int i = 0;
        for (String[] w : WANTED) {
            if (!granted(w[0])) {
                out[i++] = w[0];
            }
        }
        return out;
    }

    private boolean granted(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void render() {
        StringBuilder b = new StringBuilder();
        b.append("joan IMS\n").append(JoanTrace.readBuild(this)).append("\n\n");
        boolean all = true;
        for (String[] w : WANTED) {
            boolean ok = granted(w[0]);
            all &= ok;
            b.append(ok ? "GRANTED  " : "DENIED   ")
             .append(w[0].substring(w[0].lastIndexOf('.') + 1))
             .append('\n').append("    ").append(w[1]).append("\n\n");
        }
        b.append(all
                ? "Nothing else to do here. Calls can use the microphone and "
                  + "the cell id; you can close this."
                : "Denied above can be granted in Settings > Apps > joan IMS "
                  + "> Permissions, or by reopening this screen. A denied "
                  + "microphone is worth fixing before reporting call audio.");
        mText.setText(b.toString());
    }
}
