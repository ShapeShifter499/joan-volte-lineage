package org.joan.ims;

import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class JoanUimHelper {
    private static final String TAG = "JoanUim";
    private static final String ISIM_AID = "A0000000871004FFFFFFFF8907030000";
    private static String runCmd(String... args) {
        try {
            Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append('\n');
            }
            p.waitFor();
            return out.toString();
        } catch (Exception e) {
            Log.w(TAG, "cmd error " + e);
            return null;
        }
    }
    public static String runAkaQmi(byte[] randAutn) {
        // Open logical channel
        String open = runCmd("/system/bin/qmicli", "-d", "qrtr://0", "--uim-open-logical-channel=1," + ISIM_AID);
        int ch = -1;
        for (String l : open.split("\n")) {
            if (l.matches(".*\\d+$")) {
                try { ch = Integer.parseInt(l.replaceAll(".*\\D", "")); } catch (Exception ignored) {}
            }
        }
        if (ch < 0) return null;
        // Build APDU data string: 0088008122 + 10 RAND + 10 AUTN (hex)
        StringBuilder data = new StringBuilder();
        data.append("0088008122");
        for (int i = 0; i < 16; i++) data.append(String.format("%02X", randAutn[i] & 0xFF));
        for (int i = 16; i < 32; i++) data.append(String.format("%02X", randAutn[i] & 0xFF));
        String resp = runCmd("/system/bin/qmicli", "-d", "qrtr://0", "--uim-send-apdu=1," + ch + "," + data.toString());
        // close channel
        runCmd("/system/bin/qmicli", "-d", "qrtr://0", "--uim-close-logical-channel=1," + ch);
        return resp;
    }
}
