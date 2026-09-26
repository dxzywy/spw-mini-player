package com.spw.miniplayer;

import com.xuncorp.spw.workshop.api.config.ConfigHelper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 用宿主真实的配置实现（{@code androidx.compose.ui.hw}）测量 {@code ConfigHelper.get} 的语义。
 *
 * <p>该探针回答三个问题：
 * <ol>
 *   <li>传入 {@code null} 默认值时，读取结果是值还是 null？</li>
 *   <li>宿主把值写成 JSON 字符串（如 {@code "40"}）时，按 Integer / Double 读取能否取到？</li>
 *   <li>按 String 默认值读取，能否稳定拿到原始文本？</li>
 * </ol>
 */
public final class HostConfigProbe {

    private static final List<String> FAILURES = new ArrayList<>();

    private HostConfigProbe() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("spw-cfg-probe");
        Path file = dir.resolve("config.json");
        String content = "{\"last_x\":1621,\"last_y\":516,\"last_edge\":\"right\","
                + "\"opacity\":\"40\",\"hide_delay_ms\":3000.0,\"hide_delay\":\"1.5\","
                + "\"auto_hide\":true}";
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        System.out.println("== 构造宿主真实配置实现 ==");
        ConfigHelper helper = openHostHelper(file);
        System.out.println("  实现类: " + helper.getClass().getName());
        System.out.println("  配置文件: " + helper.getConfigPath());
        System.out.println();

        Method get = ConfigHelper.class.getMethod("get", String.class, Object.class);

        System.out.println("== 1. 传 null 默认值（插件旧实现的做法）==");
        for (String key : new String[]{"hide_delay", "opacity", "auto_hide", "last_x", "last_edge"}) {
            Object value = get.invoke(helper, key, null);
            System.out.println("  get(\"" + key + "\", null) = " + value);
        }
        boolean nullAlwaysNull = true;
        for (String key : new String[]{"hide_delay", "opacity", "auto_hide", "last_x", "last_edge"}) {
            if (get.invoke(helper, key, null) != null) {
                nullAlwaysNull = false;
            }
        }
        check(nullAlwaysNull, "传 null 默认值时，宿主一律返回 null（配置项永远读不到）");
        System.out.println();

        System.out.println("== 2. 按强类型默认值读取（宿主会把 list 值存成 JSON 字符串）==");
        System.out.println("  get(\"hide_delay\", 0.0)  = " + get.invoke(helper, "hide_delay", 0.0));
        System.out.println("  get(\"opacity\", 0)       = " + get.invoke(helper, "opacity", 0));
        System.out.println("  get(\"last_x\", 0)        = " + get.invoke(helper, "last_x", 0));
        System.out.println("  get(\"auto_hide\", false) = " + get.invoke(helper, "auto_hide", false));
        System.out.println("  get(\"dock_edge\", \"z\")  = " + get.invoke(helper, "dock_edge", "z"));
        System.out.println();

        System.out.println("== 3. 按 String 默认值读取（拿原始文本）==");
        for (String key : new String[]{"hide_delay", "opacity", "auto_hide", "last_x", "last_edge"}) {
            Object raw = get.invoke(helper, key, "");
            System.out.println("  get(\"" + key + "\", \"\") = " + quote(String.valueOf(raw)));
        }
        check("1.5".equals(get.invoke(helper, "hide_delay", "")), "String 默认值读到 \"1.5\"");
        check("40".equals(get.invoke(helper, "opacity", "")), "String 默认值读到 \"40\"");
        check("true".equals(get.invoke(helper, "auto_hide", "")), "String 默认值读到 \"true\"");
        check("1621".equals(get.invoke(helper, "last_x", "")), "String 默认值读到 \"1621\"");
        check("".equals(get.invoke(helper, "not_exists", "")), "缺失键返回默认值空串");
        System.out.println();

        System.out.println("== 4. 宿主 set 的落盘形态 ==");
        helper.set("probe_text", "hello");
        helper.set("probe_int", 42);
        helper.set("probe_bool", Boolean.TRUE);
        helper.set("probe_double", 2.5d);
        helper.save();
        System.out.println("  " + new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        System.out.println();

        System.out.println("== 5. 生产代码 PluginConfig 的读取结果 ==");
        PluginConfig config = PluginConfig.attach(helper);
        int legacyMs = legacyReadHideDelayMs(helper);
        System.out.println("  旧实现 readHideDelayMs() = " + legacyMs + " ms");
        check(legacyMs == 500, "旧实现读不到配置，永远退化为默认 500ms（这就是「延迟无效」）");

        check(config.getDouble("hide_delay", -1.0) == 1.5,
                "getDouble(\"hide_delay\") = " + config.getDouble("hide_delay", -1.0));
        check(config.getInt("opacity", -1) == 40,
                "getInt(\"opacity\") = " + config.getInt("opacity", -1));
        check(config.getInt("last_x", -1) == 1621,
                "getInt(\"last_x\") = " + config.getInt("last_x", -1));
        check(config.getInt("last_y", -1) == 516,
                "getInt(\"last_y\") = " + config.getInt("last_y", -1));
        check(config.getBoolean("auto_hide", false),
                "getBoolean(\"auto_hide\") = " + config.getBoolean("auto_hide", false));
        check("right".equals(config.getString("last_edge", "")),
                "getString(\"last_edge\") = " + config.getString("last_edge", ""));
        check("right".equals(config.getString("dock_edge", "right")),
                "缺失键回退：getString(\"dock_edge\", \"right\")");
        check(config.getInt("hide_delay_ms", -1) == 3000,
                "兼容旧毫秒键 getInt(\"hide_delay_ms\") = " + config.getInt("hide_delay_ms", -1));
        check(config.getBoolean("not_exists", true), "缺失布尔键回退默认值");
        check(config.getInt("not_exists", 7) == 7, "缺失整数键回退默认值");
        check(config.getDouble("not_exists", 2.5) == 2.5, "缺失浮点键回退默认值");

        int effectiveMs = (int) Math.round(
                Math.max(0.05, config.getDouble("hide_delay", -1.0)) * 1000.0);
        System.out.println("  生效的自动收起延迟 = " + effectiveMs + " ms");
        check(effectiveMs == 1500, "自动收起延迟按配置生效（1.5 秒 = 1500ms）");

        System.out.println("== 6. 写入后重新读取（位置记忆链路）==");
        config.put("last_edge", "left");
        config.put("last_x", 321);
        config.put("last_y", 654);
        config.reload();
        check("left".equals(config.getString("last_edge", "")),
                "写入 last_edge 后可读回: " + config.getString("last_edge", ""));
        check(config.getInt("last_x", -1) == 321,
                "写入 last_x 后可读回: " + config.getInt("last_x", -1));
        check(config.getInt("last_y", -1) == 654,
                "写入 last_y 后可读回: " + config.getInt("last_y", -1));

        System.out.println();
        if (FAILURES.isEmpty()) {
            System.out.println("宿主配置语义探针全部通过 ✅");
        } else {
            System.out.println("存在失败项 ❌");
            for (String failure : FAILURES) {
                System.out.println("  - " + failure);
            }
            System.exit(1);
        }
    }

    /** 复刻修复前的 PluginConfig 读取方式：一律传 null 默认值。 */
    private static int legacyReadHideDelayMs(ConfigHelper helper) {
        Double seconds = helper.get("hide_delay", null);
        if (seconds != null) {
            return (int) Math.round(Math.max(0.05, seconds) * 1000.0);
        }
        Integer legacy = helper.get("hide_delay_ms", null);
        return legacy == null ? 500 : legacy;
    }

    private static ConfigHelper openHostHelper(Path configDir) throws Exception {
        Class<?> impl = Class.forName("androidx.compose.ui.hw");
        Constructor<?> constructor = impl.getDeclaredConstructor(Path.class);
        constructor.setAccessible(true);
        return (ConfigHelper) constructor.newInstance(configDir);
    }

    private static String quote(String text) {
        return text == null ? "null" : "\"" + text + "\"";
    }

    private static void check(boolean condition, String label) {
        System.out.println((condition ? "  [OK]   " : "  [FAIL] ") + label);
        if (!condition) {
            FAILURES.add(label);
        }
    }
}
