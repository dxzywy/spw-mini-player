package com.spw.miniplayer;

import com.xuncorp.voxzen.util.AppConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 卡死根因的确定性复现与回归验证（不属于插件本体）。
 *
 * <p>真实宿主的 {@code AppConfig} 是 Kotlin object：实例放在静态字段 {@code INSTANCE}。
 * 插件要用桌面歌词 / 主题就必须反射读取它，而<b>读取静态字段会触发/等待该类的静态初始化</b>。
 * 宿主自身在启动时也要初始化同一个类，并且会在初始化过程中回调插件
 * （配置变更通知、媒体回调）。两边一旦交叉等待就是永久死锁 —— 界面与播放器一起卡死。
 *
 * <p>用法（两个模式必须在不同 JVM 进程中跑，因为一个类只会被初始化一次）：
 * <pre>
 *   java ... DeadlockRepro legacy   # 复刻旧实现（持锁解析）→ 必然死锁
 *   java ... DeadlockRepro fixed    # 当前实现（锁外解析 + 专用线程）→ 必须通过
 * </pre>
 */
public final class DeadlockRepro {

    public static void main(String[] args) throws Exception {
        boolean legacy = args.length > 0 && "legacy".equals(args[0]);
        List<String> failures = new ArrayList<>();

        if (legacy) {
            HostCallbackProbe.callback = LegacyBridge::isAvailable;
        } else {
            HostCallbackProbe.callback = () -> {
                // 修复后的宿主回调路径：只读已发布的桥接状态，不做任何解析
                DesktopLyricsBridge.isAvailable();
                HostThemeBridge.isAvailable();
            };
        }

        // 宿主线程：初始化 AppConfig（内部 sleep + 回调插件）
        Thread host = new Thread(() -> {
            try {
                Class.forName("com.xuncorp.voxzen.util.AppConfig", true,
                        DeadlockRepro.class.getClassLoader());
            } catch (Throwable ignored) {
                // 初始化异常在这里不关心
            }
        }, "host-startup");
        host.start();

        // 等宿主真正进入静态初始化
        long deadline = System.currentTimeMillis() + 3000;
        while (!HostCallbackProbe.hostInitStarted && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        if (!HostCallbackProbe.hostInitStarted) {
            System.out.println("  [FAIL] 伪造宿主未能进入静态初始化");
            System.exit(1);
        }

        // 插件线程：解析宿主桥接（旧实现会在持锁状态下触发类初始化）
        Thread plugin = new Thread(() -> {
            if (legacy) {
                LegacyBridge.holdLockAndResolve(700);
            } else {
                DesktopLyricsBridge.resolveNow();
                HostThemeBridge.resolveNow();
            }
        }, "plugin-start");
        plugin.start();

        host.join(6000);
        plugin.join(6000);
        boolean hung = host.isAlive() || plugin.isAlive();

        System.out.println("  宿主线程存活=" + host.isAlive() + "  插件线程存活=" + plugin.isAlive());
        System.out.println("  宿主初始化完成=" + HostCallbackProbe.hostInitFinished
                + "  回调线程=" + HostCallbackProbe.callbackThread);

        if (legacy) {
            if (hung) {
                System.out.println("  [OK]   已复现根因：旧实现（持锁解析宿主类）发生死锁");
                System.out.println("         —— 宿主线程在 AppConfig.<clinit> 内等待插件锁，");
                System.out.println("            插件线程持锁等待 AppConfig 初始化完成，双向交叉等待");
                System.exit(0);
            }
            System.out.println("  [FAIL] 未复现死锁（时序未命中，请重跑）");
            System.exit(1);
        }

        if (hung) {
            failures.add("修复后的实现仍然卡死");
        }
        if (!HostCallbackProbe.hostInitFinished) {
            failures.add("宿主静态初始化未完成");
        }
        if (!DesktopLyricsBridge.isAvailable()) {
            failures.add("桌面歌词桥接未对接成功");
        }
        if (!AppConfig.LightDarkTheme.Dark.name().equals("Dark")) {
            failures.add("伪造枚举异常");
        }
        Class<?> theme = Class.forName("com.xuncorp.voxzen.util.AppConfig");
        if (theme == null) {
            failures.add("宿主类加载失败");
        }

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("  [OK]   修复后不再死锁：宿主初始化与插件解析都能完成");
            System.exit(0);
        }
        for (String failure : failures) {
            System.out.println("  [FAIL] " + failure);
        }
        System.exit(1);
    }

    /**
     * 复刻修复前的实现：先持有本类锁，再在锁内触发宿主类静态初始化。
     */
    static final class LegacyBridge {

        private static final Object LOCK = new Object();

        static void holdLockAndResolve(long holdMillis) {
            synchronized (LOCK) {
                try {
                    Thread.sleep(holdMillis);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                isAvailable();
            }
        }

        static boolean isAvailable() {
            synchronized (LOCK) {
                try {
                    Class<?> type = Class.forName("com.xuncorp.voxzen.util.AppConfig", false,
                            LegacyBridge.class.getClassLoader());
                    return type.getField("INSTANCE").get(null) != null;
                } catch (Throwable error) {
                    return false;
                }
            }
        }
    }
}
