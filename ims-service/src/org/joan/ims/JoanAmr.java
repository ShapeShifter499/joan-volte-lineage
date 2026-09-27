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

    /* Speech data BITS per frame type. Bandwidth-efficient packing carries
     * exactly these, with no padding to a byte -- AMR-WB mode 2 is 253
     * bits, not the 32 bytes the octet-aligned form rounds it up to. Each
     * entry is ceil()-consistent with the byte table above. */
    private static final int[] NB_BITS = {
        95, 103, 118, 134, 148, 159, 204, 244, /* 0-7  4.75 .. 12.2   */
        39,                                    /* 8    SID            */
         0,  0,  0,  0,  0,                    /* 9-13 reserved       */
         0,                                    /* 14   speech lost    */
         0                                     /* 15   NO_DATA        */
    };
    private static final int[] WB_BITS = {
        132, 177, 253, 285, 317, 365, 397, 461, /* 0-7  6.60 .. 23.05 */
        477,                                    /* 8    23.85         */
         40,                                    /* 9    SID           */
          0,  0,  0,  0,                        /* 10-13 reserved     */
          0,                                    /* 14   speech lost   */
          0                                     /* 15   NO_DATA       */
    };

    /** RFC 4867 bitrates by mode. The single table for the whole stack. */
    private static final int[] NB_BPS = {
        4750, 5150, 5900, 6700, 7400, 7950, 10200, 12200 };
    private static final int[] WB_BPS = {
        6600, 8850, 12650, 14250, 15850, 18250, 19850, 23050, 23850 };

    /** Bitrate for a mode, or 0 when the mode is not a speech mode. */
    static int modeBitrate(int mode, boolean wideband) {
        int[] t = wideband ? WB_BPS : NB_BPS;
        if (mode < 0 || mode >= t.length) {
            return 0;
        }
        return t[mode];
    }

    /** Highest mode whose bitrate does not exceed bps, or -1. */
    static int bitrateMode(int bps, boolean wideband) {
        int[] t = wideband ? WB_BPS : NB_BPS;
        int best = -1;
        for (int i = 0; i < t.length; i++) {
            if (t[i] <= bps) {
                best = i;
            }
        }
        return best;
    }

    /** Number of speech modes for this bandwidth. */
    static int modeCount(boolean wideband) {
        return (wideband ? WB_BPS : NB_BPS).length;
    }

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

    /** Speech bits for a frame type, or -1 if the type is not carried. */
    static int frameBits(int ft, boolean wideband) {
        if (ft < 0 || ft > 15) {
            return -1;
        }
        if (ft == FT_NO_DATA || ft == FT_SPEECH_LOST) {
            return 0;
        }
        int n = (wideband ? WB_BITS : NB_BITS)[ft];
        return n == 0 ? -1 : n;
    }

    private static int putBits(byte[] out, int pos, int value, int nbits) {
        for (int i = nbits - 1; i >= 0; i--) {
            if (((value >> i) & 1) != 0) {
                out[pos >> 3] |= (byte) (0x80 >>> (pos & 7));
            }
            pos++;
        }
        return pos;
    }

    private static int getBits(byte[] in, int base, int pos, int nbits) {
        int v = 0;
        for (int i = 0; i < nbits; i++) {
            int at = pos + i;
            v = (v << 1) | ((in[base + (at >> 3)] >>> (7 - (at & 7))) & 1);
        }
        return v;
    }

    /**
     * One storage-format frame -> one bandwidth-efficient RTP payload
     * (RFC 4867 4.3): CMR(4), then ToC F(1)+FT(4)+Q(1), then the speech
     * bits, zero-padded to the next octet. Nothing is byte-aligned, which
     * is the whole difference from {@link #pack}.
     */
    static int packBe(byte[] storage, int off, int len, int cmr,
                      boolean wideband, byte[] out) {
        if (storage == null || out == null || len < 1
                || off < 0 || off + len > storage.length) {
            return -1;
        }
        int ft = ftOf(storage[off]);
        int nBytes = frameBytes(ft, wideband);
        int nBits = frameBits(ft, wideband);
        if (nBytes < 0 || nBits < 0 || len < 1 + nBytes) {
            return -1;
        }
        int outLen = (4 + 6 + nBits + 7) / 8;
        if (out.length < outLen) {
            return -1;
        }
        for (int i = 0; i < outLen; i++) {
            out[i] = 0;
        }
        int pos = putBits(out, 0, cmr & 0x0f, 4);
        pos = putBits(out, pos, 0, 1);                       // F: last frame
        pos = putBits(out, pos, ft & 0x0f, 4);
        pos = putBits(out, pos, qualityOk(storage[off]) ? 1 : 0, 1);
        for (int i = 0; i < nBits; i++) {
            int bit = (storage[off + 1 + (i >> 3)] >>> (7 - (i & 7))) & 1;
            pos = putBits(out, pos, bit, 1);
        }
        return outLen;
    }

    /**
     * Bandwidth-efficient RTP payload -> the first speech frame, in
     * storage format. As with {@link #unpack}, later frames in a bundle
     * are skipped over rather than decoded.
     */
    static int unpackBe(byte[] rtp, int off, int len, boolean wideband,
                        byte[] out) {
        if (rtp == null || out == null || len < 2
                || off < 0 || off + len > rtp.length) {
            return -1;
        }
        int avail = len * 8;
        int pos = 4;                                          // skip CMR
        int firstFt = -1;
        int firstQ = 0;
        int frames = 0;
        int bitsOfSpeech = 0;
        while (true) {
            if (pos + 6 > avail) {
                return -1;
            }
            int f = getBits(rtp, off, pos, 1);
            int ft = getBits(rtp, off, pos + 1, 4);
            int q = getBits(rtp, off, pos + 5, 1);
            pos += 6;
            frames++;
            int bits = frameBits(ft, wideband);
            if (bits < 0) {
                return -1;
            }
            if (firstFt < 0) {
                firstFt = ft;
                firstQ = q;
            } else {
                bitsOfSpeech += bits;
            }
            if (f == 0) {
                break;
            }
            if (frames > 8) {
                return -1;
            }
        }
        int nBits = frameBits(firstFt, wideband);
        int nBytes = frameBytes(firstFt, wideband);
        if (nBits < 0 || nBytes < 0 || out.length < 1 + nBytes) {
            return -1;
        }
        if (pos + nBits > avail) {
            return -1;
        }
        out[0] = (byte) (((firstFt & 0x0f) << 3) | ((firstQ & 1) << 2));
        for (int i = 0; i < nBytes; i++) {
            out[1 + i] = 0;
        }
        for (int i = 0; i < nBits; i++) {
            if (getBits(rtp, off, pos + i, 1) != 0) {
                out[1 + (i >> 3)] |= (byte) (0x80 >>> (i & 7));
            }
        }
        return 1 + nBytes;
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

    /**
     * AMR frame type reserved for comfort noise (SID).
     *
     * <p>8 in both AMR-NB and AMR-WB; the tables above give it 5 bytes of
     * payload in either. A SID frame means the sender has stopped
     * transmitting speech, which is the one moment a jitter buffer can
     * give latency back without anyone hearing the stream shorten.
     */
    static final int FT_SID = 8;

    /**
     * Whether an RTP payload's first frame is comfort noise.
     *
     * <p>Octet-aligned and bandwidth-efficient put the ToC in different
     * places, so the framing has to be known -- reading the wrong one
     * misclassifies speech as silence, which would have the buffer
     * shorten while somebody is talking.
     *
     * <p>Anything it cannot parse reads as "not SID", so an unreadable
     * frame never causes a shrink.
     */
    static boolean isSid(byte[] rtp, int off, int len, boolean octetAligned,
                         boolean wideband) {
        if (rtp == null || off < 0 || len < 2 || off + len > rtp.length) {
            return false;
        }
        int ft;
        if (octetAligned) {
            /* CMR octet, then the ToC octet: FT is bits 6..3. */
            ft = ftOf(rtp[off + 1]);
        } else {
            /* Bandwidth-efficient packs CMR in the top 4 bits and the
             * ToC immediately after, so FT straddles the octet boundary:
             * the low 4 bits of the first octet carry F and the top 3
             * bits of FT, and the next octet supplies the last. */
            int b0 = rtp[off] & 0xff;
            int b1 = rtp[off + 1] & 0xff;
            ft = ((b0 & 0x07) << 1) | ((b1 >> 7) & 0x01);
        }
        return ft == FT_SID;
    }

    /**
     * A storage-format frame telling the decoder a frame was lost.
     *
     * <p>RFC 4867 s4.3.2 defines frame type 14 as SPEECH_LOST, which is
     * exactly this signal, and an AMR decoder answers it by running the
     * error concealment 3GPP specifies for the codec. That is a far
     * better reconstruction than anything this application could write,
     * and it is how AOSP handles a gap too: ImsMedia's audio player calls
     * onDataFrame(nullptr, 0, NO_DATA) and lets the codec decide, rather
     * than synthesising audio itself.
     *
     * @return bytes written, always 1, or -1 if out is too small
     */
    static int lostFrame(byte[] out) {
        if (out == null || out.length < 1) {
            return -1;
        }
        /* Storage format is the ToC octet with the F bit clear, then the
         * frame's bytes -- and a lost frame has none. */
        out[0] = (byte) (FT_SPEECH_LOST << 3);
        return 1;
    }

    /** CMR the peer is requesting, or CMR_NONE. */
    static int requestedMode(byte[] rtp, int off, int len) {
        if (rtp == null || len < 1 || off < 0 || off >= rtp.length) {
            return CMR_NONE;
        }
        return (rtp[off] >> 4) & 0x0f;
    }
}
