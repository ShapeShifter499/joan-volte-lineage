package org.joan.ims;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

/**
 * 8 kHz PCMU between AudioFlinger and the native RTP UA.
 *
 * Capture and playback run on separate threads: a single loop that
 * blocked on mic read then UDP receive sent uplink every ~40 ms
 * (choppy on the far end).
 *
 * Telecom MODE_IN_CALL ducks STREAM_VOICE_CALL and
 * USAGE_VOICE_COMMUNICATION, so write() can return 160 with no
 * audible output. STREAM_MUSIC + speakerphone is the diagnostic
 * path that can actually be heard during an IMS call.
 */
final class JoanMedia {
    private static final String TAG = "JoanIms";
    private static final int SAMPLE_HZ = 8000;
    private static final int PTIME_SAMPLES = 160;
    private static final int NATIVE_PORT = 15091;

    private static final int[] SOURCES = {
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
    };

    private static volatile boolean sRun;
    private static Thread sCap;
    private static Thread sPlay;
    private static DatagramSocket sSock;

    private JoanMedia() {}

    static void start(Context ctx) {
        stop();
        Context app = ctx.getApplicationContext();
        try {
            sSock = new DatagramSocket();
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
        JoanTrace.note("media start split");
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
            InetAddress loop = InetAddress.getByName("127.0.0.1");
            short[] pcm = new short[PTIME_SAMPLES];
            byte[] ulaw = new byte[PTIME_SAMPLES];
            DatagramPacket out = new DatagramPacket(ulaw, ulaw.length, loop, NATIVE_PORT);
            JoanTrace.note("media cap rolling src=" + rec.getAudioSource());
            while (sRun) {
                int n = rec.read(pcm, 0, PTIME_SAMPLES);
                if (n <= 0) {
                    continue;
                }
                int m = Math.min(n, PTIME_SAMPLES);
                for (int i = 0; i < m; i++) {
                    ulaw[i] = linearToUlaw(pcm[i]);
                }
                out.setLength(m);
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
            trk = openMusicTrack(outBuf);
            if (trk == null) {
                JoanTrace.note("media no play track");
                return;
            }
            trk.setVolume(1.0f);
            trk.play();
            routeOutput(am, trk, true);
            DatagramSocket sock = sSock;
            if (sock == null) {
                return;
            }
            InetAddress loop = InetAddress.getByName("127.0.0.1");
            byte[] prime = new byte[PTIME_SAMPLES];
            sock.send(new DatagramPacket(prime, prime.length, loop, NATIVE_PORT));
            byte[] down = new byte[512];
            short[] pcm = new short[PTIME_SAMPLES];
            DatagramPacket in = new DatagramPacket(down, down.length);
            int dl = 0;
            boolean lastSpk = am != null && am.isSpeakerphoneOn();
            JoanTrace.note("media play rolling spk=" + lastSpk);
            while (sRun) {
                try {
                    sock.receive(in);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                int m = in.getLength();
                if (m > PTIME_SAMPLES) {
                    m = PTIME_SAMPLES;
                }
                if (m <= 0) {
                    continue;
                }
                for (int i = 0; i < m; i++) {
                    pcm[i] = ulawToLinear(down[i]);
                }
                int wr = trk.write(pcm, 0, m);
                dl++;
                boolean spk = am != null && am.isSpeakerphoneOn();
                if (spk != lastSpk) {
                    lastSpk = spk;
                    routeOutput(am, trk, false);
                    JoanTrace.note("media route spk=" + spk);
                }
                if (dl == 1 || (dl % 50) == 0) {
                    JoanTrace.note("media dl frames=" + dl + " write=" + wr
                            + " mode=" + (am == null ? -1 : am.getMode())
                            + " spk=" + spk);
                }
            }
        } catch (Throwable t) {
            JoanTrace.note("media play fail " + t.getClass().getSimpleName());
            Log.w(TAG, "media play", t);
        } finally {
            try { if (trk != null) trk.release(); } catch (Throwable ignored) {}
        }
    }

    private static void routeOutput(AudioManager am, AudioTrack trk, boolean init) {
        if (am == null || trk == null) {
            return;
        }
        boolean spk = am.isSpeakerphoneOn();
        int want = spk ? AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                       : AudioDeviceInfo.TYPE_BUILTIN_EARPIECE;
        try {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (d.getType() == want) {
                    trk.setPreferredDevice(d);
                    try {
                        am.setCommunicationDevice(d);
                    } catch (Throwable ignored) {}
                    if (init) {
                        JoanTrace.note("media route init spk=" + spk);
                    }
                    return;
                }
            }
        } catch (Throwable t) {
            JoanTrace.note("media route " + t.getClass().getSimpleName());
        }
    }

    private static AudioRecord openRecord(int inBuf) {
        for (int src : SOURCES) {
            AudioRecord rec = null;
            try {
                rec = new AudioRecord(src, SAMPLE_HZ, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, inBuf);
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

    private static AudioTrack openMusicTrack(int outBuf) {
        try {
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
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
        } catch (Throwable t) {
            JoanTrace.note("media music " + t.getClass().getSimpleName());
        }
        try {
            AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_HZ,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    outBuf, AudioTrack.MODE_STREAM);
            if (t.getState() == AudioTrack.STATE_INITIALIZED) {
                return t;
            }
            t.release();
        } catch (Throwable ignored) {}
        return null;
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
