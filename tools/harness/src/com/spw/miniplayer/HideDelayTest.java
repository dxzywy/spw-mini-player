package com.spw.miniplayer;

import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.util.ArrayList;
import java.util.List;

/**
 * 自动收起延迟的行为验证（不属于插件本体）。
 *
 * <p>前一个探针只证明「配置能读到值」，这里进一步证明「读到的值真的作用到
 * 自动收起上」：真实创建贴边小窗，把光标移开，测量从鼠标离开到收起为封面
 * 悬浮窗的实际耗时，并对比两组不同延迟。
 *
 * <p>运行时会短暂在桌面边缘出现窗口，并把鼠标指针移到屏幕左上角，随后自动关闭。
 */
public final class HideDelayTest {

    /** 允许的计时误差（毫秒）：轮询间隔 + 系统调度。 */
    private static final int TOLERANCE_MS = 600;
    /** MiniWindow 的展开动画时长，实测耗时需要扣掉它。 */
    private static final int ANIMATION_MS = 160;

    private static final List<String> FAILURES = new ArrayList<>();

    private HideDelayTest() {
    }

    public static void main(String[] args) throws Exception {
        MiniWindow[] holder = new MiniWindow[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            MiniPlayerController controller = new MiniPlayerController(null);
            MiniWindow window = new MiniWindow(controller);
            holder[0] = window;
            window.setDefaultEdge(MiniWindow.Edge.RIGHT);
            window.setAutoHide(false);
            window.show(true);
        });

        MiniWindow window = holder[0];
        if (window == null) {
            System.out.println("窗口创建失败");
            System.exit(1);
        }
        Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        Robot robot = new Robot();

        try {
            // 短延迟：先建立基线
            long shortRaw = measure(window, robot, 500);
            // 长延迟：与基线相差 3 倍，若延迟配置无效两者会一致
            long longRaw = measure(window, robot, 1500);
            long shortMs = shortRaw - ANIMATION_MS;
            long longMs = longRaw - ANIMATION_MS;

            System.out.println();
            System.out.println("  配置 500ms  -> 实测 " + shortMs + " ms（含展开动画 "
                    + shortRaw + " ms）");
            System.out.println("  配置 1500ms -> 实测 " + longMs + " ms（含展开动画 "
                    + longRaw + " ms）");
            System.out.println("  差值 " + (longMs - shortMs) + " ms（期望 1000ms）");

            check(Math.abs(shortMs - 500) <= TOLERANCE_MS,
                    "500ms 配置按预期收起（实测 " + shortMs + "ms）");
            check(Math.abs(longMs - 1500) <= TOLERANCE_MS,
                    "1500ms 配置按预期收起（实测 " + longMs + "ms）");
            check(longMs - shortMs >= 600,
                    "两组延迟确实产生差异（差值 " + (longMs - shortMs) + "ms）");
        } finally {
            javax.swing.SwingUtilities.invokeAndWait(window::dispose);
        }

        System.out.println();
        if (FAILURES.isEmpty()) {
            System.out.println("自动收起延迟行为验证通过 ✅");
            System.exit(0);
        }
        System.out.println("存在失败项 ❌");
        for (String failure : FAILURES) {
            System.out.println("  - " + failure);
        }
        System.exit(1);
    }

    /** 展开窗口、设置延迟、把光标移开，返回实际收起耗时（毫秒）。 */
    private static long measure(MiniWindow window, Robot robot, int delayMs) throws Exception {
        // 先收起再展开：展开会复位「鼠标离开时刻」，让计时起点干净
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            window.setHideDelay(delayMs);
            window.setAutoHide(true);
            if (!window.isCompact()) {
                window.collapse();
            }
        });
        Thread.sleep(320);
        // 光标移到左上角，确保不在窗口上（窗口吸附在右边缘）
        robot.mouseMove(2, 2);
        Thread.sleep(200);

        // 计时从展开那一刻开始；展开动画期间 pollTimer 不累计离开时长，
        // 因此实测值 = 展开动画（约 160ms）+ 配置延迟
        long start = System.currentTimeMillis();
        javax.swing.SwingUtilities.invokeAndWait(window::expand);
        long deadline = start + delayMs + 5000L;
        while (System.currentTimeMillis() < deadline) {
            if (window.isCompact()) {
                return System.currentTimeMillis() - start;
            }
            Thread.sleep(25);
        }
        check(false, "延迟 " + delayMs + "ms 时窗口始终未收起");
        return -1;
    }

    private static void check(boolean condition, String label) {
        System.out.println((condition ? "  [OK]   " : "  [FAIL] ") + label);
        if (!condition) {
            FAILURES.add(label);
        }
    }
}
