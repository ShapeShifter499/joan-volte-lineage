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
    /** Slack allowed when deciding the buffer has filled, milliseconds. */
    static final int ALLOWABLE_ERROR_MS = 10;
    /** Window over which the drop rate is judged, milliseconds. */
    static final int DROP_WINDOW_MS = 5000;
    /** Drop rate, in percent, that forces the buffer to re-fill or grow. */
    static final int RESET_THRESHOLD_DTX = 80;
    static final int RESET_THRESHOLD_NO_DTX = 35;
    /** Rounding margin, milliseconds. */
    static final int ROUNDUP_MARGIN_MS = 10;

    /**
     * Frames the queue may hold above the target before any are given up.
     *
     * <p>Sitting one or two frames over is ordinary: an arrival can
     * always land just before its slot is played, and trimming that away
     * would tear a hole in a stream with nothing wrong with it. What has
     * to be caught is a backlog that cannot drain -- measured on the
     * bench as nine frames held against a target of two, which is about
     * 180 ms of latency the call never gets back.
     */
    static final int SLACK = 3;

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
    /** Extended sequence numbers whose frame is comfort noise. */
    private final java.util.HashSet<Long> sidSeqs = new java.util.HashSet<>();

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
    /** Arrived after its slot had already played: the depth was short. */
    private int late;
    /** Given up to bound the latency: the buffer doing its job. */
    private int trimmed;
    private int reordered;

    /**
     * True while the buffer is filling and playing nothing.
     *
     * <p>Filling is judged by elapsed time, not by how many packets are
     * queued. A count-based rule stalls outright when packets are lost
     * during the fill -- the queue never reaches the target and playback
     * never starts -- and this link drops packets: six on one measured
     * call. AOSP's AudioJitterBuffer waits on
     * {@code currentTime - mTimeStarted < size * FRAME_INTERVAL} for the
     * same reason.
     */
    private boolean waiting = true;
    private long waitStartedMs = -1;

    /**
     * Why the last poll() returned nothing.
     *
     * <p>A gap and a fill both come back as null and need opposite
     * answers: a gap wants concealment written so the stream keeps its
     * timing, while a fill wants silence because the call has not
     * started. Concealing during the fill would play invented audio
     * before the first real frame.
     */
    private boolean lastWasGap;

    /** True when the last poll() found a frame missing, not still filling. */
    boolean lastWasGap() {
        return lastWasGap;
    }

    /** SSRC of the stream being buffered; a change means a new stream. */
    private int ssrc;
    private boolean haveSsrc;

    /** Drop timestamps inside the current window, for the drop rate. */
    private final java.util.ArrayDeque<Long> dropTimes =
            new java.util.ArrayDeque<>();
    private int voiceInWindow;
    private boolean sawSid;

    /**
     * Note the stream's SSRC, restarting if it has changed.
     *
     * <p>A mid-call SSRC change means a different stream: a re-INVITE,
     * or the network moving us. Carrying the old sequence baseline across
     * it reads as enormous loss and the reordering guard then discards
     * everything that arrives. AOSP handles this as
     * MEDIASUBTYPE_REFRESHED and calls Reset(); so do we.
     *
     * @return true when the stream changed and the buffer was reset
     */
    boolean onSsrc(int newSsrc) {
        if (haveSsrc && newSsrc == ssrc) {
            return false;
        }
        boolean changed = haveSsrc;
        reset();
        ssrc = newSsrc;
        haveSsrc = true;
        return changed;
    }

    /** Start again for a new call; a tracker outlives one session. */
    void reset() {
        queue.clear();
        depth = 4;
        expected = -1;
        lastSeq = -1;
        cycles = 0;
        received = 0;
        lostCumulative = 0;
        baseSeq = -1;
        maxSeq = 0;
        jitter = 0;
        lastTransit = 0;
        haveTransit = false;
        lastGrowAtMs = 0;
        late = 0;
        trimmed = 0;
        reordered = 0;
        lastExpected = 0;
        lastReceived = 0;
        waiting = true;
        waitStartedMs = -1;
        lastWasGap = false;
        haveSsrc = false;
        ssrc = 0;
        dropTimes.clear();
        voiceInWindow = 0;
        sawSid = false;
        sidSeqs.clear();
    }

    /** Current buffer depth, in packets. */
    int depth() {
        return depth;
    }

    int queued() {
        return queue.size();
    }

    /**
     * Packets that arrived after their slot had played.
     *
     * <p>Distinct from {@link #trimmed()}, and they mean opposite
     * things: late arrivals say the buffer is too shallow for this link,
     * while trims say it is holding the latency down as designed.
     * Reporting them as one number cannot tell those apart, which is
     * what the first bounded call did.
     */
    int late() {
        return late;
    }

    /** Frames given up to keep the held audio within the depth. */
    int trimmed() {
        return trimmed;
    }

    /** Everything discarded, by either route. */
    int dropped() {
        return late + trimmed;
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
    /**
     * Account for one arrived packet without queueing it.
     *
     * <p>Lets the reception statistics -- which RFC 3550 requires us to
     * report whether or not we buffer -- be collected while playback
     * still runs straight through, so the report blocks and the buffer
     * can be landed and judged separately.
     */
    void observe(int seq, long rtpTs, long arrivalTs, long nowMs) {
        long prevMax = lastSeq < 0 ? -1 : maxSeq;
        long ext = extend(seq);
        updateStats(ext, rtpTs, arrivalTs, nowMs);
        if (prevMax >= 0 && ext < prevMax) {
            reordered++;
        }
    }

    boolean offer(int seq, long rtpTs, long arrivalTs, byte[] payload,
                  long nowMs) {
        return offer(seq, rtpTs, arrivalTs, payload, false, nowMs);
    }

    /**
     * @param sid true when this frame is AMR comfort noise (a SID).
     *        Silence is the one moment latency can be given back without
     *        anybody hearing it shorten, which is where AOSP shrinks.
     */
    boolean offer(int seq, long rtpTs, long arrivalTs, byte[] payload,
                  boolean sid, long nowMs) {
        if (sid) {
            sawSid = true;
        } else {
            voiceInWindow++;
        }
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
            late++;
            noteDrop(nowMs);
            return false;
        }
        if (prevMax >= 0 && ext < prevMax) {
            reordered++;
        }
        queue.put(ext, payload);
        if (sid) {
            sidSeqs.add(ext);
        }
        while (queue.size() > MAX_DEPTH) {
            queue.remove(queue.firstKey());
            trimmed++;
            noteDrop(nowMs);
        }
        checkDropRate(nowMs);
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
        return poll(System.currentTimeMillis());
    }

    byte[] poll(long nowMs) {
        lastWasGap = false;
        if (waiting) {
            if (queue.isEmpty()) {
                return null;
            }
            if (waitStartedMs < 0) {
                waitStartedMs = nowMs;
            }
            /* Time, not count. A count-based rule never completes when
             * packets are lost during the fill, and playback simply never
             * starts -- an intermittent that looks like a dead call. */
            if (nowMs - waitStartedMs + ALLOWABLE_ERROR_MS
                    < (long) depth * PACKET_INTERVAL_MS) {
                return null;
            }
            waiting = false;
        }
        /* Bound the held audio to the depth. Without this the buffer
         * only ever grows: the frames accumulated during the fill are
         * never worked off, because after it the loop plays exactly one
         * frame per arrival and can never catch up. Every shrink made it
         * worse, dropping the target while keeping the audio. Measured
         * on the bench as queued=9 at depth=2 -- about 180 ms of
         * permanent, undrainable latency.
         *
         * AOSP does the same thing by advancing mCurrPlayingTS, which
         * discards a frame; discarding the oldest is the same decision
         * said plainly. The oldest is the right one to lose: it is the
         * most stale, and the alternative is carrying the delay for the
         * rest of the call. */
        while (queue.size() > depth + SLACK) {
            queue.remove(queue.firstKey());
            expected = queue.isEmpty() ? expected : queue.firstKey();
            trimmed++;
            noteDrop(nowMs);
        }
        java.util.Map.Entry<Long, byte[]> first = queue.firstEntry();
        if (first == null) {
            /* Ran dry. Keep the playout clock moving so the stream does
             * not silently slip, and refill before playing again. */
            if (expected >= 0) {
                expected++;
            }
            waiting = true;
            waitStartedMs = -1;
            lastWasGap = true;
            return null;
        }
        if (expected >= 0 && first.getKey() > expected) {
            /* The packet we wanted never came. Advance past it and let
             * the caller conceal rather than playing the next one early. */
            expected++;
            lastWasGap = true;
            return null;
        }
        queue.remove(first.getKey());
        expected = first.getKey() + 1;
        if (sidSeqs.remove(first.getKey())) {
            /* Playing comfort noise: shorten now, while it cannot be
             * heard, rather than carrying congestion-era depth forward. */
            shrinkOnSilence();
        }
        return first.getValue();
    }

    private void noteDrop(long nowMs) {
        dropTimes.addLast(nowMs);
    }

    /**
     * Too many drops in a window means the buffer is the wrong size, not
     * that the network is merely lossy.
     *
     * <p>AOSP uses two thresholds because DTX changes what "normal"
     * looks like: with comfort noise in the stream a high drop rate is
     * expected and only 80% is alarming, without it 35% already is. At
     * the ceiling there is no room to grow, so the answer is to refill;
     * below it, grow.
     */
    private void checkDropRate(long nowMs) {
        while (!dropTimes.isEmpty()
                && nowMs - dropTimes.peekFirst() > DROP_WINDOW_MS) {
            dropTimes.removeFirst();
        }
        int total = voiceInWindow + dropTimes.size();
        if (total < 20) {
            return; /* too little to judge */
        }
        int rate = (dropTimes.size() * 100) / total;
        int threshold = sawSid ? RESET_THRESHOLD_DTX : RESET_THRESHOLD_NO_DTX;
        if (rate <= threshold) {
            return;
        }
        if (depth >= MAX_DEPTH) {
            waiting = true;
            waitStartedMs = nowMs;
        } else {
            depth++;
            lastGrowAtMs = nowMs;
        }
        dropTimes.clear();
        voiceInWindow = 0;
    }

    /**
     * Give latency back during silence.
     *
     * <p>A comfort-noise frame is the one place the buffer can shorten
     * without anybody hearing it, so a depth that grew during congestion
     * is returned while nobody is speaking rather than held for the rest
     * of the call.
     */
    private void shrinkOnSilence() {
        if (sawSid && depth > MIN_DEPTH) {
            depth--;
            /* Give the frame back as well as the target. Lowering the
             * depth alone leaves the audio queued and the latency
             * exactly where it was. */
            if (!queue.isEmpty()) {
                queue.remove(queue.firstKey());
                trimmed++;
            }
        }
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
                + " late=" + late
                + " trimmed=" + trimmed;
    }
}
