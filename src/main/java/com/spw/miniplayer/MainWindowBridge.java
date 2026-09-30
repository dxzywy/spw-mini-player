package com.spw.miniplayer;

import javax.swing.SwingUtilities;
import java.awt.Frame;
import java.awt.Window;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * 主播放器窗口桥接：从迷你播放器一键打开 / 收起 Salt Player 主窗口。
 *
 * <p>宿主的「主窗口是否可见」保存在 {@code com.xuncorp.voxzen.util.AppConfig}
 * （Kotlin object）的 {@code mainWindowVisible} 状态上，宿主自己的全局热键与播放服务也走
 * {@code updateMainWindowVisible(boolean)} 这个入口，因此这里调用同一入口，宿主界面会立即响应
 * （包括最小化到系统托盘的场景）。
 *
 * <p><b>收起时必须先看宿主的「关闭主窗口」设置</b>（{@code getCloseMainWindowStrategy()}，
 * 枚举 {@code CloseMainWindowStrategy} 的取值为 {@code SystemTray} / {@code ExitProgress}）：
 * <ul>
 *   <li>{@code SystemTray}：走宿主入口 {@code updateMainWindowVisible(false)}，主窗口最小化到托盘；</li>
 *   <li>{@code ExitProgress}：宿主关闭主窗口会<b>退出整个应用</b>，插件绝不能踩这条路，
 *       只用 AWT 把窗口最小化到任务栏；</li>
 *   <li>读不到策略：按最保守的方式处理（同样只最小化到任务栏）。</li>
 * </ul>
 *
 * <p><b>线程与锁约定</b>（与 {@link DesktopLyricsBridge} 完全一致）：解析宿主类与调用宿主方法
 * 一律在 {@link HostBridgeWorker} 的专用线程上、且<b>不持有任何锁</b>。反射读取
 * {@code AppConfig.INSTANCE} 会触发该类的静态初始化，同时持锁会与宿主启动线程交叉等待形成死锁。
 */
final class MainWindowBridge {

    /** 宿主「关闭主窗口」的行为策略。 */
    enum CloseStrategy {
        /** 关闭主窗口 = 最小化到系统托盘。 */
        TRAY,
        /** 关闭主窗口 = 退出应用（插件不走这条路）。 */
        EXIT,
        /** 未能识别：按最保守的方式处理。 */
        UNKNOWN
    }

    private static final String APP_CONFIG_CLASS = "com.xuncorp.voxzen.util.AppConfig";
    /** 不同宿主版本可能使用不同的访问器命名，按优先级依次尝试。 */
    private static final String[] VISIBLE_GETTERS = {
            "getMainWindowVisible", "isMainWindowVisible"
    };
    private static final String[] VISIBLE_SETTERS = {
            "updateMainWindowVisible", "setMainWindowVisible"
    };
    private static final String[] STRATEGY_GETTERS = {
            "getCloseMainWindowStrategy", "isCloseMainWindowStrategy"
    };
    private static final String INSTANCE_FIELD = "INSTANCE";

    private static volatile Object appConfig;
    private static volatile Method visibleGetter;
    private static volatile Method visibleSetter;
    private static volatile Method strategyGetter;

    /**
     * 上一次是否由本插件把主窗口最小化到了任务栏。
     *
     * <p>宿主策略为「退出应用」时我们不能改 {@code mainWindowVisible}（那会退出应用），
     * 于是宿主侧的状态仍是「可见」，需要有这么一个标记来判断下一次点击该打开还是该收起。
     */
    private static volatile boolean minimizedToTaskbar;

    private MainWindowBridge() {
    }

    /** 是否成功对接宿主主窗口开关（纯读取，不做任何解析）。 */
    static boolean isAvailable() {
        return appConfig != null && visibleSetter != null;
    }

    /** 主窗口当前是否可见；读不到时返回 null。 */
    static Boolean readVisible() {
        Object instance = appConfig;
        Method getter = visibleGetter;
        if (instance == null || getter == null) {
            return null;
        }
        try {
            Object value = getter.invoke(instance);
            return value instanceof Boolean ? (Boolean) value : null;
        } catch (Throwable error) {
            PluginLog.w("读取主窗口可见状态失败: " + error);
            return null;
        }
    }

    /** 宿主的「关闭主窗口」策略。 */
    static CloseStrategy closeStrategy() {
        Object instance = appConfig;
        Method getter = strategyGetter;
        if (instance == null || getter == null) {
            return CloseStrategy.UNKNOWN;
        }
        try {
            return parseStrategy(enumName(getter.invoke(instance)));
        } catch (Throwable error) {
            PluginLog.w("读取宿主关闭主窗口策略失败: " + error);
            return CloseStrategy.UNKNOWN;
        }
    }

    /**
     * 打开 / 收起主播放器窗口，返回一句可以直接用于提示的文案。
     *
     * <p>只能在 {@link HostBridgeWorker} 的线程上调用。
     */
    static String toggle() {
        Boolean visible = readVisible();
        CloseStrategy strategy = closeStrategy();
        if (Boolean.TRUE.equals(visible) && !minimizedToTaskbar) {
            return collapse(strategy);
        }
        minimizedToTaskbar = false;
        boolean viaHost = applyVisible(true);
        // 无论宿主接口是否可用，都把已存在的主窗口顶到前台（处理最小化 / 被遮挡）
        SwingUtilities.invokeLater(MainWindowBridge::focusHostWindow);
        return viaHost ? "已打开播放器" : "已尝试唤起播放器窗口";
    }

    /** 收起主窗口：按宿主的「关闭主窗口」设置选择具体行为。 */
    private static String collapse(CloseStrategy strategy) {
        if (strategy == CloseStrategy.TRAY && applyVisible(false)) {
            return "已最小化到系统托盘";
        }
        /*
         * 其余情况（宿主设置为退出应用 / 策略未知）一律只做「最小化到任务栏」：
         * 绝不调用宿主的关闭入口，避免把整个应用退掉。
         */
        minimizedToTaskbar = true;
        SwingUtilities.invokeLater(MainWindowBridge::iconifyHostWindow);
        return "已最小化到任务栏";
    }

    private static boolean applyVisible(boolean visible) {
        Object instance = appConfig;
        Method setter = visibleSetter;
        if (instance == null || setter == null) {
            return false;
        }
        try {
            setter.invoke(instance, visible);
            return true;
        } catch (Throwable error) {
            PluginLog.e("设置主窗口可见状态失败", error);
            return false;
        }
    }

    /**
     * 执行解析。只能由 {@link HostBridgeWorker} 的专用线程调用。
     *
     * <p>整个过程<b>不持有任何锁</b>；失败时不永久标记，由
     * {@link HostBridgeWorker#prepare(Runnable)} 的定时重试兜底。
     */
    static void resolveNow() {
        if (isAvailable()) {
            return;
        }
        Object instance = null;
        Method resolvedVisibleGetter = null;
        Method resolvedVisibleSetter = null;
        Method resolvedStrategyGetter = null;
        try {
            Class<?> type = HostReflection.loadHostClass(APP_CONFIG_CLASS);
            if (type != null) {
                java.lang.reflect.Field instanceField =
                        HostReflection.staticFieldHandle(type, INSTANCE_FIELD);
                if (instanceField != null) {
                    // 注意：这一步会触发宿主类的静态初始化
                    instance = instanceField.get(null);
                }
                for (String candidate : VISIBLE_GETTERS) {
                    resolvedVisibleGetter = HostReflection.findMethod(type, candidate);
                    if (resolvedVisibleGetter != null) {
                        break;
                    }
                }
                for (String candidate : VISIBLE_SETTERS) {
                    resolvedVisibleSetter = HostReflection.findMethod(type, candidate, boolean.class);
                    if (resolvedVisibleSetter != null) {
                        break;
                    }
                }
                for (String candidate : STRATEGY_GETTERS) {
                    resolvedStrategyGetter = HostReflection.findMethod(type, candidate);
                    if (resolvedStrategyGetter != null) {
                        break;
                    }
                }
            }
        } catch (Throwable error) {
            PluginLog.e("解析宿主主窗口接口失败", error);
        }

        if (instance != null && resolvedVisibleSetter != null) {
            visibleGetter = resolvedVisibleGetter;
            visibleSetter = resolvedVisibleSetter;
            strategyGetter = resolvedStrategyGetter;
            appConfig = instance;
            PluginLog.i("已对接宿主主窗口开关: " + instance.getClass().getName()
                    + "（关闭主窗口策略=" + closeStrategy() + "）");
        } else {
            PluginLog.w("暂未找到宿主主窗口开关（" + APP_CONFIG_CLASS + "），稍后重试");
        }
    }

    // ------------------------------------------------------------ AWT 兜底

    /**
     * 把宿主主窗口顶到前台（EDT）。
     *
     * <p>{@code updateMainWindowVisible} 负责「可见性」，而「最小化 / 被其他窗口遮挡」
     * 需要 AWT 这一层才能恢复。全程 try-catch，失败不影响迷你播放器本身。
     */
    static void focusHostWindow() {
        try {
            Window target = findHostWindow();
            if (target == null) {
                return;
            }
            if (target instanceof Frame) {
                Frame frame = (Frame) target;
                int state = frame.getExtendedState();
                if ((state & Frame.ICONIFIED) != 0) {
                    frame.setExtendedState(state & ~Frame.ICONIFIED);
                }
                if (!frame.isVisible()) {
                    frame.setVisible(true);
                }
            } else if (!target.isVisible()) {
                target.setVisible(true);
            }
            target.toFront();
            target.requestFocus();
        } catch (Throwable error) {
            PluginLog.w("前置主播放器窗口失败: " + error);
        }
    }

    /** 把宿主主窗口最小化到任务栏（EDT），不触碰宿主的可见性状态。 */
    static void iconifyHostWindow() {
        try {
            Window target = findHostWindow();
            if (target == null) {
                return;
            }
            if (target instanceof Frame) {
                Frame frame = (Frame) target;
                frame.setExtendedState(frame.getExtendedState() | Frame.ICONIFIED);
            } else {
                target.setVisible(false);
            }
        } catch (Throwable error) {
            PluginLog.w("最小化主播放器窗口失败: " + error);
        }
    }

    /**
     * 在所有 AWT 窗口里挑出宿主主窗口。
     *
     * <p>优先取可见的 {@link Frame}（Compose Desktop 的主窗口是一个 {@code JFrame} 子类），
     * 按面积取最大；迷你播放器自身是 {@code JWindow}，天然被排除。
     */
    private static Window findHostWindow() {
        Window best = null;
        int bestArea = -1;
        try {
            for (Frame frame : Frame.getFrames()) {
                Window candidate = pickBetter(best, bestArea, frame);
                if (candidate != null) {
                    best = candidate;
                    bestArea = area(candidate);
                }
            }
        } catch (Throwable error) {
            PluginLog.w("枚举宿主窗口失败: " + error);
        }
        if (best != null) {
            return best;
        }
        try {
            for (Window window : Window.getWindows()) {
                if (window instanceof javax.swing.JWindow) {
                    continue;
                }
                Window candidate = pickBetter(best, bestArea, window);
                if (candidate != null) {
                    best = candidate;
                    bestArea = area(candidate);
                }
            }
        } catch (Throwable error) {
            PluginLog.w("枚举宿主窗口失败: " + error);
        }
        return best;
    }

    private static Window pickBetter(Window current, int currentArea, Window candidate) {
        if (candidate == null || !candidate.isVisible()) {
            return null;
        }
        int area = area(candidate);
        return current == null || area > currentArea ? candidate : null;
    }

    private static int area(Window window) {
        java.awt.Rectangle bounds = window.getBounds();
        return Math.max(0, bounds.width) * Math.max(0, bounds.height);
    }

    // ---------------------------------------------------------------- 工具

    /** 取枚举常量的名字；不是枚举时退回 {@code toString()}。 */
    private static String enumName(Object value) {
        if (value == null) {
            return null;
        }
        try {
            Object name = value.getClass().getMethod("name").invoke(value);
            if (name instanceof String) {
                return (String) name;
            }
        } catch (Throwable ignored) {
            // 不是枚举，退回 toString()
        }
        return String.valueOf(value);
    }

    /** 按枚举名判定策略；真实取值为 {@code SystemTray} / {@code ExitProgress}。 */
    private static CloseStrategy parseStrategy(String name) {
        if (name == null) {
            return CloseStrategy.UNKNOWN;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        if (upper.contains("TRAY")) {
            return CloseStrategy.TRAY;
        }
        if (upper.contains("EXIT") || upper.contains("QUIT") || upper.contains("CLOSE")) {
            return CloseStrategy.EXIT;
        }
        return CloseStrategy.UNKNOWN;
    }
}
