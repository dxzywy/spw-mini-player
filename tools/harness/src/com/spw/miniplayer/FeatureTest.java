package com.spw.miniplayer;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 新功能自检（不属于插件本体）。
 *
 * <p>覆盖三项：
 * <ol>
 *   <li>从悬浮窗唤起宿主主窗口：命中测试 → 桥接反射 → 宿主状态真的被改写；</li>
 *   <li>进度条颜色自定义：十六进制解析 + 实际像素颜色随配置变化；</li>
 *   <li>封面主题色背景：提取 → 派生 → 与不透明度叠加后的实际像素。</li>
 * </ol>
 *
 * <p>渲染断言全部在离屏 BufferedImage 上做，不依赖真实窗口。
 */
public final class FeatureTest {

    private static final List<String> FAILURES = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path root = new File(args.length > 0 ? args[0] : "build/harness").toPath();
        Path out = root.resolve("preview");
        java.nio.file.Files.createDirectories(out);

        System.out.println("== 1. 打开 / 收起宿主主窗口 ==");
        checkMainEntryExists();
        checkToggleStrategy();

        System.out.println("== 2. 进度条颜色自定义 ==");
        checkColorParsing();
        checkProgressColorPixel(out);

        System.out.println("== 3. 封面主题色背景 ==");
        checkCoverExtraction();
        checkTintAndOpacity(out);

        System.out.println("== 4. 配置项清理 ==");
        checkPreferenceCleanup();

        System.out.println();
        if (FAILURES.isEmpty()) {
            System.out.println("新功能自检全部通过");
            System.exit(0);
        } else {
            System.out.println("存在失败项");
            for (String failure : FAILURES) {
                System.out.println("  - " + failure);
            }
            System.exit(1);
        }
    }

    private static void check(boolean condition, String message) {
        System.out.println((condition ? "  [OK]   " : "  [FAIL] ") + message);
        if (!condition) {
            FAILURES.add(message);
        }
    }

    // ---------------------------------------------------------- 1. 主窗口

    /** 展开态与悬浮层的「打开播放器」按钮都要能被点到。 */
    private static void checkMainEntryExists() {
        MiniPlayerView view = new MiniPlayerView();
        view.setSize(Theme.WIDTH, Theme.HEIGHT);
        // 顶部一行从右往左依次为 关闭 / 桌面歌词 / 打开播放器
        int x = Theme.WIDTH - 14 - 24 - 6 - 24 - 6 - 12;
        MiniPlayerView.Hit hit = view.hitTest(new java.awt.Point(x, 28));
        check(hit == MiniPlayerView.Hit.OPEN_PLAYER,
                "展开态命中「打开播放器」按钮，实际=" + hit);

        MiniPlayerView bubble = new MiniPlayerView();
        bubble.setSize(Theme.COMPACT_SIZE, Theme.COMPACT_SIZE);
        bubble.setCompact(true);
        bubble.setCompactHover(true);
        MiniPlayerView.Hit bubbleHit = bubble.hitTest(new java.awt.Point(13, 13));
        check(bubbleHit == MiniPlayerView.Hit.OPEN_PLAYER,
                "悬浮层命中「打开播放器」按钮，实际=" + bubbleHit);
    }

    /**
     * 覆盖「再次点击按宿主的关闭主窗口设置收起」这条规则。
     *
     * <p>用替身 AppConfig 直接观察宿主的可见状态有没有被改写：
     * <ul>
     *   <li>策略 = SystemTray → 收起时走宿主入口，{@code mainWindowVisible} 变为 false；</li>
     *   <li>策略 = ExitProgress → 收起时<b>绝不</b>碰宿主入口，状态保持 true，只做任务栏最小化。</li>
     * </ul>
     */
    private static void checkToggleStrategy() throws Exception {
        CountDownLatch prepared = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        List<String> steps = new ArrayList<>();

        HostBridgeWorker.prepare(prepared::countDown);
        check(prepared.await(20, TimeUnit.SECONDS), "宿主桥接解析完成");
        check(MainWindowBridge.isAvailable(), "已对接宿主主窗口开关");
        check(MainWindowBridge.closeStrategy() != MainWindowBridge.CloseStrategy.UNKNOWN,
                "识别到宿主的关闭主窗口策略，实际=" + MainWindowBridge.closeStrategy());

        com.xuncorp.voxzen.util.AppConfig.CloseMainWindowStrategy tray =
                com.xuncorp.voxzen.util.AppConfig.CloseMainWindowStrategy.SystemTray;
        com.xuncorp.voxzen.util.AppConfig.CloseMainWindowStrategy exit =
                com.xuncorp.voxzen.util.AppConfig.CloseMainWindowStrategy.ExitProgress;

        HostBridgeWorker.submit(() -> {
            try {
                Object appConfig = com.xuncorp.voxzen.util.AppConfig.INSTANCE;
                java.lang.reflect.Method setVisible =
                        appConfig.getClass().getMethod("updateMainWindowVisible", boolean.class);
                java.lang.reflect.Method setStrategy = appConfig.getClass()
                        .getMethod("updateCloseMainWindowStrategy",
                                com.xuncorp.voxzen.util.AppConfig.CloseMainWindowStrategy.class);
                java.lang.reflect.Method getVisible =
                        appConfig.getClass().getMethod("getMainWindowVisible");

                steps.add(onWorkerThread());

                // 策略 = 最小化到系统托盘
                setStrategy.invoke(appConfig, tray);
                setVisible.invoke(appConfig, Boolean.FALSE);
                steps.add("tray/open=" + MainWindowBridge.toggle()
                        + " visible=" + getVisible.invoke(appConfig));
                steps.add("tray/close=" + MainWindowBridge.toggle()
                        + " visible=" + getVisible.invoke(appConfig));

                // 策略 = 退出应用（插件必须绕开宿主的关闭入口）
                setStrategy.invoke(appConfig, exit);
                setVisible.invoke(appConfig, Boolean.TRUE);
                steps.add("exit/close=" + MainWindowBridge.toggle()
                        + " visible=" + getVisible.invoke(appConfig));
                steps.add("exit/reopen=" + MainWindowBridge.toggle()
                        + " visible=" + getVisible.invoke(appConfig));
            } catch (Throwable error) {
                check(false, "主窗口切换失败: " + error);
            } finally {
                finished.countDown();
            }
        });
        check(finished.await(20, TimeUnit.SECONDS), "主窗口切换序列执行完毕");
        for (String step : steps) {
            System.out.println("  [i]     " + step);
        }
        check(steps.size() == 5, "四步切换都跑到了");
        check(steps.contains("true"), "宿主调用落在专用线程 spw-mini-host 上");
        check(steps.stream().anyMatch(s -> s.startsWith("tray/open=已打开播放器")
                        && s.endsWith("visible=true")),
                "托盘策略：第一次点击打开主窗口，宿主状态变为可见");
        check(steps.stream().anyMatch(s -> s.startsWith("tray/close=已最小化到系统托盘")
                        && s.endsWith("visible=false")),
                "托盘策略：再次点击走宿主关闭入口，宿主状态变为不可见");
        check(steps.stream().anyMatch(s -> s.startsWith("exit/close=已最小化到任务栏")
                        && s.endsWith("visible=true")),
                "退出策略：再次点击只最小化到任务栏，宿主状态保持可见（不退出应用）");
        check(steps.stream().anyMatch(s -> s.startsWith("exit/reopen=已打开播放器")
                        && s.endsWith("visible=true")),
                "退出策略：再一次点击可以重新打开");
    }

    private static String onWorkerThread() {
        return String.valueOf("spw-mini-host".equals(Thread.currentThread().getName()));
    }

    /** 配置项清理：不再有旧的按钮入口，也不应残留旧文案。 */
    private static void checkPreferenceCleanup() throws Exception {
        String preference = readFile("plugin/preference_config.json");
        if (preference != null) {
            check(!preference.contains("collapseMiniPlayer"), "配置界面已移除「收起为封面小窗」入口");
            check(!preference.contains("toggleMiniPlayer"), "配置界面已移除旧的显示/隐藏按钮入口");
            check(preference.contains("\"mini_player_visible\""),
                    "新增 mini_player_visible 配置项");
            check(preference.contains("\"switch\""), "显示迷你播放器使用 switch 控件");
        }
        String view = readFile("src/main/java/com/spw/miniplayer/MiniPlayerView.java");
        if (view != null) {
            check(!view.contains("点击展开"), "悬浮窗不再绘制「点击展开」提示文字");
        }
        String theme = readFile("src/main/java/com/spw/miniplayer/Theme.java");
        if (theme != null) {
            check(!theme.contains("hintText") && !theme.contains("ON_SCRIM_TEXT"),
                    "移除了随提示文字一起失效的 Theme.hintText / ON_SCRIM_TEXT");
        }
    }

    private static String readFile(String relative) throws Exception {
        java.io.File file = new java.io.File(relative);
        if (!file.isFile()) {
            System.out.println("  [i]     跳过检查（未找到 " + relative + "）");
            return null;
        }
        return new String(java.nio.file.Files.readAllBytes(file.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------- 2. 进度条颜色

    private static void checkColorParsing() {
        check(MiniPlayerController.isAutoColor("auto"), "auto 表示跟随封面");
        check(MiniPlayerController.isAutoColor("跟随封面"), "「跟随封面」表示跟随封面");
        check(MiniPlayerController.parseColor("auto") == null, "auto 不产生固定颜色");

        Color six = MiniPlayerController.parseColor("#FF4081");
        check(six != null && six.getRed() == 0xFF && six.getGreen() == 0x40
                        && six.getBlue() == 0x81 && six.getAlpha() == 255,
                "#RRGGBB 解析正确，实际=" + describe(six));

        Color three = MiniPlayerController.parseColor("f0a");
        check(three != null && three.getRed() == 0xFF && three.getGreen() == 0x00
                        && three.getBlue() == 0xAA,
                "省略 # 的 #RGB 解析正确，实际=" + describe(three));

        Color eight = MiniPlayerController.parseColor("#80FF4081");
        check(eight != null && eight.getAlpha() == 0x80 && eight.getRed() == 0xFF,
                "#AARRGGBB 保留透明度，实际=" + describe(eight));

        check(MiniPlayerController.parseColor("#12345") == null, "长度非法时返回 null");
        check(MiniPlayerController.parseColor("#GGHHII") == null, "非十六进制字符时返回 null");
    }

    /** 改配置后进度条像素必须真的变色（无需重启）。 */
    private static void checkProgressColorPixel(Path out) throws Exception {
        MiniPlayerView view = new MiniPlayerView();
        view.setSize(Theme.WIDTH, Theme.HEIGHT);
        view.setNowPlaying("晴天", "周杰伦");
        view.setProgress(120_000, 240_000);

        view.setProgressColor(null, true);
        Color before = progressPixel(view);
        view.setProgressColor(new Color(0xFF, 0x40, 0x81), false);
        Color after = progressPixel(view);
        check(!sameRgb(before, after), "自定义颜色改变了进度条像素："
                + describe(before) + " -> " + describe(after));
        check(sameRgb(after, new Color(0xFF, 0x40, 0x81)),
                "进度条像素等于配置的颜色，实际=" + describe(after));
        writePng(view, out.resolve("12-custom-progress-color.png"));
    }

    // ------------------------------------------------------ 3. 封面主题色

    private static void checkCoverExtraction() {
        check(CoverTheme.dominant(null) == null, "无封面时主色为 null");

        Color seed = new Color(24, 96, 210);
        BufferedImage image = syntheticCover(seed);
        Color dominant = CoverTheme.dominant(image);
        check(dominant != null, "从封面中提取到主色，实际=" + describe(dominant));
        if (dominant != null) {
            float[] expected = Color.RGBtoHSB(seed.getRed(), seed.getGreen(), seed.getBlue(), null);
            float[] actual = Color.RGBtoHSB(dominant.getRed(), dominant.getGreen(),
                    dominant.getBlue(), null);
            check(Math.abs(expected[0] - actual[0]) < 0.08f,
                    "主色色相与封面主区域一致（期望 " + hue(expected)
                            + "°，实际 " + hue(actual) + "°）");
        }

        Theme.setDark(true);
        Color tintDark = CoverTheme.panelTint(seed);
        Color accentDark = CoverTheme.progressAccent(seed);
        float[] tintHsb = toHsb(tintDark);
        float[] accentHsb = toHsb(accentDark);
        check(tintHsb[2] < 0.35f, "深色主题下面板底色足够暗（亮度 "
                + String.format("%.2f", tintHsb[2]) + "）");
        check(accentHsb[2] > tintHsb[2] + 0.2f, "进度条色明显比面板底色亮，保证可见");
        check(accentHsb[1] >= tintHsb[1], "进度条色饱和度不低于面板底色");
        check(tintDark.getAlpha() == 255, "面板底色不带 alpha，透明度由视图叠加");

        Theme.setDark(false);
        Color tintLight = CoverTheme.panelTint(seed);
        float[] lightHsb = toHsb(tintLight);
        check(lightHsb[2] > 0.68f, "浅色主题下面板底色足够亮（亮度 "
                + String.format("%.2f", lightHsb[2]) + "）");
        Theme.setDark(true);
    }

    /** 主题色与不透明度必须是叠加关系：改不透明度只影响像素 alpha，不动 RGB。 */
    private static void checkTintAndOpacity(Path out) throws Exception {
        Color seed = new Color(196, 62, 84);
        BufferedImage cover = syntheticCover(seed);
        Color dominant = CoverTheme.dominant(cover);

        MiniPlayerView view = new MiniPlayerView();
        view.setSize(Theme.WIDTH, Theme.HEIGHT);
        view.setNowPlaying("晴天", "周杰伦 · 叶惠美");
        view.setCover(cover);
        view.setCoverDominant(dominant);
        view.setProgress(80_000, 240_000);

        Color expectedTint = CoverTheme.panelTint(dominant);
        Color tinted = panelPixel(view, 204);
        check(sameRgb(tinted, expectedTint),
                "面板底色取自封面主题色：期望 " + describe(expectedTint) + "，实际 " + describe(tinted));
        check(tinted.getAlpha() == 204, "不透明度 80% 叠加到主题色上（alpha="
                + tinted.getAlpha() + "）");

        Color half = panelPixel(view, 128);
        check(sameRgb(half, expectedTint), "换成 50% 不透明度后 RGB 不变");
        check(half.getAlpha() == 128, "换成 50% 不透明度后 alpha 变为 128，实际 "
                + half.getAlpha());

        view.setCoverTintEnabled(false);
        Color plain = panelPixel(view, 204);
        check(sameRgb(plain, Theme.color().panelBg), "关闭开关后回到主题原色："
                + describe(plain));
        view.setCoverTintEnabled(true);

        writePng(view, out.resolve("13-cover-tint-80.png"));
        view.setDarkTheme(false);
        writePng(view, out.resolve("14-cover-tint-light.png"));
        Theme.setDark(true);
    }

    // -------------------------------------------------------------- 工具

    /** 读取面板空白处（不含封面、文字与按钮）的像素。 */
    private static Color panelPixel(MiniPlayerView view, int alpha) {
        view.setPanelAlpha(alpha);
        BufferedImage canvas = new BufferedImage(Theme.WIDTH, Theme.HEIGHT,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        view.paintPreview(g);
        g.dispose();
        return new Color(canvas.getRGB(200, 94), true);
    }

    /** 读取进度条已播放段的像素。 */
    private static Color progressPixel(MiniPlayerView view) {
        BufferedImage canvas = new BufferedImage(Theme.WIDTH, Theme.HEIGHT,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        view.paintPreview(g);
        g.dispose();
        return new Color(canvas.getRGB(60, Theme.HEIGHT - 7), true);
    }

    /** 合成一张主色明确的封面（带少量杂色，验证走的是直方图挑选而非简单平均）。 */
    private static BufferedImage syntheticCover(Color base) {
        int size = 128;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(base);
        g.fillRect(0, 0, size, size);
        g.setColor(new Color(250, 248, 240));
        for (int i = 0; i < size; i += 32) {
            g.fillRect(i, 0, 6, 6);
        }
        g.dispose();
        return image;
    }

    private static void writePng(MiniPlayerView view, Path target) throws Exception {
        BufferedImage canvas = new BufferedImage(Theme.WIDTH + 80, Theme.HEIGHT + 80,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setPaint(new java.awt.GradientPaint(0, 0, new Color(28, 32, 48),
                Theme.WIDTH + 80, Theme.HEIGHT + 80, new Color(120, 96, 140)));
        g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        g.translate(40, 40);
        view.paintPreview(g);
        g.dispose();
        ImageIO.write(canvas, "png", target.toFile());
        System.out.println("  [OK]   " + target.getFileName());
    }

    private static float[] toHsb(Color color) {
        return Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
    }

    private static String hue(float[] hsb) {
        return String.format("%.0f", hsb[0] * 360);
    }

    private static boolean sameRgb(Color left, Color right) {
        return left.getRed() == right.getRed()
                && left.getGreen() == right.getGreen()
                && left.getBlue() == right.getBlue();
    }

    private static String describe(Color color) {
        if (color == null) {
            return "null";
        }
        return "#" + Integer.toHexString(color.getRGB() & 0xFFFFFFFF);
    }
}
