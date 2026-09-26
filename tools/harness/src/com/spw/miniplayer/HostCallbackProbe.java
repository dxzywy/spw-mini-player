package com.spw.miniplayer;

/**
 * 伪造宿主回调入口（仅用于验证，不属于插件本体）。
 *
 * <p>模拟宿主「在 AppConfig 静态初始化过程中回调插件」这一行为，
 * 对应真实宿主里插件注册的配置变更监听器 / 媒体回调。
 */
public final class HostCallbackProbe {

    /** 宿主是否已开始初始化 AppConfig。 */
    public static volatile boolean hostInitStarted;
    /** 宿主是否已完成 AppConfig 初始化。 */
    public static volatile boolean hostInitFinished;
    /** 初始化期间会触发的插件回调。 */
    public static volatile Runnable callback;
    /** 记录回调发生在哪个线程。 */
    public static volatile String callbackThread;

    private HostCallbackProbe() {
    }

    /** 由伪造 AppConfig 的静态初始化块调用。 */
    public static void duringHostInit() {
        callbackThread = Thread.currentThread().getName();
        Runnable current = callback;
        if (current != null) {
            current.run();
        }
    }
}
