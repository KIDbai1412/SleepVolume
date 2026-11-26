package com.sleepvolume;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_NOTIFICATIONS = 1001;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean polling = false;

    private AudioManager audio;
    private LoopSettings settings;

    private SeekBar sbPlay, sbSilent, sbStep, sbCycles, sbFadeOut, sbFadeIn, sbCap;
    private TextView lblPlay, lblSilent, lblStep, lblCycles, lblFadeOut, lblFadeIn, lblCap;
    private TextView statusPhase, statusTime, statusInfo, preview;
    private ProgressBar progress;
    private Button btnStart, btnStop, btnBattery;

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            render();
            if (polling) {
                ui.postDelayed(this, 500L);
            }
        }
    };

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        settings = LoopSettings.load(this);

        statusPhase = findViewById(R.id.status_phase);
        statusTime = findViewById(R.id.status_time);
        statusInfo = findViewById(R.id.status_info);
        preview = findViewById(R.id.preview);
        progress = findViewById(R.id.progress);

        sbPlay = findViewById(R.id.sb_play);
        sbSilent = findViewById(R.id.sb_silent);
        sbStep = findViewById(R.id.sb_step);
        sbCycles = findViewById(R.id.sb_cycles);
        sbFadeOut = findViewById(R.id.sb_fade_out);
        sbFadeIn = findViewById(R.id.sb_fade_in);
        sbCap = findViewById(R.id.sb_cap);

        lblPlay = findViewById(R.id.lbl_play);
        lblSilent = findViewById(R.id.lbl_silent);
        lblStep = findViewById(R.id.lbl_step);
        lblCycles = findViewById(R.id.lbl_cycles);
        lblFadeOut = findViewById(R.id.lbl_fade_out);
        lblFadeIn = findViewById(R.id.lbl_fade_in);
        lblCap = findViewById(R.id.lbl_cap);

        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnBattery = findViewById(R.id.btn_battery);

        bindSeek(sbPlay, lblPlay, 1, 30, settings.playMinutes, "分钟", null,
                v -> settings.playMinutes = v);
        bindSeek(sbSilent, lblSilent, 1, 30, settings.silentMinutes, "分钟", null,
                v -> settings.silentMinutes = v);
        bindSeek(sbStep, lblStep, 5, 80, settings.stepPercent, "%", null,
                v -> settings.stepPercent = v);
        bindSeek(sbCycles, lblCycles, 1, 12, settings.cycles, "轮", null,
                v -> settings.cycles = v);
        bindSeek(sbFadeOut, lblFadeOut, 0, 30, settings.fadeOutSeconds, "秒", "瞬间",
                v -> settings.fadeOutSeconds = v);
        bindSeek(sbFadeIn, lblFadeIn, 0, 30, settings.fadeInSeconds, "秒", "瞬间",
                v -> settings.fadeInSeconds = v);
        bindSeek(sbCap, lblCap, 0, 120, settings.totalCapMinutes, "分钟", "不限",
                v -> settings.totalCapMinutes = v);

        btnStart.setOnClickListener(v -> startLoop());
        btnStop.setOnClickListener(v -> stopLoop());
        btnBattery.setOnClickListener(v -> requestIgnoreBattery());

        askNotificationPermissionIfNeeded();
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startPolling();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPolling();
    }

    // ---------------------------------------------------------------- 动作

    private void startLoop() {
        if (LoopState.running) {
            toast("已经在运行了");
            return;
        }

        int vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (vol <= 0) {
            toast("当前媒体音量为 0，请先用音量键调大一点");
            return;
        }

        settings.save(this);

        Intent i = new Intent(this, SleepLoopService.class)
                .setAction(SleepLoopService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            LoopState.running = false;
            LoopState.phase = "启动失败";
            toast("启动失败：" + e.getClass().getSimpleName());
            render();
            return;
        }

        LoopState.running = true;
        LoopState.phase = "准备中…";
        startPolling();
        ui.postDelayed(this::render, 300L);
    }

    private void stopLoop() {
        Intent i = new Intent(this, SleepLoopService.class)
                .setAction(SleepLoopService.ACTION_STOP);
        try {
            startService(i);
        } catch (Exception ignored) {
        }
        ui.postDelayed(this::render, 400L);
    }

    private void requestIgnoreBattery() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            toast("已经不受电池优化限制了");
            return;
        }
        try {
            Intent i = new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                toast("请到系统设置里手动允许后台运行");
            }
        }
    }

    private void askNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATIONS);
        }
    }

    // ---------------------------------------------------------------- 界面

    private void render() {
        boolean running = LoopState.running;

        if (running) {
            statusPhase.setText(LoopState.phase);
            statusTime.setText(fmtTime(LoopState.remainMs));
            statusInfo.setText("第 " + LoopState.cycle + "/" + LoopState.totalCycles
                    + " 轮 · 媒体音量 " + LoopState.volume + "/" + LoopState.maxVolume);

            long total = LoopState.phaseDurationMs;
            if (total > 0L) {
                long done = total - Math.max(0L, LoopState.remainMs);
                int p = (int) (done * 1000L / total);
                progress.setMax(1000);
                progress.setProgress(Math.max(0, Math.min(1000, p)));
            } else {
                progress.setProgress(0);
            }
        } else {
            statusPhase.setText(LoopState.phase.length() > 0 ? LoopState.phase : "未开始");
            statusTime.setText("--:--");
            statusInfo.setText("当前媒体音量 "
                    + audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    + "/" + audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            progress.setProgress(0);
        }

        btnStart.setEnabled(!running);
        btnStop.setEnabled(running);
        updatePreview();
    }

    private void updatePreview() {
        int vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));

        // 步长按起始音量算一次，之后每轮固定减这个数
        int step = settings.fixedStep(vol);
        int possible = Math.max(1, (int) Math.ceil(vol / (double) step));
        int rounds = Math.min(Math.max(1, settings.cycles), possible);

        StringBuilder path = new StringBuilder("音量走向：");
        int v = vol;
        boolean zero = false;
        for (int i = 0; i < rounds; i++) {
            if (i > 0) {
                path.append(" → ");
            }
            path.append(v);
            v -= step;
            if (v <= 0) {
                path.append(" → 0");
                zero = true;
                break;
            }
        }
        path.append("（满格 ").append(max).append("）");

        int perCycle = settings.playMinutes + settings.silentMinutes;
        int totalMinutes = rounds * perCycle;

        StringBuilder t = new StringBuilder(path);

        t.append("\n每轮固定降 ").append(step).append(" 档（起始 ").append(vol)
                .append(" × ").append(settings.stepPercent).append("% = ")
                .append(oneDecimal(vol * settings.stepPercent / 100f))
                .append("，取整为 ").append(step).append("）");

        t.append("\n共 ").append(rounds).append(" 轮，约 ").append(totalMinutes).append(" 分钟");
        if (!zero) {
            t.append("；轮数用完时还剩 ").append(Math.max(0, v)).append(" 档，没归零。");
        } else if (rounds < settings.cycles) {
            t.append("；音量先归零，不用跑满轮数。");
        } else {
            t.append("。");
        }

        if (settings.totalCapMinutes > 0 && settings.totalCapMinutes < totalMinutes) {
            t.append("\n但总时长上限是 ").append(settings.totalCapMinutes).append(" 分钟，会先到上限。");
        }

        boolean noOut = settings.fadeOutSeconds <= 0;
        boolean noIn = settings.fadeInSeconds <= 0;
        if (noOut && noIn) {
            t.append("\n双向硬切：消失和恢复都是瞬间的。");
        } else if (noOut) {
            t.append("\n淡出瞬间，淡入 ").append(settings.fadeInSeconds).append(" 秒。");
        } else if (noIn) {
            t.append("\n淡出 ").append(settings.fadeOutSeconds).append(" 秒，淡入瞬间。");
        }

        preview.setText(t.toString());
    }

    /** 保留一位小数，整数就不带小数点。 */
    private static String oneDecimal(float value) {
        int tenths = Math.round(value * 10f);
        if (tenths % 10 == 0) {
            return String.valueOf(tenths / 10);
        }
        return (tenths / 10) + "." + Math.abs(tenths % 10);
    }

    private void startPolling() {
        if (polling) {
            return;
        }
        polling = true;
        ui.post(poller);
    }

    private void stopPolling() {
        polling = false;
        ui.removeCallbacks(poller);
    }

    // ---------------------------------------------------------------- 工具

    private interface IntSetter {
        void set(int v);
    }

    private void bindSeek(SeekBar bar, final TextView label,
                          final int min, final int max, int value,
                          final String unit, final String zeroText,
                          final IntSetter setter) {
        bar.setMax(max - min);
        bar.setProgress(value - min);
        label.setText(formatValue(value, unit, zeroText));

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) {
                int v = min + p;
                label.setText(formatValue(v, unit, zeroText));
                setter.set(v);
                updatePreview();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        setter.set(value);
    }

    private static String formatValue(int v, String unit, String zeroText) {
        if (v == 0 && zeroText != null) {
            return zeroText;
        }
        if ("%".equals(unit)) {
            return v + "%";
        }
        return v + " " + unit;
    }

    private static String fmtTime(long ms) {
        if (ms < 0L) {
            ms = 0L;
        }
        long totalSec = ms / 1000L;
        return String.format("%d:%02d", totalSec / 60L, totalSec % 60L);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
