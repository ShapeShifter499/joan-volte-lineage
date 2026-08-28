package org.joan.ims;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * Bridge 8 kHz PCMU between AudioFlinger and the native RTP UA.
 * CAF leaves media in the modem; we are AP SIP, so Dialer ACTIVE still
 * needs this path for earpiece/mic on a working LOS kernel.
 */
final class JoanMedia {
    private static final String TAG = "JoanIms";
    private static final int SAMPLE_HZ = 8000;
    private static final int PTIME_SAMPLES = 160;
    private static final int NATIVE_PORT = 15091;

    private static volatile boolean sRun;
    private static Thread sThread;

    private JoanMedia() {}

    static void start(Context ctx) {
        stop();
        Context app = ctx.getApplicationContext();
        sRun = true;
        sThread = new Thread(() -> loop(app), "joan-ims-media");
        sThread.start();
        JoanTrace.note("media start");
    }

    static void stop() {
        sRun = false;
        Thread t = sThread;
        sThread = null;
        if (t != null) {
            t.interrupt();
        }
        JoanTrace.note("media stop");
    }

    private static void loop(Context app) {
        AudioRecord rec = null;
        AudioTrack trk = null;
        DatagramSocket sock = null;
        try {
            AudioManager am = app.getSystemService(AudioManager.class);
            if (am != null) {
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            }
            int minIn = AudioRecord.getMinBufferSize(SAMPLE_HZ,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int minOut = AudioTrack.getMinBufferSize(SAMPLE_HZ,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_HZ, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minIn, PTIME_SAMPLES * 4));
            trk = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_HZ)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build())
                    .setBufferSizeInBytes(Math.max(minOut, PTIME_SAMPLES * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            rec.startRecording();
            trk.play();
            sock = new DatagramSocket();
            sock.setSoTimeout(20);
            InetAddress loop = InetAddress.getByName("127.0.0.1");
            short[] pcm = new short[PTIME_SAMPLES];
            byte[] ulaw = new byte[PTIME_SAMPLES];
            byte[] down = new byte[512];
            DatagramPacket out = new DatagramPacket(ulaw, ulaw.length, loop, NATIVE_PORT);
            DatagramPacket in = new DatagramPacket(down, down.length);
            while (sRun) {
                int n = rec.read(pcm, 0, PTIME_SAMPLES);
                if (n > 0) {
                    for (int i = 0; i < n && i < PTIME_SAMPLES; i++) {
                        ulaw[i] = linearToUlaw(pcm[i]);
                    }
                    out.setLength(Math.min(n, PTIME_SAMPLES));
                    sock.send(out);
                }
                try {
                    sock.receive(in);
                    int m = in.getLength();
                    if (m > PTIME_SAMPLES) {
                        m = PTIME_SAMPLES;
                    }
                    for (int i = 0; i < m; i++) {
                        pcm[i] = ulawToLinear(down[i]);
                    }
                    trk.write(pcm, 0, m);
                } catch (java.net.SocketTimeoutException ignored) {
                    // no downlink this ptime
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "media loop " + t.getClass().getSimpleName() + ":"
                    + String.valueOf(t.getMessage()));
            JoanTrace.note("media fail " + t.getClass().getSimpleName());
        } finally {
            try { if (rec != null) rec.release(); } catch (Throwable ignored) {}
            try { if (trk != null) trk.release(); } catch (Throwable ignored) {}
            try { if (sock != null) sock.close(); } catch (Throwable ignored) {}
            try {
                AudioManager am = app.getSystemService(AudioManager.class);
                if (am != null) {
                    am.setMode(AudioManager.MODE_NORMAL);
                }
            } catch (Throwable ignored) {}
        }
    }

    /* ITU-T G.711 µ-law, same as native/src/rtp.c */
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
