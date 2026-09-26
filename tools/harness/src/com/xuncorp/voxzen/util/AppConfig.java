package com.xuncorp.voxzen.util;

/**
 * 宿主 AppConfig 的教学式替身（仅用于验证，不属于插件本体）。
 *
 * <p>刻意复刻真实宿主最关键的两个特征：
 * <ol>
 *   <li>Kotlin object 形态，实例放在静态字段 {@code INSTANCE}，
 *       因此「反射读取 INSTANCE」会触发/等待这个类的静态初始化；</li>
 *   <li>静态初始化耗时较长（真实宿主在这里创建近百个 spkv 状态），
 *       并且会<b>在初始化过程中回调插件</b>（配置变更通知、媒体回调等）。</li>
 * </ol>
 *
 * <p>这两点叠加，正是「插件跟随播放器一起启动才卡死」的必要条件。
 */
public final class AppConfig {

    public static final AppConfig INSTANCE;

    static {
        com.spw.miniplayer.HostCallbackProbe.hostInitStarted = true;
        // 模拟宿主漫长的初始化过程，放大并发窗口
        try {
            Thread.sleep(400);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        // 模拟宿主在初始化期间回调插件（这里是插件的配置变更监听器）
        com.spw.miniplayer.HostCallbackProbe.duringHostInit();
        INSTANCE = new AppConfig();
        com.spw.miniplayer.HostCallbackProbe.hostInitFinished = true;
    }

    private boolean desktopLyrics = true;

    public boolean getDesktopLyrics() {
        return desktopLyrics;
    }

    public void setDesktopLyrics(boolean value) {
        desktopLyrics = value;
    }

    /** 模拟 LightDarkTheme 枚举。 */
    public Object getLightDarkTheme() {
        return LightDarkTheme.Dark;
    }

    public enum LightDarkTheme {
        Dark, FollowSystem, Light
    }
}
