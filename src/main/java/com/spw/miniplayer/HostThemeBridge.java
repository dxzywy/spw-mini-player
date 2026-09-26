package com.spw.miniplayer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 宿主主题桥接：让迷你播放器跟随 Salt Player 的深色 / 浅色设置。
 *
 * <p>读取 {@code com.xuncorp.voxzen.util.AppConfig} 的 {@code LightDarkTheme} 属性
 * （取值 {@code Dark} / {@code Light} / {@code FollowSystem}）。当设置为跟随系统时，
 * 再去读 Windows 的「应用模式」注册表项，结果会缓存并由后台线程刷新。
 */
final class HostThemeBridge {

    private static final String APP_CONFIG_CLASS = "com.xuncorp.voxzen.util.AppConfig";
    private static final String[] GETTERS = {"getLightDarkTheme", "getLightDarkThemeState"};
    private static final String INSTANCE_FIELD = "INSTANCE";

    private static final String REG_PATH =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
    private static final String REG_VALUE = "AppsUseLightTheme";
    /** 系统主题查询结果的缓存时长。 */
    private static final long SYSTEM_TTL_MS = 30_000L;
    private static final Pattern HEX = Pattern.compile("0x([0-9a-fA-F]+)");

    private static volatile Object appConfig;
    private static volatile Method getter;

    private static final AtomicBoolean REFRESHING = new AtomicBoolean();
    private static volatile boolean systemDark;
    private static volatile long systemQueriedAt;

    private HostThemeBridge() {
    }

    /** 当前是否应使用深色外观（纯读取 + 调用已初始化的宿主方法，不做解析）。 */
    static boolean isDark() {
        String mode = readModeName();
        if ("Dark".equalsIgnoreCase(mode)) {
            return true;
        }
        if ("Light".equalsIgnoreCase(mode)) {
            return false;
        }
        return systemDark();
    }

    /** 是否成功对接宿主主题设置。 */
    static boolean isAvailable() {
        return getter != null && appConfig != null;
    }

    /**
     * 读取宿主主题模式名（Dark / Light / FollowSystem），不可用时返回 null。
     */
    private static String readModeName() {
        Method currentGetter = getter;
        Object instance = appConfig;
        if (currentGetter == null || instance == null) {
            return null;
        }
        try {
            Object value = currentGetter.invoke(instance);
            if (value instanceof Enum) {
                return ((Enum<?>) value).name();
            }
            return value == null ? null : String.valueOf(value);
        } catch (Throwable error) {
            PluginLog.w("读取宿主主题失败: " + error);
            return null;
        }
    }

    /**
     * 执行解析。只能由 {@link HostBridgeWorker} 的专用线程调用；
     * 全程不持锁，避免与宿主 {@code AppConfig} 的静态初始化交叉等待。
     */
    static void resolveNow() {
        if (isAvailable()) {
            return;
        }
        Object instance = null;
        Method resolvedGetter = null;
        try {
            Class<?> type = HostReflection.loadHostClass(APP_CONFIG_CLASS);
            if (type != null) {
                java.lang.reflect.Field instanceField = null;
                try {
                    instanceField = type.getField(INSTANCE_FIELD);
                } catch (Throwable ignored) {
                    for (java.lang.reflect.Field field : type.getFields()) {
                        if (INSTANCE_FIELD.equals(field.getName())
                                && java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                            instanceField = field;
                            break;
                        }
                    }
                }
                if (instanceField != null) {
                    // 注意：这一步会触发宿主类的静态初始化
                    instance = instanceField.get(null);
                }
                for (String candidate : GETTERS) {
                    resolvedGetter = HostReflection.findMethod(type, candidate);
                    if (resolvedGetter != null) {
                        break;
                    }
                }
            }
        } catch (Throwable error) {
            PluginLog.w("解析宿主主题接口失败: " + error);
        }

        if (instance != null && resolvedGetter != null) {
            appConfig = instance;
            getter = resolvedGetter;
            PluginLog.i("已对接宿主主题设置，当前模式: " + readModeName());
        } else {
            PluginLog.w("暂未找到宿主主题设置（" + APP_CONFIG_CLASS + "），将跟随系统并稍后重试");
        }
    }

    // ------------------------------------------------------------ 系统主题

    private static boolean systemDark() {
        long now = System.currentTimeMillis();
        if (now - systemQueriedAt > SYSTEM_TTL_MS && REFRESHING.compareAndSet(false, true)) {
            Thread worker = new Thread(() -> {
                try {
                    systemDark = queryWindowsDark();
                } catch (Throwable error) {
                    PluginLog.w("查询系统主题失败: " + error);
                } finally {
                    systemQueriedAt = System.currentTimeMillis();
                    REFRESHING.set(false);
                }
            }, "spw-mini-theme");
            worker.setDaemon(true);
            worker.setPriority(Thread.MIN_PRIORITY);
            worker.start();
        }
        return systemDark;
    }

    /** 读取 Windows「应用使用浅色主题」设置；失败时按浅色处理。 */
    private static boolean queryWindowsDark() throws Exception {
        String os = System.getProperty("os.name", "");
        if (!os.toLowerCase().contains("win")) {
            return false;
        }
        Process process = new ProcessBuilder("reg", "query", REG_PATH, "/v", REG_VALUE)
                .redirectErrorStream(true)
                .start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
            process.destroy();
            return false;
        }
        for (String line : output.toString().split("\n")) {
            if (!line.contains(REG_VALUE)) {
                continue;
            }
            Matcher matcher = HEX.matcher(line);
            if (matcher.find()) {
                // 0x1 = 使用浅色，0x0 = 使用深色
                return Integer.parseInt(matcher.group(1), 16) == 0;
            }
        }
        return false;
    }
}
