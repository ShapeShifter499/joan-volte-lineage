package org.joan.ims;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Process;
import android.util.Log;

import android.net.Network;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;

/**
 * 8 kHz PCMU between AudioFlinger and the peer's RTP.
 *
 * Capture and playback run on separate threads (a single loop that
 * blocked on mic read then UDP receive sent uplink every ~40 ms).
 *
 * Audio is the AOSP AP-IMS path: after
 * {@code MmTelFeature.setCallAudioHandler(AUDIO_HANDLER_ANDROID)},
 * Telecom uses {@code MODE_IN_COMMUNICATION} and the voice-communication
 * stream is the in-call mixer. Do not change AudioManager mode or
 * speakerphone from this class. Routing stays with Dialer/Telecom.
 */
final class JoanMedia {
    private static final String TAG = "JoanIms";
    /* PCMU: 8 kHz, 160 samples per 20 ms. AMR-WB moves both. Held as
     * fields rather than constants because the codec is negotiated. */
    private static final int PCMU_HZ = 8000;
    private static final int PCMU_SAMPLES = 160;
    private static volatile int sRate = PCMU_HZ;
    private static volatile int sFrame = PCMU_SAMPLES;
    /** RTP payload type actually negotiated; 0 for PCMU. */
    private static volatile int sPt;
    /** Non-null when the call negotiated AMR rather than G.711. */
    private static volatile JoanAmrCodec sAmr;

    private static volatile boolean sRun;
    private static Thread sCap;
    private static Thread sPlay;
    private static Thread sRtcpRx;
    /** Bound only when RTCP is NOT muxed onto the RTP port. */
    private static volatile DatagramSocket sRtcpSock;
    private static volatile long sRtcpReports;
    private static volatile int sLossFrac = -1;
    private static volatile int sLossCum;
    private static volatile int sJitter;
    private static volatile int sWorstLossFrac;

    /** Accept one RTCP packet and keep its first receiver-report block. */
    static void onRtcp(byte[] b, int len) {
        /* LSR/DLSR let the far end compute a round trip from our next
         * report. Capturing the arrival time matters as much as the
         * timestamp: DLSR is the delay between the two. */
        long lsr = JoanRtcp.lastSrTimestamp(b, len);
        if (lsr != 0) {
            sLastSr = lsr;
            sLastSrAtMs = System.currentTimeMillis();
        }
        JoanRtcp.Report r = JoanRtcp.parse(b, len);
        if (r == null) {
            return;
        }
        sRtcpReports++;
        sLossFrac = r.fractionLost;
        sLossCum = r.cumulativeLost;
        sJitter = r.jitter;
        if (r.fractionLost > sWorstLossFrac) {
            sWorstLossFrac = r.fractionLost;
            if (r.lossPercent() >= 5) {
                JoanTrace.note("rtcp peer reports loss " + r.lossPercent()
                        + "% jitter=" + r.jitter);
            }
        }
    }

    /* Readers for the host tests: the parse is the part worth pinning. */
    static int lastLossPercent() {
        return sLossFrac < 0 ? -1 : sLossFrac * 100 / 256;
    }

    static int lastCumulativeLost() {
        return sLossCum;
    }

    static int lastJitter() {
        return sJitter;
    }

    /** What the peer last told us about the stream it is receiving. */
    private static String rtcpSummary() {
        if (sRtcpReports == 0) {
            return "rtcp_rr=none";
        }
        return "rtcp_rr=" + sRtcpReports + " loss=" + (sLossFrac * 100 / 256)
                + "% worst=" + (sWorstLossFrac * 100 / 256)
                + "% cum=" + sLossCum + " jitter=" + sJitter;
    }

    private static void rtcpReceive() {
        byte[] buf = new byte[1500];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (sRun) {
            DatagramSocket s = sRtcpSock;
            if (s == null) {
                return;
            }
            try {
                s.receive(p);
            } catch (java.net.SocketTimeoutException e) {
                continue;
            } catch (Exception e) {
                return;     // closed by stop(), or the socket died
            }
            onRtcp(p.getData(), p.getLength());
        }
    }
    private static DatagramSocket sSock;

    /** Consecutive codec errors tolerated before a direction gives up. */
    private static final int MAX_CODEC_FAILS = 25;

    private static final int RTP_HDR = 12;
    private static volatile InetAddress sDest;
    /* Where the RTP we receive actually comes from. Deliberately counters
     * and a match/differ verdict rather than addresses: an IMS PDN address
     * identifies a subscriber, and testers post these traces in public. */
    private static volatile long sRtpFromDest;
    private static volatile long sRtpFromOther;
    private static volatile int sDestPort;
    private static volatile int sRtcpPort;
    private static volatile boolean sMux;
    private static volatile int sSeq;
    private static volatile int sTs;
    private static volatile int sSsrc;
    private static volatile int sSent;
    private static volatile int sRecv;
    private static volatile int sOctets;
    private static volatile long sRtcpNext;
    /** Negotiated AMR framing; false selects the bandwidth-efficient packer. */
    private static volatile boolean sAmrOct = true;
    /** Highest AMR mode the peer permits; -1 when they set no ceiling. */
    private static volatile int sAmrMaxMode = -1;
    /** Mode asked for by CMR or ANBR, applied by the capture thread. */
    /* ---- RFC 4733 telephone-event (DTMF) ------------------------------
     *
     * Digits cannot be played into the microphone path: AMR is a speech
     * codec and a DTMF tone put through it does not survive as one, which
     * is why every IMS stack -- AOSP's ImsMedia included, where this is a
     * DtmfSenderNode feeding the same RTP encoder -- sends the digit as
     * its own payload type and pauses the audio while it does.
     *
     * The capture thread owns the socket and the sequence space, so the
     * dialler's thread only queues an event here and the capture loop
     * emits it. */

    /** Negotiated telephone-event payload type; 0 when the peer offered none. */
    private static volatile int sTePt;

    /**
     * One queued digit.
     *
     * <p>{@code done} is run when the tone has actually finished on the
     * wire, not when it was accepted. The framework's post-dial machinery
     * (ImsPhoneConnection.processPostDialChar) sends one digit, waits for
     * that callback, waits a carrier-configured gap, and only then sends
     * the next -- so answering early turns a post-dial string like
     * "555000,,1234#" into whichever digits happened to fit.
     */
    private static final class Tone {
        final int event;
        final boolean held;
        int leftMs;
        Runnable done;

        Tone(int event, boolean held, int leftMs, Runnable done) {
            this.event = event;
            this.held = held;
            this.leftMs = leftMs;
            this.done = done;
        }
    }

    /**
     * Digits accepted but not yet sent.
     *
     * <p>A queue rather than a single slot for the same reason: a burst
     * arriving faster than one tone per 200 ms must be played in order,
     * not overwritten.
     */
    private static final java.util.Queue<Tone> sDtmfQueue =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** Longest backlog worth holding; beyond this the dialler is runaway. */
    private static final int DTMF_QUEUE_MAX = 32;
    /** False asks the capture loop to end a held tone. */
    private static volatile boolean sDtmfHold;

    /**
     * What WE observe about the peer's stream, for the report block RFC
     * 3550 requires us to send.
     *
     * <p>Deliberately not named sJitter: that already holds what the peer
     * reports about OUR stream, read out of its receiver reports. The two
     * are opposite directions and confusing them would have us echo the
     * far end's own numbers back at it.
     */
    private static final JoanJitter sRx = new JoanJitter();
    /** The peer's SSRC, learned from its RTP; 0 until we hear one. */
    private static volatile int sPeerSsrc;
    /** Middle 32 bits of the peer's last SR timestamp, and when it came. */
    private static volatile long sLastSr;
    private static volatile long sLastSrAtMs;

    /** One trace line per call when the peer sends us DTMF. */
    private static volatile boolean sDtmfRxSeen;
    /** Packets dropped for carrying a payload type we never negotiated. */
    private static volatile int sRtpWrongPt;
    /** Frames handed to the decoder as lost, for it to conceal. */
    private static volatile int sConcealed;

    /* Owned by the capture thread alone. */
    private static Tone sDtmfTone;
    private static int sDtmfTs;
    private static int sDtmfDur;
    private static int sDtmfEnds;
    private static boolean sDtmfMark;

    /**
     * RFC 4733 s2.5.2: repeat the final packet so a loss cannot hang the
     * tone. AOSP spells the same thing as a 40 ms retransmit window
     * (DTMF_DEFAULT_RETRANSMIT_DURATION), which at a 20 ms frame is these
     * three packets.
     */
    private static final int DTMF_END_REPEATS = 3;
    /** What sendDtmf() plays when the framework does not say. */
    private static final int DTMF_TONE_MS = 200;
    /**
     * Shortest tone worth sending.
     *
     * <p>AOSP forces this floor in calculateDtmfDuration() before it
     * builds a single packet: a tone briefer than this is not reliably
     * detected at the far end, so honouring a shorter request would look
     * like a digit was sent when nothing usable was.
     */
    private static final int DTMF_MIN_MS = 40;
    /**
     * A held tone is released after this long whatever the dialler does.
     *
     * <p>The duration field is 16 bits of timestamp units -- about four
     * seconds at 16 kHz -- and a key held past that would wrap into a
     * shorter tone. Ending it is both correct on the wire and a backstop
     * against a missed stopDtmf() leaving the audio muted for the call.
     */
    private static final int DTMF_MAX_MS = 4000;

    private static volatile int sPendingMode = -1;
    private static volatile int sAppliedMode = -1;

    /**
     * Ask the encoder to move to an AMR mode, from a CMR in the peer's RTP
     * or an ANBR recommendation from the radio.
     *
     * <p>Refused above the negotiated ceiling: a request outside the
     * mode-set both sides agreed is not a request we may honour, and AOSP
     * makes the same check before applying an ANBR bitrate. The capture
     * thread performs the change at a frame boundary, because reconfiguring
     * a MediaCodec underneath a thread that is mid-encode is not safe.
     */
    static void requestMode(int mode, String why) {
        if (sAmr == null || mode < 0) {
            return;
        }
        if (sAmrMaxMode >= 0 && mode > sAmrMaxMode) {
            JoanTrace.note("amr " + why + " asked mode " + mode
                    + " above negotiated " + sAmrMaxMode + "; refused");
            return;
        }
        if (mode == sAppliedMode) {
            return;
        }
        JoanAmrCodec amr = sAmr;
        if (amr != null && !JoanAmrCodec.retuneSupported(amr.wideband())) {
            JoanTrace.note("amr " + why + " asked mode " + mode
                    + "; this ROM cannot retune, staying at " + sAppliedMode);
            return;
        }
        JoanTrace.note("amr " + why + " requests mode " + mode
                + " (from " + sAppliedMode + ")");
        sPendingMode = mode;
    }

    private JoanMedia() {}

    /**
     * Begin a tone that plays until {@link #stopDtmf()}.
     *
     * @return false when the digit is not a DTMF digit, no telephone-event
     *         payload type was negotiated, or no call is carrying media.
     *         Refusing is the honest answer: there is no way to place the
     *         tone in the stream, and encoding it as audio would reach the
     *         far end as noise an IVR cannot decode.
     */
    static boolean startDtmf(char digit) {
        sDtmfHold = true;
        return queueDtmf(digit, true, 0, null);
    }

    /**
     * Play a tone of a fixed length, as the framework's sendDtmf asks.
     *
     * @param done run once the tone has finished on the wire, or
     *             immediately if the digit could not be queued at all.
     *             Never dropped: the post-dial state machine stalls
     *             forever on a callback that does not arrive.
     */
    static boolean sendDtmf(char digit, int durationMs, Runnable done) {
        return queueDtmf(digit, false,
                durationMs > 0 ? durationMs : DTMF_TONE_MS, done);
    }

    /** Release a held tone; the capture loop sends the end packets. */
    static void stopDtmf() {
        sDtmfHold = false;
    }

    private static boolean queueDtmf(char digit, boolean held, int durationMs,
                                     Runnable done) {
        int ev = JoanDtmf.event(digit);
        String refuse = null;
        if (ev < 0) {
            refuse = "not a DTMF digit";
        } else if (!sRun) {
            refuse = "no media running";
        } else if (sTePt <= 0) {
            refuse = "no telephone-event type was negotiated";
        } else if (sDtmfQueue.size() >= DTMF_QUEUE_MAX) {
            refuse = "queue full";
        }
        if (refuse != null) {
            JoanTrace.note("dtmf '" + digit + "' refused: " + refuse);
            run(done);
            return false;
        }
        int ms = held ? DTMF_MAX_MS
                : Math.min(Math.max(durationMs, DTMF_MIN_MS), DTMF_MAX_MS);
        sDtmfQueue.add(new Tone(ev, held, ms, done));
        return true;
    }

    /** Run a completion without letting its failure reach the audio path. */
    private static void run(Runnable done) {
        if (done == null) {
            return;
        }
        try {
            done.run();
        } catch (Throwable t) {
            JoanTrace.note("dtmf callback " + t.getClass().getSimpleName());
        }
    }

    /**
     * Answer every outstanding completion and drop the backlog.
     *
     * <p>Called when media starts or stops. A digit queued against a call
     * that has ended will never be sent, but its caller is still waiting:
     * dropping the callback with the tone would wedge a post-dial string
     * until the wake lock times out.
     */
    private static void finishDtmfQueue() {
        for (Tone t = sDtmfQueue.poll(); t != null; t = sDtmfQueue.poll()) {
            run(t.done);
        }
        Tone cur = sDtmfTone;
        sDtmfTone = null;
        if (cur != null) {
            run(cur.done);
        }
    }

    /**
     * Fill {@code out} with the next telephone-event payload, or return 0
     * when no tone is in flight and the frame should carry audio.
     *
     * <p>Called only from the capture thread, once per captured frame, so
     * the tone is paced by the same clock as the audio it replaces.
     */
    private static int dtmfFrame(byte[] out, int samples) {
        if (sDtmfTone == null) {
            Tone next = sDtmfQueue.poll();
            if (next == null) {
                return 0;
            }
            sDtmfTone = next;
            if (next.held && !sDtmfHold) {
                /* Released before it ever started: play the shortest tone
                 * that is still detectable rather than nothing, so a very
                 * quick key press is not silently swallowed. */
                next.leftMs = DTMF_MIN_MS;
            }
            /* The event's timestamp is the instant the tone began and does
             * not advance with its packets; only the duration field grows.
             * The audio clock keeps running underneath so that speech
             * resumes on the right tick. */
            sDtmfTs = sTs;
            sDtmfDur = 0;
            sDtmfEnds = 0;
            sDtmfMark = true;
            JoanTrace.note("dtmf start event=" + next.event + " pt=" + sTePt
                    + (next.held ? " held" : " ms=" + next.leftMs));
        }
        Tone tone = sDtmfTone;
        boolean end = tone.held && !sDtmfHold;
        if (!end) {
            int ms = samples * 1000 / (sRate > 0 ? sRate : PCMU_HZ);
            tone.leftMs -= ms;
            if (tone.leftMs <= 0) {
                end = true;
            }
            sDtmfDur += samples;
            if (sDtmfDur >= 0xffff) {
                /* Sixteen bits of timestamp units is the whole field. */
                sDtmfDur = 0xffff;
                end = true;
            }
        }
        int n = JoanDtmf.pack(tone.event, end, JoanDtmf.DEFAULT_VOLUME,
                sDtmfDur, out);
        if (n < 0) {
            sDtmfTone = null;
            run(tone.done);
            return 0;
        }
        if (end && ++sDtmfEnds >= DTMF_END_REPEATS) {
            JoanTrace.note("dtmf end event=" + tone.event
                    + " ticks=" + sDtmfDur);
            sDtmfTone = null;
            run(tone.done);
        }
        return n;
    }

    static boolean startRtp(Context ctx, Network net, InetAddress local,
                            InetAddress dest, int destPort, int rtcpPort,
                            boolean mux) {
        return startRtp(ctx, net, local, dest, destPort, rtcpPort, mux, 0,
                null, 0, true, -1, 0);
    }

    /**
     * @param payloadType the RTP payload type the answer selected
     * @param amrWideband TRUE for AMR-WB, FALSE for AMR-NB, null for PCMU.
     *        When AMR is asked for and the codec will not open, the call
     *        falls back to PCMU rather than running with no media -- the
     *        codec is negotiated, so a silent failure here is a silent
     *        call.
     */
    static boolean startRtp(Context ctx, Network net, InetAddress local,
                            InetAddress dest, int destPort, int rtcpPort,
                               boolean mux, int payloadType, Boolean amrWideband,
                            int amrBitrate, boolean amrOctetAligned,
                            int amrMaxMode, int telephoneEventPt) {
        stop();
        sPt = payloadType;
        sTePt = telephoneEventPt;
        sDtmfRxSeen = false;
        sRtpWrongPt = 0;
        sConcealed = 0;
        sPeerSsrc = 0;
        sLastSr = 0;
        sLastSrAtMs = 0;
        sRx.reset();
        sRx.setClockRate(amrWideband == null ? PCMU_HZ
                : (amrWideband ? 16000 : 8000));
        finishDtmfQueue();
        sDtmfHold = false;
        sDtmfTone = null;
        sDtmfEnds = 0;
        sAmrOct = amrOctetAligned;
        sAmrMaxMode = amrMaxMode;
        sPendingMode = -1;
        sAppliedMode = amrWideband == null ? -1
                : JoanAmr.bitrateMode(amrBitrate > 0 ? amrBitrate
                        : (amrWideband ? 23850 : 12200), amrWideband);
        sAmr = null;
        sRate = PCMU_HZ;
        sFrame = PCMU_SAMPLES;
        if (amrWideband != null) {
            JoanAmrCodec c = JoanAmrCodec.open(amrWideband, amrBitrate);
            if (c != null) {
                sAmr = c;
                sRate = c.sampleRate();
                sFrame = c.samplesPerFrame();
            } else {
                /* The codec was negotiated. Streaming u-law on the AMR
                 * payload type instead would leave the peer sending AMR
                 * and both directions dead, with the call still showing as
                 * connected -- the exact silent failure an OEM guarantee
                 * is supposed to rule out, and we do not have one. Refuse
                 * instead, and let the caller end the call audibly.
                 * JoanAmrCodec.selfTest() at startup should have removed
                 * this codec from the profile already, so reaching here
                 * means a transient, not a missing codec. */
                JoanTrace.note("amr negotiated but will not open wb="
                        + amrWideband + "; refusing to carry PCMU instead");
                return false;
            }
        }
        Context app = ctx.getApplicationContext();
        sDest = dest;
        sDestPort = destPort;
        sRtcpPort = rtcpPort > 0 ? rtcpPort : destPort + 1;
        sMux = mux;
        sSeq = new SecureRandom().nextInt() & 0xffff;
        sTs = new SecureRandom().nextInt();
        sSsrc = new SecureRandom().nextInt();
        sSent = sRecv = sOctets = 0;
        sRtcpNext = System.currentTimeMillis() + 400;
        try {
            sSock = new DatagramSocket(null);
            sSock.setReuseAddress(true);
            if (net != null) {
                net.bindSocket(sSock);
            }
            sSock.bind(new InetSocketAddress(local, JoanSipUa.RTP_PORT));
            /* Short enough to notice sRun going false, and to pace the
             * PCMU track feed when no packet arrives; stop() closes the
             * socket which unblocks receive anyway. 40 ms bounds a
             * receive-starved track to two frames behind real time. */
            sSock.setSoTimeout(40);
        } catch (Exception e) {
            JoanTrace.note("media sock " + e.getClass().getSimpleName());
            return false;
        }
        sRun = true;
        sRtcpReports = 0;
        sLossFrac = -1;
        sLossCum = 0;
        sJitter = 0;
        sWorstLossFrac = 0;
        if (!sMux) {
            /* Non-muxed RTCP arrives on RTP+1, which nothing bound before
             * this: the kernel dropped every receiver report the peer
             * sent. Sending is left exactly as it was -- e7783f8 found
             * that this core needs the SR on the RTP 5-tuple, and that is
             * not a thing to disturb while adding a reader. */
            try {
                DatagramSocket r = new DatagramSocket(null);
                r.setReuseAddress(true);
                if (net != null) {
                    net.bindSocket(r);
                }
                r.bind(new InetSocketAddress(local, JoanSipUa.RTP_PORT + 1));
                r.setSoTimeout(500);
                sRtcpSock = r;
            } catch (Exception e) {
                JoanTrace.note("rtcp bind " + e.getClass().getSimpleName()
                        + "; receiver reports will not be seen");
                sRtcpSock = null;
            }
        }
        sCap = new Thread(() -> capture(app), "joan-ims-cap");
        sPlay = new Thread(() -> playback(app), "joan-ims-play");
        sCap.start();
        sPlay.start();
        if (sRtcpSock != null) {
            sRtcpRx = new Thread(JoanMedia::rtcpReceive, "joan-ims-rtcp");
            sRtcpRx.start();
        }
        sRtpFromDest = 0;
        sRtpFromOther = 0;
        JoanTrace.note("media start rtp mux=" + mux
                + " fam=" + (local instanceof java.net.Inet6Address ? "v6" : "v4")
                + " local_port=" + JoanSipUa.RTP_PORT
                + " dest_port=" + destPort
                + " rtcp_port=" + (sMux ? sDestPort : sRtcpPort)
                + " codec=" + (sAmr == null ? "PCMU"
                        : (sAmr.wideband() ? "AMR-WB" : "AMR-NB"))
                + " pt=" + sPt + " rate=" + sRate
                + " bitrate=" + (amrBitrate > 0 ? amrBitrate : 0)
                + " framing=" + (sAmr == null ? "n/a"
                        : (sAmrOct ? "octet-aligned" : "bandwidth-efficient"))
                + " te_pt=" + sTePt);
        return true;
    }

    static void stop() {
        sRun = false;
        sDtmfHold = false;
        finishDtmfQueue();
        Thread[] ts = { sCap, sPlay, sRtcpRx };
        sCap = null;
        sPlay = null;
        sRtcpRx = null;
        DatagramSocket sock = sSock;
        sSock = null;
        if (sock != null) {
            sock.close();
        }
        DatagramSocket rs = sRtcpSock;
        sRtcpSock = null;
        if (rs != null) {
            rs.close();
        }
        for (Thread t : ts) {
            if (t == null) {
                continue;
            }
            t.interrupt();
            try {
                t.join(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        JoanAmrCodec amr = sAmr;
        sAmr = null;
        if (amr != null) {
            amr.close();
        }
        JoanTrace.note("media stop");
    }

    /**
     * Both media threads carry a 20 ms deadline. At default priority the
     * scheduler is free to leave either of them behind a background task,
     * which shows up as dropouts rather than as anything logged.
     */
    private static void audioPriority(String which) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        } catch (Throwable t) {
            JoanTrace.note("media " + which + " priority "
                    + t.getClass().getSimpleName());
        }
    }

    /**
     * Pure summary of where received RTP came from. Host-testable.
     *
     * <p>This is the line that tells a one-way-audio report apart: silence
     * from the expected peer is a media-negotiation or core problem, silence
     * with nothing arriving at all is a routing problem, and packets from an
     * unexpected source means the peer moved and we never followed.
     */
    static String rtpSourceSummary(long fromDest, long fromOther) {
        if (fromDest == 0 && fromOther == 0) {
            return "rtp_src=none";
        }
        if (fromOther == 0) {
            return "rtp_src=peer";
        }
        if (fromDest == 0) {
            return "rtp_src=other-only n=" + fromOther;
        }
        return "rtp_src=mixed peer=" + fromDest + " other=" + fromOther;
    }

    private static AudioAttributes voiceAttrs() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setLegacyStreamType(AudioManager.STREAM_VOICE_CALL)
                .build();
    }

    /** Below this, over five seconds, the microphone is not producing voice. */
    private static final double UL_SILENT_DBFS = -50.0;
    /** And speech was detected in fewer than this share of the samples. */
    private static final double UL_SILENT_ACTIVE = 0.05;

    /**
     * Whether five seconds of capture amount to silence.
     *
     * <p>Both conditions are required so that a quiet room is not
     * mistaken for a dead capture: a live microphone in a silent room
     * still moves, and a real speaker trips the activity share long
     * before five seconds are up. The observed failure was -59 dBFS with
     * an activity share of 0.
     */
    static boolean silentUplink(long sumSq, long samples, long activeSamples) {
        if (samples <= 0) {
            return false;
        }
        double rms = Math.sqrt((double) sumSq / (double) samples);
        double dbfs = rms <= 0 ? -120.0
                : 20.0 * Math.log10(rms / 32768.0);
        double active = (double) activeSamples / (double) samples;
        return dbfs < UL_SILENT_DBFS && active < UL_SILENT_ACTIVE;
    }

    /** Reopen the capture on MIC. Returns null if that is no better. */
    private static AudioRecord swapToMic(AudioRecord old, int inBuf) {
        JoanTrace.note("media ul silent on src="
                + MediaRecorder.AudioSource.VOICE_COMMUNICATION
                + "; reopening on MIC");
        try {
            old.stop();
        } catch (Throwable ignored) {
            // Already stopped, or never really started.
        }
        try {
            old.release();
        } catch (Throwable ignored) {
            // Nothing to do; we are replacing it either way.
        }
        AudioRecord alt = openRecord(inBuf,
                new int[] { MediaRecorder.AudioSource.MIC });
        if (alt == null) {
            JoanTrace.note("media ul swap failed; no MIC capture");
            return null;
        }
        try {
            alt.startRecording();
        } catch (Throwable t) {
            JoanTrace.note("media ul swap start "
                    + t.getClass().getSimpleName());
            try { alt.release(); } catch (Throwable ignored) { }
            return null;
        }
        return alt;
    }

    private static void capture(Context app) {
        AudioRecord rec = null;
        boolean ulSwapped = false;
        boolean micPermitted = true;
        int inBuf = 0;
        long ulSumSq = 0;
        long ulSamples = 0;
        long ulActSq = 0;
        long ulActSamples = 0;
        int ulPeak = 0;
        boolean ulLogged = false;
        audioPriority("cap");
        try {
            int minIn = AudioRecord.getMinBufferSize(sRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            inBuf = Math.max(minIn, sFrame * 8);
            /* Say whether we may actually record, because AudioRecord
             * will not.
             *
             * Without RECORD_AUDIO the appops layer hands back digital
             * silence rather than an error: the object constructs,
             * reports STATE_INITIALIZED, and read() returns zeros. The
             * trace then says "media record ok" on a handset whose
             * uplink is dead, which is exactly how this was read as the
             * microphone working and the network being at fault.
             * Measured on Digi.Mobil RO: rms=-99.0dBFS peak=-99.0dBFS,
             * every sample zero, on both sources. */
            micPermitted = app.checkSelfPermission(
                    android.Manifest.permission.RECORD_AUDIO)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            JoanTrace.note("media capture record_audio="
                    + (micPermitted ? "granted"
                            : "DENIED; uplink will be digital silence"));
            rec = openRecord(inBuf);
            if (rec == null) {
                JoanTrace.note("media no AudioRecord");
                return;
            }
            rec.startRecording();
            DatagramSocket sock = sSock;
            if (sock == null) {
                return;
            }
            InetAddress dest = sDest;
            int dport = sDestPort;
            JoanAmrCodec amr = sAmr;
            short[] pcm = new short[sFrame];
            byte[] ulaw = new byte[sFrame];
            /* AMR-WB's largest frame is 60 bytes plus a ToC; the RTP
             * payload is far smaller than a PCMU frame, but the buffer has
             * to fit whichever codec is running. */
            /* Sized for a whole output buffer, not for one nominal frame:
             * MediaCodec may hand back more than the 60-byte AMR-WB
             * maximum, and an outsized frame must be skippable rather
             * than fatal. */
            byte[] storage = new byte[512];
            byte[] payload = new byte[544];
            int encFails = 0;
            byte[] rtp = new byte[RTP_HDR + Math.max(sFrame, payload.length)];
            DatagramPacket out = new DatagramPacket(
                    rtp, rtp.length, dest, dport);
            AudioManager cam = app.getSystemService(AudioManager.class);
            JoanTrace.note("media cap rolling src=" + rec.getAudioSource()
                    + " mode=" + (cam == null ? -1 : cam.getMode()));
            while (sRun) {
                int n = rec.read(pcm, 0, sFrame);
                if (n <= 0) {
                    continue;
                }
                int m = Math.min(n, sFrame);
                long rawSq = 0;
                for (int i = 0; i < m; i++) {
                    int a = pcm[i] < 0 ? -pcm[i] : pcm[i];
                    rawSq += (long) a * a;
                }
                boolean speech = m > 0 && rawSq / m > ACTIVE_MEAN_SQ;
                long frameSq = rawSq;
                for (int i = 0; i < m; i++) {
                    int a = pcm[i] < 0 ? -pcm[i] : pcm[i];
                    if (a > ulPeak) {
                        ulPeak = a;
                    }
                    if (amr == null) {
                        ulaw[i] = linearToUlaw(pcm[i]);
                    }
                }
                ulSumSq += frameSq;
                ulSamples += m;
                if (speech) {
                    ulActSq += frameSq;
                    ulActSamples += m;
                }
                if (!ulLogged && ulSamples >= sRate * 5L) {
                    JoanTrace.note("media ul level " + level(ulSumSq,
                            ulSamples, ulPeak, ulActSq, ulActSamples)
                            + " platform_agc=" + sPlatformAgc);
                    ulLogged = true;
                    /* A capture that opened and is delivering silence.
                     * Swap the source once and say so, rather than
                     * spending the call talking to nobody: the caller
                     * cannot hear that their own uplink is dead, and the
                     * three calls that proved this were each reported as
                     * a network problem.
                     *
                     * Deliberately one attempt and deliberately loud. If
                     * MIC is silent too then the source is not the fault
                     * and the next trace says so, which is worth more
                     * than a quiet retry that leaves both outcomes
                     * looking identical. */
                    if (!ulSwapped && !micPermitted) {
                        /* No point trying another source: appops
                         * silences every one of them. Name the cause
                         * rather than burning the swap on it. */
                        ulSwapped = true;
                        JoanTrace.note("media ul silent because RECORD_AUDIO "
                                + "is not granted; open joan IMS and allow "
                                + "the microphone");
                    } else if (!ulSwapped && silentUplink(ulSumSq, ulSamples,
                            ulActSamples)) {
                        ulSwapped = true;
                        AudioRecord alt = swapToMic(rec, inBuf);
                        if (alt != null) {
                            rec = alt;
                            ulSumSq = 0;
                            ulSamples = 0;
                            ulActSq = 0;
                            ulActSamples = 0;
                            ulPeak = 0;
                            ulLogged = false;
                        }
                    }
                }
                int dtmfLen = sTePt > 0 ? dtmfFrame(payload, m) : 0;
                if (dtmfLen > 0) {
                    /* The tone replaces this frame's audio: the far end
                     * must not hear the codec's attempt at the same tone
                     * underneath the event. */
                    System.arraycopy(payload, 0, rtp, RTP_HDR, dtmfLen);
                    rtp[0] = (byte) 0x80;
                    rtp[1] = (byte) ((sDtmfMark ? 0x80 : 0) | (sTePt & 0x7f));
                    sDtmfMark = false;
                    rtp[2] = (byte) (sSeq >> 8);
                    rtp[3] = (byte) sSeq;
                    sSeq = (sSeq + 1) & 0xffff;
                    put32(rtp, 4, sDtmfTs);
                    sTs += m;
                    put32(rtp, 8, sSsrc);
                    out.setLength(RTP_HDR + dtmfLen);
                    sSent++;
                    sOctets += dtmfLen;
                    sock.send(out);
                    continue;
                }
                int paylen;
                if (amr != null) {
                    int want = sPendingMode;
                    if (want >= 0 && want != sAppliedMode) {
                        int bps = JoanAmr.modeBitrate(want, amr.wideband());
                        if (bps > 0 && amr.retuneEncoder(bps)) {
                            sAppliedMode = want;
                            JoanTrace.note("amr encoder now mode " + want
                                    + " (" + bps + " bps)");
                        }
                        sPendingMode = -1;
                    }
                    int slen = amr.encode(pcm, m, storage);
                    if (slen < 0) {
                        /* One encoder error is a lost frame, not a lost
                         * call. Breaking here killed the uplink silently
                         * for the rest of the call and left "media ul
                         * stopped" as the only clue. */
                        if (++encFails >= MAX_CODEC_FAILS) {
                            JoanTrace.note("media cap: encoder failed "
                                    + encFails + " times; stopping uplink");
                            break;
                        }
                        continue;
                    }
                    encFails = 0;
                    if (slen == 0) {
                        /* Encoder still filling its pipeline. Send nothing
                         * this tick rather than a malformed packet. */
                        continue;
                    }
                    paylen = sAmrOct
                            ? JoanAmr.pack(storage, 0, slen, JoanAmr.CMR_NONE,
                                    amr.wideband(), payload)
                            : JoanAmr.packBe(storage, 0, slen, JoanAmr.CMR_NONE,
                                    amr.wideband(), payload);
                    if (paylen < 0) {
                        continue;
                    }
                    System.arraycopy(payload, 0, rtp, RTP_HDR, paylen);
                } else {
                    System.arraycopy(ulaw, 0, rtp, RTP_HDR, m);
                    paylen = m;
                }
                rtp[0] = (byte) 0x80;
                rtp[1] = (byte) sPt;
                rtp[2] = (byte) (sSeq >> 8);
                rtp[3] = (byte) sSeq;
                sSeq = (sSeq + 1) & 0xffff;
                put32(rtp, 4, sTs);
                sTs += m;
                put32(rtp, 8, sSsrc);
                out.setLength(RTP_HDR + paylen);
                sSent++;
                sOctets += paylen;
                if (System.currentTimeMillis() >= sRtcpNext) {
                    sendRtcp(sock, dest, dport);
                }
                sock.send(out);
            }
        } catch (Throwable t) {
            /* A closed socket during stop() is the normal way these threads
             * end. Stack-tracing it twice per call buries real failures. */
            if (sRun) {
                JoanTrace.note("media cap fail " + t.getClass().getSimpleName());
                Log.w(TAG, "media cap", t);
            }
        } finally {
            try { if (rec != null) rec.release(); } catch (Throwable ignored) {}
            JoanTrace.note("media ul stopped " + level(ulSumSq, ulSamples,
                    ulPeak, ulActSq, ulActSamples));
        }
    }

    private static void playback(Context app) {
        AudioTrack trk = null;
        /* Downlink accounting, shared by every write path below and read
         * by the stop summary in the finally block. */
        final Dl d = new Dl();
        /* PCMU pacing state. The pace clock anchors when the first real
         * frame is written; from then the track is owed one frame per
         * frameMs of wall clock -- real when the buffer has one,
         * concealed when it does not -- so a lost frame is heard as a
         * decaying voice rather than a tear, and the stream does not
         * slip. AMR is deliberately excluded (see the drain below). */
        boolean anchored = false;
        long anchorMs = 0;
        long loopStartMs = System.currentTimeMillis();
        long firstRxMs = -1;
        audioPriority("play");
        try {
            AudioManager am = app.getSystemService(AudioManager.class);
            int minOut = AudioTrack.getMinBufferSize(sRate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int outBuf = Math.max(minOut, sFrame * 16);
            trk = openVoiceTrack(outBuf);
            if (trk == null) {
                JoanTrace.note("media no play track");
                return;
            }
            trk.play();
            DatagramSocket sock = sSock;
            if (sock == null) {
                return;
            }
            byte[] down = new byte[512];
            JoanAmrCodec amr = sAmr;
            short[] pcm = new short[sFrame];
            byte[] storage = new byte[512];
            int decFails = 0;
            boolean concealWarned = false;
            int lastCmr = JoanAmr.CMR_NONE;
            DatagramPacket in = new DatagramPacket(down, down.length);
            final int frameMs = Math.max(1, sFrame * 1000 / sRate);
            int written = 0;
            int concealRun = 0;
            short[] lastReal = new short[sFrame];
            boolean haveLast = false;
            int validOffers = 0;
            long lastNoAudioNoteMs = loopStartMs;
            JoanTrace.note("media play rolling voice mode="
                    + (am == null ? -1 : am.getMode()));
            while (sRun) {
                long nowMs = System.currentTimeMillis();
                boolean arrived = true;
                try {
                    sock.receive(in);
                } catch (SocketTimeoutException e) {
                    arrived = false;
                }
                if (arrived) {
                    /* With rtcp-mux the peer's reports share this port.
                     * RFC 5761 4: RTCP types 200-204 sit at 72-76 in the
                     * payload-type octet, which no audio payload type we
                     * negotiate can collide with. */
                    byte[] raw = in.getData();
                    int rawLen = in.getLength();
                    if (sMux && JoanRtcp.isRtcp(raw, rawLen)) {
                        onRtcp(raw, rawLen);
                        continue;
                    }
                    InetAddress from = in.getAddress();
                    if (from != null && from.equals(sDest)) {
                        sRtpFromDest++;
                    } else {
                        sRtpFromOther++;
                    }
                    int m = in.getLength();
                    if (m < RTP_HDR) {
                        continue;
                    }
                    /* RTCP packet types live in the whole second octet
                     * (200..204). RTP's payload type is the low 7 bits of
                     * that octet because bit 7 is the marker, so masking
                     * 0x7f before the comparison folded an SR (200) to 72
                     * and the test could never be true -- every report the
                     * peer sent was decoded as u-law and played. With
                     * a=rtcp-mux, which this UA both offers and answers,
                     * those reports arrive on this very socket. Version
                     * must be 2; anything else is not ours. */
                    if ((down[0] & 0xc0) != 0x80) {
                        continue;
                    }
                    int type = down[1] & 0xff;
                    if (type >= 200 && type <= 204) {
                        continue; /* RTCP, not audio */
                    }
                    /* Payload type, without the marker bit. Until alpha25
                     * we never offered telephone-event, so nothing but
                     * audio arrived and this was never checked. We now
                     * negotiate it in both directions, which means a
                     * far-end keypress or an IVR sends four-byte RFC 4733
                     * events to this socket -- and handing those to the
                     * AMR decoder as speech is audible noise, not a
                     * silent mismatch. */
                    int inPt = down[1] & 0x7f;
                    if (sTePt > 0 && inPt == sTePt) {
                        int ev = JoanDtmf.eventOf(down, RTP_HDR, m - RTP_HDR);
                        if (ev >= 0 && !sDtmfRxSeen) {
                            /* Once per call: an event repeats every packet
                             * for the length of the tone, and a line per
                             * packet would bury the call in the trace. */
                            sDtmfRxSeen = true;
                            JoanTrace.note("dtmf inbound event=" + ev
                                    + " pt=" + inPt
                                    + " (not decoded as audio)");
                        }
                        continue;
                    }
                    if (inPt != sPt) {
                        /* Some other payload type we did not agree to
                         * carry. Dropping it is right; decoding it is how
                         * a codec mismatch becomes a burst of noise. */
                        sRtpWrongPt++;
                        continue;
                    }
                    /* Account for it before decoding. The statistics are
                     * owed to the far end whether or not we go on to play
                     * it, and they are what fills the RTCP report block. */
                    int inSeq = ((down[2] & 0xff) << 8) | (down[3] & 0xff);
                    long inTs = get32(down, 4) & 0xffffffffL;
                    if (sPeerSsrc == 0) {
                        sPeerSsrc = get32(down, 8);
                    }
                    if (sRx.onSsrc(get32(down, 8))) {
                        JoanTrace.note("media dl: peer SSRC changed; buffer reset");
                    }
                    int off = RTP_HDR;
                    m -= RTP_HDR;
                    if (m <= 0) {
                        continue;
                    }
                    /* Into the buffer, not straight to the decoder. What
                     * comes back out is in sequence order and has been
                     * held long enough to absorb the arrival jitter this
                     * link actually has -- measured at about 14 ms, most
                     * of a packet interval, which straight-through
                     * playback passes directly to the speaker. */
                    byte[] held = new byte[m];
                    System.arraycopy(down, off, held, 0, m);
                    boolean isSid = amr != null
                            && JoanAmr.isSid(held, 0, m, sAmrOct, amr.wideband());
                    sRx.offer(inSeq, inTs,
                            (System.nanoTime() / 1000000L)
                                    * Math.max(1, sRate / 1000),
                            held, isSid, nowMs);
                    validOffers++;
                    if (firstRxMs < 0) {
                        firstRxMs = nowMs - loopStartMs;
                    }
                }
                if (amr != null) {
                    if (!arrived) {
                        /* DTX: an AMR sender transmits nothing while the
                         * line is silent, so a timeout is not loss. Polling
                         * here would also advance the buffer past frames
                         * the sender has yet to send. */
                        continue;
                    }
                    byte[] play = sRx.poll(nowMs);
                    if (play == null) {
                        if (!sRx.lastWasGap()) {
                            /* Still filling: the call has not started, and
                             * concealing here would invent audio before the
                             * first real frame. */
                            continue;
                        }
                        /* A frame is missing. Tell the decoder so, rather
                         * than writing nothing and letting the track
                         * underrun -- an AMR decoder runs the concealment
                         * 3GPP specifies for it, which is a better
                         * reconstruction than this application could make.
                         * AOSP does the same: onDataFrame(nullptr, 0,
                         * NO_DATA) and let the codec decide. */
                        sConcealed++;
                        int ll = JoanAmr.lostFrame(storage);
                        int cm = ll < 0 ? -1 : amr.decode(storage, ll, pcm);
                        if (cm <= 0) {
                            if (!concealWarned) {
                                concealWarned = true;
                                JoanTrace.note("media dl: decoder will not"
                                        + " conceal a lost frame; gaps stay"
                                        + " silent");
                            }
                            continue;
                        }
                        trk.write(pcm, 0, cm);
                        continue;
                    }
                    int off = RTP_HDR;
                    System.arraycopy(play, 0, down, off, play.length);
                    int m = play.length;
                    /* The peer can ask us to change mode in every packet.
                     * We advertise mode-change-capability and then ignore
                     * it, and MediaCodec offers no runtime bitrate key for
                     * an audio encoder -- honouring a request would mean
                     * reopening the codec mid-call. Before building that,
                     * find out whether any network actually asks: a CMR
                     * that never moves off 15 (no request) makes the whole
                     * question moot. Logged on change only. */
                    int cmr = JoanAmr.requestedMode(down, off, m);
                    if (cmr != lastCmr) {
                        JoanTrace.note("amr peer CMR " + lastCmr + " -> " + cmr
                                + (cmr == JoanAmr.CMR_NONE ? " (no request)" : ""));
                        lastCmr = cmr;
                        if (cmr != JoanAmr.CMR_NONE) {
                            requestMode(cmr, "CMR");
                        }
                    }
                    int slen = sAmrOct
                            ? JoanAmr.unpack(down, off, m, amr.wideband(), storage)
                            : JoanAmr.unpackBe(down, off, m, amr.wideband(), storage);
                    if (slen < 0) {
                        continue;
                    }
                    m = amr.decode(storage, slen, pcm);
                    if (m < 0) {
                        /* Same rule as the uplink: a bad frame is a bad
                         * frame, not the end of the downlink. */
                        if (++decFails >= MAX_CODEC_FAILS) {
                            JoanTrace.note("media play: decoder failed "
                                    + decFails + " times; stopping downlink");
                            break;
                        }
                        continue;
                    }
                    decFails = 0;
                    if (m == 0) {
                        continue;
                    }
                    long dFrameSq = 0;
                    for (int i = 0; i < m; i++) {
                        short v = pcm[i];
                        int a = v < 0 ? -v : v;
                        dFrameSq += (long) a * a;
                        if (a > d.peak) {
                            d.peak = a;
                        }
                    }
                    d.sumSq += dFrameSq;
                    d.samples += m;
                    if (dFrameSq / m > ACTIVE_MEAN_SQ) {
                        d.actSq += dFrameSq;
                        d.actSamples += m;
                    }
                    int wr = trk.write(pcm, 0, m);
                    sRecv++;
                    d.frames++;
                    if (d.frames == 1) {
                        /* Downlink has started. Nothing else is traced
                         * from this loop: JoanTrace.note() opens, writes
                         * and closes a FileWriter under a process-global
                         * lock, and this loop runs every 20 ms. The
                         * blackout clock anchors here for every codec:
                         * anchored was PCMU-only at first, and every AMR
                         * call then reported its whole duration as a
                         * blackout in the stop summary. */
                        anchored = true;
                        anchorMs = nowMs;
                        JoanTrace.note("media dl first frame write=" + wr
                                + " mode=" + (am == null ? -1 : am.getMode())
                                + " spk=" + (am != null && am.isSpeakerphoneOn()));
                    }
                    if (!d.logged && d.samples >= sRate * 5L) {
                        JoanTrace.note("media dl level " + level(d.sumSq,
                                d.samples, d.peak, d.actSq, d.actSamples));
                        d.logged = true;
                    }
                    continue;
                }
                if (!anchored) {
                    if (!arrived) {
                        /* Blackout visibility: until this, a call answered
                         * into minutes of silence was indistinguishable in
                         * the trace from one that never rang. */
                        long silent = nowMs - loopStartMs;
                        if (silent > 2000
                                && nowMs - lastNoAudioNoteMs >= 2000) {
                            lastNoAudioNoteMs = nowMs;
                            JoanTrace.note("media dl: no audio " + silent
                                    + "ms after answer (rx=" + validOffers
                                    + " wrong_pt=" + sRtpWrongPt
                                    + " other_src=" + sRtpFromOther + ")");
                        }
                        continue;
                    }
                    byte[] play = sRx.poll(nowMs);
                    if (play == null) {
                        /* Still filling: the call has not started, and
                         * concealing here would invent audio before the
                         * first real frame. */
                        continue;
                    }
                    writePcmuFrame(play, pcm, trk, lastReal, d, am);
                    haveLast = true;
                    concealRun = 0;
                    anchored = true;
                    anchorMs = nowMs;
                    written = 1;
                    continue;
                }
                /* Anchored PCMU: the track is owed frames by wall clock,
                 * not by arrivals. Draining here on timeouts as well is
                 * what keeps the queue from backing up into trims during
                 * loss -- measured on a call at 42% loss where 167 arrived
                 * frames were trimmed while the speaker starved. */
                int owed = JoanPcmu.framesOwed(anchorMs, frameMs, written,
                        nowMs);
                if (owed < 0) {
                    /* The stall is history, not jitter; replaying it as
                     * frames of silence only delays the audio still
                     * coming. Skip it and keep the pace from now. */
                    anchorMs = nowMs - (long) written * frameMs;
                    owed = 0;
                }
                while (owed-- > 0) {
                    byte[] play = sRx.poll(nowMs);
                    if (play != null) {
                        writePcmuFrame(play, pcm, trk, lastReal, d, am);
                        haveLast = true;
                        concealRun = 0;
                    } else {
                        JoanPcmu.conceal(pcm, haveLast ? lastReal : null,
                                concealRun++);
                        sConcealed++;
                        trk.write(pcm, 0, pcm.length);
                    }
                    written++;
                }
            }
        } catch (Throwable t) {
            if (sRun) {
                JoanTrace.note("media play fail " + t.getClass().getSimpleName());
                Log.w(TAG, "media play", t);
            }
        } finally {
            try { if (trk != null) trk.release(); } catch (Throwable ignored) {}
            long blackoutMs = (anchored ? anchorMs : System.currentTimeMillis())
                    - loopStartMs;
            JoanTrace.note("media dl stopped frames=" + d.frames + " "
                    + level(d.sumSq, d.samples, d.peak, d.actSq, d.actSamples)
                    + " first_rx=" + (firstRxMs >= 0 ? firstRxMs + "ms" : "none")
                    + (blackoutMs > 500 ? " blackout_ms=" + blackoutMs : "")
                    + " " + rtpSourceSummary(sRtpFromDest, sRtpFromOther)
                    + " " + rtcpSummary()
                    + " rx{" + sRx.summary() + "}"
                    + (sConcealed > 0 ? " concealed=" + sConcealed : "")
                    + (sRtpWrongPt > 0 ? " wrong_pt=" + sRtpWrongPt : "")
                    + (sDtmfRxSeen ? " dtmf_rx=yes" : ""));
        }
    }

    private static AudioRecord openRecord(int inBuf) {
        return openRecord(inBuf, new int[] {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.MIC,
        });
    }

    /**
     * Open a capture on the first source that initialises.
     *
     * <p>Initialising is not the same as working, which is the whole
     * reason the caller can now ask for a specific source. On a
     * Digi.Mobil RO handset VOICE_COMMUNICATION opened cleanly --
     * {@code media record ok src=7} -- and then delivered silence for
     * three consecutive calls, so the far end heard nothing while the
     * downlink played at -19 dBFS with no loss. A source that reports
     * STATE_INITIALIZED and returns near-zero samples is a dead
     * instrument that looks alive, and the old fallback could never
     * reach it because it only ran when the open itself failed.
     */
    private static AudioRecord openRecord(int inBuf, int[] sources) {
        for (int src : sources) {
            AudioRecord rec = null;
            try {
                rec = new AudioRecord.Builder()
                        .setAudioSource(src)
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(sRate)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build())
                        .setBufferSizeInBytes(inBuf)
                        .build();
                if (rec.getState() == AudioRecord.STATE_INITIALIZED) {
                    attachEffects(rec.getAudioSessionId());
                    JoanTrace.note("media record ok src=" + src
                            + " platform_agc=" + sPlatformAgc);
                    return rec;
                }
                JoanTrace.note("media record uninit src=" + src);
            } catch (Throwable t) {
                JoanTrace.note("media record src=" + src + " "
                        + t.getClass().getSimpleName());
            }
            if (rec != null) {
                try { rec.release(); } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /**
     * Use the platform's own preprocessing where a ROM offers it, and only
     * fall back to the software AGC above when it does not. On stock
     * LineageOS 22 for joan, AutomaticGainControl.isAvailable() is false:
     * libaudiopreprocessing.so is on the device but nothing in
     * audio_effects.xml points at it.
     */
    private static void attachEffects(int sessionId) {
        sPlatformAgc = false;
        try {
            if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
                android.media.audiofx.AutomaticGainControl agc =
                        android.media.audiofx.AutomaticGainControl.create(sessionId);
                if (agc != null) {
                    agc.setEnabled(true);
                    sPlatformAgc = true;
                }
            }
        } catch (Throwable t) {
            JoanTrace.note("media agc " + t.getClass().getSimpleName());
        }
        try {
            if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                android.media.audiofx.AcousticEchoCanceler aec =
                        android.media.audiofx.AcousticEchoCanceler.create(sessionId);
                if (aec != null) {
                    aec.setEnabled(true);
                }
            }
        } catch (Throwable ignored) {
            // platform default stands
        }
    }

    /** Downlink accounting shared by the PCMU write paths in playback(). */
    private static final class Dl {
        int frames;
        long sumSq;
        long samples;
        long actSq;
        long actSamples;
        int peak;
        boolean logged;
    }

    /**
     * Decode, measure and write one real PCMU frame, keeping the last
     * real frame for concealment. The once-per-call notes live here so
     * every write path reports them identically.
     */
    private static void writePcmuFrame(byte[] play, short[] pcm,
            AudioTrack trk, short[] lastReal, Dl d, AudioManager am) {
        int m = play.length;
        if (m > sFrame) {
            m = sFrame;
        }
        if (m <= 0) {
            return;
        }
        long frameSq = 0;
        for (int i = 0; i < m; i++) {
            short v = ulawToLinear(play[i]);
            int a = v < 0 ? -v : v;
            frameSq += (long) a * a;
            if (a > d.peak) {
                d.peak = a;
            }
            pcm[i] = v;
            lastReal[i] = v;
        }
        d.sumSq += frameSq;
        d.samples += m;
        if (frameSq / m > ACTIVE_MEAN_SQ) {
            d.actSq += frameSq;
            d.actSamples += m;
        }
        int wr = trk.write(pcm, 0, m);
        sRecv++;
        d.frames++;
        if (d.frames == 1) {
            JoanTrace.note("media dl first frame write=" + wr
                    + " mode=" + (am == null ? -1 : am.getMode())
                    + " spk=" + (am != null && am.isSpeakerphoneOn()));
        }
        if (!d.logged && d.samples >= sRate * 5L) {
            JoanTrace.note("media dl level " + level(d.sumSq, d.samples,
                    d.peak, d.actSq, d.actSamples));
            d.logged = true;
        }
    }

    private static AudioTrack openVoiceTrack(int outBuf) {
        try {
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(voiceAttrs())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(sRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build())
                    .setBufferSizeInBytes(outBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            if (t.getState() == AudioTrack.STATE_INITIALIZED) {
                return t;
            }
            t.release();
            JoanTrace.note("media voice track uninit");
        } catch (Throwable t) {
            JoanTrace.note("media voice " + t.getClass().getSimpleName());
        }
        return null;
    }

    /**
     * Signal level of one direction of the call, as RMS and peak dBFS.
     *
     * There is no gain stage anywhere between AudioRecord and the u-law
     * encoder, so the uplink level the far end hears is exactly the level
     * the microphone delivered. When someone reports "they said I sounded
     * quiet" this is the number that says whether the capture is low or
     * the problem is downstream of us.
     */
    /**
     * A frame whose mean square exceeds this counts as speech rather than
     * silence. 10000 is about -50 dBFS, comfortably above the noise floor
     * of an idle handset and well below any real talking.
     */
    private static final long ACTIVE_MEAN_SQ = 10000L;

    /* Gain control belongs in the platform audio path, not here. No other
     * IMS implementation does it in the application: the ADSP voice
     * topology conditions the uplink from ACDB calibration, and for the
     * AP VoIP path the platform's own AGC effect does it. This class
     * carried a software AGC for a while because joan's audio_effects.xml
     * declares only Qualcomm's aec and ns. It is gone -- it ran 10-13 dB
     * hotter than the platform's and sounded worse, and its limiter was
     * squaring off peaks the platform had already levelled.
     *
     * A device tree enables the platform AGC instead -- the zip cannot,
     * and until 2026-09-16 nothing did, which is why this said
     * platform_agc=false on every call. Whether enabling it actually
     * raises the level is unproven: see upstream/README.md.
     * joan ships libaudiopreprocessing.so under /vendor/lib/soundfx but
     * audio_effects.xml never declared it, so the effect the comment
     * relied on did not exist. scripts/merge-agc-effect.sh adds it.
     *
     * Still reported in the trace, so a false value makes it obvious the
     * audio_effects.xml override is missing or was wiped by a ROM update
     * and the uplink is whatever the microphone happened to give us. */
    private static volatile boolean sPlatformAgc;

    private static String level(long sumSq, long samples, int peak,
                                long actSq, long actSamples) {
        if (samples <= 0) {
            return "no samples";
        }
        double rms = Math.sqrt((double) sumSq / (double) samples);
        double rmsDb = rms > 0 ? 20.0 * Math.log10(rms / 32768.0) : -99.0;
        double peakDb = peak > 0 ? 20.0 * Math.log10(peak / 32768.0) : -99.0;
        /* Whole-call RMS is dominated by however long nobody was talking,
         * so it cannot be compared between the two directions. The level
         * over speech-active frames can be. */
        String speech;
        if (actSamples > 0) {
            double a = Math.sqrt((double) actSq / (double) actSamples);
            speech = String.format(java.util.Locale.US,
                    " speech=%.1fdBFS active=%d%%",
                    a > 0 ? 20.0 * Math.log10(a / 32768.0) : -99.0,
                    (int) (100L * actSamples / samples));
        } else {
            speech = " speech=silent";
        }
        return String.format(java.util.Locale.US,
                "rms=%.1fdBFS peak=%.1fdBFS n=%d", rmsDb, peakDb, samples)
                + speech;
    }

    private static int get32(byte[] b, int off) {
        return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16)
                | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    private static void put32(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    private static void sendRtcp(DatagramSocket sock, InetAddress dest, int rtpPort) {
        try {
            /* RFC 3550 6.4.1: an SR carries one reception report block
             * per source we are receiving from. We receive from exactly
             * one and sent RC=0, so every report we have ever sent told
             * the far end our transmit counters and nothing at all about
             * how it sounds to us. A block is 24 bytes and is included as
             * soon as we have heard a packet to report on. */
            int peer = sPeerSsrc;
            boolean withReport = peer != 0 && sRx.received() > 0;
            int srWords = withReport ? 12 : 6;   /* (bytes / 4) - 1 */
            int total = withReport ? 72 : 48;
            byte[] pkt = new byte[total];
            pkt[0] = (byte) (withReport ? 0x81 : 0x80);  /* version + RC */
            pkt[1] = (byte) 200; /* SR */
            pkt[3] = (byte) srWords;
            put32(pkt, 4, sSsrc);
            long now = System.currentTimeMillis();
            int ntpSec = (int) (now / 1000 + 2208988800L);
            put32(pkt, 8, ntpSec);
            put32(pkt, 16, sTs);
            put32(pkt, 20, sSent);
            put32(pkt, 24, sOctets);
            int at = 28;
            if (withReport) {
                put32(pkt, at, peer);
                int frac = sRx.fractionLostAndReset();
                int cum = sRx.cumulativeLost();
                pkt[at + 4] = (byte) frac;
                /* Cumulative lost is a signed 24-bit field: duplicates can
                 * make it negative, and truncating instead of masking
                 * would report a large positive loss on a healthy call. */
                pkt[at + 5] = (byte) (cum >> 16);
                pkt[at + 6] = (byte) (cum >> 8);
                pkt[at + 7] = (byte) cum;
                put32(pkt, at + 8, (int) sRx.extendedMaxSeq());
                put32(pkt, at + 12, sRx.jitter());
                put32(pkt, at + 16, (int) sLastSr);
                /* DLSR is in units of 1/65536 s. Zero when no SR has been
                 * heard, which is what RFC 3550 asks for and is true
                 * until the peer sends one. */
                int dlsr = sLastSrAtMs == 0 ? 0
                        : (int) (((now - sLastSrAtMs) * 65536L) / 1000L);
                put32(pkt, at + 20, dlsr);
                at += 24;
            }
            pkt[at] = (byte) 0x81;
            pkt[at + 1] = (byte) 202; /* SDES */
            pkt[at + 3] = 4;
            put32(pkt, at + 4, sSsrc);
            pkt[at + 8] = 1;
            pkt[at + 9] = 8;
            byte[] cname = "joan.ims".getBytes("US-ASCII");
            System.arraycopy(cname, 0, pkt, at + 10, 8);
            /* RFC 5761: with rtcp-mux, RTCP shares the RTP port.
             * Otherwise it belongs on the peer's a=rtcp: port, which
             * parseSdp parses and which we now honour instead of assuming
             * RTP+1. */
            int port = sMux ? rtpPort : sRtcpPort;
            sock.send(new DatagramPacket(pkt, pkt.length, dest, port));
            if (!sMux && port != rtpPort) {
                /* And also on the RTP 5-tuple. This is load-bearing, not
                 * a leftover probe: e7783f8 added it because this core
                 * answers mux=0 with no a=rtcp:, and sending the SR only
                 * to RTP+1 froze the downlink at ~16s and lost the call at
                 * ~32s. The SBC keeps the media path bound to the RTP
                 * 5-tuple. Removing it reproduced exactly that failure --
                 * an answered inbound call with frames=0 downlink -- so it
                 * stays until something proves the SBC no longer needs it.
                 * The cost is a non-audio packet in the peer's RTP stream
                 * every five seconds, which their jitter buffer discards. */
                sock.send(new DatagramPacket(pkt, pkt.length, dest, rtpPort));
            }
            sRtcpNext = now + 5000;
        } catch (Exception ignored) {
            sRtcpNext = System.currentTimeMillis() + 5000;
        }
    }

    private static byte linearToUlaw(short pcm) {
        final int BIAS = 0x84;
        final int CLIP = 32635;
        int sign = (pcm >> 8) & 0x80;
        int x = pcm;
        if (sign != 0) {
            x = -x;
        }
        if (x > CLIP) {
            x = CLIP;
        }
        x += BIAS;
        int exp = 7;
        for (int mask = 0x4000; (x & mask) == 0 && exp > 0; mask >>= 1) {
            exp--;
        }
        int mantissa = (x >> (exp + 3)) & 0x0F;
        return (byte) ~(sign | (exp << 4) | mantissa);
    }

    private static short ulawToLinear(byte ulaw) {
        int u = (~ulaw) & 0xFF;
        int sign = u & 0x80;
        int exp = (u >> 4) & 0x07;
        int mantissa = u & 0x0F;
        int sample = ((mantissa << 3) + 0x84) << exp;
        sample -= 0x84;
        return (short) (sign != 0 ? -sample : sample);
    }
}
