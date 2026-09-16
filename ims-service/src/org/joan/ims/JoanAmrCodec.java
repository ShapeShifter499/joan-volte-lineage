package org.joan.ims;

import android.media.MediaCodec;
import android.media.MediaFormat;

import java.nio.ByteBuffer;

/**
 * AMR / AMR-WB encode and decode over MediaCodec, framed for a 20 ms
 * real-time loop.
 *
 * MediaCodec speaks the AMR *storage* format -- one header byte then the
 * speech data. JoanAmr converts that to and from the RFC 4867 payload
 * format. This class deals only in storage frames; nothing here knows
 * about RTP.
 *
 * The codecs are software (c2.android.amrwb.*), which is fine: AMR-WB was
 * designed to run on feature phones and the encode cost is negligible
 * here. What is not negligible is that a buffer-queue API now sits on a
 * thread with a 20 ms deadline, so every call uses a short timeout and
 * returns rather than blocking the media loop.
 */
final class JoanAmrCodec {
    static final String MIME_WB = "audio/amr-wb";
    static final String MIME_NB = "audio/amr-nb";

    /** 20 ms at the codec's sample rate. */
    static final int WB_SAMPLES = 320;
    static final int NB_SAMPLES = 160;

    /* AMR-WB mode 8 (23.85 kbit/s). IR.92 leaves the mode to the network;
     * this is the top rate and what most cores request when they care. */
    private static final int WB_BITRATE = 23850;
    private static final int NB_BITRATE = 12200;

    /** Short enough not to hold up a 20 ms frame if the codec is busy. */
    private static final long TIMEOUT_US = 8000;

    private final boolean wideband;
    private final MediaCodec encoder;
    private final MediaCodec decoder;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private long ptsUs;

    private JoanAmrCodec(boolean wideband, MediaCodec enc, MediaCodec dec) {
        this.wideband = wideband;
        this.encoder = enc;
        this.decoder = dec;
    }

    boolean wideband() {
        return wideband;
    }

    int samplesPerFrame() {
        return wideband ? WB_SAMPLES : NB_SAMPLES;
    }

    int sampleRate() {
        return wideband ? 16000 : 8000;
    }

    /** Null if the platform cannot provide the codec pair. */
    /**
     * Encoding names this device can both encode and decode, for
     * {@link JoanSipBuilder#restrictProfile}. Both directions are required:
     * an encoder alone would let us offer a codec we cannot receive.
     */
    static java.util.Set<String> availableAmr() {
        java.util.Set<String> names = new java.util.HashSet<>();
        try {
            android.media.MediaCodecList list = new android.media.MediaCodecList(
                    android.media.MediaCodecList.REGULAR_CODECS);
            boolean wbEnc = false, wbDec = false, nbEnc = false, nbDec = false;
            for (android.media.MediaCodecInfo info : list.getCodecInfos()) {
                for (String type : info.getSupportedTypes()) {
                    if (MIME_WB.equalsIgnoreCase(type)) {
                        if (info.isEncoder()) {
                            wbEnc = true;
                        } else {
                            wbDec = true;
                        }
                    } else if (MIME_NB.equalsIgnoreCase(type)) {
                        if (info.isEncoder()) {
                            nbEnc = true;
                        } else {
                            nbDec = true;
                        }
                    }
                }
            }
            if (wbEnc && wbDec && selfTest(true)) {
                names.add("AMR-WB");
            }
            if (nbEnc && nbDec && selfTest(false)) {
                names.add("AMR");
            }
        } catch (Throwable t) {
            JoanTrace.note("codec probe failed: " + t.getClass().getSimpleName());
        }
        return names;
    }

    /**
     * Open the codec and round-trip one frame before we advertise it.
     *
     * <p>A listing in MediaCodecList is not a promise that the codec works:
     * AOSP can offer whatever carrier config names because an OEM has
     * qualified the device against it, and we have no such guarantee on an
     * arbitrary LineageOS build. Encoding and decoding a frame is the only
     * evidence available in its place, and it costs a few hundred
     * milliseconds once per boot.
     *
     * <p>Deliberately not a silence frame: a codec that returns a fixed
     * empty payload would pass that and fail on speech. This is a tone,
     * and the check is that the encoder produced a non-empty payload and
     * the decoder gave back a full frame of PCM.
     */
    static boolean selfTest(boolean wideband) {
        JoanAmrCodec c = null;
        try {
            c = open(wideband);
            if (c == null) {
                return false;
            }
            int n = c.samplesPerFrame();
            short[] pcm = new short[n];
            for (int i = 0; i < n; i++) {
                pcm[i] = (short) (8000 * Math.sin(2 * Math.PI * 440 * i
                        / (double) c.sampleRate()));
            }
            byte[] packed = new byte[n * 2];
            int len = c.encode(pcm, n, packed);
            if (len <= 0) {
                JoanTrace.note("amr self-test: encoder produced nothing wb="
                        + wideband);
                return false;
            }
            short[] back = new short[n];
            int got = c.decode(packed, len, back);
            if (got != n) {
                JoanTrace.note("amr self-test: decoded " + got + " of " + n
                        + " wb=" + wideband);
                return false;
            }
            return true;
        } catch (Throwable t) {
            JoanTrace.note("amr self-test failed wb=" + wideband + ": "
                    + t.getClass().getSimpleName());
            return false;
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                    // going away regardless
                }
            }
        }
    }

    static JoanAmrCodec open(boolean wideband) {
        String mime = wideband ? MIME_WB : MIME_NB;
        MediaCodec enc = null;
        MediaCodec dec = null;
        try {
            int rate = wideband ? 16000 : 8000;
            MediaFormat fmt = MediaFormat.createAudioFormat(mime, rate, 1);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE,
                    wideband ? WB_BITRATE : NB_BITRATE);

            enc = MediaCodec.createEncoderByType(mime);
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();

            dec = MediaCodec.createDecoderByType(mime);
            dec.configure(MediaFormat.createAudioFormat(mime, rate, 1),
                    null, null, 0);
            dec.start();

            JoanTrace.note("amr codec open " + mime);
            return new JoanAmrCodec(wideband, enc, dec);
        } catch (Throwable t) {
            JoanTrace.note("amr codec open failed "
                    + t.getClass().getSimpleName());
            releaseQuietly(enc);
            releaseQuietly(dec);
            return null;
        }
    }

    /**
     * One PCM frame in, at most one storage frame out.
     *
     * The encoder pipelines, so the first call or two may accept input and
     * return nothing. That is normal and the caller simply sends no packet
     * for that tick.
     *
     * @return storage bytes written to out, 0 if the encoder had nothing
     *         ready, or -1 on failure.
     */
    int encode(short[] pcm, int samples, byte[] out) {
        try {
            int in = encoder.dequeueInputBuffer(TIMEOUT_US);
            if (in >= 0) {
                ByteBuffer b = encoder.getInputBuffer(in);
                b.clear();
                for (int i = 0; i < samples; i++) {
                    b.putShort(pcm[i]);
                }
                encoder.queueInputBuffer(in, 0, samples * 2, ptsUs, 0);
                ptsUs += (samples * 1000000L) / sampleRate();
            }
            int idx = encoder.dequeueOutputBuffer(info, TIMEOUT_US);
            while (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                    || idx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                idx = encoder.dequeueOutputBuffer(info, TIMEOUT_US);
            }
            if (idx < 0) {
                return 0;
            }
            int n = info.size;
            if (n > out.length) {
                encoder.releaseOutputBuffer(idx, false);
                return -1;
            }
            ByteBuffer b = encoder.getOutputBuffer(idx);
            b.position(info.offset);
            b.get(out, 0, n);
            encoder.releaseOutputBuffer(idx, false);
            return n;
        } catch (Throwable t) {
            JoanTrace.note("amr encode " + t.getClass().getSimpleName());
            return -1;
        }
    }

    /**
     * One storage frame in, PCM out.
     *
     * @return samples written to pcm, 0 if the decoder had nothing ready,
     *         or -1 on failure.
     */
    int decode(byte[] storage, int len, short[] pcm) {
        try {
            int in = decoder.dequeueInputBuffer(TIMEOUT_US);
            if (in >= 0) {
                ByteBuffer b = decoder.getInputBuffer(in);
                b.clear();
                b.put(storage, 0, len);
                decoder.queueInputBuffer(in, 0, len, ptsUs, 0);
            }
            int idx = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
            while (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                    || idx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                idx = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
            }
            if (idx < 0) {
                return 0;
            }
            int samples = info.size / 2;
            if (samples > pcm.length) {
                samples = pcm.length;
            }
            ByteBuffer b = decoder.getOutputBuffer(idx);
            b.position(info.offset);
            for (int i = 0; i < samples; i++) {
                pcm[i] = b.getShort();
            }
            decoder.releaseOutputBuffer(idx, false);
            return samples;
        } catch (Throwable t) {
            JoanTrace.note("amr decode " + t.getClass().getSimpleName());
            return -1;
        }
    }

    void close() {
        releaseQuietly(encoder);
        releaseQuietly(decoder);
    }

    private static void releaseQuietly(MediaCodec c) {
        if (c == null) {
            return;
        }
        try {
            c.stop();
        } catch (Throwable ignored) {
            // already stopped or never started
        }
        try {
            c.release();
        } catch (Throwable ignored) {
            // nothing more to do
        }
    }
}
