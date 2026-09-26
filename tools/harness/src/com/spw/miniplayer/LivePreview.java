package com.spw.miniplayer;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 真机预览（不属于插件本体）：真实创建贴边小窗并截屏。
 *
 * <p>验证窗口透明、始终置顶、贴边吸附、收起为封面悬浮窗等行为。
 * 运行时会在桌面上短暂出现窗口，随后自动关闭。
 */
public final class LivePreview {

    public static void main(String[] args) throws Exception {
        Path workspace = Paths.get(args.length > 0 ? args[0] : "build/harness");
        Path out = workspace.resolve("live");
        Files.createDirectories(out);
        Path cover = workspace.resolve("media/embedded.mp3");

        MiniWindow[] holder = new MiniWindow[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            MiniPlayerController controller = new MiniPlayerController(null);
            MiniWindow window = new MiniWindow(controller);
            holder[0] = window;
            window.setDefaultEdge(MiniWindow.Edge.RIGHT);
            window.setAutoHide(false);
            MiniPlayerView view = window.view();
            view.setNowPlaying("Someone Like You", "Adele · 21 (Deluxe Edition)");
            view.setCover(CoverArtLoader.loadAndScale(cover.toString(), 192));
            view.setPlaying(true);
            view.setLyrics(true, true);
            view.setProgress(134_000, 300_000);
            window.show(true);
        });

        MiniWindow window = holder[0];
        if (window == null) {
            System.out.println("窗口创建失败");
            System.exit(1);
        }

        Thread.sleep(900);
        System.out.println("  屏幕: " + java.awt.Toolkit.getDefaultToolkit().getScreenSize()
                + " 缩放: " + java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration().getDefaultTransform());
        System.out.println("  窗口: " + describe(window));
        capture(out.resolve("live-01-expanded.png"), window);
        captureFullScreen(out.resolve("live-00-fullscreen.png"));

        javax.swing.SwingUtilities.invokeAndWait(window::collapse);
        Thread.sleep(900);
        System.out.println("  收起后: " + describe(window));
        capture(out.resolve("live-02-collapsed-bubble.png"), window);

        javax.swing.SwingUtilities.invokeAndWait(() -> {
            window.view().setCompactHover(true);
            window.view().setHover(MiniPlayerView.Hit.TOGGLE_PLAY);
        });
        Thread.sleep(500);
        capture(out.resolve("live-03-bubble-hover.png"), window);

        javax.swing.SwingUtilities.invokeAndWait(window::expand);
        Thread.sleep(900);
        System.out.println("  展开后: " + describe(window));
        capture(out.resolve("live-04-expanded-again.png"), window);

        // 浅色主题真机效果
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            Theme.setDark(false);
            window.view().repaint();
            window.applyTheme();
        });
        Thread.sleep(700);
        System.out.println("  浅色: " + describe(window));
        capture(out.resolve("live-05-light-expanded.png"), window);

        javax.swing.SwingUtilities.invokeAndWait(window::collapse);
        Thread.sleep(800);
        capture(out.resolve("live-06-light-bubble.png"), window);

        javax.swing.SwingUtilities.invokeAndWait(window::dispose);
        System.out.println("真机预览完成");
        System.exit(0);
    }

    private static String describe(MiniWindow window) {
        try {
            java.lang.reflect.Field field = MiniWindow.class.getDeclaredField("window");
            field.setAccessible(true);
            java.awt.Window target = (java.awt.Window) field.get(window);
            if (target == null) {
                return "null";
            }
            return target.getBounds() + " visible=" + target.isVisible()
                    + " opaque=" + target.isOpaque();
        } catch (Throwable error) {
            return "未知: " + error;
        }
    }

    /** 截取窗口所在区域（略放大），便于观察透明与边缘效果。 */
    private static void capture(Path target, MiniWindow window) throws Exception {
        Rectangle screen = new Rectangle(java.awt.Toolkit.getDefaultToolkit().getScreenSize());
        Rectangle bounds = windowBounds(window);
        int pad = 24;
        int x = Math.max(0, Math.min(bounds.x - pad, screen.width - 1));
        int y = Math.max(0, Math.min(bounds.y - pad, screen.height - 1));
        int width = Math.min(screen.width - x, bounds.width + pad * 2);
        int height = Math.min(screen.height - y, bounds.height + pad * 2);
        BufferedImage image = new Robot().createScreenCapture(new Rectangle(x, y, width, height));
        ImageIO.write(image, "png", target.toFile());
        System.out.println("  截屏 " + target.getFileName() + " -> "
                + new File(target.toString()).length() + " 字节");
    }

    private static Rectangle windowBounds(MiniWindow window) {
        try {
            java.lang.reflect.Field field = MiniWindow.class.getDeclaredField("window");
            field.setAccessible(true);
            java.awt.Window target = (java.awt.Window) field.get(window);
            if (target != null) {
                return target.getBounds();
            }
        } catch (Throwable ignored) {
            // 退回整屏
        }
        return new Rectangle(java.awt.Toolkit.getDefaultToolkit().getScreenSize());
    }

    private static void captureFullScreen(Path target) throws Exception {
        Rectangle screen = new Rectangle(java.awt.Toolkit.getDefaultToolkit().getScreenSize());
        ImageIO.write(new Robot().createScreenCapture(screen), "png", target.toFile());
        System.out.println("  全屏截屏 " + target.getFileName());
    }
}
