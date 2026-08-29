package org.joan.ims;

import java.util.ArrayList;
import java.util.List;

/**
 * RFC 3329 {@code ipsec-3gpp} parse + selection.
 *
 * Public IMS stacks do not hardcode one cipher. The UE offers a set;
 * the P-CSCF returns one or more mechanisms with a q-value; the UE
 * takes the highest-q mechanism it actually implements (PhhIms
 * {@code SipSecurityServerSelector} behaviour; Kamailio documents the
 * same names from TS 33.203 Annex I).
 */
final class JoanSecAgree {
    final String alg;
    final String ealg;
    final long spiC;
    final long spiS;
    final int portC;
    final int portS;
    final float q;

    JoanSecAgree(String alg, String ealg, long spiC, long spiS,
                 int portC, int portS, float q) {
        this.alg = alg;
        this.ealg = ealg;
        this.spiC = spiC;
        this.spiS = spiS;
        this.portC = portC;
        this.portS = portS;
        this.q = q;
    }

    /** First mechanism only — kept for host tests of the C parser. */
    static JoanSecAgree parse(String value) {
        List<JoanSecAgree> all = parseAll(value);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Best supported mechanism from a Security-Server value (comma list
     * or a single mechanism). Highest q wins; unsupported rows skipped.
     */
    static JoanSecAgree select(String value) {
        List<JoanSecAgree> all = parseAll(value);
        JoanSecAgree best = null;
        for (JoanSecAgree m : all) {
            if (!JoanSipCrypto.supportedAlg(m.alg)
                    || !JoanSipCrypto.supportedEalg(m.ealg)) {
                continue;
            }
            if (best == null || m.q > best.q) {
                best = m;
            }
        }
        return best;
    }

    static List<JoanSecAgree> parseAll(String value) {
        List<JoanSecAgree> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        String body = value.trim();
        if (body.regionMatches(true, 0, "Security-Server:", 0, 16)) {
            body = body.substring(16).trim();
        } else if (body.regionMatches(true, 0, "Security-Client:", 0, 16)) {
            body = body.substring(16).trim();
        }
        for (String mech : splitMechanisms(body)) {
            JoanSecAgree m = parseOne(mech);
            if (m != null) {
                out.add(m);
            }
        }
        return out;
    }

    static String cartesianClientValue(JoanSipBuilder.Params p) {
        StringBuilder sb = new StringBuilder();
        for (String alg : JoanSipCrypto.OFFER_ALGS) {
            for (String ealg : JoanSipCrypto.OFFER_EALGS) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append("ipsec-3gpp; alg=").append(alg)
                        .append("; ealg=").append(ealg)
                        .append("; prot=esp; mod=trans; spi-c=").append(p.spiC)
                        .append("; spi-s=").append(p.spiS)
                        .append("; port-c=").append(p.portC)
                        .append("; port-s=").append(p.portS);
            }
        }
        return sb.toString();
    }

    private static JoanSecAgree parseOne(String mech) {
        String buf = mech.trim();
        if (!buf.toLowerCase(java.util.Locale.ROOT).contains("ipsec-3gpp")) {
            return null;
        }
        Long spiC = kvU32(buf, "spi-c");
        Long spiS = kvU32(buf, "spi-s");
        Long portC = kvU32(buf, "port-c");
        Long portS = kvU32(buf, "port-s");
        if (spiC == null || spiS == null || portC == null || portS == null) {
            return null;
        }
        if (spiC == 0 || spiS == 0 || portC == 0 || portS == 0) {
            return null;
        }
        if (portC > 65535 || portS > 65535) {
            return null;
        }
        String alg = kv(buf, "alg");
        String ealg = kv(buf, "ealg");
        if (alg == null || alg.isEmpty()) {
            alg = JoanSipCrypto.ALG_SHA1_96;
        }
        if (ealg == null || ealg.isEmpty()) {
            ealg = JoanSipCrypto.EALG_NULL;
        }
        float q = 0f;
        String qs = kv(buf, "q");
        if (qs != null) {
            try {
                q = Float.parseFloat(qs);
            } catch (NumberFormatException ignored) {
                q = 0f;
            }
        }
        return new JoanSecAgree(alg, ealg, spiC, spiS,
                portC.intValue(), portS.intValue(), q);
    }

    private static List<String> splitMechanisms(String header) {
        List<String> mechanisms = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
            } else if (c == ',' && !inQuotes) {
                String m = current.toString().trim();
                if (!m.isEmpty()) {
                    mechanisms.add(m);
                }
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        String m = current.toString().trim();
        if (!m.isEmpty()) {
            mechanisms.add(m);
        }
        return mechanisms;
    }

    private static String kv(String mech, String key) {
        String needle = key + "=";
        int p = 0;
        while (p < mech.length()) {
            int i = indexOfKey(mech, needle, p);
            if (i < 0) {
                return null;
            }
            int v = i + needle.length();
            int e = v;
            while (e < mech.length() && mech.charAt(e) != ';') {
                e++;
            }
            return mech.substring(v, e).trim();
        }
        return null;
    }

    private static int indexOfKey(String mech, String needle, int from) {
        int i = from;
        while (i <= mech.length() - needle.length()) {
            if (mech.regionMatches(true, i, needle, 0, needle.length())) {
                boolean start = (i == 0)
                        || mech.charAt(i - 1) == ';'
                        || mech.charAt(i - 1) == ' '
                        || mech.charAt(i - 1) == ',';
                if (start) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    private static Long kvU32(String mech, String key) {
        String s = kv(mech, key);
        if (s == null || s.isEmpty()) {
            return null;
        }
        int base = 10;
        int i = 0;
        if (s.length() > 2 && s.charAt(0) == '0'
                && (s.charAt(1) == 'x' || s.charAt(1) == 'X')) {
            base = 16;
            i = 2;
        }
        long v = 0;
        for (; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            int d;
            if (c >= '0' && c <= '9') {
                d = c - '0';
            } else if (base == 16 && c >= 'a' && c <= 'f') {
                d = c - 'a' + 10;
            } else {
                break;
            }
            v = v * base + d;
        }
        return v;
    }
}
