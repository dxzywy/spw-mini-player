package com.spw.miniplayer;

import com.xuncorp.spw.workshop.api.PluginContext;
import com.xuncorp.spw.workshop.api.SpwPlugin;

import java.io.InputStream;
import java.util.jar.Manifest;

/**
 * SPW 迷你播放器插件主类。
 *
 * <p>插件提供一个可贴边隐藏的小窗播放器：迷你封面、歌曲名与歌手、
 * 播放/暂停、上一首/下一首，以及宿主桌面歌词的开关控制。
 */
public class MiniPlayerPlugin extends SpwPlugin {

    private static volatile MiniPlayerController controller;

    public MiniPlayerPlugin(PluginContext pluginContext) {
        super(pluginContext);
    }

    @Override
    public void start() {
        try {
            PluginLog.init(getPluginContext());
            PluginLog.i("插件版本 " + pluginVersion());
            MiniPlayerController created = new MiniPlayerController(getPluginContext());
            controller = created;
            created.start();
        } catch (Throwable error) {
            PluginLog.e("插件启动失败", error);
        }
    }

    /**
     * 插件版本号。
     *
     * <p>从插件包自身的 {@code META-INF/MANIFEST.MF} 读取，与宿主工坊中显示的
     * {@code Plugin-Version} 同源。版本号只在仓库根目录的 {@code VERSION} 文件里维护，
     * 这里刻意不写死，避免打包时漏改。
     */
    private static String pluginVersion() {
        try (InputStream stream = MiniPlayerPlugin.class.getResourceAsStream(
                "/META-INF/MANIFEST.MF")) {
            if (stream != null) {
                String version = new Manifest(stream).getMainAttributes()
                        .getValue("Plugin-Version");
                if (version != null && !version.isEmpty()) {
                    return version;
                }
            }
        } catch (Throwable error) {
            PluginLog.w("读取插件版本失败: " + error);
        }
        return "(未知)";
    }

    @Override
    public void stop() {
        MiniPlayerController current = controller;
        if (current != null) {
            current.stop();
        }
        PluginLog.i("迷你播放器已停用");
    }

    @Override
    public void delete() {
        stop();
        PluginLog.i("迷你播放器已卸载");
    }

    @Override
    public void update() {
        PluginLog.i("迷你播放器已更新");
    }

    static MiniPlayerController controller() {
        return controller;
    }

    // ------------------------------------------------- 供配置界面反射调用

    /**
     * 配置界面的按钮入口：显示 / 隐藏迷你播放器。
     *
     * <p>由 {@code preference_config.json} 的 {@code on_click} 指定，
     * 宿主通过反射调用静态无参方法，因此必须是 {@code public static}。
     */
    @SuppressWarnings("unused")
    public static void toggleMiniPlayer() {
        MiniPlayerController current = controller;
        if (current == null) {
            PluginLog.w("插件尚未启动，无法切换迷你播放器");
            return;
        }
        current.toggleVisible();
    }

    /**
     * 配置界面的按钮入口：立即收起为封面悬浮窗。
     */
    @SuppressWarnings("unused")
    public static void collapseMiniPlayer() {
        MiniPlayerController current = controller;
        if (current == null) {
            PluginLog.w("插件尚未启动，无法收起迷你播放器");
            return;
        }
        current.collapseToBubble();
    }
}
