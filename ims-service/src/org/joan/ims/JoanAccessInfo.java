package org.joan.ims;

import java.util.Locale;

/**
 * The {@code utran-cell-id-3gpp} parameter of P-Access-Network-Info.
 *
 * <p>TS 24.229 7.2A.4 wants the access type followed by the serving
 * cell, and the reference stack never omits it: AOSP's
 * AccessNetworkInfoFormatter.cpp has no branch of {@code GetAccessInfo}
 * that emits an access type alone -- it uses a supplied cell string, else
 * a cached one, else formats the PLMN, TAC and Cell Id by hand, with
 * separate spellings for a 2-digit and a 3-digit MNC. joan sent the bare
 * token for a long time, which is a real divergence in the one header a
 * core reads for location.
 *
 * <p>The field widths here are AOSP's. For E-UTRAN it prints the MCC and
 * MNC as their own digits, then the TAC as four hex and the E-UTRAN Cell
 * Identifier as seven -- {@code %02x%02x} over two TAC bytes and
 * {@code %02x%02x%02x%x} over the 28-bit ECI. Lower case, also AOSP's.
 *
 * <p>No Android imports, so the host tests exercise the real formatter.
 */
final class JoanAccessInfo {

    /** 3GPP TS 23.003: TAC is 16 bits, the E-UTRAN Cell Identifier 28. */
    private static final int TAC_MAX = 0xFFFF;
    private static final int ECI_MAX = 0x0FFFFFFF;

    private JoanAccessInfo() {}

    /**
     * {@code <access-type>;utran-cell-id-3gpp=<MCC><MNC><TAC><ECI>}, or
     * the access type alone when the cell is not knowable.
     *
     * <p>Falling back to the bare token is deliberate. Inventing a cell
     * id would put a false location in the one header an operator reads
     * for location, and a wrong answer there is worse than a missing one
     * -- this is exactly the fallback-that-lies shape that once put an
     * IMSI on screen as a caller id. The caller records why it was bare.
     */
    static String pani(String accessType, String mcc, String mnc,
                       int tac, int cellId) {
        if (accessType == null || accessType.isEmpty()) {
            return "";
        }
        if (!plmnOk(mcc) || !plmnOk(mnc) || mcc.length() != 3
                || mnc.length() < 2 || mnc.length() > 3
                || tac < 0 || tac > TAC_MAX
                || cellId < 0 || cellId > ECI_MAX) {
            return accessType;
        }
        return accessType + ";utran-cell-id-3gpp=" + mcc + mnc
                + String.format(Locale.US, "%04x%07x", tac, cellId);
    }

    /** Digits only: anything else is not a PLMN code. */
    private static boolean plmnOk(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
