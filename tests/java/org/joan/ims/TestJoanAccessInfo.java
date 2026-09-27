package org.joan.ims;

/**
 * P-Access-Network-Info's cell id. The field widths are AOSP's
 * (AccessNetworkInfoFormatter.cpp): MCC and MNC as their own digits, the
 * TAC as four hex, the 28-bit ECI as seven, lower case.
 *
 * The refusals matter as much as the successes. This header is what an
 * operator reads for location, so a wrong cell id is worse than a missing
 * one, and every case below that cannot be formatted honestly must come
 * back as the bare access type rather than as a guess.
 */
public final class TestJoanAccessInfo {
    private static int fail;

    private static void check(boolean ok, String what) {
        if (ok) {
            System.out.println("ok   " + what);
        } else {
            System.out.println("FAIL " + what);
            fail++;
        }
    }

    public static void main(String[] args) {
        String lte = "3GPP-E-UTRAN-FDD";

        // Digi.Mobil RO: MCC 226, MNC 05, 2-digit MNC.
        check("3GPP-E-UTRAN-FDD;utran-cell-id-3gpp=22605006401a2b3c"
                        .equals(JoanAccessInfo.pani(lte, "226", "05",
                                0x0064, 0x1a2b3c)),
                "a 2-digit MNC gives MCC+MNC then 4 hex TAC and 7 hex ECI");

        // 3-digit MNC, e.g. 310-260.
        check("3GPP-E-UTRAN-FDD;utran-cell-id-3gpp=310260ffff0000001"
                        .equals(JoanAccessInfo.pani(lte, "310", "260",
                                0xFFFF, 1)),
                "a 3-digit MNC is one digit longer, widths unchanged");

        check(JoanAccessInfo.pani(lte, "226", "05", 0, 0)
                        .endsWith("=2260500000000000"),
                "zero TAC and zero ECI still pad to their full widths");

        check(JoanAccessInfo.pani(lte, "226", "05", 0xFFFF, 0x0FFFFFFF)
                        .endsWith("=22605fffffffffff"),
                "the top of both ranges is formatted, not rejected");

        // Everything that cannot be known honestly stays bare.
        check(lte.equals(JoanAccessInfo.pani(lte, null, "05", 1, 1)),
                "a null MCC yields the bare access type, not a guess");
        check(lte.equals(JoanAccessInfo.pani(lte, "226", null, 1, 1)),
                "a null MNC yields the bare access type");
        check(lte.equals(JoanAccessInfo.pani(lte, "22", "05", 1, 1)),
                "a short MCC is refused: it is not a 3-digit country code");
        check(lte.equals(JoanAccessInfo.pani(lte, "226", "5", 1, 1)),
                "a 1-digit MNC is refused rather than silently padded");
        check(lte.equals(JoanAccessInfo.pani(lte, "226", "0500", 1, 1)),
                "a 4-digit MNC is refused");
        check(lte.equals(JoanAccessInfo.pani(lte, "2x6", "05", 1, 1)),
                "a non-digit in the PLMN is refused");
        check(lte.equals(JoanAccessInfo.pani(lte, "226", "05", -1, 1)),
                "a negative TAC is refused");
        check(lte.equals(JoanAccessInfo.pani(lte, "226", "05", 1, -1)),
                "a negative cell id is refused");
        check(lte.equals(JoanAccessInfo.pani(lte, "226", "05",
                        Integer.MAX_VALUE, 1)),
                "Integer.MAX_VALUE is how Android spells 'unset', not a TAC");
        check(lte.equals(JoanAccessInfo.pani(lte, "226", "05", 1,
                        Integer.MAX_VALUE)),
                "and an unset cell id is refused the same way");
        check(lte.equals(JoanAccessInfo.pani(lte, "226", "05", 0x10000, 1)),
                "a TAC past 16 bits is refused rather than truncated");

        check("".equals(JoanAccessInfo.pani(null, "226", "05", 1, 1)),
                "no access type yields nothing at all");
        check("IEEE-802.11".equals(JoanAccessInfo.pani("IEEE-802.11",
                        null, null, -1, -1)),
                "a non-3GPP access type passes through untouched");

        if (fail != 0) {
            System.out.println("pani cell id: FAIL " + fail);
            System.exit(1);
        }
        System.out.println("ok   pani cell id tests (17 checks)");
    }
}
