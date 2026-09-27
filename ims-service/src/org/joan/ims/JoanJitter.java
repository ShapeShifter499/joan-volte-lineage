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
    /**
     * Weight applied to the worst offset in the window before the
     * round-up margin. AOSP's MARGIN_WEIGHT, 1.0 and configurable there.
     *
     * <p>Its BUFFER_INCREASE_TH (200 ms) is deliberately absent. AOSP
     * plumbs that constant through SetJitterOptions and then does not
     * consult it when sizing, and our first port turned it into an upper
     * bound on which arrivals may grow the buffer -- so a delta of 200 ms
     * or more, exactly the kind that needs the depth, grew it by nothing.
     */
    static final int BUFFER_WEIGHT = 1;
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
    /* Headroom above the adapted depth before a frame is discarded.
     *
     * Raised from 3 after a tester reported bad stutter with
     * depth=9 queued=4 jitter=402 loss=0% late=0 trimmed=16 of 575
     * frames. depth=9 is MAX_DEPTH: the adaptation had already decided
     * this link needed the deepest buffer allowed, the network delivered
     * everything, and the bound then threw away sixteen frames anyway --
     * sixteen 20 ms holes, which is what a stutter sounds like. Trimming
     * at depth+3 to save 60 ms while spending an audible gap is the wrong
     * trade at the ceiling.
     *
     * AOSP's equivalent cap is MAX_QUEUE_SIZE, 150 frames or three
     * seconds, and it does not trim toward the target at all -- latency
     * comes back through the comfort-noise skip and a reset when the drop
     * rate spikes, both of which joan now has. 6 is a deliberate middle:
     * 300 ms of worst-case hold against AOSP's 3000, chosen to be sized
     * properly next round rather than guessed at twice. qpeak below is
     * what will size it. */
    static final int SLACK = 6;

    /**
     * Buffer depth bounds, in packets.
     *
     * <p>The ceiling was 50 -- a full second of held audio at a 20 ms
     * frame. Nothing ever asked for that: the buffer only grows one frame
     * at a time, so reaching it takes a sustained bad stretch, and what it
     * buys at the far end is delay the call never gets back. ITU-T G.114
     * puts the one-way budget for unimpaired conversation at 150 ms
     * end-to-end, and the jitter buffer is only one term in that sum.
     *
     * <p>AOSP's own IMS media stack caps the audio jitter buffer at 9
     * frames (packages/modules/ImsMedia,
     * AudioJitterBuffer.cpp AUDIO_JITTER_BUFFER_MAX_SIZE), starting at 4
     * and floored at 3. We already borrowed that file's tuning constants
     * -- 20 ms interval, 10 ms round-up margin, 200/2000 ms thresholds,
     * step 2 -- so the ceiling matching is the rule rather than the
     * exception. This file's own SLACK comment already called nine frames
     * against a target of two "about 180 ms of latency the call never gets
     * back", which is the same judgement from the other direction.
     */
    /* AOSP's AUDIO_JITTER_BUFFER_MIN_SIZE. joan used 2 and everything
     * around it already matched: MAX_DEPTH 9 is AUDIO_JITTER_BUFFER_MAX_SIZE,
     * the initial 4 is AUDIO_JITTER_BUFFER_START_SIZE, DROP_WINDOW_MS 5000
     * and the 80/35 DTX thresholds are RESET_THRESHOLD_IN_DTX_ENABLED and
     * _DISABLED, and adapt() is GetNextJitterBufferSize. One frame below
     * the reference floor is 20 ms of headroom the reference keeps, and
     * the Digi.Mobil RO trace shows the buffer sitting exactly there. */
    static final int MIN_DEPTH = 3;
    static final int MAX_DEPTH = 9;

    /**
     * Hard ceiling on queued frames, independent of the playout target.
     *
     * <p>Two different jobs used to share MAX_DEPTH: the cap on how deep
     * the adaptive target may grow, and the absolute guard on the arrival
     * queue. They are not the same number -- the queue legitimately sits a
     * little above the target while a frame waits for its slot, which is
     * what SLACK is for. Lowering the target cap to 9 without splitting
     * them would have tightened the arrival guard below the drain bound of
     * depth + SLACK and started trimming frames that were about to play.
     */
    /* Deliberately NOT AOSP's MAX_QUEUE_SIZE, which is 150 frames -- a
     * three-second safety valve rather than a latency control. AOSP holds
     * latency down by skipping a frame during comfort noise and by
     * resetting when the drop rate spikes; joan bounds the queue directly
     * because that was measured not to be enough here, queued=9 against
     * depth=2, about 180 ms of permanent undrainable latency. Kept as a
     * known divergence rather than aligned blind: the shrink fix has
     * already taken the observed trim rate from 19% to under 3%, so the
     * bound is doing far less work than it was. */
    static final int MAX_QUEUE = MAX_DEPTH + SLACK;

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

    /* --- AOSP JitterNetworkAnalyser sizing state ---------------------
     *
     * The window holds each packet's accumulated arrival offset in
     * milliseconds -- how far its arrival has drifted from its nominal
     * slot since the stream began -- normalised to the smallest offset
     * seen while the window was still filling. The buffer is sized from
     * the WORST offset still in the window, not from the newest arrival.
     *
     * That distinction is the whole point. Sizing from the latest delta
     * alone, as the first port did, means one calm packet can talk the
     * buffer back down while the link is still misbehaving, and a single
     * outlier can talk it up when nothing else agrees. MAX_HISTORY was
     * declared for this and then never used.
     */
    private final int[] offsets = new int[MAX_HISTORY];
    private int offsetCount;
    private int offsetAt;
    private long firstTransit;
    private boolean haveFirstTransit;
    private int minOffsetMs = Integer.MAX_VALUE;
    /** Whether the last sizing decision judged the network GOOD. */
    private boolean inGood;
    private long goodSinceMs;
    /** When a packet last missed its slot; 0 for never. */
    private long lastLateAtMs;

    /** Arrived after its slot had already played: the depth was short. */
    private int late;
    /** Given up to bound the latency: the buffer doing its job. */
    private int trimmed;
    /* The deepest the queue ever got. queued= is the instantaneous value
     * at report time, which says nothing about the burst that caused a
     * trim; without this the bound above can only be guessed at. */
    private int queuePeak;
    /* The depth adapt() last computed from observed transit offsets.
     * AOSP calls this mUpdatedDelay and gates the comfort-noise shrink on
     * it being negative; without it a shrink can fight the adaptation. */
    private int lastTarget = -1;
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
        java.util.Arrays.fill(offsets, 0);
        offsetCount = 0;
        offsetAt = 0;
        firstTransit = 0;
        haveFirstTransit = false;
        minOffsetMs = Integer.MAX_VALUE;
        inGood = false;
        goodSinceMs = 0;
        lastLateAtMs = 0;
        late = 0;
        trimmed = 0;
        queuePeak = 0;
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
            /* AudioJitterBuffer calls SetLateArrivals here; the shrink
             * refuses to act while one is this recent. */
            lastLateAtMs = nowMs;
            noteDrop(nowMs);
            return false;
        }
        if (prevMax >= 0 && ext < prevMax) {
            reordered++;
        }
        queue.put(ext, payload);
        if (queue.size() > queuePeak) {
            queuePeak = queue.size();
        }
        if (sid) {
            sidSeqs.add(ext);
        }
        while (queue.size() > MAX_QUEUE) {
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
            shrinkOnSilence(nowMs);
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
    private void shrinkOnSilence(long nowMs) {
        if (!sawSid || depth <= MIN_DEPTH) {
            return;
        }
        /* Only when the buffer has not needed to grow recently.
         *
         * DECREASE_THRESHOLD_MS and lastGrowAtMs already existed for
         * exactly this, and this path did not consult either: every
         * comfort-noise frame shrank the target, so on a stream with
         * ordinary DTX the depth walked straight down to MIN_DEPTH and
         * stayed there. The Digi.Mobil RO trace shows the end state,
         * depth=2 with a clean link.
         *
         * AOSP guards the same decision with mUpdatedDelay < 0 -- it
         * shrinks only when the analyser actually asked for less delay,
         * not merely because comfort noise is playing
         * (ImsMedia AudioJitterBuffer.cpp, "decrease delay"). */
        if (lastGrowAtMs != 0 && nowMs >= lastGrowAtMs
                && nowMs - lastGrowAtMs < DECREASE_THRESHOLD_MS) {
            return;
        }
        /* And only when the adaptation actually wants less depth.
         *
         * This is AOSP's mUpdatedDelay < 0. Without it the comfort-noise
         * shrink fights adapt(): on a link whose observed transit offsets
         * still justify the current depth, every SID frame pulled the
         * buffer down one anyway, and it walked to the floor while the
         * measurements said to stay. Comfort noise is the moment latency
         * can be handed back cheaply, not a reason to hand it back. */
        if (lastTarget >= depth) {
            return;
        }
        depth--;
        /* Give the frame back as well as the target, but only if that
         * frame is itself comfort noise.
         *
         * This used to drop queue.firstKey() unconditionally, on the
         * reasoning that a shrink during silence cannot be heard. The
         * frame being PLAYED is silent; the one being deleted is the
         * oldest QUEUED frame, which is a future frame and may well be
         * speech. So the shrink was paying for its latency with audio
         * the caller was about to hear.
         *
         * Measured on Digi.Mobil RO: loss=0% jitter=0 on the wire and
         * trimmed=191 of 981 frames on a call whose speech activity was
         * 81% -- the discard rate tracked the silence rate, which is
         * this path and not the network. It was reported as "quality
         * wasn't that great".
         *
         * Lowering the target alone is not a no-op: the queue drains
         * naturally against the new bound as later frames arrive, which
         * returns the same latency without deleting anything. */
        if (queue.isEmpty()) {
            return;
        }
        long oldest = queue.firstKey();
        if (sidSeqs.remove(oldest)) {
            queue.remove(oldest);
            trimmed++;
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
        }
        if (!haveFirstTransit) {
            firstTransit = transit;
            haveFirstTransit = true;
        }
        noteOffset((int) ((transit - firstTransit) / ticksPerMs()), nowMs);
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
    /**
     * Record one packet's normalised arrival offset and re-size.
     *
     * <p>The normalisation baseline only falls while the window is still
     * filling, which is what AOSP does: once there is a full window to
     * judge from, moving the baseline would quietly re-scale every
     * comparison underneath it.
     */
    private void noteOffset(int offsetMs, long nowMs) {
        offsets[offsetAt] = minOffsetMs == Integer.MAX_VALUE
                ? offsetMs : offsetMs - minOffsetMs;
        offsetAt = (offsetAt + 1) % MAX_HISTORY;
        if (offsetCount < MAX_HISTORY) {
            offsetCount++;
            if (offsetMs < minOffsetMs) {
                minOffsetMs = offsetMs;
            }
        }
        adapt(nowMs);
    }

    /**
     * AOSP's {@code JitterNetworkAnalyser::GetNextJitterBufferSize}.
     *
     * <p>The target is the worst offset in the window, weighted, plus a
     * round-up margin, divided by the packet interval. Three outcomes,
     * and they are deliberately asymmetric: growth is immediate and goes
     * straight to the computed size, because audio that needed the depth
     * is already being discarded by the time we notice; shrinking is
     * slow, stepped, and refuses to act at all until the network has been
     * quiet for {@code DECREASE_THRESHOLD_MS} with no late arrival in
     * that window, because giving depth back too eagerly is how a buffer
     * oscillates.
     *
     * <p>One divergence from AOSP, on purpose: it lets a negative
     * computed size fall into an unsigned divide, which cannot end well.
     * The target floors at zero here and the ordinary bounds take it from
     * there.
     */
    private void adapt(long nowMs) {
        if (offsetCount == 0) {
            return;
        }
        int worst = 0;
        for (int i = 0; i < offsetCount; i++) {
            if (offsets[i] > worst) {
                worst = offsets[i];
            }
        }
        int target = (worst * BUFFER_WEIGHT + ROUNDUP_MARGIN_MS)
                / PACKET_INTERVAL_MS;
        lastTarget = target;
        if (target > depth) {
            depth = target > MAX_DEPTH ? MAX_DEPTH : target;
            if (depth < MIN_DEPTH) {
                depth = MIN_DEPTH;
            }
            lastGrowAtMs = nowMs;
            inGood = false;
            return;
        }
        if (target < depth - 1) {
            if (!inGood) {
                inGood = true;
                goodSinceMs = nowMs;
                return;
            }
            if (nowMs - goodSinceMs < DECREASE_THRESHOLD_MS) {
                return;
            }
            if (lastLateAtMs != 0
                    && nowMs - lastLateAtMs <= DECREASE_THRESHOLD_MS) {
                return;
            }
            int step = depth - target;
            if (step > DECREASE_STEP) {
                step = DECREASE_STEP;
            }
            depth -= step;
            if (depth < MIN_DEPTH) {
                depth = MIN_DEPTH;
            }
            /* AOSP drops back to NORMAL after a decrease, so the dwell
             * has to be earned again before the next one. */
            inGood = false;
            return;
        }
        inGood = false;
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
                + " qpeak=" + queuePeak
                + " trimmed=" + trimmed;
    }
}
