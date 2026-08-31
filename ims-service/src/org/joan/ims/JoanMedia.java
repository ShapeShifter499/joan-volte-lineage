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
    private static DatagramSocket sSock;

    private static final int RTP_HDR = 12;
    private static volatile InetAddress sDest;
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

    private JoanMedia() {}

    static void startRtp(Context ctx, Network net, InetAddress local,
                         InetAddress dest, int destPort, int rtcpPort,
                         boolean mux) {
        startRtp(ctx, net, local, dest, destPort, rtcpPort, mux, 0, null);
    }

    /**
     * @param payloadType the RTP payload type the answer selected
     * @param amrWideband TRUE for AMR-WB, FALSE for AMR-NB, null for PCMU.
     *        When AMR is asked for and the codec will not open, the call
     *        falls back to PCMU rather than running with no media -- the
     *        codec is negotiated, so a silent failure here is a silent
     *        call.
     */
    static void startRtp(Context ctx, Network net, InetAddress local,
                         InetAddress dest, int destPort, int rtcpPort,
                         boolean mux, int payloadType, Boolean amrWideband) {
        stop();
        sPt = payloadType;
        sAmr = null;
        sRate = PCMU_HZ;
        sFrame = PCMU_SAMPLES;
        if (amrWideband != null) {
            JoanAmrCodec c = JoanAmrCodec.open(amrWideband);
            if (c != null) {
                sAmr = c;
                sRate = c.sampleRate();
                sFrame = c.samplesPerFrame();
            } else {
                JoanTrace.note("amr requested but unavailable; PCMU");
                sPt = 0;
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
            /* Only needs to be short enough to notice sRun going false,
             * and stop() closes the socket which unblocks receive anyway.
             * 40 ms was 25 pointless wakeups a second on the audio thread. */
            sSock.setSoTimeout(500);
        } catch (Exception e) {
            JoanTrace.note("media sock " + e.getClass().getSimpleName());
            return;
        }
        sRun = true;
        sCap = new Thread(() -> capture(app), "joan-ims-cap");
        sPlay = new Thread(() -> playback(app), "joan-ims-play");
        sCap.start();
        sPlay.start();
        JoanTrace.note("media start rtp mux=" + mux
                + " rtcp_port=" + (sMux ? sDestPort : sRtcpPort)
                + " codec=" + (sAmr == null ? "PCMU"
                        : (sAmr.wideband() ? "AMR-WB" : "AMR-NB"))
                + " pt=" + sPt + " rate=" + sRate);
    }

    static void stop() {
        sRun = false;
        Thread[] ts = { sCap, sPlay };
        sCap = null;
        sPlay = null;
        DatagramSocket sock = sSock;
        sSock = null;
        if (sock != null) {
            sock.close();
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

    private static AudioAttributes voiceAttrs() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setLegacyStreamType(AudioManager.STREAM_VOICE_CALL)
                .build();
    }

    private static void capture(Context app) {
        AudioRecord rec = null;
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
            rec = openRecord(Math.max(minIn, sFrame * 8));
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
            byte[] storage = new byte[80];
            byte[] payload = new byte[96];
            byte[] rtp = new byte[RTP_HDR + Math.max(sFrame, 96)];
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
                }
                int paylen;
                if (amr != null) {
                    int slen = amr.encode(pcm, m, storage);
                    if (slen < 0) {
                        break;
                    }
                    if (slen == 0) {
                        /* Encoder still filling its pipeline. Send nothing
                         * this tick rather than a malformed packet. */
                        continue;
                    }
                    paylen = JoanAmr.pack(storage, 0, slen, JoanAmr.CMR_NONE,
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
        int dl = 0;
        long dlSumSq = 0;
        long dlSamples = 0;
        long dlActSq = 0;
        long dlActSamples = 0;
        int dlPeak = 0;
        boolean dlLogged = false;
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
            byte[] storage = new byte[80];
            DatagramPacket in = new DatagramPacket(down, down.length);
            JoanTrace.note("media play rolling voice mode="
                    + (am == null ? -1 : am.getMode()));
            while (sRun) {
                try {
                    sock.receive(in);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                int m = in.getLength();
                if (m < RTP_HDR) {
                    continue;
                }
                /* RTCP packet types live in the whole second octet
                 * (200..204). RTP's payload type is the low 7 bits of that
                 * octet because bit 7 is the marker, so masking 0x7f before
                 * the comparison folded an SR (200) to 72 and the test
                 * could never be true -- every report the peer sent was
                 * decoded as u-law and played. With a=rtcp-mux, which this
                 * UA both offers and answers, those reports arrive on this
                 * very socket. Version must be 2; anything else is not
                 * ours. */
                if ((down[0] & 0xc0) != 0x80) {
                    continue;
                }
                int type = down[1] & 0xff;
                if (type >= 200 && type <= 204) {
                    continue; /* RTCP, not audio */
                }
                int off = RTP_HDR;
                m -= RTP_HDR;
                if (m <= 0) {
                    continue;
                }
                if (amr != null) {
                    int slen = JoanAmr.unpack(down, off, m, amr.wideband(),
                            storage);
                    if (slen < 0) {
                        continue;
                    }
                    m = amr.decode(storage, slen, pcm);
                    if (m < 0) {
                        break;
                    }
                    if (m == 0) {
                        continue;
                    }
                } else {
                    if (m > sFrame) {
                        m = sFrame;
                    }
                }
                long dFrameSq = 0;
                for (int i = 0; i < m; i++) {
                    short v = (amr != null) ? pcm[i]
                            : ulawToLinear(down[off + i]);
                    int a = v < 0 ? -v : v;
                    dFrameSq += (long) a * a;
                    if (a > dlPeak) {
                        dlPeak = a;
                    }
                    pcm[i] = v;
                }
                dlSumSq += dFrameSq;
                dlSamples += m;
                if (m > 0 && dFrameSq / m > ACTIVE_MEAN_SQ) {
                    dlActSq += dFrameSq;
                    dlActSamples += m;
                }
                if (!dlLogged && dlSamples >= sRate * 5L) {
                    JoanTrace.note("media dl level " + level(dlSumSq,
                            dlSamples, dlPeak, dlActSq, dlActSamples));
                    dlLogged = true;
                }
                int wr = trk.write(pcm, 0, m);
                sRecv++;
                dl++;
                if (dl == 1) {
                    /* Downlink has started. Nothing else is traced from
                     * this loop: JoanTrace.note() opens, writes and closes
                     * a FileWriter under a process-global lock, and this
                     * loop runs every 20 ms. */
                    JoanTrace.note("media dl first frame write=" + wr
                            + " mode=" + (am == null ? -1 : am.getMode())
                            + " spk=" + (am != null && am.isSpeakerphoneOn()));
                }
            }
        } catch (Throwable t) {
            if (sRun) {
                JoanTrace.note("media play fail " + t.getClass().getSimpleName());
                Log.w(TAG, "media play", t);
            }
        } finally {
            try { if (trk != null) trk.release(); } catch (Throwable ignored) {}
            JoanTrace.note("media dl stopped frames=" + dl + " "
                    + level(dlSumSq, dlSamples, dlPeak, dlActSq, dlActSamples));
        }
    }

    private static AudioRecord openRecord(int inBuf) {
        int[] sources = {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.MIC,
        };
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
     * squaring off peaks the platform had already levelled. The zip
     * enables the platform AGC instead.
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

    private static void put32(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    private static void sendRtcp(DatagramSocket sock, InetAddress dest, int rtpPort) {
        try {
            byte[] pkt = new byte[48];
            pkt[0] = (byte) 0x80;
            pkt[1] = (byte) 200; /* SR */
            pkt[3] = 6; /* 28 bytes / 4 - 1 */
            put32(pkt, 4, sSsrc);
            long now = System.currentTimeMillis();
            int ntpSec = (int) (now / 1000 + 2208988800L);
            put32(pkt, 8, ntpSec);
            put32(pkt, 16, sTs);
            put32(pkt, 20, sSent);
            put32(pkt, 24, sOctets);
            pkt[28] = (byte) 0x81;
            pkt[29] = (byte) 202; /* SDES */
            pkt[31] = 4;
            put32(pkt, 32, sSsrc);
            pkt[36] = 1;
            pkt[37] = 8;
            byte[] cname = "joan.ims".getBytes("US-ASCII");
            System.arraycopy(cname, 0, pkt, 38, 8);
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
