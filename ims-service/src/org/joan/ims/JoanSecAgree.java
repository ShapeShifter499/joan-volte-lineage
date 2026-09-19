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
    /** "esp" or "ah"; "esp" when the row said nothing (RFC default). */
    final String prot;
    /** "trans" or "tun"; "trans" when the row said nothing. */
    final String mod;

    JoanSecAgree(String alg, String ealg, long spiC, long spiS,
            int portC, int portS, float q) {
        this(alg, ealg, spiC, spiS, portC, portS, q, "esp", "trans");
    }

    JoanSecAgree(String alg, String ealg, long spiC, long spiS,
            int portC, int portS, float q, String prot, String mod) {
        this.alg = alg;
        this.ealg = ealg;
        this.spiC = spiC;
        this.spiS = spiS;
        this.portC = portC;
        this.portS = portS;
        this.q = q;
        this.prot = prot;
        this.mod = mod;
    }

    /** First mechanism only — kept for host tests of the C parser. */
    static JoanSecAgree parse(String value) {
        List<JoanSecAgree> all = parseAll(value);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Best supported mechanism from a Security-Server value (comma list
     * or a single mechanism), the way the reference stack chooses:
     * {@code RegParameter::ChoosePreferredSecurityServer} keeps a server
     * row only when its whole tuple matches a mechanism we offered --
     * mechanism, alg, ealg, protocol and mode, each with its RFC default
     * when the row omits it -- and among the survivors the highest
     * {@code q} wins with the first listed kept on a tie.
     *
     * <p>Rows we cannot use are skipped here and named by
     * {@link #offerSummary} -- nothing disappears silently again.
     */
    static JoanSecAgree select(String value) {
        List<JoanSecAgree> all = parseAll(value);
        JoanSecAgree best = null;
        for (JoanSecAgree m : all) {
            if (!supportedByOffer(m)) {
                continue;
            }
            if (best == null || m.q > best.q) {
                best = m;
            }
        }
        return best;
    }

    /** The reference stack's tuple match against our own offer. */
    private static boolean supportedByOffer(JoanSecAgree m) {
        if (!JoanSipCrypto.supportedAlg(m.alg)
                || !JoanSipCrypto.supportedEalg(m.ealg)) {
            return false;
        }
        if (!"esp".equals(m.prot) || !"trans".equals(m.mod)) {
            return false;
        }
        return JoanSipCrypto.algOffered(m.alg)
                && JoanSipCrypto.ealgOffered(m.ealg);
    }

    /**
     * Every mechanism the P-CSCF offered, as "alg/ealg" pairs with the
     * chosen one marked and every rejected one annotated with why.
     *
     * <p>Which mechanism was selected decides whether the ESP SA carries
     * encryption at all, and a network offering only null encryption
     * exercises a path an aes-cbc network never touches. When a REGISTER
     * dies inside the SA, knowing what else was available is the
     * difference between a guess and a next step -- and a mechanism list
     * that silently drops rows it could not parse is indistinguishable
     * from a network that offered one mechanism, which is exactly the
     * reading this field was trusted for and got wrong.
     */
    static String offerSummary(String value, JoanSecAgree chosen) {
        String body = value == null ? "" : value.trim();
        if (body.regionMatches(true, 0, "Security-Server:", 0, 16)) {
            body = body.substring(16).trim();
        } else if (body.regionMatches(true, 0, "Security-Client:", 0, 16)) {
            body = body.substring(16).trim();
        }
        StringBuilder sb = new StringBuilder();
        for (String raw : splitMechanisms(body)) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            String mech = raw.trim();
            if (mech.isEmpty()) {
                continue;
            }
            if (!mech.toLowerCase(java.util.Locale.ROOT)
                    .contains("ipsec-3gpp")) {
                sb.append(mechName(mech)).append("(not-ipsec)");
                continue;
            }
            JoanSecAgree m = parseOne(mech);
            if (m == null) {
                sb.append(mechName(mech)).append("(unparsed)");
                continue;
            }
            sb.append(m.alg).append('/').append(m.ealg);
            if (chosen != null && m.alg.equals(chosen.alg)
                    && m.ealg.equals(chosen.ealg)
                    && m.prot.equals(chosen.prot)
                    && m.mod.equals(chosen.mod)) {
                sb.append('*');
                continue;
            }
            String why = rejectReason(m);
            if (why != null) {
                sb.append('(').append(why).append(')');
            }
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    /** First semicolon-delimited token, as a row label for rejects. */
    private static String mechName(String mech) {
        int i = mech.indexOf(';');
        String n = (i < 0 ? mech : mech.substring(0, i)).trim();
        return n.length() > 24 ? n.substring(0, 24) : n;
    }

    private static String rejectReason(JoanSecAgree m) {
        if (!"esp".equals(m.prot)) {
            return "prot";
        }
        if (!"trans".equals(m.mod)) {
            return "mod";
        }
        if (!JoanSipCrypto.supportedAlg(m.alg)) {
            return "alg";
        }
        if (!JoanSipCrypto.supportedEalg(m.ealg)) {
            return "ealg";
        }
        if (!JoanSipCrypto.algOffered(m.alg)
                || !JoanSipCrypto.ealgOffered(m.ealg)) {
            return "not-offered";
        }
        return null;
    }

    /**
     * How many comma-separated mechanisms the header actually contains,
     * parsed or not.
     *
     * <p>Against {@link #parseAll}'s size this says whether anything was
     * dropped. A P-CSCF that writes several values into one parameter
     * without quoting them -- {@code ealg=aes-cbc,null} -- has its single
     * mechanism split in two here, and both halves are then unreadable:
     * one has no SPIs, the other has no mechanism name. Two counts that
     * disagree is the signal to go and read the raw header.
     */
    static int rawMechanismCount(String value) {
        if (value == null) {
            return 0;
        }
        String body = value.trim();
        if (body.regionMatches(true, 0, "Security-Server:", 0, 16)) {
            body = body.substring(16).trim();
        } else if (body.regionMatches(true, 0, "Security-Client:", 0, 16)) {
            body = body.substring(16).trim();
        }
        return splitMechanisms(body).size();
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

    /**
     * The Security-Client offer. Filtered by the carrier's declared
     * algorithm set when one is loaded, full otherwise; a mask that
     * excludes everything implementable falls back to the full offer,
     * because a REGISTER that offers no mechanism cannot draw a
     * challenge at all. Encryption stays ahead of null so a network
     * that offers both at equal preference selects the protected SA.
     */
    static String cartesianClientValue(JoanSipBuilder.Params p) {
        StringBuilder sb = new StringBuilder();
        for (String alg : JoanSipCrypto.OFFER_ALGS) {
            if (!JoanSipCrypto.algOffered(alg)) {
                continue;
            }
            for (String ealg : JoanSipCrypto.OFFER_EALGS) {
                if (!JoanSipCrypto.ealgOffered(ealg)) {
                    continue;
                }
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
        if (sb.length() == 0) {
            /* Mask left nothing offerable (a 3DES-only carrier, say).
             * Offering nothing cannot register; offering everything is
             * what every profile-less carrier already sends. */
            for (String alg : JoanSipCrypto.OFFER_ALGS) {
                for (String ealg : JoanSipCrypto.OFFER_EALGS) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append("ipsec-3gpp; alg=").append(alg)
                            .append("; ealg=").append(ealg)
                            .append("; prot=esp; mod=trans; spi-c=")
                            .append(p.spiC)
                            .append("; spi-s=").append(p.spiS)
                            .append("; port-c=").append(p.portC)
                            .append("; port-s=").append(p.portS);
                }
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
        String prot = kv(buf, "prot");
        if (prot == null || prot.isEmpty()
                || prot.equalsIgnoreCase("esp")) {
            prot = "esp";
        } else {
            prot = prot.trim().toLowerCase(java.util.Locale.ROOT);
        }
        String mod = kv(buf, "mod");
        if (mod == null || mod.isEmpty()) {
            mod = "trans";
        } else if (mod.equalsIgnoreCase("trans")
                || mod.equalsIgnoreCase("transport")) {
            mod = "trans";
        } else {
            mod = "tun";
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
                portC.intValue(), portS.intValue(), q, prot, mod);
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
