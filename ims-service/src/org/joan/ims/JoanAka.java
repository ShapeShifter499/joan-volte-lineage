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

    /* AID candidates, tried in order.
     *
     * The long forms are what pmOS read off the bench card with QMI UIM,
     * and they work there -- but the trailing bytes after the 3GPP
     * RID+PIX are an issuer/card suffix, not part of the application
     * identity. Selecting the full 16 bytes only matches a card whose
     * ISIM happens to carry that exact suffix.
     *
     * That is not hypothetical. On the China Mobile tester's card BOTH
     * long AIDs fail to open -- `apdu: open failed status=3` appears 94
     * times against 47 registration attempts, two per attempt -- and the
     * card then authenticates fine through
     * getIccAuthentication(APPTYPE_USIM). A card that answers AKA on its
     * USIM unquestionably HAS a USIM, so the long-AID open failing there
     * is our selector being wrong, not the applet being absent. Which
     * means the identical failure on the ISIM proves nothing about
     * whether that card has an ISIM either -- and if it has one we have
     * been registering with identities derived from the IMSI while the
     * card was holding provisioned ones.
     *
     * The bench card settles it. Its own application list reads:
     *   APPTYPE_USIM  a0000000871002ffffffff8906190000
     *   APPTYPE_ISIM  a0000000871004ffffffff8907030000
     * The ISIM matches the long form below. **The USIM does not** -- we
     * ship ...8907090000 and that card carries ...8906190000 -- so the
     * USIM-by-AID route has never worked on any card we own, and only
     * goes unnoticed because the ISIM route succeeds first here. Note
     * also that the suffix differs between two applications on the SAME
     * card, which is the clearest possible evidence that it is not part
     * of the application's identity.
     *
     * Per TS 101 220 those trailing bytes are a country code, a provider
     * code and a free-form application-provider field; issuers set them
     * as they like. ETSI TS 102 221 SELECT by DF name matches on a
     * PARTIAL AID for exactly this reason, so the 7-byte RID+PIX is
     * tried as well.
     * AOSP does not guess at all: UiccProfile enumerates applications by
     * AppType and takes each AID from the modem's card status, which is
     * what getIccAuthentication resolves against and why that route
     * survived where these did not.
     *
     * Order matters and the long form stays first: it is proven on the
     * bench modem, and this change must not move that card off the path
     * it already works on.
     */
    private static final String[] ISIM_AIDS = {
        "A0000000871004FFFFFFFF8907030000",
        "A0000000871004",
    };
    /* Cards without an ISIM authenticate on the USIM instead. TS 33.203
     * allows IMS AKA against the USIM when no ISIM is present, and plenty
     * of operators ship USIM-only cards -- the stack was ISIM-only and
     * simply stopped on those. */
    private static final String[] USIM_AIDS = {
        "A0000000871002FFFFFFFF8907090000",
        "A0000000871002",
    };
    private static final String ISIM_AID = ISIM_AIDS[0];

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
        String apduAuth = apduAuthenticateAny(tm, nonceRaw, ISIM_AIDS, "ISIM");
        if (apduAuth != null) {
            return apduAuth;
        }

        // Fallback: TelephonyManager.getIccAuthentication with the correct
        // TLV (both tags 0x10 per TS 31.102), for devices whose modem path
        // accepts it. Kept after the proven APDU route.
        String viaIsim = runIccAuthViaGet(tm, nonceRaw,
                TelephonyManager.APPTYPE_ISIM);
        if (viaIsim != null) {
            JoanTrace.note("aka via ISIM getIccAuthentication");
            return viaIsim;
        }

        /* Cards with no ISIM. TS 33.203 allows IMS AKA against the USIM,
         * and an ISIM-only stack simply stops on such a card -- which is
         * what "no ISIM IMPI on this device/SIM yet" was reporting. Try
         * the USIM applet by AID first, then the framework route. */
        String usimApdu = apduAuthenticateAny(tm, nonceRaw, USIM_AIDS, "USIM");
        if (usimApdu != null) {
            return usimApdu;
        }
        String viaUsim = runIccAuthViaGet(tm, nonceRaw,
                TelephonyManager.APPTYPE_USIM);
        if (viaUsim != null) {
            JoanTrace.note("aka via USIM getIccAuthentication");
            return viaUsim;
        }
        JoanTrace.note("aka: no ISIM or USIM route succeeded");
        return null;
    }

    /**
     * The card's real AIDs, read from EF_DIR, or an empty list.
     *
     * <p>This is the difference between asking the card what it has and
     * guessing. `iccExchangeSimIO` is reached by reflection because the
     * six-argument form is not in every platform's public surface; a
     * missing method, a refused permission or an odd card all answer the
     * same way -- empty -- and the AID candidates below still run.
     *
     * <p>AIDs are application identifiers, not subscriber data: nothing
     * read here identifies the user, and nothing else from the card is
     * touched.
     */
    private static java.util.List<String> readEfDir(TelephonyManager tm) {
        java.util.List<String> aids = new java.util.ArrayList<>();
        try {
            java.lang.reflect.Method m = TelephonyManager.class.getMethod(
                    "iccExchangeSimIO", int.class, int.class, int.class,
                    int.class, int.class, String.class);
            byte[] fcp = (byte[]) m.invoke(tm, JoanEfDir.EF_DIR,
                    JoanEfDir.CMD_GET_RESPONSE, 0, 0, 15, JoanEfDir.MF);
            int[] geom = JoanEfDir.parseFcpRecordInfo(fcp);
            if (geom == null) {
                JoanTrace.note("ef_dir: no record geometry");
                return aids;
            }
            int n = Math.min(geom[1], JoanEfDir.MAX_RECORDS);
            for (int rec = 1; rec <= n; rec++) {
                byte[] r = (byte[]) m.invoke(tm, JoanEfDir.EF_DIR,
                        JoanEfDir.CMD_READ_RECORD, rec,
                        JoanEfDir.READ_ABSOLUTE, geom[0], JoanEfDir.MF);
                aids.addAll(JoanEfDir.parseRecord(r));
            }
            JoanTrace.note("ef_dir: records=" + n + " aids=" + aids);
        } catch (Throwable t) {
            JoanTrace.note("ef_dir: unavailable ("
                    + t.getClass().getSimpleName() + ")");
        }
        return aids;
    }

    /**
     * AUTHENTICATE against the first AID candidate the card accepts.
     *
     * <p>The trace names which one worked, because "ISIM apdu" alone
     * could not distinguish a card that matched the long issuer-specific
     * AID from one that only matched the 7-byte 3GPP prefix -- and that
     * distinction is the whole point of trying both.
     */
    private static String apduAuthenticateAny(TelephonyManager tm,
                                              byte[] randAutn,
                                              String[] aids, String what) {
        /* What the card says it has beats anything we can guess. */
        String prefix = "ISIM".equals(what)
                ? JoanEfDir.ISIM_PREFIX : JoanEfDir.USIM_PREFIX;
        String real = JoanEfDir.firstWithPrefix(readEfDir(tm), prefix);
        if (real != null) {
            String r = apduAuthenticate(tm, randAutn, 0, 0, real);
            if (r != null) {
                JoanTrace.note("aka via " + what + " apdu aid=ef_dir");
                return r;
            }
        }
        for (int i = 0; i < aids.length; i++) {
            String r = apduAuthenticate(tm, randAutn, 0, 0, aids[i]);
            if (r != null) {
                JoanTrace.note("aka via " + what + " apdu aid="
                        + (i == 0 ? "full" : "prefix"));
                return r;
            }
        }
        return null;
    }

    /** AUTHENTICATE over a logical channel; returns "RES=.. CK=.. IK=.." */
    private static String apduAuthenticate(TelephonyManager tm, byte[] randAutn,
                                           int off, int channel) {
        return apduAuthenticate(tm, randAutn, off, channel, ISIM_AID);
    }

    private static String apduAuthenticate(TelephonyManager tm, byte[] randAutn,
                                           int off, int channel, String aid) {
        try {
            int ch = channel;
            boolean owned = false;
            if (ch <= 0) {
                android.telephony.IccOpenLogicalChannelResponse r =
                        tm.iccOpenLogicalChannel(aid);
                if (r == null || r.getChannel() <= 0) {
                    /* Name the AID length: a status=3 against the long
                     * form and against the 7-byte prefix mean different
                     * things, and the old line could not tell them apart. */
                    JoanTrace.note("apdu: open failed status="
                            + (r == null ? "null" : r.getStatus())
                            + " aid_len=" + (aid == null ? 0 : aid.length() / 2));
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
                /* Shape-only trace: the status word, the total length,
                 * the AKA tag (0xdb success / 0xdc sync failure) and the
                 * length byte that follows it. Everything after those two
                 * bytes is RES, and RES is key material -- the previous
                 * form logged the first eight bytes, so six bytes of RES
                 * went into a plaintext log on every registration. */
                String swf = resp.substring(resp.length() - 4);
                byte[] dd = hexBytes(resp);
                String shape = (dd == null || dd.length < 2)
                        ? "tag=none"
                        : String.format(Locale.ROOT, "tag=%02x field_len=%d",
                                dd[0] & 0xff, dd[1] & 0xff);
                JoanTrace.note("apdu: len=" + resp.length()
                        + " sw=" + swf + " " + shape);
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
        if (d.length >= 2) {
            int sw = ((d[d.length - 2] & 0xff) << 8) | (d[d.length - 1] & 0xff);
            if (sw == 0x9000) {
                d = java.util.Arrays.copyOf(d, d.length - 2);
            } else if ((sw & 0xFF00) == 0x6100
                    || (sw & 0xF000) == 0x6000
                    || (sw & 0xF000) == 0x9000) {
                JoanTrace.note("apdu: sw=" + String.format(Locale.ROOT, "%04x", sw));
                return null;
            }
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
                                           byte[] nonceRaw, int appType) {
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
            Object r = m.invoke(tm, appType,
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
    /**
     * True when the card answered the AKA challenge with a SYNCHRONISATION
     * FAILURE (TS 31.102 7.1.2.1: tag 0xDC, then AUTS) rather than success
     * (0xDB). This is not a malformed response and not a wrong key -- it
     * means the card's SQN is out of step with the HSS, which is what
     * repeated half-finished registrations cause. Recovering needs a
     * REGISTER carrying auts= (RFC 3310 3.2), which JoanAppRegister now
     * sends. Telling the two apart is what makes that possible: "resync
     * needed" and "parser is broken" demand opposite responses, and a
     * retry never escapes the first one.
     *
     * <p>Shape confirmed against AOSP's IMS stack, which splits the same
     * two tags at the same offset: {@code OsUsimDigestAka::OnResponse()}
     * reads 0xDB as RES/CK/IK and 0xDC as AUTS
     * (native/libimsstack/platform/os/android/device/OsUsim.cpp). When the
     * resync REGISTER is wired, its two rules are in that tree too: the
     * auts parameter is quoted base64 ({@code SipAuHelper.cpp} STR_AUTS)
     * and "when the AUTS is present, the included response parameter is
     * calculated using an empty password, instead of a RES". Both at tag
     * android-17.0.0_r1, referenced only -- no AOSP code is used here.
     */
    static boolean isSyncFailure(String resp) {
        return isSyncFailure(authBytes(resp));
    }

    /**
     * Byte-level form, so the tag/length reasoning is provable on the host:
     * the String forms run through {@code android.util.Base64}, whose
     * android.jar body throws "Stub!" off-device.
     */
    static boolean isSyncFailure(byte[] raw) {
        return raw != null && raw.length >= 2 && (raw[0] & 0xff) == 0xDC;
    }

    /** AUTS octets from a sync failure, or null. */
    static byte[] autsBytes(byte[] raw) {
        if (!isSyncFailure(raw)) {
            return null;
        }
        int len = raw[1] & 0xff;
        if (len <= 0 || 2 + len > raw.length) {
            return null;
        }
        byte[] auts = new byte[len];
        System.arraycopy(raw, 2, auts, 0, len);
        return auts;
    }

    /**
     * The card's answer as bytes, base64 first and hex as a fallback, or
     * null. {@link #hexBytes} throws on anything that is not hex, and this
     * runs on whatever the telephony API handed back -- so the fallback is
     * guarded. An unreadable response is "not a sync failure", never an
     * exception thrown out of the registration flow.
     */
    private static byte[] authBytes(String resp) {
        if (resp == null || resp.isEmpty()) {
            return null;
        }
        byte[] raw = b64(resp);
        if (raw != null && raw.length >= 2) {
            return raw;
        }
        try {
            return hexBytes(resp);
        } catch (RuntimeException e) {
            return raw;
        }
    }

    /**
     * Base64 AUTS from a synchronisation failure, or null. This is the
     * value the resynchronisation REGISTER carries in auts=; it is not key
     * material (it is the card telling the HSS its own SQN) but it is
     * still subscriber state, so it is never traced.
     */
    static String autsBase64(String resp) {
        byte[] auts = autsBytes(authBytes(resp));
        return auts == null ? null : b64e(auts);
    }

    /** AUTS length in octets for a sync failure, or -1. */
    static int autsLength(String resp) {
        byte[] auts = autsBytes(authBytes(resp));
        return auts == null ? -1 : auts.length;
    }

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
                        && res.length() >= 8 && (res.length() % 2) == 0
                        && res.length() / 2 >= 4 && res.length() / 2 <= 16
                        && ck.length() == 32 && ik.length() == 32) {
                    return new String[]{
                            lower(res),
                            lower(ck),
                            lower(ik)
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
        if (raw == null || raw.length < 8) {
            return null;
        }
        return parseUiccTlv(raw);
    }

    /**
     * TS 31.102 / AOSP EAP-AKA success:
     *   DB | resLen | RES | ckLen | CK | ikLen | IK
     * Prefer that length-delimited form. The older "DB | total | RES|CK|IK"
     * layout is only used when the length-delimited fields are absent.
     */
    private static String[] parseUiccTlv(byte[] d) {
        if (d.length < 2 || (d[0] & 0xff) != 0xDB) {
            return null;
        }
        int declared = d[1] & 0xff;
        int p = 2 + declared;
        if (declared >= 4 && declared <= 16
                && p + 1 + 16 + 1 + 16 <= d.length) {
            int ckLen = d[p] & 0xff;
            int ikLen = d[p + 1 + 16] & 0xff;
            if (ckLen == 16 && ikLen == 16) {
                return new String[]{
                        hex(d, 2, declared),
                        hex(d, p + 1, 16),
                        hex(d, p + 1 + 16 + 1, 16)
                };
            }
        }
        if (declared >= 36 && 2 + declared <= d.length) {
            int resLen = declared - 32;
            if (resLen >= 4 && resLen <= 16) {
                return new String[]{
                        hex(d, 2, resLen),
                        hex(d, 2 + resLen, 16),
                        hex(d, 2 + resLen + 16, 16)
                };
            }
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
