package dev.rokumirror;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Turns H.264 access units (Annex-B, as produced by Android's MediaCodec) into a
 * live HLS stream: a rolling playlist plus short MPEG-TS segments held in memory.
 * Pure Java, no Android dependencies, so it can be tested on any JVM.
 */
public final class HlsSegmenter {
    private static final int PAT_PID = 0x0000;
    private static final int PMT_PID = 0x1000;
    private static final int VIDEO_PID = 0x0100;

    private static final long MIN_SEGMENT_US = 900_000;   // cut at the first keyframe after ~0.9 s
    private static final int KEEP_SEGMENTS = 12;          // held in memory
    private static final int LIST_SEGMENTS = 4;           // advertised in the playlist

    private static final byte[] AUD = {0, 0, 0, 1, 0x09, (byte) 0xF0};

    private final Object lock = new Object();
    private final LinkedHashMap<Integer, Segment> segments = new LinkedHashMap<>();

    private byte[] codecConfig;
    private ByteArrayOutputStream current;
    private long currentStartUs = -1;
    private long firstPtsUs = -1;
    private int nextSeq = 0;
    private int ccPat, ccPmt, ccVideo;
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
            writeAccessUnit(annexB, Math.max(0, ptsUs - firstPtsUs), keyFrame);
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

    private void writeAccessUnit(byte[] data, long relUs, boolean key) {
        long pts = relUs * 9 / 100 + 126_000;      // 90 kHz clock, 1.4 s offset
        long pcr = pts - 63_000;                   // decoder clock 0.7 s behind PTS

        byte[] config = key ? codecConfig : null;
        int esLen = AUD.length + (config != null ? config.length : 0) + data.length;
        byte[] pes = new byte[14 + esLen];
        int p = 0;
        pes[p++] = 0; pes[p++] = 0; pes[p++] = 1; pes[p++] = (byte) 0xE0;   // video stream
        pes[p++] = 0; pes[p++] = 0;                                       // unbounded length
        pes[p++] = (byte) 0x80;                                           // marker bits
        pes[p++] = (byte) 0x80;                                           // PTS only
        pes[p++] = 5;                                                     // header data length
        pes[p++] = (byte) (0x21 | ((pts >> 29) & 0x0E));
        pes[p++] = (byte) (pts >> 22);
        pes[p++] = (byte) (((pts >> 14) & 0xFE) | 1);
        pes[p++] = (byte) (pts >> 7);
        pes[p++] = (byte) (((pts << 1) & 0xFE) | 1);
        System.arraycopy(AUD, 0, pes, p, AUD.length);
        p += AUD.length;
        if (config != null) {
            System.arraycopy(config, 0, pes, p, config.length);
            p += config.length;
        }
        System.arraycopy(data, 0, pes, p, data.length);

        writePes(pes, pcr, key);
    }

    private void writePes(byte[] pes, long pcr, boolean key) {
        int pos = 0;
        boolean first = true;
        byte[] pkt = new byte[188];
        while (pos < pes.length) {
            int remaining = pes.length - pos;

            // Adaptation field content (excluding its length byte).
            int afContent = 0;
            if (first) afContent = 7;              // flags + PCR
            int afTotal = first ? 1 + afContent : 0;
            int space = 184 - afTotal;
            if (remaining < space) {
                // Pad with stuffing so the packet is exactly 188 bytes.
                afTotal = 184 - remaining;
                afContent = afTotal - 1;
                space = remaining;
            }

            pkt[0] = 0x47;
            pkt[1] = (byte) ((first ? 0x40 : 0) | ((VIDEO_PID >> 8) & 0x1F));
            pkt[2] = (byte) VIDEO_PID;
            pkt[3] = (byte) ((afTotal > 0 ? 0x30 : 0x10) | (ccVideo++ & 0x0F));
            int i = 4;
            if (afTotal > 0) {
                pkt[i++] = (byte) afContent;
                if (afContent > 0) {
                    int flags = 0;
                    if (first) flags |= 0x10;                  // PCR present
                    if (first && key) flags |= 0x40;           // random access point
                    pkt[i++] = (byte) flags;
                    int written = 1;
                    if (first) {
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
        java.util.Arrays.fill(pkt, (byte) 0xFF);
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

    private static byte[] pmt() {
        byte[] s = {
                0x02, (byte) 0xB0, 0x12, 0x00, 0x01, (byte) 0xC1, 0x00, 0x00,
                (byte) (0xE0 | (VIDEO_PID >> 8)), (byte) VIDEO_PID, (byte) 0xF0, 0x00,
                0x1B, (byte) (0xE0 | (VIDEO_PID >> 8)), (byte) VIDEO_PID, (byte) 0xF0, 0x00
        };
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
