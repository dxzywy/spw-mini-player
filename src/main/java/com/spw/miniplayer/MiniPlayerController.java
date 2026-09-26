package com.spw.miniplayer;

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint;
import com.xuncorp.spw.workshop.api.PluginContext;
import com.xuncorp.spw.workshop.api.WorkshopApi;
import com.xuncorp.spw.workshop.api.config.ConfigManager;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Image;
import java.awt.Rectangle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 迷你播放器的状态中枢。
 *
 * <p>线程约定（很重要）：
 * <ul>
 *   <li>插件 {@code start()} 可能运行在宿主启动线程或 EDT 上，因此**不在其中做任何
 *       宿主 API 调用、宿主类反射或磁盘 I/O**，只登记状态并投递到 EDT 延后引导。</li>
 *   <li>所有宿主调用（配置、提示、桌面歌词、主题反射）与 Swing 操作都收敛到 EDT，
 *       避免与宿主启动期/回调线程形成交叉持锁。</li>
 *   <li>配置落盘与音频探测放到后台线程，EDT 不做 I/O。</li>
 * </ul>
 */
final class MiniPlayerController {

    /** 封面按 2 倍尺寸解码，保证高 DPI 下依旧清晰。 */
    private static final int COVER_PIXELS = 128;
    /** 引导延迟：避开宿主启动最繁忙的阶段再创建窗口。 */
    private static final int BOOTSTRAP_DELAY_MS = 400;
    /** 自动收起延迟默认值（秒）与下限（秒）。 */
    private static final double DEFAULT_HIDE_DELAY_SECONDS = 0.5;
    private static final double MIN_HIDE_DELAY_SECONDS = 0.05;

    private final PluginContext pluginContext;
    private final AtomicBoolean started = new AtomicBoolean();

    private volatile PluginConfig config;
    private volatile ConfigManager configManager;

    private volatile MiniWindow window;
    private volatile MiniPlayerView view;

    // 当前曲目状态（任意线程写入，EDT 读取）
    private volatile String title = "";
    private volatile String artist = "";
    private volatile String album = "";
    private volatile String coverPath = "";
    private volatile long durationMs = -1;
    private volatile long positionMs;
    private volatile boolean playing;

    private final AtomicInteger lyricsSyncTick = new AtomicInteger();
    private final AtomicInteger themeSyncTick = new AtomicInteger();

    /** 配置落盘：后台线程 + 去抖，绝不在 EDT 写文件。 */
    private final ExecutorService persistWorker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "spw-mini-persist");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean persistScheduled = new AtomicBoolean();
    private final AtomicBoolean persistDirty = new AtomicBoolean();
    private final AtomicReference<String> pendingEdge = new AtomicReference<>();
    private final AtomicReference<Rectangle> pendingBounds = new AtomicReference<>();

    /** 配置变更去抖定时器（EDT）。 */
    private Timer configApplyTimer;

    MiniPlayerController(PluginContext pluginContext) {
        this.pluginContext = pluginContext;
    }

    PluginContext pluginContext() {
        return pluginContext;
    }

    // -------------------------------------------------------------- 生命周期

    /**
     * 由插件主类调用。这里刻意保持极轻量：
     * 不触碰宿主 API、不加载宿主类、不做 I/O，只投递一次 EDT 引导。
     */
    void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        PluginLog.i("迷你播放器启动，插件路径: " + describePath());
        startEdtWatchdog();
        if (SwingUtilities.isEventDispatchThread()) {
            scheduleBootstrap();
        } else {
            SwingUtilities.invokeLater(this::scheduleBootstrap);
        }
    }

    private String describePath() {
        try {
            return pluginContext == null ? "(未知)" : pluginContext.getPluginPath();
        } catch (Throwable error) {
            return "(读取失败)";
        }
    }

    /** 再延后一小段时间创建窗口，避开宿主启动阶段。 */
    private void scheduleBootstrap() {
        if (!started.get()) {
            return;
        }
        Timer delay = new Timer(BOOTSTRAP_DELAY_MS, event -> {
            ((Timer) event.getSource()).stop();
            bootstrapOnEdt();
        });
        delay.setRepeats(false);
        delay.start();
    }

    /** 全部在 EDT 上执行：宿主调用、配置读取、窗口创建。 */
    private void bootstrapOnEdt() {
        if (!started.get() || window != null) {
            return;
        }
        ConfigManager manager = null;
        try {
            manager = WorkshopApi.manager().createConfigManager();
        } catch (Throwable error) {
            PluginLog.w("无法创建配置管理器: " + error);
        }
        try {
            config = PluginConfig.open(manager);
        } catch (Throwable error) {
            PluginLog.w("初始化配置失败: " + error);
            config = PluginConfig.unavailable();
        }
        registerConfigListener(manager);
        createUi();
        /*
         * 宿主桥接（桌面歌词 / 主题）放到专用工作线程异步解析：
         * 反射读取宿主 AppConfig 的静态字段会触发这个类的静态初始化，
         * 而宿主启动时可能正由别的线程在做同一件事。放在这里解析既不会卡 EDT，
         * 也不会与宿主初始化线程交叉持锁。
         */
        HostBridgeWorker.prepare(this::refreshHostState);
        toast("迷你播放器已就绪", WorkshopApi.Ui.ToastType.Success);
    }

    /** 宿主桥接就绪后在 EDT 上刷新一次界面状态。 */
    private void refreshHostState() {
        refreshLyricsState();
        refreshTheme();
        if (!DesktopLyricsBridge.isAvailable()) {
            PluginLog.w("未能对接宿主桌面歌词开关，歌词按钮显示为禁用");
        }
    }

    void stop() {
        started.set(false);
        MiniWindow target = window;
        window = null;
        view = null;
        if (target != null) {
            if (SwingUtilities.isEventDispatchThread()) {
                target.dispose();
            } else {
                SwingUtilities.invokeLater(target::dispose);
            }
        }
        persistWorker.shutdownNow();
    }

    /** 供配置界面按钮调用：显示 / 隐藏小窗。 */
    void toggleVisible() {
        onEdt(() -> {
            if (window == null) {
                bootstrapOnEdt();
                return;
            }
            window.toggle();
            boolean shown = window.isShown();
            toast(shown ? "迷你播放器已显示" : "迷你播放器已隐藏",
                    shown ? WorkshopApi.Ui.ToastType.Success : WorkshopApi.Ui.ToastType.Warning);
        });
    }

    /** 供配置界面按钮调用：收起为封面悬浮窗。 */
    void collapseToBubble() {
        onEdt(() -> {
            if (window == null) {
                bootstrapOnEdt();
                return;
            }
            if (!window.isShown()) {
                window.show(true);
            }
            window.collapse();
        });
    }

    private void createUi() {
        if (window != null || !started.get()) {
            return;
        }
        // 诊断开关：设置 -Dspw.miniplayer.noWindow=true 可只加载插件而不显示窗口
        if (Boolean.getBoolean("spw.miniplayer.noWindow")) {
            PluginLog.i("已通过 -Dspw.miniplayer.noWindow 禁用小窗显示");
            return;
        }
        MiniWindow created = new MiniWindow(this);
        MiniPlayerView targetView = created.view();

        String edgeKey = config.getString("last_edge", config.getString("dock_edge", "right"));
        created.setAutoHide(config.getBoolean("auto_hide", true));
        created.setHideDelay(readHideDelayMs());
        created.setDefaultEdge(parseEdge(edgeKey));
        created.setAlwaysOnTop(config.getBoolean("always_on_top", true));
        int lastX = config.getInt("last_x", Integer.MIN_VALUE);
        int lastY = config.getInt("last_y", Integer.MIN_VALUE);
        if (lastX != Integer.MIN_VALUE && lastY != Integer.MIN_VALUE) {
            created.setInitialPosition(lastX, lastY);
        }
        targetView.setPanelAlpha(alphaFrom(config.getInt("opacity", 80)));
        targetView.setDarkTheme(HostThemeBridge.isDark());

        window = created;
        view = targetView;
        pushStateToView();
        created.show(true);
        PluginLog.i("小窗已创建：" + describeEffectiveConfig(created));
    }

    // ------------------------------------------------ PlaybackExtensionPoint

    void onMediaItem(PlaybackExtensionPoint.MediaItem item) {
        if (item == null) {
            return;
        }
        String newPath = safe(item.getPath());
        boolean trackChanged = !newPath.equals(coverPath);
        title = safe(item.getTitle());
        artist = safe(item.getArtist());
        album = safe(item.getAlbum());
        if (trackChanged) {
            coverPath = newPath;
            durationMs = -1;
            positionMs = 0;
        }
        if (!newPath.isEmpty()) {
            CoverArtLoader.request(newPath, COVER_PIXELS, this::onCoverReady);
            if (trackChanged) {
                Thread probe = new Thread(() -> {
                    long duration = MediaProbe.durationMs(newPath);
                    if (duration > 0) {
                        durationMs = duration;
                        SwingUtilities.invokeLater(this::pushProgress);
                    }
                }, "spw-mini-probe");
                probe.setDaemon(true);
                probe.setPriority(Thread.MIN_PRIORITY);
                probe.start();
            }
        } else {
            onCoverReady(null);
        }
        SwingUtilities.invokeLater(this::pushStateToView);
    }

    void onPlayingChanged(boolean isPlaying) {
        playing = isPlaying;
        SwingUtilities.invokeLater(() -> {
            MiniPlayerView target = view;
            if (target != null) {
                target.setPlaying(isPlaying);
            }
        });
    }

    void onStateChanged(PlaybackExtensionPoint.State state) {
        if (state == PlaybackExtensionPoint.State.Idle
                || state == PlaybackExtensionPoint.State.Ended) {
            playing = false;
        } else {
            playing = true;
        }
        onPlayingChanged(playing);
    }

    /**
     * 每秒回调一次。这里**只写状态**，宿主反射与界面同步都投递到 EDT 或按节奏抽样，
     * 避免在宿主回调线程上加载/调用宿主类。
     */
    void onPositionUpdated(long position) {
        positionMs = position;
        // 桌面歌词状态每 4 秒核对一次，主题每 6 秒核对一次
        if (lyricsSyncTick.incrementAndGet() % 4 == 0) {
            onEdt(this::refreshLyricsState);
        }
        if (themeSyncTick.incrementAndGet() % 6 == 0) {
            onEdt(this::refreshTheme);
        }
        SwingUtilities.invokeLater(this::pushProgress);
    }

    private void onCoverReady(Image image) {
        onEdt(() -> {
            MiniPlayerView target = view;
            if (target != null) {
                target.setCover(image);
            }
        });
    }

    // ------------------------------------------------------------ 界面动作

    void onButton(MiniPlayerView.Hit hit) {
        if (hit == null) {
            return;
        }
        switch (hit) {
            case TOGGLE_PLAY:
                togglePlay();
                break;
            case PREVIOUS:
                runPlayback("切换上一首", () -> WorkshopApi.playback().previous());
                break;
            case NEXT:
                runPlayback("切换下一首", () -> WorkshopApi.playback().next());
                break;
            case TOGGLE_LYRICS:
                toggleDesktopLyrics();
                break;
            case COLLAPSE:
                collapseWindow();
                break;
            case EXPAND:
                expandWindow();
                break;
            case CLOSE:
                hideWindow();
                break;
            default:
                break;
        }
    }

    private void collapseWindow() {
        MiniWindow target = window;
        if (target != null) {
            target.collapse();
        }
    }

    private void expandWindow() {
        MiniWindow target = window;
        if (target != null) {
            target.expand();
        }
    }

    private void togglePlay() {
        boolean shouldPlay = !playing;
        runPlayback(shouldPlay ? "继续播放" : "暂停播放", () -> {
            if (shouldPlay) {
                WorkshopApi.playback().play();
            } else {
                WorkshopApi.playback().pause();
            }
        });
        playing = shouldPlay;
        MiniPlayerView target = view;
        if (target != null) {
            target.setPlaying(playing);
        }
    }

    private void toggleDesktopLyrics() {
        if (!DesktopLyricsBridge.isAvailable()) {
            toast("当前宿主版本不支持通过插件切换桌面歌词",
                    WorkshopApi.Ui.ToastType.Warning);
            refreshLyricsState();
            return;
        }
        HostBridgeWorker.submit(() -> {
            Boolean next = DesktopLyricsBridge.toggle();
            if (next == null) {
                toast("桌面歌词切换失败", WorkshopApi.Ui.ToastType.Error);
            } else {
                toast(next ? "桌面歌词已开启" : "桌面歌词已关闭",
                        next ? WorkshopApi.Ui.ToastType.Success
                                : WorkshopApi.Ui.ToastType.Warning);
            }
            onEdt(this::refreshLyricsState);
        });
    }

    /** 仅在 EDT 调用：读取宿主桌面歌词状态并刷新按钮。 */
    void refreshLyricsState() {
        if (!SwingUtilities.isEventDispatchThread()) {
            onEdt(this::refreshLyricsState);
            return;
        }
        boolean available = DesktopLyricsBridge.isAvailable();
        Boolean enabled = DesktopLyricsBridge.isEnabled();
        MiniPlayerView target = view;
        if (target != null) {
            target.setLyrics(enabled != null && enabled, available);
        }
    }

    /** 仅在 EDT 调用：跟随宿主深浅色主题。 */
    void refreshTheme() {
        if (!SwingUtilities.isEventDispatchThread()) {
            onEdt(this::refreshTheme);
            return;
        }
        boolean dark = HostThemeBridge.isDark();
        MiniPlayerView target = view;
        if (target != null) {
            target.setDarkTheme(dark);
        }
        MiniWindow mini = window;
        if (mini != null) {
            mini.applyTheme();
        }
    }

    private void hideWindow() {
        MiniWindow target = window;
        if (target == null) {
            return;
        }
        target.hide();
        toast("迷你播放器已隐藏，可在插件设置中重新显示",
                WorkshopApi.Ui.ToastType.Warning);
    }

    /** 拖动 / 吸附后记录位置，落盘交给后台线程。 */
    void onEdgeChanged(MiniWindow.Edge edge, Rectangle bounds) {
        if (edge != null) {
            pendingEdge.set(edgeName(edge));
        }
        if (bounds != null) {
            pendingBounds.set(bounds);
        }
        schedulePersist();
    }

    /** 收起到封面悬浮窗时记录位置。 */
    void onCollapsed(MiniWindow.Edge edge, Rectangle bounds) {
        if (bounds != null) {
            pendingBounds.set(bounds);
        }
        if (edge != null) {
            pendingEdge.set(edgeName(edge));
        }
        schedulePersist();
    }

    // ------------------------------------------------------------ 配置

    /** 注册监听：回调线程只投递，避免在宿主的通知路径上做任何工作。 */
    private void registerConfigListener(ConfigManager manager) {
        try {
            if (manager == null) {
                PluginLog.w("配置管理器不可用，跳过配置变更监听");
                return;
            }
            configManager = manager;
            java.util.function.Consumer<Object> listener =
                    value -> onEdt(this::scheduleConfigApply);
            manager.addConfigChangeListener((java.util.function.Consumer) listener);
            PluginLog.i("已注册配置变更监听");
        } catch (Throwable error) {
            PluginLog.w("注册配置变更监听失败: " + error);
        }
    }

    /** 去抖：连续变更只在最后一次生效，且仍然在 EDT 上串行执行。 */
    private void scheduleConfigApply() {
        if (configApplyTimer == null) {
            configApplyTimer = new Timer(200, event -> {
                ((Timer) event.getSource()).stop();
                applyConfig();
            });
            configApplyTimer.setRepeats(false);
        }
        configApplyTimer.restart();
    }

    private void applyConfig() {
        PluginConfig current = config;
        if (current == null) {
            return;
        }
        current.reload();
        MiniWindow target = window;
        if (target == null) {
            return;
        }
        target.setAutoHide(current.getBoolean("auto_hide", true));
        target.setHideDelay(readHideDelayMs());
        target.setAlwaysOnTop(current.getBoolean("always_on_top", true));
        MiniPlayerView targetView = view;
        if (targetView != null) {
            targetView.setPanelAlpha(alphaFrom(current.getInt("opacity", 80)));
            targetView.setDarkTheme(HostThemeBridge.isDark());
        }
        target.applyTheme();
        PluginLog.i("配置已更新：" + describeEffectiveConfig(target));
    }

    /** 汇总当前生效的配置，便于用户从日志确认设置是否真的生效。 */
    private String describeEffectiveConfig(MiniWindow target) {
        PluginConfig current = config;
        boolean autoHide = current != null && current.getBoolean("auto_hide", true);
        String edge = current == null ? "right"
                : current.getString("last_edge", current.getString("dock_edge", "right"));
        int opacity = current == null ? 80 : current.getInt("opacity", 80);
        return "edge=" + edge
                + ", autoHide=" + autoHide
                + ", hideDelay=" + readHideDelayMs() + "ms"
                + ", opacity=" + opacity + "%"
                + ", alwaysOnTop=" + (current == null || current.getBoolean("always_on_top", true))
                + ", compact=" + (target != null && target.isCompact());
    }

    /** 自动收起延迟：配置以「秒」为单位，兼容旧版毫秒键。 */
    private int readHideDelayMs() {
        PluginConfig current = config;
        if (current == null) {
            return (int) Math.round(DEFAULT_HIDE_DELAY_SECONDS * 1000.0);
        }
        // 宿主把 list 选项存成字符串（如 "1.5"），PluginConfig 内部已兼容
        double seconds = current.getDouble("hide_delay", -1.0);
        if (seconds > 0) {
            return (int) Math.round(Math.max(MIN_HIDE_DELAY_SECONDS, seconds) * 1000.0);
        }
        // 兼容早期版本的毫秒滑条（hide_delay_ms）
        int legacyMs = current.getInt("hide_delay_ms", -1);
        if (legacyMs > 0) {
            return legacyMs;
        }
        return (int) Math.round(DEFAULT_HIDE_DELAY_SECONDS * 1000.0);
    }

    private void schedulePersist() {
        persistDirty.set(true);
        if (persistScheduled.compareAndSet(false, true)) {
            try {
                persistWorker.execute(this::persistLoop);
            } catch (Throwable error) {
                persistScheduled.set(false);
            }
        }
    }

    private void persistLoop() {
        try {
            Thread.sleep(700);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            persistScheduled.set(false);
            return;
        }
        while (persistDirty.getAndSet(false)) {
            PluginConfig current = config;
            if (current != null && current.isAvailable()) {
                String edge = pendingEdge.getAndSet(null);
                if (edge != null) {
                    current.put("last_edge", edge);
                }
                Rectangle bounds = pendingBounds.getAndSet(null);
                if (bounds != null) {
                    current.put("last_x", bounds.x);
                    current.put("last_y", bounds.y);
                }
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        persistScheduled.set(false);
    }

    // ---------------------------------------------------------- EDT 看门狗

    /**
     * 界面线程卡死诊断：定期向 EDT 投放一个探针，若长时间无法执行说明 EDT 被阻塞，
     * 立即把全部线程堆栈写入插件日志，便于定位（日志见 workshop/data/&lt;id&gt;/mini-player.log）。
     */
    private void startEdtWatchdog() {
        Thread watchdog = new Thread(() -> {
            int reported = 0;
            while (started.get()) {
                CountDownLatch latch = new CountDownLatch(1);
                try {
                    SwingUtilities.invokeLater(latch::countDown);
                    if (!latch.await(6, TimeUnit.SECONDS)) {
                        reported++;
                        if (reported <= 3) {
                            PluginLog.e("界面线程连续 6 秒无响应，导出线程堆栈用于定位", null);
                            logAllThreads();
                        }
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable error) {
                    PluginLog.w("看门狗探针失败: " + error);
                }
                try {
                    Thread.sleep(2500);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "spw-mini-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private void logAllThreads() {
        try {
            for (java.util.Map.Entry<Thread, StackTraceElement[]> entry
                    : Thread.getAllStackTraces().entrySet()) {
                Thread thread = entry.getKey();
                StringBuilder builder = new StringBuilder();
                builder.append("线程 \"").append(thread.getName()).append("\" state=")
                        .append(thread.getState()).append(" daemon=").append(thread.isDaemon());
                PluginLog.e(builder.toString(), null);
                for (StackTraceElement element : entry.getValue()) {
                    PluginLog.e("    at " + element, null);
                }
            }
        } catch (Throwable error) {
            PluginLog.w("导出线程堆栈失败: " + error);
        }
    }

    // ------------------------------------------------------------ 内部工具

    private void pushStateToView() {
        MiniPlayerView target = view;
        if (target == null) {
            return;
        }
        target.setNowPlaying(title, buildSubtitle(artist, album));
        target.setPlaying(playing);
        target.setProgress(positionMs, durationMs);
        refreshLyricsState();
    }

    private void pushProgress() {
        MiniPlayerView target = view;
        if (target != null) {
            target.setProgress(positionMs, durationMs);
        }
    }

    /** 0-100 的整数不透明度；低于 10 时按 10 处理，避免窗口完全不可见。 */
    private static int alphaFrom(int percent) {
        int clamped = Math.max(10, Math.min(100, percent));
        return Math.round(clamped * 2.55f);
    }

    private static String buildSubtitle(String artist, String album) {
        String left = safe(artist);
        String right = safe(album);
        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty()) {
            return left;
        }
        return left + " · " + right;
    }

    private static String safe(String text) {
        return text == null ? "" : text.trim();
    }

    /** 播放控制交给宿主工作线程，避免任何宿主调用落在 EDT 上。 */
    private void runPlayback(String label, Runnable action) {
        HostBridgeWorker.submit(action);
    }

    private void toast(String message, WorkshopApi.Ui.ToastType type) {
        HostBridgeWorker.submit(() -> {
            try {
                WorkshopApi.ui().toast(message, type);
            } catch (Throwable error) {
                PluginLog.w("发送提示失败: " + message);
            }
        });
    }

    private static void onEdt(Runnable runnable) {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
        } else {
            SwingUtilities.invokeLater(runnable);
        }
    }

    private static MiniWindow.Edge parseEdge(String value) {
        if (value == null) {
            return MiniWindow.Edge.RIGHT;
        }
        switch (value.trim().toLowerCase()) {
            case "left":
                return MiniWindow.Edge.LEFT;
            case "top":
                return MiniWindow.Edge.TOP;
            case "bottom":
                return MiniWindow.Edge.BOTTOM;
            default:
                return MiniWindow.Edge.RIGHT;
        }
    }

    private static String edgeName(MiniWindow.Edge edge) {
        switch (edge) {
            case LEFT:
                return "left";
            case TOP:
                return "top";
            case BOTTOM:
                return "bottom";
            default:
                return "right";
        }
    }

    /** 预览 / 测试用：当前是否存在窗口。 */
    boolean hasWindow() {
        return window != null;
    }
}
