package dev.rokumirror;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private EditText ipField;
    private TextView statusView;
    private LinearLayout deviceList;
    private Button scanButton;
    private Switch builtInSwitch;

    private final Runnable statusPoller = new Runnable() {
        @Override
        public void run() {
            statusView.setText(MirrorService.status);
            main.postDelayed(this, 700);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("mirror", MODE_PRIVATE);
        setContentView(buildUi());

        List<String> wanted = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!wanted.isEmpty()) requestPermissions(wanted.toArray(new String[0]), 2);
        if (ipField.getText().length() == 0) scan();
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.post(statusPoller);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(statusPoller);
    }

    private ViewGroup buildUi() {
        int pad = dp(20);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, dp(40), pad, pad);

        TextView title = new TextView(this);
        title.setText("Roku Mirror");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        col.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Pick your Roku, then tap Start. The Roku needs the Phone Mirror "
                + "receiver channel installed. Expect a delay of a few seconds, so it's best "
                + "for videos and photos rather than games.\n\n"
                + "For sound, allow the audio permission. Android calls it \"record audio\", "
                + "but the app only captures what other apps play, not your microphone.");
        hint.setPadding(0, dp(8), 0, dp(16));
        col.addView(hint);

        scanButton = new Button(this);
        scanButton.setText("Find my Roku");
        scanButton.setOnClickListener(v -> scan());
        col.addView(scanButton);

        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        col.addView(deviceList);

        ipField = new EditText(this);
        ipField.setHint("Roku IP address, e.g. 192.168.1.50");
        ipField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        ipField.setSingleLine(true);
        ipField.setText(prefs.getString("roku", ""));
        col.addView(ipField);

        builtInSwitch = new Switch(this);
        builtInSwitch.setText("No-channel mode (experimental): use the Roku's built-in "
                + "player instead of the Phone Mirror channel");
        builtInSwitch.setChecked(prefs.getBoolean("builtIn", false));
        builtInSwitch.setPadding(0, dp(12), 0, dp(12));
        builtInSwitch.setOnCheckedChangeListener(
                (b, on) -> prefs.edit().putBoolean("builtIn", on).apply());
        col.addView(builtInSwitch);

        Button start = new Button(this);
        start.setText("Start mirroring");
        start.setOnClickListener(v -> startMirroring());
        col.addView(start);

        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setOnClickListener(v -> startService(
                new Intent(this, MirrorService.class).setAction(MirrorService.ACTION_STOP)));
        col.addView(stop);

        statusView = new TextView(this);
        statusView.setPadding(0, dp(16), 0, 0);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        col.addView(statusView);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(col);
        return scroll;
    }

    private void scan() {
        scanButton.setEnabled(false);
        scanButton.setText("Searching…");
        deviceList.removeAllViews();
        new Thread(() -> {
            List<RokuClient.Device> found = RokuClient.discover(3000);
            main.post(() -> {
                scanButton.setEnabled(true);
                scanButton.setText("Find my Roku");
                if (found.isEmpty()) {
                    TextView none = new TextView(this);
                    none.setText("No Roku found. Make sure you're on the same Wi-Fi with any "
                            + "VPN off, or type its IP (Roku: Settings > Network > About).");
                    none.setPadding(0, dp(8), 0, dp(8));
                    deviceList.addView(none);
                    return;
                }
                for (RokuClient.Device d : found) {
                    Button b = new Button(this);
                    b.setAllCaps(false);
                    b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                    b.setText(d.name + "  ·  " + d.ip);
                    b.setOnClickListener(v -> ipField.setText(d.ip));
                    deviceList.addView(b);
                }
                if (found.size() == 1) ipField.setText(found.get(0).ip);
            });
        }, "roku-scan").start();
    }

    private void startMirroring() {
        String ip = ipField.getText().toString().trim();
        if (!ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            Toast.makeText(this, "Pick a Roku or enter its IP address first", Toast.LENGTH_LONG).show();
            return;
        }
        prefs.edit().putString("roku", ip).apply();
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            MirrorService.status = "Screen capture was cancelled";
            return;
        }
        Intent svc = new Intent(this, MirrorService.class)
                .putExtra(MirrorService.EXTRA_CODE, resultCode)
                .putExtra(MirrorService.EXTRA_DATA, data)
                .putExtra(MirrorService.EXTRA_ROKU, ipField.getText().toString().trim())
                .putExtra(MirrorService.EXTRA_BUILT_IN, builtInSwitch.isChecked());
        MirrorService.status = "Starting…";
        startForegroundService(svc);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
