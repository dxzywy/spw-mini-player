package com.spw.miniplayer;

import javax.swing.SwingUtilities;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 宿主交互工作线程。
 *
 * <p>插件对宿主的所有「重」交互都在这条唯一的守护线程上串行执行：
 * <ul>
 *   <li>宿主类反射解析（{@code Class.forName} + 读取 {@code AppConfig.INSTANCE}）</li>
 *   <li>宿主 API 调用（配置读写、播放控制、桌面歌词、toast）</li>
 * </ul>
 *
 * <p>这样安排的原因是一个真实存在的死锁：
 * 宿主启动时会同步初始化 {@code AppConfig}（Kotlin object，静态初始化里创建近百个
 * spkv 状态），而<b>反射读取它的静态字段会触发/等待这个类的初始化</b>。
 * 如果插件在持有自身锁的情况下做这件事，同时宿主又在 {@code AppConfig.<clinit>}
 * 内回调插件（配置变更通知、媒体回调），双方就会交叉等待形成永久死锁；
 * 在 EDT 上做这件事同样危险（EDT 被初始化流程卡住，整个界面冻结）。
 *
 * <p>因此这里的铁律是：
 * <b>绝不在 EDT 或宿主回调线程上触发宿主类初始化，也绝不在持锁状态下加载宿主类。</b>
 */
final class HostBridgeWorker {

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "spw-mini-host");
        thread.setDaemon(true);
        return thread;
    });

    private static final AtomicBoolean PREPARED = new AtomicBoolean();
    /** 尚未成功对接时的重试间隔。 */
    private static final long RETRY_INTERVAL_MS = 15_000L;
    private static final long RETRY_WINDOW_MS = 120_000L;

    private static volatile long startedAt;
    private static volatile long nextRetryAt;
    private static volatile boolean retryScheduled;

    private HostBridgeWorker() {
    }

    /** 是否已经跑过一次解析。 */
    static boolean isPrepared() {
        return PREPARED.get();
    }

    /**
     * 解析宿主桥接（幂等）。完成后在 EDT 回调 {@code onDone}，可传 null。
     */
    static void prepare(Runnable onDone) {
        if (startedAt == 0) {
            startedAt = System.currentTimeMillis();
        }
        WORKER.execute(() -> {
            try {
                DesktopLyricsBridge.resolveNow();
                HostThemeBridge.resolveNow();
                MainWindowBridge.resolveNow();
            } catch (Throwable error) {
                PluginLog.w("解析宿主桥接失败: " + error);
            }
            PREPARED.set(true);
            if (onDone != null) {
                SwingUtilities.invokeLater(onDone);
            }
            scheduleRetryIfNeeded();
        });
    }

    /** 在专用线程上执行宿主调用（配置读写、播放控制、宿主 API）。 */
    static void submit(Runnable task) {
        try {
            WORKER.execute(() -> {
                try {
                    task.run();
                } catch (Throwable error) {
                    PluginLog.w("宿主调用失败: " + error);
                }
            });
        } catch (Throwable error) {
            PluginLog.w("提交宿主调用失败: " + error);
        }
    }

    /**
     * 若某个桥接没对接上（例如宿主还没初始化完就被我们撞上），
     * 定时重试一段时间，避免「一次失败永久降级」。
     */
    private static void scheduleRetryIfNeeded() {
        boolean bothReady = DesktopLyricsBridge.isAvailable()
                && HostThemeBridge.isAvailable()
                && MainWindowBridge.isAvailable();
        if (bothReady || retryScheduled) {
            return;
        }
        if (System.currentTimeMillis() - startedAt > RETRY_WINDOW_MS) {
            return;
        }
        retryScheduled = true;
        long delay = Math.max(1000L, nextRetryAt - System.currentTimeMillis());
        nextRetryAt = System.currentTimeMillis() + RETRY_INTERVAL_MS;
        javax.swing.Timer timer = new javax.swing.Timer((int) delay, event -> {
            ((javax.swing.Timer) event.getSource()).stop();
            retryScheduled = false;
            prepare(null);
        });
        timer.setRepeats(false);
        timer.start();
    }

    /** 供 MainKotlin/诊断使用的队列信息。 */
    static String describe() {
        return "prepared=" + PREPARED.get()
                + ", lyrics=" + DesktopLyricsBridge.isAvailable()
                + ", theme=" + HostThemeBridge.isAvailable()
                + ", mainWindow=" + MainWindowBridge.isAvailable();
    }
}
