package org.joan.ims;

import android.content.Context;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Drives the two-stage REGISTER with the native UA over the ctl socket:
 *   REG1 -> CHALLENGE <nonce> ; ISIM AKA on-device ; REG2 res ck ik.
 * Identity (ID) and network (NET) are pushed once per cycle. The device
 * itself computes AKA, so no key material ever leaves the SIM except the
 * derived RES/CK/IK handed to this app by the framework API and then to
 * the native UA in the same kernel. Never logged.
 */
final class JoanAka {
    private static final String TAG = "JoanIms";

    private JoanAka() {}

    /** Full registration cycle. Returns true iff final state registered. */
    static boolean registerCycle(Context ctx, String impi, String impu,
                                 String domain, String imei,
                                 String localIp, int localPort,
                                 String pcscf, int pcscfPort) {
        if (!pushId(impi, impu, domain, imei)) {
            return false;
        }
        if (!pushNet(localIp, localPort, pcscf, pcscfPort)) {
            return false;
        }
        String r1 = JoanCtl.txn("REG1");
        if (r1 == null || !r1.startsWith("CHALLENGE ")) {
            Log.w(TAG, "reg1 no challenge");
            return false;
        }
        String nonce = r1.substring("CHALLENGE ".length()).trim();
        if (nonce.isEmpty()) {
            Log.w(TAG, "reg1 empty nonce");
            return false;
        }

        String authHex = runIccAuth(ctx, nonce);
        if (authHex == null) {
            Log.e(TAG, "icc auth unavailable");
            return false;
        }
        // Response is "RES=... CK=... IK=..." hex triple (framework format).
        String[] parts = parseAuthResponse(authHex);
        if (parts == null) {
            Log.e(TAG, "icc auth parse failed");
            return false;
        }
        String r2 = JoanCtl.txn("REG2 " + parts[0] + " " + parts[1]
                + " " + parts[2]);
        if (r2 == null || !r2.startsWith("STATE")) {
            Log.w(TAG, "reg2 not registered: "
                    + (r2 == null ? "timeout" : r2));
            return false;
        }
        Log.i(TAG, "IMS registered via native UA");
        return true;
    }

    private static boolean pushId(String impi, String impu, String domain,
                                  String imei) {
        StringBuilder sb = new StringBuilder("ID ").append(safe(impi));
        if (impu != null && !impu.isEmpty()) {
            sb.append(' ').append(safe(impu));
            if (domain != null && !domain.isEmpty()) {
                sb.append(' ').append(safe(domain));
                if (imei != null && !imei.isEmpty()) {
                    sb.append(' ').append(safe(imei));
                }
            }
        }
        String r = JoanCtl.txn(sb.toString());
        return r != null && r.startsWith("OK");
    }

    private static boolean pushNet(String localIp, int localPort,
                                   String pcscf, int pcscfPort) {
        String r = JoanCtl.txn(String.format(
                "NET %s %d %s %d",
                safe(localIp), localPort, safe(pcscf), pcscfPort));
        return r != null && r.startsWith("OK");
    }

    /**
     * Runs ISIM AKA. Tries, in order:
     *   1. ImsService hidden APIs (getImsAuthentication / AUth body via
     *      simAuthFamily=1)
     *   2. getIccAuthentication with 3GPP AKA payload
     * Returns raw response string from framework or null.
     */
    static String runIccAuth(Context ctx, String nonceB64) {
        TelephonyManager tm0 = ctx.getSystemService(TelephonyManager.class);
        if (tm0 == null) {
            return null;
        }
        int sub = SubscriptionManager.getDefaultDataSubscriptionId();
        if (sub < 0) {
            sub = SubscriptionManager.getDefaultSubscriptionId();
        }
        TelephonyManager tm = (sub >= 0)
                ? tm0.createForSubscriptionId(sub) : tm0;

        // Path used in prior LOS experiments: TM.getIccAuthentication
        // (appType, authType, base64(RAND|AUTN)). Constants differ across
        // Android versions, so probe both app types (USIM=0/ISIM=1) and
        // both auth types (EAP-AKA=1 / 3GPP AKA variants) until one
        // returns data. Payload per 3GPP TS 31.102 7.1.2.
        for (int appType = 0; appType <= 1; appType++) {
            for (int authType = 0; authType <= 1; authType++) {
                String r = iccAuthOnce(tm, appType, authType, nonceB64);
                if (r != null) {
                    Log.i(TAG, "icc auth ok app=" + appType
                            + " type=" + authType);
                    return r;
                }
            }
        }
        return null;
    }

    private static String iccAuthOnce(TelephonyManager tm, int appType,
                                      int authType, String nonceB64) {
        try {
            // GBA: RAND + AUTN concatenated then base64:
            // RAND = first half of nonce? No — AKA nonce = base64(RAND|AUTN).
            byte[] nonceRaw = b64(nonceB64);
            if (nonceRaw == null || nonceRaw.length < 32) {
                return null;
            }
            byte[] rand = new byte[16];
            byte[] autn = new byte[16];
            System.arraycopy(nonceRaw, 0, rand, 0, 16);
            System.arraycopy(nonceRaw, 16, autn, 0, 16);
            String payload = b64e(concat(rand, autn));

            Method m = TelephonyManager.class.getMethod(
                    "getIccAuthentication", int.class, int.class,
                    String.class);
            Object r = m.invoke(tm, appType, authType, payload);
            return r == null ? null : String.valueOf(r);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Framework returns (for EAP-AKA style):
     *   "RES=<hex>" or full hex 'E0..+RES len...' etc depending on path.
     * We accept both shapes: base64 UICC response of
     * [DB tag][len][tag E1/CX][...] as well as simple hex triple.
     */
    static String[] parseAuthResponse(String resp) {
        if (resp == null || resp.isEmpty()) {
            return null;
        }
        // Shape 1: explicit hex triple
        if (resp.startsWith("RES=")) {
            try {
                String[] kv = resp.split("[; ]");
                String res = null, ck = null, ik = null;
                for (String k : kv) {
                    if (k.startsWith("RES=")) res = k.substring(4);
                    else if (k.startsWith("CK=")) ck = k.substring(3);
                    else if (k.startsWith("IK=")) ik = k.substring(3);
                }
                if (res != null && ck != null && ik != null
                        && res.length() >= 32
                        && ck.length() >= 32 && ik.length() >= 32) {
                    return new String[]{
                            lower(res.substring(0, 32)),
                            lower(ck.substring(0, 32)),
                            lower(ik.substring(0, 32))
                    };
                }
            } catch (Exception e) {
                // fall through
            }
            return null;
        }
        // Shape 2: base64 UICC TLV per TS 31.102 / 3GPP TS 31.103.
        // UICC return:  DB
        //   tag=success(0xDB)| len | tag=E1(res) ... | tag=CK ... | tag=IK ...
        byte[] raw = b64(resp);
        if (raw == null || raw.length < 40) {
            return null;
        }
        return parseUiccTlv(raw);
    }

    private static String[] parseUiccTlv(byte[] d) {
        // Walk top-level: expect 0xDC (status words + data) or direct
        // sequence starting at a data object.
        int i = 0;
        while (i + 2 <= d.length) {
            int tag = d[i] & 0xff;
            int len = d[i + 1] & 0xff;
            if (i + 2 + len > d.length) {
                break;
            }
            if (tag == 0xDB && len >= 38) {
                // success TLV containing RES(16) CKKEY(16) IKKEY(16) + tags
                byte[] inner = new byte[len];
                System.arraycopy(d, i + 2, inner, 0, len);
                return splitResCkIk(inner);
            }
            i += 2 + len;
        }
        // Raw fallback: some modems return bare RES|CK|IK (48 bytes).
        if (d.length >= 48) {
            return new String[]{
                    hex(d, 0, 16), hex(d, 16, 16), hex(d, 32, 16)};
        }
        return null;
    }

    private static String[] splitResCkIk(byte[] inner) {
        // Inner: tag 0x81 res (often prefixed by length), CK, IK same.
        // Be tolerant: find three consecutive 16-byte values after tag 0x81.
        int p = 0;
        short t1 = inner.length > 0 ? (short) (inner[0] & 0xff) : -1;
        if (t1 == 0x81 && inner.length >= 49) {
            // 81 <len> res(16) ck(16) ik(16)
            return new String[]{
                    hex(inner, 2, 16), hex(inner, 18, 16),
                    hex(inner, 34, 16)
            };
        }
        // Unqualified layout: res@0, ck@16, ik@32
        if (inner.length >= 48) {
            return new String[]{
                    hex(inner, 0, 16), hex(inner, 16, 16),
                    hex(inner, 32, 16)
            };
        }
        return null;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] o = new byte[a.length + b.length];
        System.arraycopy(a, 0, o, 0, a.length);
        System.arraycopy(b, 0, o, a.length, b.length);
        return o;
    }

    private static byte[] b64(String s) {
        try {
            return android.util.Base64.decode(s, android.util.Base64.DEFAULT);
        } catch (Exception e) {
            return null;
        }
    }

    private static String b64e(byte[] b) {
        return android.util.Base64.encodeToString(
                b, android.util.Base64.NO_WRAP);
    }

    private static String hex(byte[] d, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = off; i < off + len; i++) {
            sb.append(String.format(Locale.ROOT, "%02x", d[i]));
        }
        return sb.toString();
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    /** Strip characters that would break the line protocol. */
    private static String safe(String s) {
        return s == null ? "" : s.replace(" ", "").replace("\n", "")
                .replace("\r", "").replace("\0", "");
    }
}
