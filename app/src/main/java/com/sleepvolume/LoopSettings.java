package com.sleepvolume;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 用户可调的六个数，存 SharedPreferences。
 */
public class LoopSettings {

    private static final String PF = "sleep_volume";

    public int playMinutes = 5;      // 单轮播放时长（分钟）
    public int silentMinutes = 5;    // 静默时长（分钟）
    public int stepPercent = 34;     // 每轮降多少，按「起始音量」的百分比算
    public int cycles = 6;           // 最多循环轮数
    public int fadeOutSeconds = 8;   // 淡出秒数，0 = 瞬间静音
    public int fadeInSeconds = 8;    // 淡入秒数，0 = 瞬间恢复
    public int totalCapMinutes = 0;  // 总时长上限（分钟），0 表示不限
    public boolean endSilent = true; // 正常结束后保持静音（否则恢复起始音量）

    /**
     * 固定步长：步长 = 起始音量 × 百分比，在启动那一刻算一次，之后每轮都减同一个数。
     *
     * 例：起始 40 档、34% → 步长 13.6 → 40 → 26 → 13 → 0，3 轮见底。
     *
     * Android 只认整数档，所以步长要取整；最少 1 档，否则小音量下永远降不下去。
     */
    public int fixedStep(int startVolume) {
        if (startVolume <= 0) {
            return 1;
        }
        int step = Math.round(startVolume * stepPercent / 100f);
        return Math.max(1, step);
    }

    public static LoopSettings load(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PF, Context.MODE_PRIVATE);
        LoopSettings s = new LoopSettings();

        // 老版本只有一个 fade 参数，升级后用它做两个新参数的初始值
        int legacyFade = sp.getInt("fade", -1);
        int defOut = legacyFade >= 0 ? legacyFade : s.fadeOutSeconds;
        int defIn = legacyFade >= 0 ? legacyFade : s.fadeInSeconds;

        s.playMinutes = clamp(sp.getInt("play", s.playMinutes), 1, 60);
        s.silentMinutes = clamp(sp.getInt("silent", s.silentMinutes), 1, 60);
        s.stepPercent = clamp(sp.getInt("step_percent", s.stepPercent), 1, 95);
        s.cycles = clamp(sp.getInt("cycles", s.cycles), 1, 30);
        s.fadeOutSeconds = clamp(sp.getInt("fade_out", defOut), 0, 60);
        s.fadeInSeconds = clamp(sp.getInt("fade_in", defIn), 0, 60);
        s.totalCapMinutes = clamp(sp.getInt("cap", s.totalCapMinutes), 0, 600);
        s.endSilent = sp.getBoolean("end_silent", true);
        return s;
    }

    public void save(Context c) {
        c.getSharedPreferences(PF, Context.MODE_PRIVATE).edit()
                .putInt("play", playMinutes)
                .putInt("silent", silentMinutes)
                .putInt("step_percent", stepPercent)
                .putInt("cycles", cycles)
                .putInt("fade_out", fadeOutSeconds)
                .putInt("fade_in", fadeInSeconds)
                .putInt("cap", totalCapMinutes)
                .putBoolean("end_silent", endSilent)
                .remove("fade")
                .remove("step")
                .remove("step_decay")
                .apply();
    }

    private static int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
