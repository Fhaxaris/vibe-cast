package dev.rokumirror;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Turns H.264 access units (Annex-B, as produced by Android's MediaCodec) and raw AAC
 * frames into a live HLS stream: a rolling playlist plus short MPEG-TS segments held in
 * memory. Pure Java, no Android dependencies, so it can be tested on any JVM.
 *
 * Video and audio timestamps must share one clock (on Android both come from
 * System.nanoTime()/1000). Segments are cut on video keyframes.
 */
public final class HlsSegmenter {
    private static final int PAT_PID = 0x0000;
    private static final int PMT_PID = 0x1000;
    private static final int VIDEO_PID = 0x0100;
    private static final int AUDIO_PID = 0x0101;

    private static final long MIN_SEGMENT_US = 900_000;   // cut at the first keyframe after ~0.9 s
    private static final int KEEP_SEGMENTS = 12;          // held in memory
    private static final int LIST_SEGMENTS = 4;           // advertised in the playlist

    private static final byte[] AUD = {0, 0, 0, 1, 0x09, (byte) 0xF0};

    private static final int[] AAC_RATES = {
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};

    private final Object lock = new Object();
    private final LinkedHashMap<Integer, Segment> segments = new LinkedHashMap<>();
    private final boolean withAudio;
    private final int audioRateIndex;
    private final int audioChannels;

    private byte[] codecConfig;
    private ByteArrayOutputStream current;
    private long currentStartUs = -1;
    private long firstPtsUs = -1;
    private int nextSeq = 0;
    private int ccPat, ccPmt, ccVideo, ccAudio;
    private boolean closed;

    private static final class Segment {
        final int seq;
        final byte[] data;
        final double duration;

        Segment(int seq, byte[] data, double duration) {
            this.seq = seq;
            this.data = data;
            this.duration = duration;
        }
    }

    /** Video-only stream. */
    public HlsSegmenter() {
        this(0, 0);
    }

    /**
     * Video plus an AAC-LC audio track at the given sample rate and channel count.
     * Pass 0 for both to disable audio.
     */
    public HlsSegmenter(int audioSampleRate, int audioChannels) {
        this.withAudio = audioSampleRate > 0 && audioChannels > 0;
        int idx = 3;
        for (int i = 0; i < AAC_RATES.length; i++) {
            if (AAC_RATES[i] == audioSampleRate) idx = i;
        }
        this.audioRateIndex = idx;
        this.audioChannels = audioChannels;
    }

    /** SPS + PPS, Annex-B with start codes. Re-sent before every keyframe. */
    public void setCodecConfig(byte[] config) {
        synchronized (lock) {
            codecConfig = config.clone();
        }
    }

    public boolean hasCodecConfig() {
        synchronized (lock) {
            return codecConfig != null;
        }
    }

    public void addFrame(byte[] annexB, long ptsUs, boolean keyFrame) {
        synchronized (lock) {
            if (closed) return;
            if (current == null) {
                if (!keyFrame) return;              // a stream must start on a keyframe
                startSegment(ptsUs);
            } else if (keyFrame && ptsUs - currentStartUs >= MIN_SEGMENT_US) {
                finishSegment(ptsUs);
                startSegment(ptsUs);
            }
            if (firstPtsUs < 0) firstPtsUs = ptsUs;
            writeVideo(annexB, Math.max(0, ptsUs - firstPtsUs), keyFrame);
        }
    }

    /** One raw AAC frame (no ADTS header), as MediaCodec's AAC encoder outputs it. */
    public void addAudio(byte[] aac, long ptsUs) {
        synchronized (lock) {
            if (closed || !withAudio || current == null || firstPtsUs < 0) return;
            if (ptsUs < firstPtsUs) return;         // from before the video started
            writeAudio(aac, ptsUs - firstPtsUs);
        }
    }

    public void close() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
    }

    /**
     * Returns the live playlist, waiting up to {@code waitMs} for at least two
     * segments to exist. Returns null if none are ready.
     */
    public String playlist(long waitMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + waitMs;
        synchronized (lock) {
            while (segments.size() < 2 && !closed) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                lock.wait(left);
            }
            if (segments.isEmpty()) return null;

            List<Segment> all = new ArrayList<>(segments.values());
            List<Segment> listed = all.subList(Math.max(0, all.size() - LIST_SEGMENTS), all.size());
            double max = 1;
            for (Segment s : listed) max = Math.max(max, s.duration);

            StringBuilder sb = new StringBuilder();
            sb.append("#EXTM3U\n");
            sb.append("#EXT-X-VERSION:3\n");
            sb.append("#EXT-X-TARGETDURATION:").append((int) Math.ceil(max)).append('\n');
            sb.append("#EXT-X-MEDIA-SEQUENCE:").append(listed.get(0).seq).append('\n');
            for (Segment s : listed) {
                sb.append(String.format(Locale.US, "#EXTINF:%.3f,\n", s.duration));
                sb.append("seg").append(s.seq).append(".ts\n");
            }
            if (closed) sb.append("#EXT-X-ENDLIST\n");
            return sb.toString();
        }
    }

    public byte[] segment(int seq) {
        synchronized (lock) {
            Segment s = segments.get(seq);
            return s == null ? null : s.data;
        }
    }

    // ---------------------------------------------------------------- muxing

    private void startSegment(long ptsUs) {
        current = new ByteArrayOutputStream(512 * 1024);
        currentStartUs = ptsUs;
        writePsi(PAT_PID, pat(), ccPat++);
        writePsi(PMT_PID, pmt(), ccPmt++);
    }

    private void finishSegment(long endUs) {
        double duration = (endUs - currentStartUs) / 1_000_000.0;
        int seq = nextSeq++;
        segments.put(seq, new Segment(seq, current.toByteArray(), duration));
        while (segments.size() > KEEP_SEGMENTS) {
            segments.remove(segments.keySet().iterator().next());
        }
        lock.notifyAll();
    }

    private static long toPts(long relUs) {
        return relUs * 9 / 100 + 126_000;          // 90 kHz clock, 1.4 s offset
    }

    private static int writePtsHeader(byte[] pes, int p, int streamId, int pesLength, long pts) {
        pes[p++] = 0; pes[p++] = 0; pes[p++] = 1; pes[p++] = (byte) streamId;
        pes[p++] = (byte) (pesLength >> 8);
        pes[p++] = (byte) pesLength;
        pes[p++] = (byte) 0x80;                     // marker bits
        pes[p++] = (byte) 0x80;                     // PTS only
        pes[p++] = 5;                               // header data length
        pes[p++] = (byte) (0x21 | ((pts >> 29) & 0x0E));
        pes[p++] = (byte) (pts >> 22);
        pes[p++] = (byte) (((pts >> 14) & 0xFE) | 1);
        pes[p++] = (byte) (pts >> 7);
        pes[p++] = (byte) (((pts << 1) & 0xFE) | 1);
        return p;
    }

    private void writeVideo(byte[] data, long relUs, boolean key) {
        long pts = toPts(relUs);
        long pcr = pts - 63_000;                    // decoder clock 0.7 s behind PTS

        byte[] config = key ? codecConfig : null;
        int esLen = AUD.length + (config != null ? config.length : 0) + data.length;
        byte[] pes = new byte[14 + esLen];
        int p = writePtsHeader(pes, 0, 0xE0, 0, pts);   // 0 = unbounded length (video)
        System.arraycopy(AUD, 0, pes, p, AUD.length);
        p += AUD.length;
        if (config != null) {
            System.arraycopy(config, 0, pes, p, config.length);
            p += config.length;
        }
        System.arraycopy(data, 0, pes, p, data.length);

        writePes(VIDEO_PID, pes, pcr, key);
    }

    private void writeAudio(byte[] aac, long relUs) {
        long pts = toPts(relUs);
        int frameLen = 7 + aac.length;
        byte[] pes = new byte[14 + frameLen];
        int p = writePtsHeader(pes, 0, 0xC0, 8 + frameLen, pts);

        // ADTS header: AAC-LC, no CRC.
        pes[p++] = (byte) 0xFF;
        pes[p++] = (byte) 0xF1;
        pes[p++] = (byte) ((1 << 6) | (audioRateIndex << 2) | ((audioChannels >> 2) & 1));
        pes[p++] = (byte) (((audioChannels & 3) << 6) | ((frameLen >> 11) & 3));
        pes[p++] = (byte) (frameLen >> 3);
        pes[p++] = (byte) (((frameLen & 7) << 5) | 0x1F);
        pes[p++] = (byte) 0xFC;
        System.arraycopy(aac, 0, pes, p, aac.length);

        writePes(AUDIO_PID, pes, -1, true);
    }

    /** Splits a PES packet into 188-byte TS packets. pcr < 0 means no PCR. */
    private void writePes(int pid, byte[] pes, long pcr, boolean randomAccess) {
        int pos = 0;
        boolean first = true;
        byte[] pkt = new byte[188];
        while (pos < pes.length) {
            int remaining = pes.length - pos;
            boolean withPcr = first && pcr >= 0;
            boolean withFlags = first;              // first packet carries flags (and PCR)

            // Adaptation field content (excluding its length byte).
            int afContent = withFlags ? (withPcr ? 7 : 1) : 0;
            int afTotal = withFlags ? 1 + afContent : 0;
            int space = 184 - afTotal;
            if (remaining < space) {
                // Pad with stuffing so the packet is exactly 188 bytes.
                afTotal = 184 - remaining;
                afContent = afTotal - 1;
                space = remaining;
            }

            int cc = pid == VIDEO_PID ? ccVideo++ : ccAudio++;
            pkt[0] = 0x47;
            pkt[1] = (byte) ((first ? 0x40 : 0) | ((pid >> 8) & 0x1F));
            pkt[2] = (byte) pid;
            pkt[3] = (byte) ((afTotal > 0 ? 0x30 : 0x10) | (cc & 0x0F));
            int i = 4;
            if (afTotal > 0) {
                pkt[i++] = (byte) afContent;
                if (afContent > 0) {
                    int flags = 0;
                    if (withPcr) flags |= 0x10;                   // PCR present
                    if (first && randomAccess) flags |= 0x40;     // random access point
                    pkt[i++] = (byte) flags;
                    int written = 1;
                    if (withPcr) {
                        pkt[i++] = (byte) (pcr >> 25);
                        pkt[i++] = (byte) (pcr >> 17);
                        pkt[i++] = (byte) (pcr >> 9);
                        pkt[i++] = (byte) (pcr >> 1);
                        pkt[i++] = (byte) (((pcr & 1) << 7) | 0x7E);
                        pkt[i++] = 0;
                        written += 6;
                    }
                    while (written < afContent) {
                        pkt[i++] = (byte) 0xFF;
                        written++;
                    }
                }
            }
            System.arraycopy(pes, pos, pkt, i, space);
            pos += space;
            current.write(pkt, 0, 188);
            first = false;
        }
    }

    private void writePsi(int pid, byte[] section, int cc) {
        byte[] pkt = new byte[188];
        Arrays.fill(pkt, (byte) 0xFF);
        pkt[0] = 0x47;
        pkt[1] = (byte) (0x40 | ((pid >> 8) & 0x1F));
        pkt[2] = (byte) pid;
        pkt[3] = (byte) (0x10 | (cc & 0x0F));
        pkt[4] = 0;                                 // pointer field
        System.arraycopy(section, 0, pkt, 5, section.length);
        current.write(pkt, 0, 188);
    }

    private static byte[] pat() {
        byte[] s = {
                0x00, (byte) 0xB0, 0x0D, 0x00, 0x01, (byte) 0xC1, 0x00, 0x00,
                0x00, 0x01, (byte) (0xE0 | (PMT_PID >> 8)), (byte) PMT_PID
        };
        return withCrc(s);
    }

    private byte[] pmt() {
        byte[] video = {
                0x1B, (byte) (0xE0 | (VIDEO_PID >> 8)), (byte) VIDEO_PID, (byte) 0xF0, 0x00
        };
        byte[] audio = {
                0x0F, (byte) (0xE0 | (AUDIO_PID >> 8)), (byte) AUDIO_PID, (byte) 0xF0, 0x00
        };
        int streams = video.length + (withAudio ? audio.length : 0);
        int sectionLength = 9 + streams + 4;        // header after length field + streams + CRC
        byte[] s = new byte[3 + 9 + streams];
        int p = 0;
        s[p++] = 0x02;
        s[p++] = (byte) (0xB0 | (sectionLength >> 8));
        s[p++] = (byte) sectionLength;
        s[p++] = 0x00; s[p++] = 0x01;               // program number 1
        s[p++] = (byte) 0xC1; s[p++] = 0x00; s[p++] = 0x00;
        s[p++] = (byte) (0xE0 | (VIDEO_PID >> 8)); s[p++] = (byte) VIDEO_PID;   // PCR PID
        s[p++] = (byte) 0xF0; s[p++] = 0x00;        // no program info
        System.arraycopy(video, 0, s, p, video.length);
        p += video.length;
        if (withAudio) System.arraycopy(audio, 0, s, p, audio.length);
        return withCrc(s);
    }

    private static byte[] withCrc(byte[] section) {
        int crc = 0xFFFFFFFF;
        for (byte b : section) {
            crc ^= (b & 0xFF) << 24;
            for (int k = 0; k < 8; k++) {
                crc = (crc & 0x80000000) != 0 ? (crc << 1) ^ 0x04C11DB7 : crc << 1;
            }
        }
        byte[] out = new byte[section.length + 4];
        System.arraycopy(section, 0, out, 0, section.length);
        out[section.length] = (byte) (crc >>> 24);
        out[section.length + 1] = (byte) (crc >>> 16);
        out[section.length + 2] = (byte) (crc >>> 8);
        out[section.length + 3] = (byte) crc;
        return out;
    }
}
