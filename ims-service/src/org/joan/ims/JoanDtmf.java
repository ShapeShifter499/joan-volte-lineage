package org.joan.ims;

/**
 * DTMF as RTP events, RFC 4733.
 *
 * <p>Tones cannot be sent in the audio itself once a speech codec is
 * carrying the call: AMR is built to reproduce voice, and a DTMF tone put
 * through it arrives as something an IVR will not recognise. The digit
 * therefore travels as its own payload type, negotiated alongside the
 * codec, and the audio stream pauses while it does.
 *
 * <p>Packing only, so the host tests compile this file directly.
 */
final class JoanDtmf {

    /** Payload is event, E|R|volume, then a 16-bit duration. */
    static final int PAYLOAD_BYTES = 4;

    /** Duration units are RTP timestamp ticks, so one frame's worth. */
    static final int DEFAULT_VOLUME = 10;

    private JoanDtmf() {}

    /**
     * The RFC 4733 event number for a dialled character, or -1.
     *
     * <p>0-9 are 0-9, * is 10, # is 11 and A-D are 12-15. Anything else
     * is not a DTMF digit and must not be invented into one.
     */
    static int event(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c == '*') {
            return 10;
        }
        if (c == '#') {
            return 11;
        }
        if (c >= 'A' && c <= 'D') {
            return 12 + (c - 'A');
        }
        if (c >= 'a' && c <= 'd') {
            return 12 + (c - 'a');
        }
        return -1;
    }

    /**
     * Build one event payload.
     *
     * @param event    RFC 4733 event number, 0-15
     * @param end      set the E bit: this is the last packet of the tone
     * @param volume   0-63, as dBm0 below reference; louder is a smaller
     *                 number, and the field only has six bits
     * @param duration tone length so far, in RTP timestamp units
     * @return bytes written, or -1 if the arguments are not sendable
     */
    static int pack(int event, boolean end, int volume, int duration,
                    byte[] out) {
        if (out == null || out.length < PAYLOAD_BYTES
                || event < 0 || event > 15
                || volume < 0 || volume > 63
                || duration < 0 || duration > 0xffff) {
            return -1;
        }
        out[0] = (byte) event;
        out[1] = (byte) ((end ? 0x80 : 0) | (volume & 0x3f));
        out[2] = (byte) ((duration >> 8) & 0xff);
        out[3] = (byte) (duration & 0xff);
        return PAYLOAD_BYTES;
    }

    /** Event number carried by a payload, or -1 when it is not one. */
    static int eventOf(byte[] payload, int off, int len) {
        if (payload == null || len < PAYLOAD_BYTES || off < 0
                || off + len > payload.length) {
            return -1;
        }
        return payload[off] & 0x0f;
    }

    /** Whether a payload carries the end of a tone. */
    static boolean isEnd(byte[] payload, int off, int len) {
        return len >= PAYLOAD_BYTES && off >= 0
                && off + len <= payload.length
                && (payload[off + 1] & 0x80) != 0;
    }
}
