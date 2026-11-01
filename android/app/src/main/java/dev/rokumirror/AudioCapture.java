package dev.rokumirror;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;

import java.nio.ByteBuffer;

/**
 * Captures the audio other apps are playing (Android 10+ playback capture) and encodes
 * it to AAC for the stream. Needs the RECORD_AUDIO permission. Apps can opt out of
 * being captured, in which case their sound is simply missing.
 */
final class AudioCapture {
    static final int SAMPLE_RATE = 48_000;
    static final int CHANNELS = 2;
    private static final int BYTES_PER_FRAME = CHANNELS * 2;   // 16-bit PCM
    private static final int CHUNK_BYTES = 1024 * BYTES_PER_FRAME; // one AAC frame of audio

    private final AudioRecord record;
    private final MediaCodec encoder;
    private HlsSegmenter segmenter;
    private Thread thread;
    private volatile boolean stopping;

    /** Sets everything up; throws if audio capture isn't possible on this device. */
    @SuppressWarnings("MissingPermission")
    AudioCapture(MediaProjection projection) throws Exception {
        AudioPlaybackCaptureConfiguration config =
                new AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build();
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        record = new AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(Math.max(minBuf * 2, CHUNK_BYTES * 8))
                .setAudioPlaybackCaptureConfig(config)
                .build();
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            throw new IllegalStateException("audio capture unavailable");
        }

        MediaFormat aac = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
                SAMPLE_RATE, CHANNELS);
        aac.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        aac.setInteger(MediaFormat.KEY_BIT_RATE, 160_000);
        aac.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_BYTES * 4);
        try {
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            encoder.configure(aac, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (Exception e) {
            record.release();
            throw e;
        }
    }

    void start(HlsSegmenter segmenter) {
        this.segmenter = segmenter;
        encoder.start();
        record.startRecording();
        thread = new Thread(this::loop, "mirror-audio");
        thread.start();
    }

    void stop() {
        stopping = true;
        try {
            record.stop();          // unblocks a pending read
        } catch (Exception ignored) {
        }
        if (thread != null) {
            try {
                thread.join(1000);
            } catch (InterruptedException ignored) {
            }
        }
        record.release();
        try {
            encoder.stop();
        } catch (Exception ignored) {
        }
        encoder.release();
    }

    private void loop() {
        byte[] pcm = new byte[CHUNK_BYTES];
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long startUs = -1;
        long framesSoFar = 0;
        try {
            while (!stopping) {
                int n = 0;
                while (n < pcm.length && !stopping) {
                    int r = record.read(pcm, n, pcm.length - n);
                    if (r <= 0) break;
                    n += r;
                }
                if (stopping) return;
                if (n <= 0) continue;

                // Timestamps on the same clock as the video (System.nanoTime), derived from
                // the sample count so they stay perfectly even; re-anchor if they drift.
                int frames = n / BYTES_PER_FRAME;
                long nowUs = System.nanoTime() / 1000;
                long chunkStartUs = nowUs - frames * 1_000_000L / SAMPLE_RATE;
                long expected = startUs + framesSoFar * 1_000_000L / SAMPLE_RATE;
                if (startUs < 0 || Math.abs(expected - chunkStartUs) > 200_000) {
                    startUs = chunkStartUs - framesSoFar * 1_000_000L / SAMPLE_RATE;
                }
                long ptsUs = startUs + framesSoFar * 1_000_000L / SAMPLE_RATE;
                framesSoFar += frames;

                int in = encoder.dequeueInputBuffer(10_000);
                if (in >= 0) {
                    ByteBuffer buf = encoder.getInputBuffer(in);
                    buf.clear();
                    buf.put(pcm, 0, n);
                    encoder.queueInputBuffer(in, 0, n, ptsUs, 0);
                }

                int out;
                while ((out = encoder.dequeueOutputBuffer(info, 0)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (out < 0) continue;              // format changed etc.
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                        ByteBuffer ob = encoder.getOutputBuffer(out);
                        byte[] frame = new byte[info.size];
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ob.get(frame);
                        segmenter.addAudio(frame, info.presentationTimeUs);
                    }
                    encoder.releaseOutputBuffer(out, false);
                }
            }
        } catch (IllegalStateException e) {
            // stopped underneath us
        }
    }
}
