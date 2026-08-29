package org.joan.ims;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;

import android.net.Network;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;

/**
 * 8 kHz PCMU between AudioFlinger and the native RTP UA.
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
    private static final int SAMPLE_HZ = 8000;
    private static final int PTIME_SAMPLES = 160;
    private static final int NATIVE_PORT = 15091;

    private static volatile boolean sRun;
    private static Thread sCap;
    private static Thread sPlay;
    private static DatagramSocket sSock;

    private static final int RTP_HDR = 12;
    private static volatile boolean sRtp;
    private static volatile InetAddress sDest;
    private static volatile int sDestPort;
    private static volatile boolean sMux;
    private static volatile int sSeq;
    private static volatile int sTs;
    private static volatile int sSsrc;
    private static volatile int sSent;
    private static volatile int sRecv;
    private static volatile int sOctets;
    private static volatile long sRtcpNext;

    private JoanMedia() {}

    static void start(Context ctx) {
        startInternal(ctx, null, null, null, 0, false);
    }

    static void startRtp(Context ctx, Network net, InetAddress local,
                         InetAddress dest, int destPort, boolean mux) {
        startInternal(ctx, net, local, dest, destPort, mux);
    }

    private static void startInternal(Context ctx, Network net,
                                      InetAddress local, InetAddress dest,
                                      int destPort, boolean mux) {
        stop();
        Context app = ctx.getApplicationContext();
        sRtp = dest != null;
        sDest = dest;
        sDestPort = destPort;
        sMux = mux;
        sSeq = new SecureRandom().nextInt() & 0xffff;
        sTs = new SecureRandom().nextInt();
        sSsrc = new SecureRandom().nextInt();
        sSent = sRecv = sOctets = 0;
        sRtcpNext = System.currentTimeMillis() + 400;
        try {
            if (sRtp) {
                sSock = new DatagramSocket(null);
                sSock.setReuseAddress(true);
                if (net != null) {
                    net.bindSocket(sSock);
                }
                sSock.bind(new InetSocketAddress(local, JoanSipUa.RTP_PORT));
            } else {
                sSock = new DatagramSocket();
            }
            sSock.setSoTimeout(40);
        } catch (Exception e) {
            JoanTrace.note("media sock " + e.getClass().getSimpleName());
            return;
        }
        sRun = true;
        sCap = new Thread(() -> capture(app), "joan-ims-cap");
        sPlay = new Thread(() -> playback(app), "joan-ims-play");
        sCap.start();
        sPlay.start();
        JoanTrace.note(sRtp ? "media start rtp mux=" + mux : "media start voice");
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
        JoanTrace.note("media stop");
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
        try {
            int minIn = AudioRecord.getMinBufferSize(SAMPLE_HZ,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = openRecord(Math.max(minIn, PTIME_SAMPLES * 8));
            if (rec == null) {
                JoanTrace.note("media no AudioRecord");
                return;
            }
            rec.startRecording();
            DatagramSocket sock = sSock;
            if (sock == null) {
                return;
            }
            InetAddress dest = sRtp ? sDest : InetAddress.getByName("127.0.0.1");
            int dport = sRtp ? sDestPort : NATIVE_PORT;
            short[] pcm = new short[PTIME_SAMPLES];
            byte[] ulaw = new byte[PTIME_SAMPLES];
            byte[] rtp = new byte[RTP_HDR + PTIME_SAMPLES];
            DatagramPacket out = new DatagramPacket(
                    sRtp ? rtp : ulaw, sRtp ? rtp.length : ulaw.length, dest, dport);
            JoanTrace.note("media cap rolling src=" + rec.getAudioSource()
                    + " rtp=" + sRtp);
            while (sRun) {
                int n = rec.read(pcm, 0, PTIME_SAMPLES);
                if (n <= 0) {
                    continue;
                }
                int m = Math.min(n, PTIME_SAMPLES);
                for (int i = 0; i < m; i++) {
                    ulaw[i] = linearToUlaw(pcm[i]);
                }
                if (sRtp) {
                    rtp[0] = (byte) 0x80;
                    rtp[1] = 0; /* PCMU */
                    rtp[2] = (byte) (sSeq >> 8);
                    rtp[3] = (byte) sSeq;
                    sSeq = (sSeq + 1) & 0xffff;
                    put32(rtp, 4, sTs);
                    sTs += m;
                    put32(rtp, 8, sSsrc);
                    System.arraycopy(ulaw, 0, rtp, RTP_HDR, m);
                    out.setData(rtp);
                    out.setLength(RTP_HDR + m);
                    sSent++;
                    sOctets += m;
                    if (System.currentTimeMillis() >= sRtcpNext) {
                        sendRtcp(sock, dest, dport);
                    }
                } else {
                    out.setLength(m);
                }
                sock.send(out);
            }
        } catch (Throwable t) {
            JoanTrace.note("media cap fail " + t.getClass().getSimpleName());
            Log.w(TAG, "media cap", t);
        } finally {
            try { if (rec != null) rec.release(); } catch (Throwable ignored) {}
        }
    }

    private static void playback(Context app) {
        AudioTrack trk = null;
        try {
            AudioManager am = app.getSystemService(AudioManager.class);
            int minOut = AudioTrack.getMinBufferSize(SAMPLE_HZ,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int outBuf = Math.max(minOut, PTIME_SAMPLES * 16);
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
            InetAddress loop = InetAddress.getByName("127.0.0.1");
            if (!sRtp) {
                byte[] prime = new byte[PTIME_SAMPLES];
                sock.send(new DatagramPacket(prime, prime.length, loop, NATIVE_PORT));
            }
            byte[] down = new byte[512];
            short[] pcm = new short[PTIME_SAMPLES];
            DatagramPacket in = new DatagramPacket(down, down.length);
            int dl = 0;
            JoanTrace.note("media play rolling voice mode="
                    + (am == null ? -1 : am.getMode()) + " rtp=" + sRtp);
            while (sRun) {
                try {
                    sock.receive(in);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                int off = 0;
                int m = in.getLength();
                if (sRtp) {
                    if (m < RTP_HDR) {
                        continue;
                    }
                    int pt = down[1] & 0x7f;
                    if (pt == 200 || pt == 201 || pt == 202) {
                        continue; /* RTCP */
                    }
                    off = RTP_HDR;
                    m -= RTP_HDR;
                }
                if (m > PTIME_SAMPLES) {
                    m = PTIME_SAMPLES;
                }
                if (m <= 0) {
                    continue;
                }
                for (int i = 0; i < m; i++) {
                    pcm[i] = ulawToLinear(down[off + i]);
                }
                int wr = trk.write(pcm, 0, m);
                sRecv++;
                dl++;
                if (dl == 1 || (dl % 50) == 0) {
                    JoanTrace.note("media dl frames=" + dl + " write=" + wr
                            + " mode=" + (am == null ? -1 : am.getMode())
                            + " spk=" + (am != null && am.isSpeakerphoneOn()));
                }
            }
        } catch (Throwable t) {
            JoanTrace.note("media play fail " + t.getClass().getSimpleName());
            Log.w(TAG, "media play", t);
        } finally {
            try { if (trk != null) trk.release(); } catch (Throwable ignored) {}
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
                                .setSampleRate(SAMPLE_HZ)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build())
                        .setBufferSizeInBytes(inBuf)
                        .build();
                if (rec.getState() == AudioRecord.STATE_INITIALIZED) {
                    JoanTrace.note("media record ok src=" + src);
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

    private static AudioTrack openVoiceTrack(int outBuf) {
        try {
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(voiceAttrs())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_HZ)
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
            sock.send(new DatagramPacket(pkt, pkt.length, dest, rtpPort));
            if (!sMux) {
                sock.send(new DatagramPacket(pkt, pkt.length, dest, rtpPort + 1));
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
