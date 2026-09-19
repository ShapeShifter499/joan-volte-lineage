package org.joan.ims;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * T.140 real-time text as RFC 4103 carries it, receive side.
 *
 * <p>Parsing only, deliberately. Nothing in joan offers an
 * {@code m=text} line or sets an RTT flag on a call, so nothing here can
 * be reached from a live call yet -- see {@link JoanImsRttConfig} for why
 * the advertisement is the part held back. This is the piece that can be
 * written and proven against the RFCs without a network.
 *
 * <p>Two formats stack. RFC 4103 3 puts T.140's UTF-8 straight in the RTP
 * payload; RFC 4103 4 wraps that in RFC 2198 redundancy, so a packet
 * carries the current text plus recent generations of it. Loss of a
 * single packet is then invisible, which is why text uses it and audio
 * does not.
 */
final class JoanT140 {

    /**
     * T.140 uses U+FEFF as a fill character, not as a byte-order mark.
     *
     * <p>ITU-T T.140 7.2 and RFC 4103 8 both spend it as a keepalive so a
     * silent stream keeps its timing. It must never reach a display: a
     * user idling produces a string of invisible-but-present characters
     * that break cursor handling in any consumer that trusts the length.
     */
    static final char FILL = '﻿';

    /**
     * What a receiver puts where text was lost. RFC 4103 6 asks for
     * U+FFFD so the gap is visible to the reader rather than silently
     * closed -- the reader is the error recovery in a text conversation.
     */
    static final char LOSS = '�';

    private JoanT140() {}

    /** One RFC 2198 block: a generation of text and how old it is. */
    static final class Block {
        final int payloadType;
        /**
         * RTP timestamp units before the packet's own timestamp. Zero for
         * the primary, which RFC 2198 3 places last.
         */
        final int timestampOffset;
        final byte[] data;

        Block(int payloadType, int timestampOffset, byte[] data) {
            this.payloadType = payloadType;
            this.timestampOffset = timestampOffset;
            this.data = data;
        }

        boolean primary() {
            return timestampOffset == 0;
        }
    }

    /**
     * Split an RFC 2198 redundancy payload into its blocks, oldest first,
     * or null when the bytes do not parse.
     *
     * <p>RFC 2198 3: a run of 4-byte headers each with the top bit set,
     * then a 1-byte header with it clear for the primary, then every
     * block's data back to back in the same order. The headers carry the
     * lengths of the redundant blocks only -- the primary's length is
     * whatever is left, which is the detail that makes a truncated packet
     * parse into a plausible-looking wrong answer if the remainder is not
     * checked.
     *
     * <p>Returns null rather than a partial list on any inconsistency.
     * These are bytes off the network, and half a parse is not a result.
     */
    static List<Block> redBlocks(byte[] b, int len) {
        if (b == null || len < 1 || len > b.length) {
            return null;
        }
        List<int[]> headers = new ArrayList<>();
        int off = 0;
        while (true) {
            if (off >= len) {
                return null;
            }
            int first = b[off] & 0xff;
            if ((first & 0x80) == 0) {
                headers.add(new int[] {first & 0x7f, 0, -1});
                off += 1;
                break;
            }
            if (off + 4 > len) {
                return null;
            }
            int ts = ((b[off + 1] & 0xff) << 6)
                    | ((b[off + 2] & 0xff) >> 2);
            int blockLen = ((b[off + 2] & 0x03) << 8) | (b[off + 3] & 0xff);
            headers.add(new int[] {first & 0x7f, ts, blockLen});
            off += 4;
            /* RFC 2198 3 sets no ceiling, but a header run that never
             * clears the F bit is a malformed packet walking off the end
             * rather than a deeply redundant one. */
            if (headers.size() > 16) {
                return null;
            }
        }
        int need = 0;
        for (int[] h : headers) {
            if (h[2] > 0) {
                need += h[2];
            }
        }
        int remaining = len - off;
        if (need > remaining) {
            return null;
        }
        List<Block> out = new ArrayList<>(headers.size());
        for (int[] h : headers) {
            int blockLen = h[2] < 0 ? remaining - need : h[2];
            if (blockLen < 0 || off + blockLen > len) {
                return null;
            }
            byte[] data = new byte[blockLen];
            System.arraycopy(b, off, data, 0, blockLen);
            out.add(new Block(h[0], h[1], data));
            off += blockLen;
        }
        return out;
    }

    /**
     * The primary block of a redundancy payload, or null.
     *
     * <p>RFC 2198 3 puts it last, and it is the only block whose text has
     * not already been seen on an unlossy stream.
     */
    static Block primary(List<Block> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }
        Block last = blocks.get(blocks.size() - 1);
        return last.primary() ? last : null;
    }

    /**
     * How many trailing bytes begin a UTF-8 sequence that has not
     * finished arriving.
     *
     * <p>T.140 text is a stream cut into packets at arbitrary byte
     * offsets, so a single character can straddle two of them -- an
     * emoji, or any CJK character, is 3 or 4 bytes and has no reason to
     * respect a packet boundary. Decoding each packet independently turns
     * that character into two replacement marks and loses it, which is
     * the failure that looks like a working implementation until someone
     * types outside ASCII.
     *
     * <p>Returns 0 when the buffer ends cleanly, including when it ends in
     * a byte that cannot start a sequence at all -- that is corruption to
     * surface now, not an incomplete tail to wait on.
     */
    static int incompleteTail(byte[] b, int off, int len) {
        int max = Math.min(4, len);
        for (int back = 1; back <= max; back++) {
            int c = b[off + len - back] & 0xff;
            if ((c & 0xc0) == 0x80) {
                continue;
            }
            int need = sequenceLength(c);
            return need > back ? back : 0;
        }
        return 0;
    }

    /** Bytes in the UTF-8 sequence this lead byte opens, or -1. */
    static int sequenceLength(int lead) {
        if ((lead & 0x80) == 0) {
            return 1;
        }
        if ((lead & 0xe0) == 0xc0) {
            return 2;
        }
        if ((lead & 0xf0) == 0xe0) {
            return 3;
        }
        if ((lead & 0xf8) == 0xf0) {
            return 4;
        }
        return -1;
    }

    /**
     * Accumulates T.140 payloads into displayable text across packets.
     *
     * <p>Not thread safe: one conversation, one decoder, on whatever
     * thread reads the stream.
     */
    static final class Decoder {
        private byte[] carry = new byte[0];

        /**
         * Decode one block's bytes, holding back any sequence still
         * arriving. Fill characters are dropped; loss marks are kept.
         */
        String append(byte[] data, int off, int len) {
            if (data == null || len <= 0 || off < 0 || off + len > data.length) {
                return "";
            }
            byte[] buf;
            if (carry.length == 0) {
                buf = new byte[len];
                System.arraycopy(data, off, buf, 0, len);
            } else {
                buf = new byte[carry.length + len];
                System.arraycopy(carry, 0, buf, 0, carry.length);
                System.arraycopy(data, off, buf, carry.length, len);
            }
            int hold = incompleteTail(buf, 0, buf.length);
            int usable = buf.length - hold;
            if (hold > 0) {
                carry = new byte[hold];
                System.arraycopy(buf, usable, carry, 0, hold);
            } else {
                carry = new byte[0];
            }
            if (usable <= 0) {
                return "";
            }
            String s = new String(buf, 0, usable, StandardCharsets.UTF_8);
            return strip(s);
        }

        String append(byte[] data) {
            return data == null ? "" : append(data, 0, data.length);
        }

        /**
         * Bytes held back waiting for the rest of their character. Nonzero
         * at the end of a call means the stream was cut mid-character,
         * which is worth a trace line and is not recoverable.
         */
        int pending() {
            return carry.length;
        }

        void reset() {
            carry = new byte[0];
        }
    }

    /** Drop T.140 fill characters, keeping loss marks visible. */
    static String strip(String s) {
        if (s.indexOf(FILL) < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != FILL) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
