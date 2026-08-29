package org.joan.ims;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Carrier-neutral IMS crypto: RFC 3310 / RFC 4169 Digest AKA, and
 * 3GPP TS 33.203 Annex I ESP key wrap.
 *
 * Behaviour taken from public IMS stacks (not copied):
 * Kamailio {@code ims_ipsec_pcscf} (GPL-2.0, Rel-12+ notes hmac-md5-96
 * and des-ede3-cbc deprecated), Doubango tinyIPSec (hmac-sha-1-96 /
 * hmac-md5-96 × aes / 3des / null), PhhIms {@code SipIpsecTransformBuilder}
 * (GPL-2.0: SHA-1 IK padded to 160 bits; omit encryption when ealg is
 * {@code null}), and the proven joan pmOS helper.
 *
 * No Android imports so host tests compile this file alone.
 * Never logs identity, nonce, RES, CK or IK.
 */
final class JoanSipCrypto {
    static final String ALG_SHA1_96 = "hmac-sha-1-96";
    static final String ALG_MD5_96 = "hmac-md5-96";
    static final String EALG_AES_CBC = "aes-cbc";
    static final String EALG_NULL = "null";
    static final String EALG_3DES = "des-ede3-cbc";

    /** 3GPP UE offer, Rel-12 preferred first. Same SPIs/ports on every row. */
    static final String[] OFFER_ALGS = { ALG_SHA1_96, ALG_MD5_96 };
    static final String[] OFFER_EALGS = { EALG_AES_CBC, EALG_NULL };

    private JoanSipCrypto() {}

    /**
     * HTTP Digest AKA response hex (32 lowercase chars).
     * {@code res} is raw RES at its exact length (8 or 16) — do not pad.
     * {@code algorithm} is the 401's algorithm (AKAv1-MD5 or AKAv2-MD5).
     */
    static String akaDigestResponseHex(
            String username,
            String realm,
            String method,
            String uri,
            String nonceB64,
            byte[] res,
            String qop,
            String nc,
            String cnonce,
            String algorithm,
            byte[] ck,
            byte[] ik) {
        if (username == null || realm == null || method == null
                || uri == null || nonceB64 == null || res == null) {
            throw new IllegalArgumentException("digest args");
        }
        byte[] password = digestPassword(algorithm, res, ck, ik);
        byte[] ha1 = md5(
                username.getBytes(ascii()),
                colon(),
                realm.getBytes(ascii()),
                colon(),
                password);
        byte[] ha2 = md5(
                method.getBytes(ascii()),
                colon(),
                uri.getBytes(ascii()));
        String h1 = hex(ha1);
        String h2 = hex(ha2);
        byte[] resp;
        if (qop != null) {
            resp = md5(
                    h1.getBytes(ascii()),
                    colon(),
                    nonceB64.getBytes(ascii()),
                    colon(),
                    nc.getBytes(ascii()),
                    colon(),
                    cnonce.getBytes(ascii()),
                    colon(),
                    qop.getBytes(ascii()),
                    colon(),
                    h2.getBytes(ascii()));
        } else {
            resp = md5(
                    h1.getBytes(ascii()),
                    colon(),
                    nonceB64.getBytes(ascii()),
                    colon(),
                    h2.getBytes(ascii()));
        }
        return hex(resp);
    }

    /** AKAv1 convenience used by host vectors. */
    static String akaDigestResponseHex(
            String username, String realm, String method, String uri,
            String nonceB64, byte[] res, String qop, String nc, String cnonce) {
        return akaDigestResponseHex(username, realm, method, uri, nonceB64,
                res, qop, nc, cnonce, "AKAv1-MD5", null, null);
    }

    /**
     * RFC 3310: password = RES.
     * RFC 4169 AKAv2-MD5: password = HMAC-MD5(RES||IK||CK,
     * "http-digest-akav2-password").
     */
    static byte[] digestPassword(String algorithm, byte[] res,
                                 byte[] ck, byte[] ik) {
        String algo = algorithm == null ? "AKAv1-MD5" : algorithm.trim();
        if (algo.startsWith("\"") && algo.endsWith("\"") && algo.length() >= 2) {
            algo = algo.substring(1, algo.length() - 1);
        }
        String u = algo.toUpperCase(java.util.Locale.ROOT)
                .replace("_", "-");
        if (u.isEmpty() || u.equals("AKAV1-MD5") || u.equals("MD5")) {
            return res;
        }
        if (u.equals("AKAV2-MD5")) {
            if (ck == null || ik == null || ck.length < 16 || ik.length < 16) {
                throw new IllegalArgumentException("AKAv2-MD5 needs CK/IK");
            }
            byte[] key = new byte[res.length + 16 + 16];
            System.arraycopy(res, 0, key, 0, res.length);
            System.arraycopy(ik, 0, key, res.length, 16);
            System.arraycopy(ck, 0, key, res.length + 16, 16);
            return hmacMd5(key,
                    "http-digest-akav2-password".getBytes(ascii()));
        }
        throw new IllegalArgumentException("unsupported AKA " + algo);
    }

    static final class EspKeys {
        final String alg;
        final String ealg;
        final byte[] authKey;
        final int authTruncBits;
        final String androidAuth;   /* IpSecAlgorithm constant name */
        final byte[] encKey;        /* null when integrity-only */
        final String androidEnc;    /* null when integrity-only */

        EspKeys(String alg, String ealg, byte[] authKey, int authTruncBits,
                String androidAuth, byte[] encKey, String androidEnc) {
            this.alg = alg;
            this.ealg = ealg;
            this.authKey = authKey;
            this.authTruncBits = authTruncBits;
            this.androidAuth = androidAuth;
            this.encKey = encKey;
            this.androidEnc = androidEnc;
        }

        boolean hasEncryption() {
            return encKey != null && androidEnc != null;
        }
    }

    /**
     * Wrap CK/IK for the mechanism the P-CSCF selected.
     * Unknown names fail rather than silently using SHA-1/AES.
     */
    static EspKeys espKeys(String alg, String ealg, byte[] ck, byte[] ik) {
        if (ck == null || ik == null || ck.length < 16 || ik.length < 16) {
            throw new IllegalArgumentException("ck/ik");
        }
        String a = norm(alg);
        String e = norm(ealg);
        if (e.isEmpty()) {
            e = EALG_NULL;
        }
        byte[] authKey;
        String androidAuth;
        if (a.equals(ALG_SHA1_96) || a.equals("hmac-sha1-96") || a.equals("sha1")) {
            authKey = new byte[20];
            System.arraycopy(ik, 0, authKey, 0, 16);
            androidAuth = "hmac(sha1)";
            a = ALG_SHA1_96;
        } else if (a.equals(ALG_MD5_96) || a.equals("hmac-md5") || a.equals("md5")) {
            authKey = new byte[16];
            System.arraycopy(ik, 0, authKey, 0, 16);
            androidAuth = "hmac(md5)";
            a = ALG_MD5_96;
        } else {
            throw new IllegalArgumentException("unsupported alg " + alg);
        }

        if (e.equals(EALG_AES_CBC) || e.equals("aes")) {
            byte[] enc = new byte[16];
            System.arraycopy(ck, 0, enc, 0, 16);
            return new EspKeys(a, EALG_AES_CBC, authKey, 96, androidAuth,
                    enc, "cbc(aes)");
        }
        if (e.equals(EALG_NULL) || e.equals("cipher_null")) {
            return new EspKeys(a, EALG_NULL, authKey, 96, androidAuth,
                    null, null);
        }
        if (e.equals(EALG_3DES) || e.equals("3des-cbc") || e.equals("3des")) {
            /* IpSecManager has no 3DES. Rel-12 deprecated it anyway. */
            throw new IllegalArgumentException("unsupported ealg " + ealg
                    + " (des-ede3-cbc not in IpSecManager)");
        }
        throw new IllegalArgumentException("unsupported ealg " + ealg);
    }

    static boolean supportedAlg(String alg) {
        try {
            String a = norm(alg);
            return a.equals(ALG_SHA1_96) || a.equals("hmac-sha1-96")
                    || a.equals("sha1") || a.equals(ALG_MD5_96)
                    || a.equals("hmac-md5") || a.equals("md5");
        } catch (Exception e) {
            return false;
        }
    }

    static boolean supportedEalg(String ealg) {
        String e = norm(ealg);
        if (e.isEmpty()) {
            e = EALG_NULL;
        }
        return e.equals(EALG_AES_CBC) || e.equals("aes")
                || e.equals(EALG_NULL) || e.equals("cipher_null");
    }

    static String hex(byte[] d) {
        return hex(d, 0, d.length);
    }

    static String hex(byte[] d, int off, int len) {
        char[] out = new char[len * 2];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < len; i++) {
            int v = d[off + i] & 0xff;
            out[i * 2] = digits[v >>> 4];
            out[i * 2 + 1] = digits[v & 0x0f];
        }
        return new String(out);
    }

    static byte[] hexBytes(String s) {
        if (s == null || (s.length() & 1) != 0) {
            return null;
        }
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) + lo);
        }
        return out;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static byte[] hmacMd5(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacMD5");
            mac.init(new SecretKeySpec(key, "HmacMD5"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HmacMD5", e);
        }
    }

    private static byte[] md5(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            for (byte[] p : parts) {
                md.update(p);
            }
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5", e);
        }
    }

    private static byte[] colon() {
        return new byte[] { ':' };
    }

    private static java.nio.charset.Charset ascii() {
        return java.nio.charset.StandardCharsets.US_ASCII;
    }
}
