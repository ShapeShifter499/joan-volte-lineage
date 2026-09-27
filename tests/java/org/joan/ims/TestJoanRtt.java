package org.joan.ims;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Host tests for RTT: the RFC 4103 / RFC 2198 wire format and the pure
 * half of the carrier-config read.
 *
 * <p>The nested-bundle path in JoanImsRttConfig.payloadType is NOT
 * covered here and cannot be: PersistableBundle is a stub in android.jar
 * that throws on construction. It was verified against a live
 * {@code dumpsys carrier_config} instead, which is where the nesting was
 * found in the first place.
 */
public final class TestJoanRtt {
    private static int gFail;

    private static void check(boolean cond, String name) {
        if (cond) {
            System.out.println("ok   " + name);
        } else {
            System.out.println("FAIL " + name);
            gFail++;
        }
    }

    public static void main(String[] args) {
        testRedSingleBlock();
        testRedWithRedundancy();
        testRedTruncated();
        testRedRunaway();
        testRedGuards();
        testIncompleteTail();
        testSplitCharacters();
        testFillAndLoss();
        testRttConfig();
        System.out.println(gFail == 0 ? "PASS TestJoanRtt"
                : "FAIL TestJoanRtt (" + gFail + ")");
        if (gFail != 0) {
            System.exit(1);
        }
    }

    private static byte[] cat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    /** RFC 2198 3: 4-byte redundant header, F bit set. */
    private static byte[] redHeader(int pt, int tsOffset, int len) {
        return new byte[] {
            (byte) (0x80 | (pt & 0x7f)),
            (byte) ((tsOffset >> 6) & 0xff),
            (byte) (((tsOffset & 0x3f) << 2) | ((len >> 8) & 0x03)),
            (byte) (len & 0xff),
        };
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void testRedSingleBlock() {
        /* No redundancy at all: one 1-byte header, then the text. The
         * primary's length is never declared, so this is also the case
         * that proves the remainder rule. */
        byte[] p = cat(new byte[] {0x60}, utf8("hi"));
        List<JoanT140.Block> b = JoanT140.redBlocks(p, p.length);
        check(b != null && b.size() == 1, "one block when nothing is redundant");
        check(b != null && b.get(0).payloadType == 96, "primary payload type");
        check(b != null && b.get(0).primary(), "zero offset means primary");
        check(b != null && "hi".equals(new String(b.get(0).data,
                StandardCharsets.UTF_8)), "primary takes the remainder");
    }

    private static void testRedWithRedundancy() {
        byte[] p = cat(redHeader(96, 160, 3), new byte[] {0x60},
                utf8("old"), utf8("new"));
        List<JoanT140.Block> b = JoanT140.redBlocks(p, p.length);
        check(b != null && b.size() == 2, "redundant generation plus primary");
        check(b != null && b.get(0).timestampOffset == 160,
                "14-bit timestamp offset spans the byte boundary");
        check(b != null && !b.get(0).primary() && b.get(1).primary(),
                "RFC 2198 3 puts the primary last");
        check(b != null && "old".equals(new String(b.get(0).data,
                StandardCharsets.UTF_8)), "redundant data read at its length");
        JoanT140.Block prim = JoanT140.primary(b);
        check(prim != null && "new".equals(new String(prim.data,
                StandardCharsets.UTF_8)), "primary() picks the new text");
    }

    private static void testRedTruncated() {
        /* Header claims 10 bytes of redundant text; 3 arrived. A parser
         * that trusts the header and lets the primary absorb a negative
         * remainder returns a plausible wrong answer here. */
        byte[] p = cat(redHeader(96, 160, 10), new byte[] {0x60}, utf8("abc"));
        check(JoanT140.redBlocks(p, p.length) == null,
                "declared length past the packet end is refused");
    }

    private static void testRedRunaway() {
        byte[] p = new byte[17 * 4];
        for (int i = 0; i < 17; i++) {
            byte[] h = redHeader(96, 0, 0);
            System.arraycopy(h, 0, p, i * 4, 4);
        }
        check(JoanT140.redBlocks(p, p.length) == null,
                "a header run that never clears the F bit is refused");
        byte[] q = redHeader(96, 160, 3);
        check(JoanT140.redBlocks(q, q.length) == null,
                "headers with no terminating block are refused");
    }

    private static void testRedGuards() {
        check(JoanT140.redBlocks(null, 4) == null, "null payload");
        check(JoanT140.redBlocks(new byte[] {0x60}, 0) == null, "zero length");
        check(JoanT140.redBlocks(new byte[] {0x60}, 9) == null,
                "length past the array is refused");
        check(JoanT140.primary(null) == null, "primary(null)");
    }

    private static void testIncompleteTail() {
        byte[] ascii = utf8("hi");
        check(JoanT140.incompleteTail(ascii, 0, ascii.length) == 0,
                "ascii ends cleanly");
        byte[] half = {(byte) 0xc3};
        check(JoanT140.incompleteTail(half, 0, 1) == 1,
                "a lead byte alone is one byte short");
        byte[] whole = utf8("é");
        check(JoanT140.incompleteTail(whole, 0, whole.length) == 0,
                "a complete two-byte sequence holds nothing back");
        byte[] bad = {(byte) 0xff};
        check(JoanT140.incompleteTail(bad, 0, 1) == 0,
                "a byte that cannot lead is corruption, not a tail");
        byte[] orphans = {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80};
        check(JoanT140.incompleteTail(orphans, 0, 4) == 0,
                "continuations with no lead surface now");
        check(JoanT140.sequenceLength(0x41) == 1
                && JoanT140.sequenceLength(0xc3) == 2
                && JoanT140.sequenceLength(0xe2) == 3
                && JoanT140.sequenceLength(0xf0) == 4
                && JoanT140.sequenceLength(0x80) == -1,
                "utf-8 lead byte lengths");
    }

    private static void testSplitCharacters() {
        /* U+1F600 is four bytes, and nothing stops an RTP packet ending
         * after two of them. Decoding each packet on its own turns one
         * character into two replacement marks. */
        JoanT140.Decoder d = new JoanT140.Decoder();
        check("".equals(d.append(new byte[] {(byte) 0xf0, (byte) 0x9f})),
                "half a character emits nothing");
        check(d.pending() == 2, "the half is held, not dropped");
        String s = d.append(new byte[] {(byte) 0x98, (byte) 0x80});
        check("😀".equals(s), "the character survives the split");
        check(d.pending() == 0, "nothing held once it completes");

        JoanT140.Decoder e = new JoanT140.Decoder();
        check("".equals(e.append(new byte[] {(byte) 0xc3}))
                && "é".equals(e.append(new byte[] {(byte) 0xa9})),
                "two-byte sequence across a boundary");

        JoanT140.Decoder f = new JoanT140.Decoder();
        check("ab".equals(f.append(utf8("ab"))), "plain text passes through");
        check("".equals(f.append(null)) && "".equals(f.append(utf8(""), 0, 0)),
                "empty input is not an error");
        f.reset();
        check(f.pending() == 0, "reset clears the carry");
    }

    private static void testFillAndLoss() {
        JoanT140.Decoder d = new JoanT140.Decoder();
        String s = d.append(utf8("a" + JoanT140.FILL + "b"));
        check("ab".equals(s), "T.140 fill never reaches the display");
        String loss = d.append(utf8("x" + JoanT140.LOSS + "y"));
        check(("x" + JoanT140.LOSS + "y").equals(loss),
                "a loss mark stays visible to the reader");
        check("plain".equals(JoanT140.strip("plain")),
                "text with no fill is returned unchanged");
    }

    private static void testRttConfig() {
        JoanImsRttConfig d = JoanImsRttConfig.defaults("test");
        check(!d.supported, "RTT is off until a carrier says otherwise");
        check(d.t140PayloadType == 0 && d.redPayloadType == 0,
                "no payload type is invented");
        check(!d.usable(), "an unconfigured carrier is not usable");

        check(JoanImsRttConfig.dynamicOrZero(111) == 111
                && JoanImsRttConfig.dynamicOrZero(96) == 96
                && JoanImsRttConfig.dynamicOrZero(127) == 127,
                "RFC 3551 dynamic range is accepted");
        check(JoanImsRttConfig.dynamicOrZero(95) == 0
                && JoanImsRttConfig.dynamicOrZero(128) == 0
                && JoanImsRttConfig.dynamicOrZero(0) == 0
                && JoanImsRttConfig.dynamicOrZero(-1) == 0,
                "a static or out-of-range payload type reads as unset");

        JoanImsRttConfig ok = new JoanImsRttConfig(true, false, false, false,
                111, 112, 4, 100, 300, true, false, "test");
        check(ok.usable(), "supported, typed and unconditioned is usable");
        JoanImsRttConfig gated = new JoanImsRttConfig(true, false, false,
                false, 111, 112, 4, 100, 300, false, true, "test");
        check(!gated.usable(),
                "a carrier wanting RFC 3312 preconditions is not usable");
        JoanImsRttConfig typeless = new JoanImsRttConfig(true, false, false,
                false, 0, 0, 4, 100, 300, true, false, "test");
        check(!typeless.usable(), "supported with no payload type is not usable");
        check(ok.summary().contains("usable=true")
                && gated.summary().contains("precondition=true"),
                "the trace states the blocker");
    }
}
