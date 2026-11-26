package com.sleepvolume;

/**
 * 界面和服务之间传递的运行时状态。字段都是 volatile，界面只读。
 */
public class LoopState {

    public static volatile boolean running = false;

    /** 当前阶段名，例如「播放中」「静默」「淡入」。 */
    public static volatile String phase = "";

    /** 当前阶段剩余毫秒。 */
    public static volatile long remainMs = 0L;

    /** 当前阶段总时长，用于画进度条。 */
    public static volatile long phaseDurationMs = 0L;

    public static volatile int cycle = 0;
    public static volatile int totalCycles = 0;
    public static volatile int volume = -1;
    public static volatile int maxVolume = -1;

    private LoopState() {
    }
}
