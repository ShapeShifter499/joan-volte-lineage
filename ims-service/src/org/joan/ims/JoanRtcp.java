package org.joan.ims;

/**
 * RTCP sender/receiver reports, RFC 3550 6.4.
 *
 * <p>Parsing only, so the host tests compile this file directly: the
 * socket and the threading live in {@link JoanMedia}, which cannot be
 * built without Android. The report block is how the far end tells us
 * what it is actually receiving -- fraction lost, cumulative loss and
 * interarrival jitter -- and until this existed a call could degrade the
 * whole way to unusable with nothing in the log to show it.
 *
 * <p>Diagnostic, not control: ANBR from the radio is what drives
 * adaptation on VoLTE, not loss inferred after the fact.
 */
final class JoanRtcp {

    /** Sender report. */
    static final int PT_SR = 200;
    /** Receiver report. */
    static final int PT_RR = 201;

    static final class Report {
        final int fractionLost;   /* 0..255, a fraction of 256 */
        final int cumulativeLost;
        final int jitter;

        Report(int fractionLost, int cumulativeLost, int jitter) {
            this.fractionLost = fractionLost;
            this.cumulativeLost = cumulativeLost;
            this.jitter = jitter;
        }

        int lossPercent() {
            return fractionLost * 100 / 256;
        }
    }

    private JoanRtcp() {}

    /**
     * First report block in a compound RTCP packet, or null.
     *
     * <p>Compound packets chain several sub-packets, each with its own
     * length, and the interesting one is rarely first -- an SR is usually
     * followed by SDES. Every length is checked against what actually
     * arrived, because this parses bytes straight off the network.
     */
    static Report parse(byte[] b, int len) {
        if (b == null || len < 8 || len > b.length) {
            return null;
        }
        int off = 0;
        while (off + 8 <= len) {
            int rc = b[off] & 0x1f;
            int pt = b[off + 1] & 0xff;
            int words = ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
            int plen = (words + 1) * 4;
            if (plen < 8 || off + plen > len) {
                return null;
            }
            /* Blocks follow the 20-byte sender info in an SR, and the
             * header alone in an RR. */
            int blocks = pt == PT_SR ? off + 28 : (pt == PT_RR ? off + 8 : -1);
            if (blocks > 0 && rc > 0 && blocks + 24 <= off + plen) {
                return new Report(
                        b[blocks + 4] & 0xff,
                        ((b[blocks + 5] & 0xff) << 16)
                                | ((b[blocks + 6] & 0xff) << 8)
                                | (b[blocks + 7] & 0xff),
                        ((b[blocks + 16] & 0xff) << 24)
                                | ((b[blocks + 17] & 0xff) << 16)
                                | ((b[blocks + 18] & 0xff) << 8)
                                | (b[blocks + 19] & 0xff));
            }
            off += plen;
        }
        return null;
    }

    /**
     * True when a packet on a muxed RTP port is really RTCP. RFC 5761 4:
     * types 200-204 occupy 72-76 in the payload-type octet, which no audio
     * payload type we negotiate can collide with.
     */
    static boolean isRtcp(byte[] b, int len) {
        if (b == null || len < 8) {
            return false;
        }
        int t = b[1] & 0x7f;
        return t >= 72 && t <= 76;
    }
}
