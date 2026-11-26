package com.sleepvolume;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * 只做一件事：按「播放一段时间 → 静默一段时间 → 更轻地回来」的节奏，
 * 反复调低系统媒体音量，直到轮数用完、总时长到顶、或者音量降到 0。
 *
 * 它不碰任何播放器，所以对 B 站、腾讯视频、酷狗、网易云都一视同仁。
 */
public class SleepLoopService extends Service {

    public static final String ACTION_START = "com.sleepvolume.action.START";
    public static final String ACTION_STOP = "com.sleepvolume.action.STOP";

    private static final String CHANNEL_ID = "sleep_loop";
    private static final int NOTIFICATION_ID = 1101;

    private static final int PH_IDLE = 0;
    private static final int PH_PLAY = 1;
    private static final int PH_FADE_OUT = 2;
    private static final int PH_SILENT = 3;
    private static final int PH_FADE_IN = 4;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private AudioManager audio;
    private PowerManager.WakeLock wakeLock;

    private LoopSettings settings;
    private int maxVol = 15;
    private int startVol = 0;
    private int curVol = 0;
    private int stepFixed = 1;
    private int cycleNo = 1;

    private int phase = PH_IDLE;
    private long phaseStartedAt = 0L;
    private long phaseDurationMs = 0L;
    private long phaseEndAt = 0L;
    private int fadeFrom = 0;
    private int fadeTo = 0;
    private long loopStartedAt = 0L;

    private boolean running = false;
    private long lastNotifyAt = 0L;

    // ---------------------------------------------------------------- 生命周期

    @Override
    public void onCreate() {
        super.onCreate();
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        createChannel();

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepVolume:loop");
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopLoop(true);
            return START_NOT_STICKY;
        }

        // 先挂上前台通知，满足 startForegroundService 的 5 秒限制
        startForeground(NOTIFICATION_ID, buildNotification("准备中…"));

        if (!running) {
            startLoop();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(ticker);
        releaseWakeLock();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 主流程

    private void startLoop() {
        settings = LoopSettings.load(this);

        maxVol = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        startVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (startVol <= 0) {
            LoopState.running = false;
            LoopState.phase = "媒体音量为 0，没有可降低的空间";
            LoopState.remainMs = 0L;
            LoopState.phaseDurationMs = 0L;
            stopForeground(true);
            stopSelf();
            return;
        }

        curVol = startVol;
        // 步长只在这里算一次，之后每轮减同一个数
        stepFixed = settings.fixedStep(startVol);
        cycleNo = 1;
        running = true;
        loopStartedAt = SystemClock.elapsedRealtime();

        acquireWakeLock();

        LoopState.running = true;
        LoopState.totalCycles = settings.cycles;
        LoopState.maxVolume = maxVol;
        LoopState.volume = startVol;
        LoopState.cycle = 1;

        applyVol(curVol);
        startPhase(PH_PLAY, 0, 0, settings.playMinutes * 60_000L);

        handler.removeCallbacks(ticker);
        handler.post(ticker);
        updateNotification(true);
    }

    /** 每 120~400ms 跑一次：推进淡入淡出、刷新状态、到点切阶段。 */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            long now = SystemClock.elapsedRealtime();

            if (phase == PH_FADE_OUT || phase == PH_FADE_IN) {
                float t = phaseDurationMs <= 0L
                        ? 1f
                        : (now - phaseStartedAt) / (float) phaseDurationMs;
                if (t < 0f) t = 0f;
                if (t > 1f) t = 1f;
                applyVol(Math.round(fadeFrom + (fadeTo - fadeFrom) * t));
            }

            long remain = phaseEndAt - now;
            if (remain <= 0L) {
                step();
                return;
            }

            LoopState.phase = phaseName();
            LoopState.remainMs = remain;
            LoopState.phaseDurationMs = phaseDurationMs;
            LoopState.cycle = cycleNo;
            LoopState.totalCycles = settings.cycles;
            LoopState.volume = currentVolume();
            LoopState.maxVolume = maxVol;

            updateNotification(false);
            long delay = (phase == PH_FADE_OUT || phase == PH_FADE_IN) ? 120L : 400L;
            handler.postDelayed(this, delay);
        }
    };

    /** 当前阶段结束，进入下一阶段。 */
    private void step() {
        switch (phase) {
            case PH_PLAY: {
                if (settings.fadeOutSeconds > 0) {
                    startPhase(PH_FADE_OUT, curVol, 0, settings.fadeOutSeconds * 1000L);
                } else {
                    applyVol(0);
                    startPhase(PH_SILENT, 0, 0, settings.silentMinutes * 60_000L);
                }
                break;
            }
            case PH_FADE_OUT: {
                applyVol(0);
                startPhase(PH_SILENT, 0, 0, settings.silentMinutes * 60_000L);
                break;
            }
            case PH_SILENT: {
                afterSilence();
                return;
            }
            case PH_FADE_IN: {
                applyVol(curVol);
                startPhase(PH_PLAY, 0, 0, settings.playMinutes * 60_000L);
                break;
            }
            default:
                return;
        }
        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, 30L);
    }

    /** 静默结束：决定是再来一轮，还是收工。 */
    private void afterSilence() {
        long elapsed = SystemClock.elapsedRealtime() - loopStartedAt;
        long capMs = settings.totalCapMinutes * 60_000L;

        if (capMs > 0L && elapsed >= capMs) {
            finish("已到总时长上限");
            return;
        }
        if (cycleNo >= settings.cycles) {
            finish("已完成设定轮数");
            return;
        }

        int next = curVol - stepFixed;
        if (next <= 0) {
            finish("音量已降到最低");
            return;
        }

        curVol = next;
        cycleNo++;

        if (settings.fadeInSeconds > 0) {
            startPhase(PH_FADE_IN, 0, curVol, settings.fadeInSeconds * 1000L);
        } else {
            applyVol(curVol);
            startPhase(PH_PLAY, 0, 0, settings.playMinutes * 60_000L);
        }

        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, 30L);
    }

    private void startPhase(int newPhase, int from, int to, long durationMs) {
        phase = newPhase;
        fadeFrom = from;
        fadeTo = to;
        phaseStartedAt = SystemClock.elapsedRealtime();
        phaseDurationMs = Math.max(1L, durationMs);
        phaseEndAt = phaseStartedAt + phaseDurationMs;

        LoopState.phase = phaseName();
        LoopState.remainMs = phaseDurationMs;
        LoopState.phaseDurationMs = phaseDurationMs;
        LoopState.cycle = cycleNo;
        LoopState.totalCycles = settings.cycles;
    }

    private String phaseName() {
        switch (phase) {
            case PH_PLAY:
                return "播放中";
            case PH_FADE_OUT:
                return "淡出";
            case PH_SILENT:
                return "静默";
            case PH_FADE_IN:
                return "淡入";
            default:
                return "待机";
        }
    }

    /** 正常跑完。 */
    private void finish(String reason) {
        boolean wasRunning = running;
        running = false;
        handler.removeCallbacks(ticker);

        if (settings != null && !settings.endSilent) {
            applyVol(startVol);
        } else {
            applyVol(0);
        }

        releaseWakeLock();

        LoopState.running = false;
        LoopState.phase = wasRunning ? ("已结束：" + reason) : LoopState.phase;
        LoopState.remainMs = 0L;
        LoopState.phaseDurationMs = 0L;

        stopForeground(true);
        stopSelf();
    }

    /** 用户手动停止：恢复开始前的音量。 */
    private void stopLoop(boolean restoreVolume) {
        running = false;
        handler.removeCallbacks(ticker);

        if (restoreVolume) {
            int v = startVol > 0 ? startVol : currentVolume();
            applyVol(v);
        }

        releaseWakeLock();

        LoopState.running = false;
        LoopState.phase = "已停止";
        LoopState.remainMs = 0L;
        LoopState.phaseDurationMs = 0L;

        stopForeground(true);
        stopSelf();
    }

    // ---------------------------------------------------------------- 音量

    private int currentVolume() {
        if (audio == null) {
            return 0;
        }
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC);
    }

    private void applyVol(int v) {
        if (audio == null) {
            return;
        }
        if (v < 0) v = 0;
        if (v > maxVol) v = maxVol;
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
        } catch (Exception ignored) {
            // 勿扰模式等情况下可能被系统拒绝，忽略即可，下一拍会再试
        }
    }

    // ---------------------------------------------------------------- 通知

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "睡眠音量循环", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setSound(null, null);
        ch.enableVibration(false);
        nm.createNotificationChannel(ch);
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent piOpen = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, SleepLoopService.class).setAction(ACTION_STOP);
        PendingIntent piStop = PendingIntent.getService(
                this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
            b.setPriority(Notification.PRIORITY_LOW);
        }

        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("睡眠音量循环")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(piOpen)
                .addAction(0, "停止", piStop);

        return b.build();
    }

    private void updateNotification(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastNotifyAt < 1500L) {
            return;
        }
        lastNotifyAt = now;

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }

        String text;
        if (running) {
            text = phaseName()
                    + " · 第 " + cycleNo + "/" + settings.cycles + " 轮"
                    + " · 剩 " + fmtTime(phaseEndAt - now)
                    + " · 音量 " + currentVolume() + "/" + maxVol;
        } else {
            text = LoopState.phase;
        }

        try {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        } catch (Exception ignored) {
        }
    }

    private static String fmtTime(long ms) {
        if (ms < 0L) ms = 0L;
        long totalSec = ms / 1000L;
        return String.format("%d:%02d", totalSec / 60L, totalSec % 60L);
    }

    // ---------------------------------------------------------------- 唤醒锁

    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }
}
