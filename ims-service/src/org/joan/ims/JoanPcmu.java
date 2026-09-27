package org.joan.ims;

/**
 * PCMU playback aids: gap concealment and wall-clock pacing.
 *
 * <p>AMR gaps are concealed by the decoder, which runs the 3GPP
 * reconstruction. PCMU has no decoder to ask, and until this existed a
 * lost frame wrote nothing at all -- the AudioTrack underran, and on a
 * call measured at 42% loss the far side came through as torn bursts
 * rather than a degraded voice. The cheapest useful reconstruction is
 * the one RFC 3550 suggests for a missing packet period: repeat the last
 * good frame while it fades to silence, then hold silence so the stream
 * keeps its timing.
 *
 * <p>These calls run in the playback loop every 20 ms; there is no
 * allocation and no android import, and the whole thing is provable on
 * the host.
 */
final class JoanPcmu {

    private JoanPcmu() {}

    /** Fade steps applied to the last real frame before pure silence. */
    static final int FADE_STEPS = 4;

    /**
     * Most frames one drain may write as catch-up. Further than this and
     * the stall is history rather than jitter -- replaying it as frames
     * of silence only delays the audio that is still coming, so the pace
     * re-anchors instead. 10 frames is 200 ms.
     */
    static final int CATCHUP_CAP = 10;

    /**
     * Synthesize one concealment frame into {@code dst}.
     *
     * @param lastReal    the last real frame played, or null
     * @param consecutive concealment frames already written in a row
     */
    static void conceal(short[] dst, short[] lastReal, int consecutive) {
        if (lastReal == null || consecutive >= FADE_STEPS
                || lastReal.length != dst.length) {
            java.util.Arrays.fill(dst, (short) 0);
            return;
        }
        /* 4/4, 3/4, 2/4, 1/4 of the last frame, then silence. A short
         * hole is bridged with something voice-shaped; a long one must
         * not repeat a syllable forever. */
        int scale = FADE_STEPS - consecutive;
        for (int i = 0; i < dst.length; i++) {
            dst[i] = (short) ((lastReal[i] * scale) / FADE_STEPS);
        }
    }

    /**
     * Frames the playback track is owed at {@code nowMs}, having written
     * {@code written} since the pace anchored.
     *
     * @return owed frames, or -1 when the track is so far behind that the
     *         caller should re-anchor the pace (skip the stall) rather
     *         than replay it.
     */
    static int framesOwed(long anchorMs, int frameMs, int written,
                          long nowMs) {
        long elapsed = nowMs - anchorMs;
        if (elapsed < 0) {
            elapsed = 0;
        }
        long target = elapsed / Math.max(1, frameMs);
        long behind = target - written;
        if (behind > CATCHUP_CAP) {
            return -1;
        }
        return behind < 0 ? 0 : (int) behind;
    }
}
