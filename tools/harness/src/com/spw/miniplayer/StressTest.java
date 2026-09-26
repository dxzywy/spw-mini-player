package com.spw.miniplayer;

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint;

import javax.swing.SwingUtilities;
import java.awt.event.MouseEvent;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 展开/收起压力测试（不属于插件本体）。
 *
 * <p>反复执行「收起 → 在封面小窗上单击展开」这一真实操作序列，
 * 同时用后台线程模拟宿主的播放回调（每秒进度、换曲、播放状态）。
 *
 * <p>看门狗线程监控主循环心跳：一旦心跳停滞（说明 EDT 被卡住导致
 * invokeAndWait 无法返回），立刻导出全部线程堆栈，用于定位卡死根因。
 */
public final class StressTest {

    private static final AtomicLong HEARTBEAT = new AtomicLong();
    private static final AtomicBoolean HUNG = new AtomicBoolean();
    private static volatile String phase = "init";
    private static volatile boolean stopCallbacks;

    private static long slowestInvokeNs;
    private static long slowestRoundNs;

    public static void main(String[] args) throws Exception {
        int iterations = args.length > 0 ? Integer.parseInt(args[0]) : 120;
        Path workspace = Paths.get(args.length > 1 ? args[1] : "build/harness");
        Path out = workspace.resolve("stress");
        Files.createDirectories(out);
        Path cover = workspace.resolve("media/embedded.mp3");

        startWatchdog(out);

        MiniWindow[] holder = new MiniWindow[1];
        MiniPlayerController[] controllerHolder = new MiniPlayerController[1];

        SwingUtilities.invokeAndWait(() -> {
            MiniPlayerController controller = new MiniPlayerController(null);
            MiniWindow window = new MiniWindow(controller);
            controllerHolder[0] = controller;
            holder[0] = window;
            window.setDefaultEdge(MiniWindow.Edge.RIGHT);
            window.setAutoHide(true);
            window.setHideDelay(150);
            MiniPlayerView view = window.view();
            wireController(controller, window);
            view.setNowPlaying("Someone Like You (Live at the Royal Albert Hall 2011)",
                    "Adele · 21 (Deluxe Edition)");
            view.setCover(CoverArtLoader.loadAndScale(cover.toString(), 128));
            view.setPlaying(true);
            view.setLyrics(true, true);
            view.setProgress(134_000, 300_000);
            window.show(true);
        });

        MiniWindow window = holder[0];
        MiniPlayerController controller = controllerHolder[0];
        if (window == null || controller == null) {
            System.out.println("窗口创建失败");
            System.exit(1);
        }

        startHostCallbacks(controller, cover.toString());

        long started = System.currentTimeMillis();
        for (int i = 1; i <= iterations; i++) {
            long roundStart = System.nanoTime();

            phase = "collapse";
            invokeAndWaitMeasured(window::collapse);
            Thread.sleep(260);

            phase = "click-to-expand";
            invokeAndWaitMeasured(() -> clickBubble(window));
            Thread.sleep(320);

            long roundNs = System.nanoTime() - roundStart;
            if (roundNs > slowestRoundNs) {
                slowestRoundNs = roundNs;
            }
            HEARTBEAT.incrementAndGet();
            if (i % 10 == 0) {
                System.out.println("  第 " + i + " 轮完成，最慢单轮 "
                        + (slowestRoundNs / 1_000_000) + " ms，最慢 EDT 调度 "
                        + (slowestInvokeNs / 1_000_000) + " ms");
            }
        }

        stopCallbacks = true;
        long elapsed = System.currentTimeMillis() - started;
        System.out.println();
        System.out.println("压力测试完成：" + iterations + " 轮，用时 " + elapsed + " ms");
        System.out.println("  最慢单轮: " + (slowestRoundNs / 1_000_000) + " ms");
        System.out.println("  最慢 EDT 调度: " + (slowestInvokeNs / 1_000_000) + " ms");

        if (slowestInvokeNs > 500_000_000L) {
            System.out.println("  ⚠ EDT 曾出现超过 500ms 的阻塞，疑似卡顿");
        }

        SwingUtilities.invokeAndWait(window::dispose);
        System.exit(0);
    }

    /** 把控制器接到真实窗口上（复刻 createUi 的内部接线）。 */
    private static void wireController(MiniPlayerController controller, MiniWindow window) {
        try {
            Field viewField = MiniPlayerController.class.getDeclaredField("view");
            viewField.setAccessible(true);
            viewField.set(controller, window.view());
            Field windowField = MiniPlayerController.class.getDeclaredField("window");
            windowField.setAccessible(true);
            windowField.set(controller, window);
            Field startedField = MiniPlayerController.class.getDeclaredField("started");
            startedField.setAccessible(true);
            ((AtomicBoolean) startedField.get(controller)).set(true);
        } catch (Throwable error) {
            System.out.println("接线失败: " + error);
        }
    }

    /** 模拟宿主在各自线程上回调插件。 */
    private static void startHostCallbacks(MiniPlayerController controller, String path) {
        Thread positionThread = new Thread(() -> {
            long position = 0;
            while (!stopCallbacks) {
                controller.onPositionUpdated(position += 1000);
                sleepQuiet(50);
            }
        }, "host-position");
        positionThread.setDaemon(true);
        positionThread.start();

        Thread mediaThread = new Thread(() -> {
            long index = 0;
            while (!stopCallbacks) {
                long n = index++;
                controller.onMediaItem(new PlaybackExtensionPoint.MediaItem(
                        "曲目 " + n + " —— 一个足够长的测试标题用于触发跑马灯",
                        "歌手 " + n, "专辑 " + n, "专辑艺术家", path));
                controller.onPlayingChanged(n % 2 == 0);
                controller.onStateChanged(n % 3 == 0
                        ? PlaybackExtensionPoint.State.Buffering
                        : PlaybackExtensionPoint.State.Ready);
                sleepQuiet(180);
            }
        }, "host-media");
        mediaThread.setDaemon(true);
        mediaThread.start();
    }

    /** 在封面小窗上模拟「悬停 + 单击展开」。 */
    private static void clickBubble(MiniWindow window) {
        MiniPlayerView view = window.view();
        int x = Math.max(2, view.getWidth() / 2);
        int y = Math.max(2, view.getHeight() / 2 - 6);
        long when = System.currentTimeMillis();
        dispatch(view, new MouseEvent(view, MouseEvent.MOUSE_ENTERED, when, 0, x, y, 0,
                false, MouseEvent.BUTTON1));
        dispatch(view, new MouseEvent(view, MouseEvent.MOUSE_MOVED, when, 0, x, y, 0,
                false, MouseEvent.BUTTON1));
        dispatch(view, new MouseEvent(view, MouseEvent.MOUSE_PRESSED, when, 0, x, y, 1,
                false, MouseEvent.BUTTON1));
        dispatch(view, new MouseEvent(view, MouseEvent.MOUSE_RELEASED, when + 30, 0, x, y, 1,
                false, MouseEvent.BUTTON1));
    }

    private static void dispatch(MiniPlayerView view, MouseEvent event) {
        try {
            view.dispatchEvent(event);
        } catch (Throwable error) {
            System.out.println("事件派发异常 (" + event.getID() + "): " + error);
        }
    }

    private static void invokeAndWaitMeasured(Runnable runnable) {
        long start = System.nanoTime();
        try {
            SwingUtilities.invokeAndWait(runnable);
        } catch (Throwable error) {
            System.out.println("invokeAndWait 失败: " + error);
        }
        long cost = System.nanoTime() - start;
        if (cost > slowestInvokeNs) {
            slowestInvokeNs = cost;
        }
    }

    private static void startWatchdog(Path out) {
        Thread watchdog = new Thread(() -> {
            long last = HEARTBEAT.get();
            int stuck = 0;
            while (true) {
                sleepQuiet(1000);
                long now = HEARTBEAT.get();
                if (now == last) {
                    stuck++;
                    if (stuck >= 5 && HUNG.compareAndSet(false, true)) {
                        System.out.println();
                        System.out.println("!!! 检测到卡死：阶段=" + phase + "，心跳停滞 "
                                + stuck + " 秒，导出线程堆栈");
                        dumpStacks(out);
                        Runtime.getRuntime().halt(3);
                    }
                } else {
                    stuck = 0;
                    last = now;
                }
            }
        }, "watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static void dumpStacks(Path out) {
        StringBuilder builder = new StringBuilder();
        builder.append("阶段: ").append(phase).append('\n');
        Map<Thread, StackTraceElement[]> traces = Thread.getAllStackTraces();
        for (Map.Entry<Thread, StackTraceElement[]> entry : traces.entrySet()) {
            Thread thread = entry.getKey();
            builder.append('\n').append('"').append(thread.getName()).append('"')
                    .append(" state=").append(thread.getState())
                    .append(" daemon=").append(thread.isDaemon()).append('\n');
            for (StackTraceElement element : entry.getValue()) {
                builder.append("    at ").append(element).append('\n');
            }
        }
        String text = builder.toString();
        System.out.println(text);
        try {
            Path target = out.resolve("hang-" + System.currentTimeMillis() + ".txt");
            Files.write(target, text.getBytes(StandardCharsets.UTF_8));
            System.out.println("堆栈已写入 " + target);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static void sleepQuiet(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
