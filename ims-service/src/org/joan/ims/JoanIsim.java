package org.joan.ims;

import java.util.ArrayList;
import java.util.List;

/**
 * The ISIM's own files, under ADF_ISIM (TS 31.103).
 *
 * <p>The framework already exposes these through {@code getIsimImpi},
 * {@code getIsimDomain}, {@code getIsimImpu} and {@code getIsimPcscf},
 * and when it finds an ISIM those are the right answer. This exists for
 * when it does not: EF_DIR now tells us whether the card actually holds
 * an ISIM, and a card that has one while the framework reports
 * {@code impi=absent} is a case worth being able to read directly rather
 * than falling back to identities derived from the IMSI.
 *
 * <p>Parsing follows AOSP's {@code IsimUiccRecords.isimTlvToString}: walk
 * the record's TLVs, take tag {@code 0x80}, decode UTF-8. All of it is
 * static and takes bytes, so the host tests exercise it with no card.
 */
final class JoanIsim {

    /** File identifiers under ADF_ISIM, TS 31.103 4.2. */
    static final int EF_IMPI = 0x6F02;
    static final int EF_DOMAIN = 0x6F03;
    static final int EF_IMPU = 0x6F04;
    static final int EF_IST = 0x6F07;
    static final int EF_PCSCF = 0x6F09;

    /** TS 31.103: every one of these files wraps its value in tag 0x80. */
    static final int TAG_VALUE = 0x80;

    /** EF_IST service numbers we act on, TS 31.103 4.2.7. */
    static final int IST_PCSCF_ADDRESS = 5;

    private JoanIsim() {}

    /** The tag-0x80 value of a record, or null. */
    static byte[] tag80(byte[] record) {
        if (record == null) {
            return null;
        }
        int i = 0;
        while (i + 1 < record.length) {
            int tag = record[i] & 0xff;
            int len = record[i + 1] & 0xff;
            if (tag == 0xff || tag == 0x00) {
                return null;          /* padding: nothing more in here */
            }
            if (i + 2 + len > record.length) {
                return null;
            }
            if (tag == TAG_VALUE) {
                byte[] out = new byte[len];
                System.arraycopy(record, i + 2, out, 0, len);
                return out;
            }
            i += 2 + len;
        }
        return null;
    }

    /** The tag-0x80 value as text, or null. AOSP decodes these UTF-8. */
    static String text(byte[] record) {
        byte[] v = tag80(record);
        if (v == null || v.length == 0) {
            return null;
        }
        try {
            String s = new String(v, "UTF-8").trim();
            return s.isEmpty() ? null : s;
        } catch (java.io.UnsupportedEncodingException e) {
            return null;
        }
    }

    /**
     * One EF_PCSCF record as an address or a name.
     *
     * <p>TS 31.103 4.2.8 puts an address-type byte first -- 00 FQDN, 01
     * IPv4, 02 IPv6 -- and AOSP hands the whole value back as a string
     * without stripping it, leaving consumers to cope. We strip it when
     * it is present and the remainder still reads as text, and otherwise
     * return the value untouched, because cards are also found storing a
     * bare {@code sip:} URI with no type byte at all.
     *
     * <p>Whatever comes back is only a candidate: it still has to satisfy
     * {@code JoanImsDiscovery.literal} or {@code isHostname} before
     * anything is done with it, so a misread here costs a dropped entry
     * rather than a lookup for nonsense.
     */
    static String pcscf(byte[] record) {
        byte[] v = tag80(record);
        if (v == null || v.length == 0) {
            return null;
        }
        int off = 0;
        int type = v[0] & 0xff;
        if (v.length > 1 && (type == 0x00 || type == 0x01 || type == 0x02)) {
            off = 1;
        }
        try {
            String s = new String(v, off, v.length - off, "UTF-8").trim();
            if (s.isEmpty()) {
                return null;
            }
            if (s.regionMatches(true, 0, "sip:", 0, 4)) {
                s = s.substring(4);
            }
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Whether EF_IST marks a service available.
     *
     * <p>TS 31.103 4.2.7: services are numbered from 1, eight to a byte,
     * least significant bit first. Service n therefore lives in byte
     * {@code (n-1)/8} at bit {@code (n-1)%8}.
     *
     * <p>A short or absent table answers false, which is the useful
     * direction: it means "do not claim this service exists", never "skip
     * the file we were going to read anyway".
     */
    static boolean istService(byte[] ist, int service) {
        if (ist == null || service < 1) {
            return false;
        }
        int index = (service - 1) / 8;
        int bit = (service - 1) % 8;
        if (index >= ist.length) {
            return false;
        }
        return ((ist[index] >> bit) & 1) == 1;
    }

    /** Non-null, non-empty strings from a set of records, in order. */
    static List<String> texts(List<byte[]> records) {
        List<String> out = new ArrayList<>();
        if (records == null) {
            return out;
        }
        for (byte[] r : records) {
            String s = text(r);
            if (s != null && !out.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }
}
