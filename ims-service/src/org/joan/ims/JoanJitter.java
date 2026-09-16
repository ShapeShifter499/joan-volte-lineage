package org.joan.ims;

/**
 * Reception tracking and an adaptive jitter buffer, RFC 3550.
 *
 * <p>Until this existed the downlink was {@code receive() -> decode() ->
 * write()}, straight through. A packet that arrived late was played
 * late, one that arrived out of order was played out of order, and a
 * lost one left a gap. The bench traces read {@code jitter=0 loss=0%},
 * which is a property of the network here and not of the code.
 *
 * <p>It also produced a second, quieter gap: nothing tracked inbound
 * sequence numbers, so we had no loss count and no jitter estimate of
 * our own, and the RTCP sender reports we send carry no reception
 * blocks at all. The far end has never had any idea how it sounds to
 * us.
 *
 * <p>The adaptation follows AOSP's {@code JitterNetworkAnalyser}: grow
 * quickly when packets arrive later than the buffer allows, shrink
 * slowly and only after a long quiet period, and keep a bounded history.
 * Its constants are named below with the same values. Pure Java with no
 * android imports, so the whole thing is provable on the host.
 */
final class JoanJitter {

    /* AOSP JitterNetworkAnalyser constants, same values. */
    /** Longest arrival history kept; 150 packets is three seconds at 20 ms. */
    static final int MAX_HISTORY = 150;
    /** Nominal packet interval, milliseconds. */
    static final int PACKET_INTERVAL_MS = 20;
    /** A late packet grows the buffer if seen within this window. */
    static final int INCREASE_THRESHOLD_MS = 200;
    /** The buffer only shrinks after this long without needing to grow. */
    static final int DECREASE_THRESHOLD_MS = 2000;
    /** Frames removed at a time when shrinking. */
    static final int DECREASE_STEP = 2;
    /** Rounding margin, milliseconds. */
    static final int ROUNDUP_MARGIN_MS = 10;

    /** Buffer depth bounds, in packets. */
    static final int MIN_DEPTH = 2;
    static final int MAX_DEPTH = 50;

    /**
     * Beyond this many packets ahead, a sequence number is treated as a
     * stream restart rather than a very large jump. RFC 3550's own
     * heuristic; without it one corrupt header strands the buffer
     * forever waiting for sequence numbers that will never come.
     */
    static final int RESTART_GAP = 3000;

    private final java.util.TreeMap<Long, byte[]> queue =
            new java.util.TreeMap<>();

    private int depth = 4;
    private long expected = -1;
    private int lastSeq = -1;
    private long cycles;

    /* RFC 3550 6.4.1 reception statistics. */
    private int received;
    private int lostCumulative;
    private long baseSeq = -1;
    private long maxSeq;
    private double jitter;
    private long lastTransit;
    private boolean haveTransit;

    private long lastGrowAtMs;
    private int dropped;
    private int reordered;

    /** Current buffer depth, in packets. */
    int depth() {
        return depth;
    }

    int queued() {
        return queue.size();
    }

    /** Packets discarded for arriving too late to be useful. */
    int dropped() {
        return dropped;
    }

    /** Packets that arrived out of order and were put back in order. */
    int reordered() {
        return reordered;
    }

    /** RFC 3550 interarrival jitter, in timestamp units. */
    int jitter() {
        return (int) jitter;
    }

    int received() {
        return received;
    }

    /** RFC 3550 6.4.1 cumulative packets lost, which may be negative. */
    int cumulativeLost() {
        return lostCumulative;
    }

    /** Extended highest sequence number received. */
    long extendedMaxSeq() {
        return maxSeq;
    }

    /**
     * Fraction of packets lost since this was last called, as the 8-bit
     * value RFC 3550 puts in a report block.
     */
    private long lastExpected;
    private long lastReceived;

    int fractionLostAndReset() {
        long expectedNow = maxSeq - baseSeq + 1;
        long expectedDelta = expectedNow - lastExpected;
        long receivedDelta = received - lastReceived;
        lastExpected = expectedNow;
        lastReceived = received;
        long lost = expectedDelta - receivedDelta;
        if (expectedDelta == 0 || lost <= 0) {
            return 0;
        }
        return (int) ((lost << 8) / expectedDelta);
    }

    /**
     * Offer one arrived packet.
     *
     * @param seq       RTP sequence number, 0-65535
     * @param rtpTs     RTP timestamp
     * @param arrivalTs arrival time in the same units as {@code rtpTs}
     * @param payload   the payload, retained by the buffer
     * @param nowMs     wall clock, for adaptation timing
     * @return false when the packet was too late to use and was dropped
     */
    boolean offer(int seq, long rtpTs, long arrivalTs, byte[] payload,
                  long nowMs) {
        /* Captured before extend() moves it: reordering means arriving
         * behind a packet we have already seen, which is a property of
         * the network. Comparing against the play position instead counts
         * the buffer filling as reordering -- every packet ahead of the
         * read point looks out of order, and a perfectly ordered stream
         * reports itself as scrambled. */
        long prevMax = lastSeq < 0 ? -1 : maxSeq;
        long ext = extend(seq);
        updateStats(ext, rtpTs, arrivalTs, nowMs);
        if (expected >= 0 && ext < expected) {
            /* Already played past this point. Keeping it would mean
             * emitting audio out of order, which is worse than the gap
             * it would fill. */
            dropped++;
            return false;
        }
        if (prevMax >= 0 && ext < prevMax) {
            reordered++;
        }
        queue.put(ext, payload);
        while (queue.size() > MAX_DEPTH) {
            queue.remove(queue.firstKey());
            dropped++;
        }
        return true;
    }

    /**
     * Take the next payload to play, or null to play nothing this tick.
     *
     * <p>Returning null is a real answer: it is what holds the buffer at
     * its depth while it fills, and what reports a genuine gap so the
     * caller can conceal it rather than pulling the next packet forward
     * and shortening the audio.
     */
    byte[] poll() {
        if (queue.size() < depth) {
            return null;
        }
        java.util.Map.Entry<Long, byte[]> first = queue.firstEntry();
        if (first == null) {
            return null;
        }
        if (expected >= 0 && first.getKey() > expected) {
            /* The packet we wanted never came. Advance past it and let
             * the caller conceal rather than playing the next one early. */
            expected++;
            return null;
        }
        queue.remove(first.getKey());
        expected = first.getKey() + 1;
        return first.getValue();
    }

    private long extend(int seq) {
        if (lastSeq < 0) {
            lastSeq = seq;
            baseSeq = seq;
            maxSeq = seq;
            expected = seq;
            return seq;
        }
        int diff = seq - lastSeq;
        if (diff < -RESTART_GAP) {
            cycles += 0x10000;
        } else if (diff > RESTART_GAP) {
            /* A jump this large backwards in extended terms is a restart,
             * not a wrap. */
            if (cycles >= 0x10000) {
                cycles -= 0x10000;
            }
        }
        lastSeq = seq;
        long ext = cycles + seq;
        if (ext > maxSeq) {
            maxSeq = ext;
        }
        return ext;
    }

    private void updateStats(long ext, long rtpTs, long arrivalTs,
                             long nowMs) {
        received++;
        long transit = arrivalTs - rtpTs;
        if (haveTransit) {
            long d = transit - lastTransit;
            if (d < 0) {
                d = -d;
            }
            /* RFC 3550 A.8: a first-order estimator with 1/16 gain. */
            jitter += (d - jitter) / 16.0;
            adapt(d, nowMs);
        }
        lastTransit = transit;
        haveTransit = true;
        long expectedNow = maxSeq - baseSeq + 1;
        lostCumulative = (int) (expectedNow - received);
    }

    /**
     * Grow fast, shrink slow.
     *
     * <p>Growing is cheap -- a few more milliseconds of latency -- while
     * shrinking too early throws away the headroom that was just proven
     * necessary, so it only happens after a long stretch with no packet
     * needing it.
     */
    private void adapt(long deltaTicks, long nowMs) {
        long deltaMs = deltaTicks / Math.max(1, ticksPerMs());
        if (deltaMs + ROUNDUP_MARGIN_MS > (long) depth * PACKET_INTERVAL_MS
                && deltaMs < INCREASE_THRESHOLD_MS) {
            if (depth < MAX_DEPTH) {
                depth++;
            }
            lastGrowAtMs = nowMs;
            return;
        }
        if (lastGrowAtMs > 0 && nowMs - lastGrowAtMs > DECREASE_THRESHOLD_MS) {
            depth -= DECREASE_STEP;
            if (depth < MIN_DEPTH) {
                depth = MIN_DEPTH;
            }
            lastGrowAtMs = nowMs;
        }
    }

    /** Timestamp ticks per millisecond; set by the caller's clock rate. */
    private int ticksPerMs = 16;

    void setClockRate(int hz) {
        ticksPerMs = Math.max(1, hz / 1000);
    }

    private int ticksPerMs() {
        return ticksPerMs;
    }

    String summary() {
        return "depth=" + depth + " queued=" + queue.size()
                + " jitter=" + (int) jitter
                + " lost=" + lostCumulative
                + " reordered=" + reordered
                + " late_dropped=" + dropped;
    }
}
