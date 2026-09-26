package com.spw.miniplayer;

import com.xuncorp.spw.workshop.api.PluginContext;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 极简日志工具。
 *
 * <p>宿主是 Windows GUI 进程，标准输出不可见，因此日志同时写入
 * {@code workshop/data/<pluginId>/mini-player.log}，方便排查问题。
 */
final class PluginLog {

    private static final Object LOCK = new Object();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    private static volatile Path logFile;

    private PluginLog() {
    }

    /** 由插件主类在 start() 时调用，确定日志落盘位置。 */
    static void init(PluginContext context) {
        if (logFile != null) {
            return;
        }
        Path base = resolveDataDir(context);
        if (base == null) {
            return;
        }
        try {
            Files.createDirectories(base);
            logFile = base.resolve("mini-player.log");
        } catch (Exception ignored) {
            // 无法写文件时退化为仅内存日志
        }
    }

    /**
     * 推断插件数据目录：优先使用 {@code <workshop>/data/<pluginId>}，
     * 这样与宿主给出的插件安装路径保持一致；失败时退回 %APPDATA%。
     */
    static Path resolveDataDir(PluginContext context) {
        String pluginId = context == null ? "com.spw.miniplayer" : context.getPluginId();
        try {
            if (context != null && context.getPluginPath() != null
                    && !context.getPluginPath().isEmpty()) {
                Path pluginPath = Paths.get(context.getPluginPath()).toAbsolutePath().normalize();
                // 典型结构：<...>/workshop/plugins/plugin-<id>-<version>
                Path pluginsDir = pluginPath.getParent();
                Path workshopDir = pluginsDir == null ? null : pluginsDir.getParent();
                if (workshopDir != null
                        && "workshop".equals(String.valueOf(workshopDir.getFileName()))) {
                    return workshopDir.resolve("data").resolve(pluginId);
                }
            }
        } catch (Exception ignored) {
            // 继续尝试 APPDATA
        }
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isEmpty()) {
            appData = System.getProperty("user.home");
        }
        if (appData == null || appData.isEmpty()) {
            return null;
        }
        Path spwData = Paths.get(appData, "Salt Player for Windows", "workshop", "data", pluginId);
        if (!Files.isDirectory(spwData)) {
            Path generic = Paths.get(appData, "workshop", "data", pluginId);
            if (!Files.isDirectory(generic) && !Files.isDirectory(spwData)) {
                // 两者都不存在时优先使用 Salt Player 专属目录
                return spwData;
            }
            if (!Files.isDirectory(spwData)) {
                return generic;
            }
        }
        return spwData;
    }

    static void i(String message) {
        write("INFO ", message, null);
    }

    static void w(String message) {
        write("WARN ", message, null);
    }

    static void e(String message, Throwable error) {
        write("ERROR", message, error);
    }

    private static void write(String level, String message, Throwable error) {
        String line = "[" + FMT.format(new Date()) + "] " + level + " " + message;
        if (error != null) {
            StringWriter sw = new StringWriter();
            error.printStackTrace(new PrintWriter(sw));
            line = line + System.lineSeparator() + sw;
        }
        System.err.println("[spw-mini-player] " + line);
        Path file = logFile;
        if (file == null) {
            return;
        }
        synchronized (LOCK) {
            try (FileWriter writer = new FileWriter(file.toFile(), StandardCharsets.UTF_8, true)) {
                writer.write(line);
                writer.write(System.lineSeparator());
            } catch (Exception ignored) {
                // 日志失败不影响插件运行
            }
        }
    }

    /** 供调试使用：暴露日志文件，可能为 null。 */
    static File logFileOrNull() {
        Path file = logFile;
        return file == null ? null : file.toFile();
    }
}
