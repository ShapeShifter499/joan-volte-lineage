package org.joan.ims;

/**
 * RFC 4867 octet-aligned AMR / AMR-WB payload format.
 *
 * MediaCodec emits AMR in the *storage* format (RFC 4867 section 5.3): a
 * one-byte header per frame followed by the speech data. RTP wants the
 * *payload* format: a CMR byte, then one ToC byte per frame, then the
 * data. The two headers share a bit layout, which is what makes the
 * conversion small -- but they are not the same thing, and sending
 * storage-format bytes as an RTP payload produces noise that nothing
 * reports as an error.
 *
 *   storage header   P F F F F Q P P      (bit 7 padding, always 0)
 *   RTP ToC          F F F F F Q P P      (bit 7 = another frame follows)
 *
 * Octet-aligned only. Bandwidth-efficient mode packs frames across octet
 * boundaries; it saves a handful of bytes per packet and is not worth the
 * bit-shuffling. Interoperating requires octet-align=1 in the fmtp, which
 * is what most IMS cores use and what this UA must offer.
 *
 * No Android imports: the host tests compile this file directly.
 */
final class JoanAmr {

    /** Speech data bytes per frame type, octet-aligned. Index is FT. */
    /* Speech data bytes per frame type, octet-aligned. Exactly 16 entries
     * each so the index is the FT and there is no off-by-one to make. */
    private static final int[] NB_BYTES = {
        12, 13, 15, 17, 19, 20, 26, 31, /* 0-7   4.75 .. 12.2 kbit/s */
         5,                             /* 8     SID                 */
         0,  0,  0,  0,  0,             /* 9-13  reserved            */
         0,                             /* 14    speech lost         */
         0                              /* 15    NO_DATA             */
    };
    private static final int[] WB_BYTES = {
        17, 23, 32, 36, 40, 46, 50, 58, /* 0-7   6.60 .. 23.05       */
        60,                             /* 8     23.85               */
         5,                             /* 9     SID                 */
         0,  0,  0,  0,                 /* 10-13 reserved            */
         0,                             /* 14    speech lost         */
         0                              /* 15    NO_DATA             */
    };

    static final int FT_NO_DATA = 15;
    static final int FT_SPEECH_LOST = 14;
    /** No mode request: CMR 15. */
    static final int CMR_NONE = 15;

    private JoanAmr() {}

    /** Speech bytes for a frame type, or -1 if the type is not carried. */
    static int frameBytes(int ft, boolean wideband) {
        if (ft < 0 || ft > 15) {
            return -1;
        }
        /* NO_DATA and speech-lost are legitimate and carry nothing. */
        if (ft == FT_NO_DATA || ft == FT_SPEECH_LOST) {
            return 0;
        }
        int n = (wideband ? WB_BYTES : NB_BYTES)[ft];
        /* Reserved types carry nothing and must not be invented. */
        return n == 0 ? -1 : n;
    }

    static int ftOf(byte header) {
        return (header >> 3) & 0x0f;
    }

    static boolean qualityOk(byte header) {
        return ((header >> 2) & 1) != 0;
    }

    /**
     * One storage-format frame -> one octet-aligned RTP payload.
     *
     * @return payload length written to out, or -1 if the frame is not
     *         something we can carry.
     */
    static int pack(byte[] storage, int off, int len, int cmr,
                    boolean wideband, byte[] out) {
        if (storage == null || out == null || len < 1
                || off < 0 || off + len > storage.length) {
            return -1;
        }
        int ft = ftOf(storage[off]);
        int n = frameBytes(ft, wideband);
        if (n < 0 || len < 1 + n || out.length < 2 + n) {
            return -1;
        }
        out[0] = (byte) ((cmr & 0x0f) << 4);
        /* Single frame: F stays 0. The storage header already has bit 7
         * clear, so the low seven bits carry across unchanged. */
        out[1] = (byte) (storage[off] & 0x7f);
        System.arraycopy(storage, off + 1, out, 2, n);
        return 2 + n;
    }

    /**
     * Octet-aligned RTP payload -> the first speech frame, in storage
     * format, written to out.
     *
     * Multi-frame payloads are parsed far enough to find the first frame
     * and are otherwise ignored: this UA offers ptime 20, so anything
     * bundling frames is doing something we did not ask for.
     *
     * @return storage bytes written to out, or -1 if the payload is
     *         malformed or the frame type is not one we carry.
     */
    static int unpack(byte[] rtp, int off, int len, boolean wideband,
                      byte[] out) {
        if (rtp == null || out == null || len < 2
                || off < 0 || off + len > rtp.length) {
            return -1;
        }
        /* rtp[off] is the CMR byte. ToCs follow, one per frame, until one
         * has F clear; then the data for each, in the same order. */
        int toc = off + 1;
        int frames = 0;
        while (toc < off + len) {
            frames++;
            boolean more = (rtp[toc] & 0x80) != 0;
            toc++;
            if (!more) {
                break;
            }
            if (frames > 8) {
                return -1;
            }
        }
        if (frames == 0 || toc > off + len) {
            return -1;
        }
        byte firstToc = rtp[off + 1];
        int ft = ftOf(firstToc);
        int n = frameBytes(ft, wideband);
        if (n < 0) {
            return -1;
        }
        /* Data for frame 1 begins immediately after the last ToC. */
        if (toc + n > off + len || out.length < 1 + n) {
            return -1;
        }
        out[0] = (byte) (firstToc & 0x7f);
        System.arraycopy(rtp, toc, out, 1, n);
        return 1 + n;
    }

    /** CMR the peer is requesting, or CMR_NONE. */
    static int requestedMode(byte[] rtp, int off, int len) {
        if (rtp == null || len < 1 || off < 0 || off >= rtp.length) {
            return CMR_NONE;
        }
        return (rtp[off] >> 4) & 0x0f;
    }
}
