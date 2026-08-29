package org.joan.ims;

import android.content.Context;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * ISIM AKA on the device: run AUTHENTICATE against the ISIM and hand the
 * derived RES/CK/IK back to JoanAppRegister, which builds the Digest AKA
 * response and the ESP keys from them. No key material leaves the process.
 * Never logged.
 */
final class JoanAka {
    private static final String TAG = "JoanIms";

    /** ISIM AID used by pmOS on this modem (proven with QMI UIM). */
    private static final String ISIM_AID = "A0000000871004FFFFFFFF8907030000";

    private JoanAka() {}

    /**
     * Runs ISIM AKA. Tries, in order:
     *   1. ImsService hidden APIs (getImsAuthentication / AUth body via
     *      simAuthFamily=1)
     *   2. getIccAuthentication with 3GPP AKA payload
     * Returns raw response string from framework or null.
     */
    static String runAkaProbe(Context ctx) {
        final TelephonyManager tm0 = ctx.getSystemService(TelephonyManager.class);
        if (tm0 == null) {
            return "no telephony manager";
        }
        int sub = SubscriptionManager.getDefaultDataSubscriptionId();
        if (sub < 0) {
            sub = SubscriptionManager.getDefaultSubscriptionId();
        }
        final TelephonyManager tm = (sub >= 0)
                ? tm0.createForSubscriptionId(sub) : tm0;

        // Fixed non-secret test vector: RAND = 00..0F, AUTN = 10..1F.
        // A valid AUTHENTICATE APDU answers with a DB success or an AUTN
        // security error — never 6700 "bad P3". Report shape only.
        final byte[] randAutn = new byte[32];
        for (int i = 0; i < 16; i++) {
            randAutn[i] = (byte) i;
            randAutn[i + 16] = (byte) (0x10 + i);
        }
        try {
            android.telephony.IccOpenLogicalChannelResponse r =
                    tm.iccOpenLogicalChannel(ISIM_AID);
            if (r == null) {
                return "open: null";
            }
            int ch = r.getChannel();
            if (ch <= 0) {
                return "open: status " + r.getStatus();
            }
            try {
                StringBuilder data = new StringBuilder("10");
                data.append(hex(randAutn, 0, 16));
                data.append("10");
                data.append(hex(randAutn, 16, 16));
                String resp = tm.iccTransmitApduLogicalChannel(
                        ch, 0, 0x88, 0, 0x81, 0x22, data.toString());
                if (resp == null) {
                    return "apdu: null";
                }
                if (resp.length() < 4) {
                    return "apdu: short " + resp;
                }
                String sw = resp.substring(resp.length() - 4);
                String body = resp.substring(0, resp.length() - 4);
                String tag = body.isEmpty() ? "-" : body.substring(0, 2);
                if (sw.startsWith("61")) {
                    String get = tm.iccTransmitApduLogicalChannel(
                            ch, 0, 0xC0, 0, 0,
                            Integer.parseInt(sw.substring(2), 16), "");
                    if (get != null && get.length() >= 4) {
                        sw = get.substring(get.length() - 4);
                        body = get.substring(0, get.length() - 4);
                        tag = body.isEmpty() ? "-" : body.substring(0, 2);
                    }
                }
                return "apdu sw=" + sw + " tag=" + tag
                        + " bodylen=" + body.length() / 2;
            } finally {
                tm.iccCloseLogicalChannel(ch);
            }
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            return "apdu err " + c.getClass().getSimpleName();
        }
    }

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

        // Primary: raw AUTHENTICATE APDU on the ISIM logical channel. This is
        // the exact call pmOS proved on this modem (TS 31.102 §7.1.2.1):
        //   00 88 00 81 22 | 10 RAND[16] 10 AUTN[16]
        // and is what this card accepts. getIccAuthentication(EAP-AKA) is
        // rejected by qcril's UIM layer with 6700 "incorrect parameter P3".
        byte[] nonceRaw = b64(nonceB64);
        if (nonceRaw == null || nonceRaw.length < 32) {
            return null;
        }
        // channel=0: apduAuthenticate opens a fresh logical channel to the
        // ISIM AID, runs AUTHENTICATE, and closes it. (A literal channel
        // number here would transmit on an unopened channel — bug fixed.)
        String apduAuth = apduAuthenticate(tm, nonceRaw, 0, 0);
        if (apduAuth != null) {
            return apduAuth;
        }

        // Fallback: TelephonyManager.getIccAuthentication with the correct
        // TLV (both tags 0x10 per TS 31.102), for devices whose modem path
        // accepts it. Kept after the proven APDU route.
        return runIccAuthViaGet(tm, nonceRaw);
    }

    /** AUTHENTICATE over a logical channel; returns "RES=.. CK=.. IK=.." */
    private static String apduAuthenticate(TelephonyManager tm, byte[] randAutn,
                                           int off, int channel) {
        try {
            int ch = channel;
            boolean owned = false;
            if (ch <= 0) {
                android.telephony.IccOpenLogicalChannelResponse r =
                        tm.iccOpenLogicalChannel(ISIM_AID);
                if (r == null || r.getChannel() <= 0) {
                    JoanTrace.note("apdu: open failed status="
                            + (r == null ? "null" : r.getStatus()));
                    return null;
                }
                ch = r.getChannel();
                owned = true;
            }
            try {
                StringBuilder data = new StringBuilder("10");
                data.append(hex(randAutn, off, 16));
                data.append("10");
                data.append(hex(randAutn, off + 16, 16));
                String resp = tm.iccTransmitApduLogicalChannel(
                        ch, 0, 0x88, 0, 0x81, 0x22, data.toString());
                if (resp == null || resp.length() < 4) {
                    JoanTrace.note("apdu: resp short/null len="
                            + (resp == null ? -1 : resp.length()));
                    return null;
                }
                // 61 XX = more data pending: GET RESPONSE.
                String sw = resp.substring(resp.length() - 4);
                if (sw.startsWith("61")) {
                    String get = tm.iccTransmitApduLogicalChannel(
                            ch, 0, 0xC0, 0, 0,
                            Integer.parseInt(sw.substring(2), 16), "");
                    if (get != null && get.length() >= 4) {
                        resp = get;
                    }
                }
                // Shape-only trace (lengths and SW), never key material.
                String swf = resp.substring(resp.length() - 4);
                StringBuilder shape = new StringBuilder();
                byte[] dd = hexBytes(resp);
                for (int k = 0; k < Math.min(8, dd.length); k++) {
                    shape.append(String.format(Locale.ROOT, "%02x", dd[k]));
                }
                JoanTrace.note("apdu: len=" + resp.length()
                        + " sw=" + swf + " head=" + shape);
                return parseApduAka(resp);
            } finally {
                if (owned) {
                    tm.iccCloseLogicalChannel(ch);
                }
            }
        } catch (Exception e) {
            JoanTrace.note("apdu aka failed: " + e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Parse AUTHENTICATE response per TS 31.102 §7.1.2.1: on success the
     * UICC returns "DB <len> [81 len RES] [82 10 CK] [83 10 IK]".
     * Sync response status words are already stripped by the caller.
     * Never logs key material — only shape metadata.
     */
    private static String parseApduAka(String respHex) {
        byte[] d;
        try {
            d = hexBytes(respHex);
        } catch (Exception e) {
            return null;
        }
        if (d.length < 2 || (d[0] & 0xff) != 0xDB) {
            JoanTrace.note("apdu: not DB tag len=" + d.length
                    + " b0=" + (d.length > 0
                    ? String.format(Locale.ROOT, "%02x", d[0]) : "-"));
            return null;
        }
        try {
            // This card (and pmOS's proven parse in joan_ims_live.py) returns
            // the plain DB layout, NOT inner 81/82/83 tags:
            //   DB <nres> <res[nres]> <nck> <ck[nck]> <nik> <ik[nik]>
            // e.g. DB 08 <res 8B> 10 <ck 16B> 10 <ik 16B> (44 bytes + SW).
            int i = 1;
            int nres = d[i++] & 0xff;
            if (i + nres > d.length) {
                JoanTrace.note("apdu: res overflow nres=" + nres);
                return null;
            }
            byte[] res = java.util.Arrays.copyOfRange(d, i, i + nres);
            i += nres;
            if (i >= d.length) {
                JoanTrace.note("apdu: truncated after res");
                return null;
            }
            int nck = d[i++] & 0xff;
            if (i + nck > d.length) {
                JoanTrace.note("apdu: ck overflow nck=" + nck);
                return null;
            }
            byte[] ck = java.util.Arrays.copyOfRange(d, i, i + nck);
            i += nck;
            if (i >= d.length) {
                JoanTrace.note("apdu: truncated after ck");
                return null;
            }
            int nik = d[i++] & 0xff;
            if (i + nik > d.length) {
                JoanTrace.note("apdu: ik overflow nik=" + nik);
                return null;
            }
            byte[] ik = java.util.Arrays.copyOfRange(d, i, i + nik);
            if (res.length < 4 || res.length > 16
                    || ck.length != 16 || ik.length != 16) {
                JoanTrace.note("apdu: bad lens res=" + res.length
                        + " ck=" + ck.length + " ik=" + ik.length);
                return null;
            }
            return "RES=" + hex(res, 0, res.length)
                    + " CK=" + hex(ck, 0, 16)
                    + " IK=" + hex(ik, 0, 16);
        } catch (Exception e) {
            JoanTrace.note("apdu: parse exception");
            return null;
        }
    }

    private static byte[] hexBytes(String s) {
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2),
                    16);
        }
        return out;
    }

    /** Legacy path kept as fallback; corrected TLV tags (both 0x10). */
    private static String runIccAuthViaGet(TelephonyManager tm,
                                           byte[] nonceRaw) {
        try {
            byte[] tlv34 = new byte[34];
            tlv34[0] = 0x10;
            System.arraycopy(nonceRaw, 0, tlv34, 1, 16);
            tlv34[17] = 0x10;
            System.arraycopy(nonceRaw, 16, tlv34, 18, 16);
            String b64 = android.util.Base64.encodeToString(tlv34,
                    android.util.Base64.NO_WRAP);
            Method m = TelephonyManager.class.getMethod(
                    "getIccAuthentication", int.class, int.class,
                    String.class);
            Object r = m.invoke(tm, TelephonyManager.APPTYPE_ISIM,
                    TelephonyManager.AUTHTYPE_EAP_AKA, b64);
            if (r == null) {
                return null;
            }
            return String.valueOf(r);
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
                        && res.length() >= 16
                        && ck.length() >= 32 && ik.length() >= 32) {
                    return new String[]{
                            lower(res),
                            lower(ck.substring(0, 32)),
                            lower(ik.substring(0, 32))
                    };
                } else {
                    JoanTrace.note("aka resp lens res="
                            + (res == null ? -1 : res.length()) + " ck="
                            + (ck == null ? -1 : ck.length()) + " ik="
                            + (ik == null ? -1 : ik.length()));
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
