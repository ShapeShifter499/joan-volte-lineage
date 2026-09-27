package org.joan.ims;

import java.util.ArrayList;
import java.util.List;

/**
 * EF_DIR: the card's own list of what it holds.
 *
 * <p>Every UICC carries an application directory at {@code 3F00/2F00}
 * listing each application with its full AID. That is the authoritative
 * answer to "does this card have an ISIM, and under what identifier" --
 * a question we had been answering by guessing an AID and reading the
 * failure, which cannot tell "no such application" apart from "not under
 * the identifier you asked for".
 *
 * <p>AOSP does not guess either: {@code IccCardApplicationStatus.aid}
 * arrives per-application from the modem, and {@code UiccPkcs15} names
 * this file as the route it should be using
 * ("TODO: ... read EF_DIR to find PKCS15").
 *
 * <p>The parser is static and takes bytes, so the host tests exercise it
 * without a card: TS 102 221 11.1.1.3 gives each record as an
 * application template, tag {@code 0x61}, containing the AID under tag
 * {@code 0x4F} and an optional label under {@code 0x50}.
 */
final class JoanEfDir {

    /** MF-relative file id of the application directory. */
    static final int EF_DIR = 0x2F00;
    /** Path to the master file, which is where EF_DIR lives. */
    static final String MF = "3F00";
    /** APDU instruction bytes, TS 102 221 10.1. */
    static final int CMD_READ_RECORD = 0xB2;
    static final int CMD_GET_RESPONSE = 0xC0;
    /** Read the record addressed absolutely by P1. */
    static final int READ_ABSOLUTE = 0x04;
    /** A card cannot hold an unbounded directory; refuse to spin. */
    static final int MAX_RECORDS = 16;

    /** BER-TLV FCP template tag, TS 102 221 11.1.1.3. */
    static final int FCP_TEMPLATE = 0x62;
    /**
     * Byte offsets in the legacy GET RESPONSE structure, TS 51.011 9.2.1.
     *
     * <p>These mirror AOSP's own {@code IccFileHandler} constants, which
     * is the point: that is the format the platform actually delivers.
     */
    static final int LEGACY_LEN = 15;
    static final int LEGACY_FILE_SIZE_HI = 2;
    static final int LEGACY_FILE_SIZE_LO = 3;
    static final int LEGACY_FILE_TYPE = 6;
    static final int LEGACY_STRUCTURE = 13;
    static final int LEGACY_RECORD_LENGTH = 14;
    /** {@code IccFileHandler.TYPE_EF}. */
    static final int LEGACY_TYPE_EF = 4;
    /** {@code IccFileHandler.EF_TYPE_LINEAR_FIXED}. */
    static final int LEGACY_LINEAR_FIXED = 1;

    /** 3GPP application identifier prefixes, TS 101 220 annex E. */
    static final String ISIM_PREFIX = "A0000000871004";
    static final String USIM_PREFIX = "A0000000871002";

    private JoanEfDir() {}

    /**
     * The first few bytes of a card response as hex, for a trace line.
     *
     * <p>Structural bytes only, and bounded: enough to tell an FCP from
     * the legacy structure from an empty answer when something fails on a
     * card nobody here can hold.
     */
    static String head(byte[] b) {
        if (b == null || b.length == 0) {
            return "none";
        }
        int n = Math.min(4, b.length);
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) {
            sb.append(String.format(java.util.Locale.ROOT, "%02x", b[i] & 0xff));
        }
        return sb.toString();
    }

    /**
     * AIDs carried by one EF_DIR record, uppercase hex.
     *
     * <p>Empty rather than null for anything unparseable: an unused
     * record is padded with {@code 0xFF} and is not an error, and a card
     * that answers with something unexpected must not take the caller
     * down with it.
     */
    static List<String> parseRecord(byte[] rec) {
        List<String> out = new ArrayList<>();
        if (rec == null) {
            return out;
        }
        int i = 0;
        while (i + 1 < rec.length) {
            int tag = rec[i] & 0xff;
            if (tag == 0xff || tag == 0x00) {
                break;              /* padding: the rest is unused */
            }
            int len = rec[i + 1] & 0xff;
            if (len == 0 || i + 2 + len > rec.length) {
                break;
            }
            if (tag == 0x61) {
                /* Application template: the AID is inside it. */
                int j = i + 2;
                int end = j + len;
                while (j + 1 < end) {
                    int t2 = rec[j] & 0xff;
                    int l2 = rec[j + 1] & 0xff;
                    if (l2 == 0 || j + 2 + l2 > end) {
                        break;
                    }
                    if (t2 == 0x4f) {
                        out.add(JoanSipCrypto.hex(rec, j + 2, l2)
                                .toUpperCase(java.util.Locale.ROOT));
                    }
                    j += 2 + l2;
                }
            }
            i += 2 + len;
        }
        return out;
    }

    /**
     * Record length and count from a SELECT response, or null.
     *
     * <p>TS 102 221 11.1.1.3: the FCP template is tag {@code 0x62}, and
     * the File Descriptor inside it, tag {@code 0x82}, carries the record
     * length and the number of records when it is five bytes long. A
     * two-byte descriptor belongs to a file that is not record-based,
     * which EF_DIR is not allowed to be -- so that answers null rather
     * than a guess.
     */
    /**
     * Record length and count from a GET RESPONSE, in whichever format
     * the platform handed back, or null.
     *
     * <p>This exists because the first version of this file understood
     * only the UICC's BER-TLV FCP, and the platform does not deliver one.
     * {@code iccExchangeSimIO} reaches the card through
     * {@code RIL_REQUEST_SIM_IO}, and the RIL normalises the card's
     * answer into the flat 15-byte structure of TS 51.011 9.2.1 -- AOSP's
     * {@code IccFileHandler} parses exactly that and contains no BER-TLV
     * code at all, on the USIM path as much as anywhere else. Asking a
     * live card gave "no record geometry" on every read as a result, and
     * the fallback to guessed AIDs hid it.
     *
     * <p>Both are accepted, chosen by the leading tag, because a RIL that
     * passes the card's FCP through untouched is not forbidden from doing
     * so and the cost of accepting it is one comparison.
     */
    static int[] parseRecordInfo(byte[] resp) {
        if (resp == null || resp.length == 0) {
            return null;
        }
        boolean fcpFirst = (resp[0] & 0xff) == FCP_TEMPLATE;
        int[] first = fcpFirst
                ? parseFcpRecordInfo(resp) : parseLegacyRecordInfo(resp);
        if (first != null) {
            return first;
        }
        return fcpFirst
                ? parseLegacyRecordInfo(resp) : parseFcpRecordInfo(resp);
    }

    /**
     * Record geometry from the legacy GET RESPONSE structure, or null.
     *
     * <p>TS 51.011 9.2.1, and AOSP's reading of it: the file size sits at
     * bytes 2-3, the record length at byte 14, and the record count is
     * the quotient. The file type and structure bytes are checked rather
     * than assumed -- a transparent file answers through this same call
     * and would otherwise yield a confident, wrong geometry.
     */
    static int[] parseLegacyRecordInfo(byte[] d) {
        if (d == null || d.length < LEGACY_LEN) {
            return null;
        }
        if ((d[LEGACY_FILE_TYPE] & 0xff) != LEGACY_TYPE_EF
                || (d[LEGACY_STRUCTURE] & 0xff) != LEGACY_LINEAR_FIXED) {
            return null;
        }
        int recLen = d[LEGACY_RECORD_LENGTH] & 0xff;
        int size = ((d[LEGACY_FILE_SIZE_HI] & 0xff) << 8)
                | (d[LEGACY_FILE_SIZE_LO] & 0xff);
        if (recLen <= 0 || size < recLen) {
            return null;
        }
        int count = size / recLen;
        return count > 0 ? new int[] { recLen, count } : null;
    }

    static int[] parseFcpRecordInfo(byte[] fcp) {
        if (fcp == null || fcp.length < 4) {
            return null;
        }
        int i = 0;
        /* Skip to the FCP template's contents if it is wrapped in one. */
        if ((fcp[0] & 0xff) == 0x62) {
            i = 2;
        }
        while (i + 1 < fcp.length) {
            int tag = fcp[i] & 0xff;
            int len = fcp[i + 1] & 0xff;
            if (len == 0 || i + 2 + len > fcp.length) {
                return null;
            }
            if (tag == 0x82) {
                if (len < 5) {
                    return null;     /* not record-based */
                }
                int recLen = ((fcp[i + 4] & 0xff) << 8) | (fcp[i + 5] & 0xff);
                int recCount = fcp[i + 6] & 0xff;
                if (recLen <= 0 || recCount <= 0) {
                    return null;
                }
                return new int[] { recLen, recCount };
            }
            i += 2 + len;
        }
        return null;
    }

    /**
     * Transparent-file size from a SELECT response, or -1.
     *
     * <p>TS 102 221 11.1.1.4.1: tag {@code 0x80} in the FCP carries the
     * file size in bytes. Record-based files answer through
     * {@link #parseFcpRecordInfo} instead.
     */
    static int parseFcpFileSize(byte[] fcp) {
        if (fcp == null || fcp.length < 4) {
            return -1;
        }
        int i = (fcp[0] & 0xff) == 0x62 ? 2 : 0;
        while (i + 1 < fcp.length) {
            int tag = fcp[i] & 0xff;
            int len = fcp[i + 1] & 0xff;
            if (len == 0 || i + 2 + len > fcp.length) {
                return -1;
            }
            if (tag == 0x80 && len >= 2) {
                int size = ((fcp[i + 2] & 0xff) << 8) | (fcp[i + 3] & 0xff);
                return size > 0 ? size : -1;
            }
            i += 2 + len;
        }
        return -1;
    }

    /** The first AID starting with {@code prefix}, or null. */
    static String firstWithPrefix(List<String> aids, String prefix) {
        if (aids == null || prefix == null) {
            return null;
        }
        String want = prefix.toUpperCase(java.util.Locale.ROOT);
        for (String a : aids) {
            if (a != null && a.startsWith(want)) {
                return a;
            }
        }
        return null;
    }
}
