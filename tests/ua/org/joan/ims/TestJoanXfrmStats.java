package org.joan.ims;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Synthetic counters only: tests do not prove any carrier packet flow. */
public final class TestJoanXfrmStats {
    private static int checks;
    private static final String BASE =
            "XfrmInNoStates\t2\nXfrmInError\t0\nXfrmOutError\t1\n";

    private static void equal(String name, String expected, String actual) {
        checks++;
        if (!expected.equals(actual)) {
            throw new AssertionError(name + ": expected=" + expected + " actual=" + actual);
        }
        System.out.println("PASS " + name);
    }

    public static void main(String[] args) throws Exception {
        String prefix = "xfrm_scope=netns xfrm=";
        equal("unreadable-before", prefix + "unavailable", JoanXfrmStats.delta(null, BASE));
        equal("unreadable-after", prefix + "unavailable", JoanXfrmStats.delta(BASE, null));
        equal("empty-is-not-zero", prefix + "invalid", JoanXfrmStats.delta("", ""));
        equal("exact-unchanged", prefix + "unchanged", JoanXfrmStats.delta(BASE, BASE));
        equal("sorted-counter-deltas", prefix + "changed XfrmInNoStates=+3 XfrmOutError=+1",
                JoanXfrmStats.delta(BASE,
                        "XfrmOutError 2\nXfrmInError 0\nXfrmInNoStates 5\n"));
        equal("key-set-changed", prefix + "incomplete",
                JoanXfrmStats.delta(BASE, "XfrmInNoStates 2\n"));
        equal("counter-reset", prefix + "reset XfrmInNoStates=reset",
                JoanXfrmStats.delta(BASE, BASE.replace("States\t2", "States\t0")));
        equal("no-substring-key-match", prefix + "incomplete",
                JoanXfrmStats.delta("XfrmInError 1\n", "XfrmInErrorExtra 1\n"));
        equal("duplicate-key-rejected", prefix + "invalid",
                JoanXfrmStats.delta(BASE, BASE + "XfrmInError 9\n"));
        equal("malformed-value", prefix + "invalid",
                JoanXfrmStats.delta(BASE, BASE.replace("States\t2", "States\t2oops")));
        equal("negative-value", prefix + "invalid",
                JoanXfrmStats.delta(BASE, BASE.replace("States\t2", "States\t-1")));
        equal("overflow-value", prefix + "invalid",
                JoanXfrmStats.delta(BASE, BASE.replace("States\t2", "States\t18446744073709551616")));
        equal("unexpected-content-not-echoed", prefix + "invalid",
                JoanXfrmStats.delta(BASE, "SECRET 123\n"));
        equal("new-kernel-counter", prefix + "changed XfrmNewCounter=+1",
                JoanXfrmStats.delta("XfrmNewCounter 0\n", "XfrmNewCounter 1\n"));
        byte[] bytes = BASE.getBytes(StandardCharsets.US_ASCII);
        try (InputStream shortReads = new ByteArrayInputStream(bytes) {
            @Override public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(len, 2));
            }
        }) {
            equal("short-reads-drained", BASE, JoanXfrmStats.readBounded(shortReads));
        }
        try (InputStream tooLong = new ByteArrayInputStream(new byte[16385])) {
            equal("oversize-rejected", "null", String.valueOf(JoanXfrmStats.readBounded(tooLong)));
        }
        try (InputStream failing = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("fixture"); }
        }) {
            try {
                JoanXfrmStats.readBounded(failing);
                throw new AssertionError("read error swallowed");
            } catch (IOException expected) {
                checks++;
                System.out.println("PASS read-error-propagated-to-capture");
            }
        }
        System.out.println("XFRM_DIAGNOSTIC_CHECKS=" + checks + " FAILURES=0");
    }
}
