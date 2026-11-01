package dev.rokumirror;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Surface;

import java.nio.ByteBuffer;

/**
 * Foreground service: captures the screen, encodes it to H.264, and serves it as
 * live HLS for the Roku receiver channel.
 */
public class MirrorService extends Service {
    public static final String ACTION_STOP = "dev.rokumirror.STOP";
    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";
    public static final String EXTRA_ROKU = "roku";

    /** Read by the activity to show progress. */
    public static volatile String status = "Not mirroring";
    public static volatile boolean running;

    private static final String CHANNEL = "mirror";
    private static final int NOTIFICATION_ID = 1;

    private final Handler main = new Handler(Looper.getMainLooper());

    private MediaProjection projection;
    private VirtualDisplay display;
    private MediaCodec codec;
    private Surface inputSurface;
    private HlsSegmenter segmenter;
    private MiniHttpServer server;
    private Thread drainThread;
    private volatile boolean stopping;
    private String rokuIp;
    private AudioCapture audio;
    private boolean audioAllowed;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopMirroring(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) stopMirroring(false);

        // Must be in the foreground before asking for the projection (Android 14+).
        audioAllowed = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        if (audioAllowed && Build.VERSION.SDK_INT >= 30) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), types);
        } catch (RuntimeException e) {
            audioAllowed = false;
            startForeground(NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        }

        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(EXTRA_DATA, Intent.class)
                : intent.getParcelableExtra(EXTRA_DATA);
        rokuIp = intent.getStringExtra(EXTRA_ROKU);

        try {
            startMirroring(code, data);
        } catch (Exception e) {
            status = "Couldn't start: " + e.getMessage();
            stopMirroring(false);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startMirroring(int code, Intent data) throws Exception {
        stopping = false;
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        projection = mpm.getMediaProjection(code, data);
        if (projection == null) throw new IllegalStateException("screen capture was not allowed");
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                main.post(() -> {
                    stopMirroring(true);
                    stopSelf();
                });
            }
        }, main);

        // Size the stream to the screen's shape, small enough for smooth Wi-Fi streaming.
        DisplayMetrics dm = new DisplayMetrics();
        Display d = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        d.getRealMetrics(dm);
        int[] size = fit(dm.widthPixels, dm.heightPixels, 1280, 1080);

        // Set up audio first: the stream only advertises a sound track if we can fill it.
        String soundNote;
        if (!audioAllowed) {
            soundNote = "no sound: audio permission off";
        } else {
            try {
                audio = new AudioCapture(projection);
                soundNote = "with sound";
            } catch (Exception e) {
                audio = null;
                soundNote = "no sound: " + e.getMessage();
            }
        }
        segmenter = audio != null
                ? new HlsSegmenter(AudioCapture.SAMPLE_RATE, AudioCapture.CHANNELS)
                : new HlsSegmenter();
        try {
            startEncoder(size[0], size[1]);
        } catch (Exception first) {
            releaseEncoder();
            size = new int[]{1280, 720};    // safe fallback size every encoder supports
            startEncoder(size[0], size[1]);
        }

        display = projection.createVirtualDisplay("RokuMirror", size[0], size[1], dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inputSurface, null, null);

        drainThread = new Thread(this::drainLoop, "mirror-encoder");
        drainThread.start();
        if (audio != null) audio.start(segmenter);

        server = new MiniHttpServer(segmenter);
        int port = server.start(8765);
        running = true;
        status = "Starting stream…";

        final String roku = rokuIp;
        final String sound = soundNote;
        new Thread(() -> {
            String phoneIp = RokuClient.localAddressToward(roku);
            if (phoneIp == null) {
                status = "Couldn't find this phone's Wi-Fi address. Is Wi-Fi on?";
                return;
            }
            String url = "http://" + phoneIp + ":" + port + "/live.m3u8";
            try {
                RokuClient.launchReceiver(roku, url);
                status = "Mirroring to " + roku + " (" + size_str() + ", " + sound + ")";
            } catch (Exception e) {
                status = e.getMessage();
            }
        }, "roku-launch").start();
    }

    private String size_str() {
        try {
            MediaFormat f = codec.getOutputFormat();
            return f.getInteger(MediaFormat.KEY_WIDTH) + "×" + f.getInteger(MediaFormat.KEY_HEIGHT);
        } catch (Exception e) {
            return "live";
        }
    }

    private void startEncoder(int w, int h) throws Exception {
        MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 5_000_000);
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        fmt.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        fmt.setInteger(MediaFormat.KEY_PRIORITY, 0);
        // A still screen produces no new frames; repeat the last one so the stream keeps flowing.
        fmt.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L);

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = codec.createInputSurface();
        codec.start();
    }

    private void drainLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (!stopping) {
            int idx;
            try {
                idx = codec.dequeueOutputBuffer(info, 100_000);
            } catch (IllegalStateException e) {
                return;
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat f = codec.getOutputFormat();
                ByteBuffer sps = f.getByteBuffer("csd-0");
                ByteBuffer pps = f.getByteBuffer("csd-1");
                if (sps != null && pps != null) {
                    byte[] cfg = new byte[sps.remaining() + pps.remaining()];
                    sps.get(cfg, 0, sps.remaining());
                    pps.get(cfg, cfg.length - pps.remaining(), pps.remaining());
                    segmenter.setCodecConfig(cfg);
                }
                continue;
            }
            if (idx < 0) continue;

            ByteBuffer buf = codec.getOutputBuffer(idx);
            if (buf != null && info.size > 0) {
                byte[] bytes = new byte[info.size];
                buf.position(info.offset);
                buf.limit(info.offset + info.size);
                buf.get(bytes);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    segmenter.setCodecConfig(bytes);
                } else {
                    boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                    segmenter.addFrame(bytes, info.presentationTimeUs, key);
                }
            }
            codec.releaseOutputBuffer(idx, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
        }
    }

    private void stopMirroring(boolean userStopped) {
        boolean wasRunning = running;
        running = false;
        stopping = true;
        if (segmenter != null) segmenter.close();
        if (display != null) {
            display.release();
            display = null;
        }
        if (drainThread != null) {
            try {
                drainThread.join(1000);
            } catch (InterruptedException ignored) {
            }
            drainThread = null;
        }
        if (audio != null) {
            audio.stop();
            audio = null;
        }
        releaseEncoder();
        if (projection != null) {
            MediaProjection p = projection;
            projection = null;
            try {
                p.stop();
            } catch (Exception ignored) {
            }
        }
        if (server != null) {
            server.stop();
            server = null;
        }
        if (wasRunning && userStopped && rokuIp != null) {
            final String ip = rokuIp;
            new Thread(() -> RokuClient.keypress(ip, "Back")).start();
        }
        if (userStopped) status = "Not mirroring";
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private void releaseEncoder() {
        if (codec != null) {
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            codec.release();
            codec = null;
        }
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
    }

    @Override
    public void onDestroy() {
        stopMirroring(true);
        super.onDestroy();
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Screen mirroring",
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, MirrorService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("Mirroring to your Roku")
                .setContentText("Tap Stop to end")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                        "Stop", stop).build())
                .build();
    }

    /** Scales (w, h) to fit inside (maxW, maxH), never upscaling, rounded to multiples of 16. */
    static int[] fit(int w, int h, int maxW, int maxH) {
        double s = Math.min(1.0, Math.min((double) maxW / w, (double) maxH / h));
        int ow = Math.max(16, ((int) (w * s)) / 16 * 16);
        int oh = Math.max(16, ((int) (h * s)) / 16 * 16);
        return new int[]{ow, oh};
    }
}
