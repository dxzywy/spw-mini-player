package com.spw.miniplayer;

import com.xuncorp.spw.workshop.api.WorkshopApi;
import com.xuncorp.spw.workshop.api.config.ConfigHelper;
import com.xuncorp.spw.workshop.api.config.ConfigManager;

/**
 * 插件配置读写。
 *
 * <p>配置项由 {@code preference_config.json} 声明，宿主渲染配置界面，
 * 这里只负责读取当前值以及写入少量运行期状态（上次贴边位置）。
 * 任何一步失败都不会影响插件主流程。
 *
 * <p><b>读取约定（踩过坑，务必保持）</b>：宿主的
 * {@code ConfigHelper.get(String, T)} 会按 <b>defaultValue 的运行时类型</b>
 * 决定如何解析 JSON 值，取不到时才回退到该默认值；如果传入 {@code null}，
 * 会直接走 default 分支返回 {@code null}，等价于「任何配置都读不到」。
 *
 * <p>因此这里采用两层读取：
 * <ol>
 *   <li><b>首选</b>按 String 取原始字面量再本地解析——宿主把 {@code list} 选项
 *       写成 JSON 字符串（{@code "1.5"}）、把滑条写成 JSON 数字（{@code 3000.0}），
 *       这一层都能拿到，且不受宿主类型转换规则影响；</li>
 *   <li><b>兜底</b>再按目标类型取一次，但默认值必须非空。</li>
 * </ol>
 */
final class PluginConfig {

    private static final String CONFIG_FILE = "config.json";

    private final ConfigHelper helper;

    private PluginConfig(ConfigHelper helper) {
        this.helper = helper;
    }

    /** 配置不可用时的空实现，所有读取返回默认值、写入被忽略。 */
    static PluginConfig unavailable() {
        return new PluginConfig(null);
    }

    /** 绑定一个已有的配置助手（验证脚本复用同一条读取路径）。 */
    static PluginConfig attach(ConfigHelper helper) {
        return new PluginConfig(helper);
    }

    static PluginConfig open() {
        ConfigManager manager = null;
        try {
            manager = WorkshopApi.manager().createConfigManager();
        } catch (Throwable error) {
            PluginLog.w("无法创建配置管理器: " + error);
        }
        return open(manager);
    }

    /**
     * 用指定的配置管理器打开配置。
     *
     * <p>建议与配置变更监听共用同一个 {@link ConfigManager}，避免宿主为同一份
     * 配置文件重复创建监听线程。
     */
    static PluginConfig open(ConfigManager manager) {
        ConfigHelper helper = null;
        if (manager != null) {
            try {
                helper = manager.getConfig(CONFIG_FILE);
            } catch (Throwable ignored) {
                helper = null;
            }
            if (helper == null) {
                try {
                    helper = manager.getConfig();
                } catch (Throwable ignored) {
                    helper = null;
                }
            }
        }
        if (helper == null) {
            PluginLog.w("配置不可用，将使用默认值");
        } else {
            try {
                helper.reload();
            } catch (Throwable error) {
                PluginLog.w("重新加载配置失败: " + error);
            }
        }
        return new PluginConfig(helper);
    }

    /** 从磁盘重新读取配置（用户在设置界面修改后调用）。 */
    void reload() {
        if (helper == null) {
            return;
        }
        try {
            helper.reload();
        } catch (Throwable error) {
            PluginLog.w("重新加载配置失败: " + error);
        }
    }

    boolean isAvailable() {
        return helper != null;
    }

    boolean getBoolean(String key, boolean fallback) {
        Boolean parsed = parseBoolean(raw(key));
        if (parsed != null) {
            return parsed;
        }
        Object typed = typed(key, Boolean.valueOf(fallback));
        return typed instanceof Boolean ? (Boolean) typed : fallback;
    }

    int getInt(String key, int fallback) {
        Double parsed = parseNumber(raw(key));
        if (parsed != null) {
            return (int) Math.round(parsed);
        }
        Object typed = typed(key, Integer.valueOf(fallback));
        return typed instanceof Number ? ((Number) typed).intValue() : fallback;
    }

    double getDouble(String key, double fallback) {
        Double parsed = parseNumber(raw(key));
        if (parsed != null) {
            return parsed;
        }
        Object typed = typed(key, Double.valueOf(fallback));
        return typed instanceof Number ? ((Number) typed).doubleValue() : fallback;
    }

    String getString(String key, String fallback) {
        String text = raw(key);
        return text.isEmpty() ? fallback : text;
    }

    /**
     * 按默认值的类型向宿主取值。
     *
     * <p>默认值绝不能为 null，否则宿主必定返回 null。
     */
    @SuppressWarnings("unchecked")
    private <T> T typed(String key, T fallback) {
        if (helper == null || fallback == null) {
            return fallback;
        }
        try {
            T value = helper.get(key, fallback);
            return value == null ? fallback : value;
        } catch (Throwable error) {
            PluginLog.w("读取配置项 " + key + " 失败: " + error);
            return fallback;
        }
    }

    /**
     * 以文本形式读取原始内容。
     *
     * <p>这是最稳定的读法：无论宿主把值写成 JSON 字符串（{@code list} 选项）、
     * 数字还是布尔，都能拿到原始字面量，便于本地解析。
     * 键不存在时返回空串。
     */
    private String raw(String key) {
        if (helper == null) {
            return "";
        }
        try {
            String text = helper.get(key, "");
            return text == null ? "" : text.trim();
        } catch (Throwable error) {
            PluginLog.w("读取配置项 " + key + " 失败: " + error);
            return "";
        }
    }

    private static Boolean parseBoolean(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        if ("true".equalsIgnoreCase(text)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(text)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static Double parseNumber(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)) {
            return null;
        }
        try {
            return Double.valueOf(trimmed);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    /** 写入运行期状态并落盘，失败静默。 */
    void put(String key, Object value) {
        if (helper == null) {
            return;
        }
        try {
            helper.set(key, value);
            helper.save();
        } catch (Throwable error) {
            PluginLog.w("保存配置项 " + key + " 失败: " + error);
        }
    }
}
