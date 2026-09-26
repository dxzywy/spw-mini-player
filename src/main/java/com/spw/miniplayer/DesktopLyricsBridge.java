package com.spw.miniplayer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 桌面歌词开关桥接。
 *
 * <p>Salt Player for Windows 自带桌面歌词功能，其开关保存在
 * {@code com.xuncorp.voxzen.util.AppConfig}（Kotlin object）上。宿主自身的
 * 「桌面歌词」热键也调用 {@code setDesktopLyrics(boolean)}，因此这里调用同一入口，
 * 宿主界面与歌词窗口会立即响应。
 *
 * <p><b>线程与锁约定</b>：解析宿主类（{@code Class.forName}）与调用宿主方法一律
 * <b>不持有任何本类锁</b>。宿主启动期会同步初始化 {@code AppConfig}，
 * 若我们持锁等待宿主类加载、而宿主线程又在等待插件启动完成，就会形成
 * 循环等待导致永久死锁 —— 这也是「插件跟随播放器一起启动才卡死」的根因。
 */
final class DesktopLyricsBridge {

    private static final String APP_CONFIG_CLASS = "com.xuncorp.voxzen.util.AppConfig";
    /** 不同宿主版本可能使用不同的访问器命名，按优先级依次尝试。 */
    private static final String[] GETTERS = {
            "getDesktopLyrics", "isDesktopLyrics", "getDesktopLyric"
    };
    private static final String[] SETTERS = {
            "setDesktopLyrics", "updateDesktopLyrics", "setDesktopLyric"
    };
    private static final String INSTANCE_FIELD = "INSTANCE";

    private static volatile Object appConfig;
    private static volatile Method getter;
    private static volatile Method setter;

    private DesktopLyricsBridge() {
    }

    /** 是否成功对接宿主的桌面歌词开关（纯读取，不做任何解析）。 */
    static boolean isAvailable() {
        return getter != null && setter != null && appConfig != null;
    }

    /** 读取当前桌面歌词开关状态；不可用时返回 null。 */
    static Boolean isEnabled() {
        Method currentGetter = getter;
        Object instance = appConfig;
        if (currentGetter == null || instance == null) {
            return null;
        }
        try {
            Object value = currentGetter.invoke(instance);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
        } catch (Throwable error) {
            PluginLog.w("读取桌面歌词状态失败: " + error);
        }
        return null;
    }

    /** 设置桌面歌词开关；返回是否成功。 */
    static boolean setEnabled(boolean enabled) {
        Method currentSetter = setter;
        Object instance = appConfig;
        if (currentSetter == null || instance == null) {
            return false;
        }
        try {
            currentSetter.invoke(instance, enabled);
            return true;
        } catch (Throwable error) {
            PluginLog.e("切换桌面歌词失败", error);
            return false;
        }
    }

    /** 取反切换；返回切换后的状态，失败返回 null。 */
    static Boolean toggle() {
        Boolean current = isEnabled();
        if (current == null) {
            return null;
        }
        boolean next = !current;
        if (!setEnabled(next)) {
            return null;
        }
        return next;
    }

    /**
     * 执行解析。只能由 {@link HostBridgeWorker} 的专用线程调用。
     *
     * <p>关键点：整个解析过程<b>不持有任何本类锁</b>。反射读取毒主类的静态字段会触发
     * 该类（可能长达数百毫秒）的静态初始化，如果此时持锁，就会与宿主初始化线程
     * 形成交叉等待。
     */
    static void resolveNow() {
        if (isAvailable()) {
            return;
        }
        Object instance = null;
        Method resolvedGetter = null;
        Method resolvedSetter = null;
        try {
            Class<?> type = HostReflection.loadHostClass(APP_CONFIG_CLASS);
            if (type != null) {
                Field instanceField = null;
                try {
                    instanceField = type.getField(INSTANCE_FIELD);
                } catch (Throwable ignored) {
                    for (Field field : type.getFields()) {
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
                for (String candidate : SETTERS) {
                    resolvedSetter = HostReflection.findMethod(type, candidate, boolean.class);
                    if (resolvedSetter != null) {
                        break;
                    }
                }
            }
        } catch (Throwable error) {
            PluginLog.e("解析宿主桌面歌词接口失败", error);
        }

        if (instance != null && resolvedGetter != null && resolvedSetter != null) {
            getter = resolvedGetter;
            setter = resolvedSetter;
            appConfig = instance;
            PluginLog.i("已对接宿主桌面歌词开关: " + instance.getClass().getName());
        } else {
            PluginLog.w("暂未找到宿主桌面歌词开关（" + APP_CONFIG_CLASS + "），稍后重试");
        }
    }
}
