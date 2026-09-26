package com.spw.miniplayer;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.HashSet;
import java.util.Set;

/**
 * 迷你播放器的视觉常量。
 *
 * <p>提供深色 / 浅色两套配色，跟随 Salt Player 的主题设置切换。
 */
final class Theme {

    /** 一套配色。 */
    static final class Palette {
        final Color panelBg;
        final Color panelBgHover;
        final Color border;
        final Color titleText;
        final Color subText;
        final Color icon;
        final Color iconHover;
        final Color iconDisabled;
        final Color accent;
        final Color hoverBg;
        final Color pressedBg;
        final Color track;
        final Color playBg;
        final Color playFg;
        final Color playBgHover;
        final Color coverPlaceholder;
        final Color opaqueBackdrop;
        final Color subTextDim;

        Palette(Color panelBg, Color panelBgHover, Color border, Color titleText, Color subText,
                Color icon, Color iconHover, Color iconDisabled, Color accent, Color hoverBg,
                Color pressedBg, Color track, Color playBg, Color playFg, Color playBgHover,
                Color coverPlaceholder, Color opaqueBackdrop, Color subTextDim) {
            this.panelBg = panelBg;
            this.panelBgHover = panelBgHover;
            this.border = border;
            this.titleText = titleText;
            this.subText = subText;
            this.icon = icon;
            this.iconHover = iconHover;
            this.iconDisabled = iconDisabled;
            this.accent = accent;
            this.hoverBg = hoverBg;
            this.pressedBg = pressedBg;
            this.track = track;
            this.playBg = playBg;
            this.playFg = playFg;
            this.playBgHover = playBgHover;
            this.coverPlaceholder = coverPlaceholder;
            this.opaqueBackdrop = opaqueBackdrop;
            this.subTextDim = subTextDim;
        }
    }

    /** 深色玻璃面板（默认）。 */
    private static final Palette DARK = new Palette(
            new Color(20, 21, 26, 216), new Color(26, 27, 33, 226),
            new Color(255, 255, 255, 32), new Color(243, 244, 248), new Color(155, 161, 175),
            new Color(214, 217, 224), new Color(255, 255, 255), new Color(110, 115, 126),
            new Color(91, 140, 255), new Color(255, 255, 255, 24), new Color(255, 255, 255, 44),
            new Color(255, 255, 255, 36), new Color(255, 255, 255, 236), new Color(22, 23, 28),
            new Color(255, 255, 255, 255), new Color(48, 51, 62), new Color(20, 21, 26),
            new Color(118, 123, 136));

    /** 浅色玻璃面板，跟随宿主浅色主题。 */
    private static final Palette LIGHT = new Palette(
            new Color(250, 250, 253, 234), new Color(255, 255, 255, 244),
            new Color(15, 18, 26, 30), new Color(24, 26, 32), new Color(104, 110, 122),
            new Color(62, 67, 78), new Color(12, 14, 18), new Color(172, 177, 188),
            new Color(56, 108, 222), new Color(15, 18, 26, 20), new Color(15, 18, 26, 38),
            new Color(15, 18, 26, 42), new Color(26, 28, 34), new Color(255, 255, 255),
            new Color(0, 0, 0), new Color(222, 225, 232), new Color(246, 247, 250),
            new Color(142, 148, 160));

    /** 工具栏 / 悬浮层的深色遮罩（盖在封面之上，两套主题都用深色）。 */
    private static final Color SCRIM = new Color(10, 11, 15, 156);
    /** 悬浮层上的浅色文字与图标。 */
    private static final Color ON_SCRIM_TEXT = new Color(255, 255, 255, 205);
    private static final Color ON_SCRIM_ICON = new Color(214, 217, 224);
    private static final Color ON_SCRIM_ICON_HOVER = new Color(255, 255, 255);

    /** 面板圆角半径。 */
    static final int RADIUS = 18;
    /** 小窗逻辑尺寸。 */
    static final int WIDTH = 400;
    static final int HEIGHT = 104;

    /** 收起后的封面悬浮窗边长（逻辑尺寸）。 */
    static final int COMPACT_SIZE = 76;
    /** 悬浮窗与屏幕边缘的最小间距。 */
    static final int COMPACT_MARGIN = 10;

    /**
     * 实际使用的字体族。
     *
     * <p>字体枚举（{@code getAvailableFontFamilyNames}）在部分机器上首次调用会耗时较久，
     * 因此放到后台线程预热；未就绪前先用 SansSerif，避免拖住 EDT。
     */
    private static volatile String fontFamily = Font.SANS_SERIF;

    private static volatile Palette current = DARK;

    static {
        Thread warmup = new Thread(() -> {
            try {
                String resolved = pickFamily();
                if (resolved != null && !resolved.isEmpty()) {
                    fontFamily = resolved;
                }
            } catch (Throwable ignored) {
                // 保持 SansSerif
            }
        }, "spw-mini-font");
        warmup.setDaemon(true);
        warmup.setPriority(Thread.MIN_PRIORITY);
        warmup.start();
    }

    private Theme() {
    }

    /** 当前配色。 */
    static Palette color() {
        return current;
    }

    static boolean isDark() {
        return current == DARK;
    }

    /** 切换深色 / 浅色，返回是否发生变化。 */
    static boolean setDark(boolean dark) {
        Palette target = dark ? DARK : LIGHT;
        if (current == target) {
            return false;
        }
        current = target;
        return true;
    }

    static Color hoverScrim() {
        return SCRIM;
    }

    static Color hintText() {
        return ON_SCRIM_TEXT;
    }

    /** 悬浮层上的图标色（始终为浅色，因为遮罩是深色）。 */
    static Color onScrimIcon(boolean hover) {
        return hover ? ON_SCRIM_ICON_HOVER : ON_SCRIM_ICON;
    }

    static Font title() {
        return new Font(fontFamily, Font.BOLD, 15);
    }

    static Font subtitle() {
        return new Font(fontFamily, Font.PLAIN, 12);
    }

    static Font tiny() {
        return new Font(fontFamily, Font.PLAIN, 10);
    }

    private static String pickFamily() {
        Set<String> available = new HashSet<>();
        try {
            String[] names = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames();
            for (String name : names) {
                available.add(name);
            }
        } catch (Throwable ignored) {
            // 无图形环境时使用默认
        }
        String[] preferred = {
                "Microsoft YaHei UI",
                "Microsoft YaHei",
                "PingFang SC",
                "Noto Sans CJK SC",
                "Source Han Sans SC",
                "Segoe UI",
                "SansSerif"
        };
        for (String name : preferred) {
            if (available.contains(name)) {
                return name;
            }
        }
        return Font.SANS_SERIF;
    }
}
