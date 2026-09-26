package harness;

import com.xuncorp.spw.workshop.api.Channel;
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint;
import com.xuncorp.spw.workshop.api.PluginContext;
import com.xuncorp.spw.workshop.api.WorkshopApi;
import com.xuncorp.spw.workshop.api.WorkshopPluginManager;
import com.xuncorp.spw.workshop.api.config.ConfigHelper;
import com.xuncorp.spw.workshop.api.config.ConfigManager;
import org.pf4j.DefaultPluginStatusProvider;
import org.pf4j.ManifestPluginDescriptorFinder;
import org.pf4j.Plugin;
import org.pf4j.PluginDescriptor;
import org.pf4j.PluginDescriptorFinder;
import org.pf4j.PluginFactory;
import org.pf4j.PluginRuntimeException;
import org.pf4j.PluginState;
import org.pf4j.PluginStatusProvider;
import org.pf4j.PluginWrapper;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 用真实的 PF4J 加载链路验证插件包（不属于插件本体）。
 *
 * <p>模拟宿主的 VoxzenPluginManager / VoxzenPluginFactory 行为：
 * 从 {@code <plugin>/META-INF/MANIFEST.MF} 读取描述符、注入 PluginContext、
 * 通过 extensions.idx 或 META-INF/services 发现拓展点。
 */
public final class PluginLoadTest {

    private static final String PLUGIN_ID = "com.spw.miniplayer";
    private static final List<String> FAILURES = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path workspace = Paths.get(args.length > 0 ? args[0] : "build/harness");
        Path spmod = Paths.get(args.length > 1 ? args[1] : "build/gen/spmod");
        Path pluginsRoot = workspace.resolve("workshop").resolve("plugins");
        Path media = workspace.resolve("media");

        // 测试模式：不创建窗口，避免影响桌面
        System.setProperty("spw.miniplayer.noWindow", "true");

        // 版本号只从插件包 MANIFEST 读取，验证代码不得硬编码
        String expectedVersion = readManifestVersion(spmod.resolve("META-INF/MANIFEST.MF"));
        check(expectedVersion != null && !expectedVersion.isEmpty(),
                "插件包 MANIFEST 声明 Plugin-Version=" + expectedVersion);
        System.out.println("== 待验证版本: " + expectedVersion + " ==");

        // 1. 安装插件（模拟宿主导入后的目录结构）
        Path installed = pluginsRoot.resolve("plugin-" + PLUGIN_ID + "-" + expectedVersion);

        // 升级场景预检：PF4J 以插件 ID 为键，同一 ID 只会加载先扫描到的那个目录，
        // 旧版本目录留着会「遮蔽」新版本，导致升级后行为没变。
        List<Path> staleVersions = findStaleVersionDirs(pluginsRoot, expectedVersion);
        if (!staleVersions.isEmpty()) {
            System.out.println("  [i]    检测到旧版本目录 " + staleVersions.size()
                    + " 个，PF4J 会优先加载它们、新版本不会生效");
            for (Path stale : staleVersions) {
                System.out.println("         清理 " + stale.getFileName());
                deleteRecursively(stale);
            }
        }
        check(findStaleVersionDirs(pluginsRoot, expectedVersion).isEmpty(),
                "插件目录中只保留当前版本 " + expectedVersion + "（升级需删除旧版本目录）");

        if (Files.exists(installed)) {
            deleteRecursively(installed);
        }
        copyRecursively(spmod, installed);
        check(Files.isRegularFile(installed.resolve("META-INF/MANIFEST.MF")),
                "插件根 META-INF/MANIFEST.MF 存在");
        check(Files.isRegularFile(installed.resolve("classes/META-INF/extensions.idx")),
                "classes/META-INF/extensions.idx 存在");
        check(Files.isRegularFile(installed.resolve(
                        "classes/META-INF/services/com.xuncorp.spw.workshop.api.PlaybackExtensionPoint")),
                "META-INF/services 拓展点声明存在");

        // 2. 注入 WorkshopApi 桩
        WorkshopApi stub = new StubApi();
        boolean injected = injectApiInstance(stub);
        check(injected, "WorkshopApi.instance 注入成功");

        // 3. 用 PF4J 加载
        TestPluginManager manager = new TestPluginManager(pluginsRoot);
        manager.loadPlugins();
        List<PluginWrapper> wrappers = manager.getPlugins();
        check(wrappers.size() == 1, "发现 1 个插件，实际 " + wrappers.size());
        for (PluginWrapper wrapper : wrappers) {
            PluginDescriptor descriptor = wrapper.getDescriptor();
            System.out.println("  -> id=" + descriptor.getPluginId()
                    + " version=" + descriptor.getVersion()
                    + " class=" + descriptor.getPluginClass());
        }

        manager.startPlugins();
        PluginWrapper wrapper = manager.getPlugin(PLUGIN_ID);
        check(wrapper != null, "插件被识别（ID=" + PLUGIN_ID + "）");
        if (wrapper == null) {
            summary();
            return;
        }
        check(expectedVersion.equals(wrapper.getDescriptor().getVersion()),
                "PF4J 描述符版本与 MANIFEST 一致（" + expectedVersion + "），实际 "
                        + wrapper.getDescriptor().getVersion());
        check(installed.getFileName().toString().equals(
                        "plugin-" + PLUGIN_ID + "-" + expectedVersion),
                "安装目录名带正确版本号: " + installed.getFileName());
        check(wrapper.getPluginState() == PluginState.STARTED,
                "插件状态为 STARTED，实际 " + wrapper.getPluginState());

        // 4. 拓展点发现
        List<PlaybackExtensionPoint> extensions = manager.getExtensions(PlaybackExtensionPoint.class);
        check(extensions.size() == 1, "发现 1 个 PlaybackExtensionPoint，实际 " + extensions.size());

        // 5. 驱动回调
        Path sample = media.resolve("plain.wav");
        for (PlaybackExtensionPoint extension : extensions) {
            extension.onBeforeLoadLyrics(new PlaybackExtensionPoint.MediaItem(
                    "测试歌曲", "测试歌手", "测试专辑", "测试专辑艺术家", sample.toString()));
            extension.onStateChanged(PlaybackExtensionPoint.State.Ready);
            extension.onIsPlayingChanged(true);
            extension.onPositionUpdated(31_000);
        }
        Thread.sleep(1200);

        // 6. 日志核对
        Path log = workspace.resolve("workshop/data/" + PLUGIN_ID + "/mini-player.log");
        String logText = Files.isRegularFile(log)
                ? new String(Files.readAllBytes(log), StandardCharsets.UTF_8) : "";
        check(!logText.isEmpty(), "插件写出日志文件");
        check(logText.contains("迷你播放器启动") || logText.contains("迷你播放器已就绪"),
                "日志中出现启动记录");

        // 7. 停止
        manager.stopPlugin(PLUGIN_ID);
        check(manager.getPlugin(PLUGIN_ID).getPluginState() == PluginState.STOPPED,
                "插件可正常停止");
        manager.unloadPlugins();

        summary();
    }

    private static void check(boolean condition, String message) {
        System.out.println((condition ? "  [OK]   " : "  [FAIL] ") + message);
        if (!condition) {
            FAILURES.add(message);
        }
    }

    /** 列出插件目录下同一插件 ID 的其它版本目录。 */
    private static List<Path> findStaleVersionDirs(Path pluginsRoot, String expectedVersion) {
        List<Path> stale = new ArrayList<>();
        if (!Files.isDirectory(pluginsRoot)) {
            return stale;
        }
        String prefix = "plugin-" + PLUGIN_ID + "-";
        try (Stream<Path> children = Files.list(pluginsRoot)) {
            for (Path child : (Iterable<Path>) children::iterator) {
                String name = child.getFileName().toString();
                if (!Files.isDirectory(child) || !name.startsWith(prefix)) {
                    continue;
                }
                if (!name.equals(prefix + expectedVersion)) {
                    stale.add(child);
                }
            }
        } catch (Throwable error) {
            System.out.println("  扫描插件目录失败: " + error);
        }
        stale.sort(null);
        return stale;
    }

    /** 从 MANIFEST.MF 中解析 Plugin-Version（按 jar 规范还原续行）。 */
    private static String readManifestVersion(Path manifest) {
        try {
            String text = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8);
            String unfolded = text.replace("\r\n ", "").replace("\n ", "");
            for (String line : unfolded.split("\r?\n")) {
                if (line.startsWith("Plugin-Version:")) {
                    return line.split(":", 2)[1].trim();
                }
            }
        } catch (Throwable error) {
            System.out.println("  读取 MANIFEST 失败: " + error);
        }
        return null;
    }

    private static void summary() {
        System.out.println();
        if (FAILURES.isEmpty()) {
            System.out.println("插件加载链路验证通过 ✅");
            System.exit(0);
        } else {
            System.out.println("存在失败项 ❌");
            for (String failure : FAILURES) {
                System.out.println("  - " + failure);
            }
            System.exit(1);
        }
    }

    private static boolean injectApiInstance(WorkshopApi stub) {
        try {
            Class<?> api = Class.forName("com.xuncorp.spw.workshop.api.WorkshopApi");

            // 情况一：@JvmStatic 直接在接口上生成了 instance 字段
            for (java.lang.reflect.Field field : api.getFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        && WorkshopApi.class.isAssignableFrom(field.getType())) {
                    field.set(null, stub);
                    return true;
                }
            }

            // 情况二：通过伴生对象注入（宿主就是这么做的）
            Object companion = null;
            try {
                companion = api.getField("Companion").get(null);
            } catch (Throwable ignored) {
                // 继续
            }
            if (companion != null) {
                for (Method method : companion.getClass().getMethods()) {
                    if ("setInstance".equals(method.getName()) && method.getParameterCount() == 1) {
                        method.invoke(companion, stub);
                        return true;
                    }
                }
                // 兜底：直接写伴生对象的私有字段
                for (java.lang.reflect.Field field : companion.getClass().getDeclaredFields()) {
                    if (WorkshopApi.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        field.set(companion, stub);
                        return true;
                    }
                }
            }
            System.out.println("  可用的 Companion 方法: "
                    + java.util.Arrays.toString(companion == null
                            ? new Object[0] : companion.getClass().getMethods()));
        } catch (Throwable error) {
            System.out.println("  注入失败: " + error);
        }
        return false;
    }

    private static void copyRecursively(Path source, Path target) throws Exception {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(Path path) throws Exception {
        try (Stream<Path> stream = Files.walk(path)) {
            List<Path> paths = new ArrayList<>();
            stream.forEach(paths::add);
            for (int i = paths.size() - 1; i >= 0; i--) {
                Files.deleteIfExists(paths.get(i));
            }
        }
    }

    // -------------------------------------------------------------- 测试替身

    /** 模拟宿主的 PluginManager：描述符从插件根 MANIFEST 读取，工厂注入 PluginContext。 */
    static final class TestPluginManager extends WorkshopPluginManager {

        TestPluginManager(Path pluginsRoot) {
            super(pluginsRoot);
        }

        @Override
        protected PluginDescriptorFinder createPluginDescriptorFinder() {
            return new ManifestPluginDescriptorFinder();
        }

        @Override
        protected PluginFactory createPluginFactory() {
            return new TestPluginFactory();
        }
    }

    static final class TestPluginFactory implements PluginFactory {

        @Override
        public Plugin create(PluginWrapper wrapper) {
            try {
                PluginDescriptor descriptor = wrapper.getDescriptor();
                ClassLoader loader = wrapper.getPluginClassLoader();
                Class<?> type = Class.forName(descriptor.getPluginClass(), true, loader);
                java.lang.reflect.Constructor<?> constructor =
                        type.getConstructor(PluginContext.class);
                PluginContext context = new PluginContext(
                        descriptor.getPluginId(),
                        descriptor.getVersion(),
                        wrapper.getPluginPath().toString(),
                        "1.18.5", // version-literal-ok 宿主版本，非插件版本
                        Channel.Steam);
                Plugin plugin = (Plugin) constructor.newInstance(context);
                return plugin;
            } catch (Throwable error) {
                throw new PluginRuntimeException(error, "创建插件实例失败");
            }
        }
    }

    /** 最小可用的 WorkshopApi 桩。 */
    static final class StubApi implements WorkshopApi {

        private final Ui ui = (text, type) ->
                System.out.println("  [toast:" + type + "] " + text);

        private final Playback playback = new Playback() {
            @Override
            public void changeExclusive(boolean exclusive) {
            }

            @Override
            public void pause() {
                System.out.println("  [playback] pause");
            }

            @Override
            public void play() {
                System.out.println("  [playback] play");
            }

            @Override
            public void previous() {
                System.out.println("  [playback] previous");
            }

            @Override
            public void next() {
                System.out.println("  [playback] next");
            }

            @Override
            public void seekTo(long position) {
                System.out.println("  [playback] seekTo " + position);
            }
        };

        private final Manager manager = new Manager() {
            @Override
            public ConfigManager createConfigManager(String pluginId) {
                return null;
            }

            @Override
            public ConfigManager createConfigManager() {
                return new ConfigManager() {
                    @Override
                    public ConfigHelper getConfig() {
                        return null;
                    }

                    @Override
                    public ConfigHelper getConfig(String path) {
                        return null;
                    }

                    @Override
                    public void addConfigChangeListener(java.util.function.Consumer consumer) {
                    }

                    @Override
                    public void addConfigChangeListener(String path,
                                                        java.util.function.Consumer consumer) {
                    }

                    @Override
                    public void removeConfigChangeListener(java.util.function.Consumer consumer) {
                    }
                };
            }
        };

        @Override
        public Playback getPlayback() {
            return playback;
        }

        @Override
        public Ui getUi() {
            return ui;
        }

        @Override
        public Manager getManager() {
            return manager;
        }
    }
}
