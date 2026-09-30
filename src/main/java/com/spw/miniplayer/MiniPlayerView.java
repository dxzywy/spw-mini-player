package com.spw.miniplayer;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.geom.RoundRectangle2D;

/**
 * 迷你播放器的绘制与命中区计算。
 *
 * <p>两种形态：
 * <ul>
 *   <li>展开态：完整面板（封面、歌曲名、控制按钮）</li>
 *   <li>收起态：一枚浮动的封面小窗，悬停时浮出快捷操作</li>
 * </ul>
 * 组件尺寸由窗口控制，绘制时按比例缩放，因此缩放动画期间也不会错位。
 */
final class MiniPlayerView extends JComponent {

    /** 可点击元素。 */
    enum Hit {
        PREVIOUS, TOGGLE_PLAY, NEXT, TOGGLE_LYRICS, CLOSE, COLLAPSE, EXPAND, OPEN_PLAYER, NONE
    }

    /** 收起态的基准坐标系（正方形）。 */
    private static final int COMPACT_VB = Theme.COMPACT_SIZE;

    private static final int COVER_SIZE = 64;
    private static final int COVER_X = 14;
    private static final int COVER_Y = 20;
    private static final int TEXT_X = 92;
    private static final int TITLE_BASELINE = 44;
    private static final int SUBTITLE_BASELINE = 65;

    private static final int TRANSPORT_SIZE = 30;
    private static final int SMALL_SIZE = 24;
    private static final int PAD = 14;

    private final Rectangle previousRect = new Rectangle();
    private final Rectangle playRect = new Rectangle();
    private final Rectangle nextRect = new Rectangle();
    private final Rectangle lyricsRect = new Rectangle();
    private final Rectangle openPlayerRect = new Rectangle();
    private final Rectangle closeRect = new Rectangle();
    private final Rectangle coverRect = new Rectangle(COVER_X, COVER_Y, COVER_SIZE, COVER_SIZE);

    // 收起态布局（以 COMPACT_VB 为坐标系）
    private final Rectangle compactPrevRect = new Rectangle();
    private final Rectangle compactPlayRect = new Rectangle();
    private final Rectangle compactNextRect = new Rectangle();
    private final Rectangle compactExpandRect = new Rectangle();
    private final Rectangle compactOpenRect = new Rectangle();

    private String title = "";
    private String subtitle = "";
    private Image cover;
    private boolean playing;
    private boolean lyricsEnabled;
    private boolean lyricsAvailable = true;
    private long positionMs;
    private long durationMs;

    private Hit hover = Hit.NONE;
    private Hit pressed = Hit.NONE;
    private boolean compact;
    private boolean compactHover;
    private int panelAlpha = Theme.color().panelBg.getAlpha();

    /** 后台线程从封面提取出的主色（可能为 null）。 */
    private Color coverDominant;
    /** 由 {@link #coverDominant} 派生的面板底色与进度条色。 */
    private Color coverTint;
    private Color coverAccent;
    /** 是否启用「封面主题色背景」。 */
    private boolean coverTintEnabled = true;
    /** 用户自定义的进度条颜色；为 null 时按 {@link #progressFollowCover} 决定。 */
    private Color progressColor;
    /** 进度条颜色是否跟随封面主题色。 */
    private boolean progressFollowCover = true;

    private double titleOffset;
    private long titlePauseUntil;
    private int titleDirection = 1;
    private final Timer marqueeTimer;

    MiniPlayerView() {
        setOpaque(false);
        setPreferredSize(new Dimension(Theme.WIDTH, Theme.HEIGHT));
        layoutPanel();
        layoutCompact();
        marqueeTimer = new Timer(40, this::onMarqueeTick);
        marqueeTimer.setCoalesce(true);
    }

    // -------------------------------------------------------------- 数据更新

    void setNowPlaying(String title, String subtitle) {
        this.title = title == null ? "" : title;
        this.subtitle = subtitle == null ? "" : subtitle;
        this.titleOffset = 0;
        this.titleDirection = 1;
        this.titlePauseUntil = System.currentTimeMillis() + 1200;
        syncMarquee();
        repaint();
    }

    void setCover(Image cover) {
        this.cover = cover;
        repaint();
    }

    /**
     * 设置从封面提取出的主色（由后台线程算好后投递到 EDT）。
     *
     * <p>这里只做廉价的派生运算（HSB 调和），逐像素扫描在
     * {@link CoverTheme#dominant(Image)} 里已经完成。
     */
    void setCoverDominant(Color dominant) {
        this.coverDominant = dominant;
        recomputeCoverColors();
        repaint();
    }

    /** 是否用封面主题色作为展开态面板背景（不透明度设置始终叠加生效）。 */
    void setCoverTintEnabled(boolean enabled) {
        if (this.coverTintEnabled != enabled) {
            this.coverTintEnabled = enabled;
            repaint();
        }
    }

    /**
     * 设置进度条颜色。
     *
     * @param color       自定义颜色；为 null 表示不指定
     * @param followCover true 表示在没有自定义颜色时跟随封面主题色
     */
    void setProgressColor(Color color, boolean followCover) {
        this.progressColor = color;
        this.progressFollowCover = followCover;
        repaint();
    }

    private void recomputeCoverColors() {
        coverTint = CoverTheme.panelTint(coverDominant);
        coverAccent = CoverTheme.progressAccent(coverDominant);
    }

    /** 进度条实际使用的颜色：自定义色 &gt; 封面主题色 &gt; 主题强调色。 */
    private Color effectiveProgressColor() {
        if (progressColor != null) {
            return progressColor;
        }
        if (progressFollowCover && coverAccent != null) {
            return coverAccent;
        }
        return Theme.color().accent;
    }

    void setPlaying(boolean playing) {
        if (this.playing != playing) {
            this.playing = playing;
            repaint();
        }
    }

    void setLyrics(boolean enabled, boolean available) {
        if (this.lyricsEnabled != enabled || this.lyricsAvailable != available) {
            this.lyricsEnabled = enabled;
            this.lyricsAvailable = available;
            repaint();
        }
    }

    void setProgress(long positionMs, long durationMs) {
        this.positionMs = Math.max(0, positionMs);
        this.durationMs = Math.max(0, durationMs);
        repaint();
    }

    /** 面板不透明度（0-255）。 */
    void setPanelAlpha(int alpha) {
        int clamped = Math.max(70, Math.min(255, alpha));
        if (this.panelAlpha != clamped) {
            this.panelAlpha = clamped;
            repaint();
        }
    }

    /** 切换展开态 / 封面收起态。 */
    void setCompact(boolean compact) {
        if (this.compact != compact) {
            this.compact = compact;
            this.hover = Hit.NONE;
            this.pressed = Hit.NONE;
            if (compact) {
                compactHover = false;
            }
            syncMarquee();
            repaint();
        }
    }

    boolean isCompact() {
        return compact;
    }

    /** 跟随宿主深色 / 浅色主题。 */
    void setDarkTheme(boolean dark) {
        if (Theme.setDark(dark)) {
            // 主题色是按当前深浅色调和出来的，换主题后要重新派生
            recomputeCoverColors();
            repaint();
        }
    }

    /** 停止所有后台动画（隐藏 / 销毁时调用，避免空转占用 EDT）。 */
    void stopAnimations() {
        marqueeTimer.stop();
    }

    void setCompactHover(boolean hovering) {
        if (compactHover != hovering) {
            compactHover = hovering;
            if (!hovering) {
                hover = Hit.NONE;
            }
            repaint();
        }
    }

    // ------------------------------------------------------------------ 命中

    Hit hitTest(Point point) {
        if (point == null) {
            return Hit.NONE;
        }
        if (compact) {
            if (!compactHover) {
                return Hit.NONE;
            }
            Point local = toCompactSpace(point);
            if (local == null) {
                return Hit.NONE;
            }
            if (compactExpandRect.contains(local)) {
                return Hit.EXPAND;
            }
            if (compactOpenRect.contains(local)) {
                return Hit.OPEN_PLAYER;
            }
            if (compactPlayRect.contains(local)) {
                return Hit.TOGGLE_PLAY;
            }
            if (compactPrevRect.contains(local)) {
                return Hit.PREVIOUS;
            }
            if (compactNextRect.contains(local)) {
                return Hit.NEXT;
            }
            return Hit.NONE;
        }
        if (closeRect.contains(point)) {
            return Hit.CLOSE;
        }
        if (lyricsRect.contains(point)) {
            return Hit.TOGGLE_LYRICS;
        }
        if (openPlayerRect.contains(point)) {
            return Hit.OPEN_PLAYER;
        }
        if (playRect.contains(point)) {
            return Hit.TOGGLE_PLAY;
        }
        if (previousRect.contains(point)) {
            return Hit.PREVIOUS;
        }
        if (nextRect.contains(point)) {
            return Hit.NEXT;
        }
        if (coverRect.contains(point)) {
            return Hit.COLLAPSE;
        }
        return Hit.NONE;
    }

    /** 把组件坐标映射回收起态基准坐标系。 */
    private Point toCompactSpace(Point point) {
        double scale = compactScale();
        if (scale <= 0) {
            return null;
        }
        double offsetX = (getWidth() - COMPACT_VB * scale) / 2.0;
        double offsetY = (getHeight() - COMPACT_VB * scale) / 2.0;
        return new Point((int) Math.round((point.x - offsetX) / scale),
                (int) Math.round((point.y - offsetY) / scale));
    }

    private double compactScale() {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return 0;
        }
        return Math.min(width / (double) COMPACT_VB, height / (double) COMPACT_VB);
    }

    void setHover(Hit hit) {
        if (hover != hit) {
            hover = hit;
            repaint();
        }
    }

    void setPressed(Hit hit) {
        if (pressed != hit) {
            pressed = hit;
            repaint();
        }
    }

    private void layoutPanel() {
        int height = Theme.HEIGHT;
        int width = Theme.WIDTH;

        int bottom = height - PAD;
        int y = bottom - TRANSPORT_SIZE;
        nextRect.setBounds(width - PAD - TRANSPORT_SIZE, y, TRANSPORT_SIZE, TRANSPORT_SIZE);
        playRect.setBounds(nextRect.x - 6 - TRANSPORT_SIZE, y, TRANSPORT_SIZE, TRANSPORT_SIZE);
        previousRect.setBounds(playRect.x - 6 - TRANSPORT_SIZE, y, TRANSPORT_SIZE, TRANSPORT_SIZE);

        int smallY = 16;
        closeRect.setBounds(width - PAD - SMALL_SIZE, smallY, SMALL_SIZE, SMALL_SIZE);
        lyricsRect.setBounds(closeRect.x - 6 - SMALL_SIZE, smallY, SMALL_SIZE, SMALL_SIZE);
        // 「打开播放器」排在歌词按钮左侧，仍落在文本区右侧的空档里
        openPlayerRect.setBounds(lyricsRect.x - 6 - SMALL_SIZE, smallY, SMALL_SIZE, SMALL_SIZE);
    }

    private void layoutCompact() {
        int size = COMPACT_VB;
        int small = 18;
        int medium = 22;
        int gap = 2;
        int rowWidth = small + gap + medium + gap + small;
        int rowX = (size - rowWidth) / 2;
        int rowY = size - 9 - medium;
        compactPrevRect.setBounds(rowX, rowY + (medium - small) / 2, small, small);
        compactPlayRect.setBounds(rowX + small + gap, rowY, medium, medium);
        compactNextRect.setBounds(rowX + small + gap + medium + gap, rowY + (medium - small) / 2,
                small, small);
        int expandSize = 17;
        compactExpandRect.setBounds(size - 6 - expandSize, 6, expandSize, expandSize);
        // 「打开播放器」与展开按钮分列悬浮层顶部的左右两侧
        compactOpenRect.setBounds(6, 6, expandSize, expandSize);
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        if (compact) {
            paintCompact(g);
        } else {
            paintPanel(g);
        }
        g.dispose();
    }

    // ------------------------------------------------------------ 展开态绘制

    private void paintPanel(Graphics2D graphics) {
        int w = Theme.WIDTH;
        int h = Theme.HEIGHT;
        double scale = Math.min(getWidth() / (double) w, getHeight() / (double) h);
        if (scale <= 0) {
            scale = 1;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        g.translate((getWidth() - w * scale) / 2.0, (getHeight() - h * scale) / 2.0);
        g.scale(scale, scale);

        RoundRectangle2D panel = new RoundRectangle2D.Double(0.5, 0.5, w - 1.0, h - 1.0,
                Theme.RADIUS, Theme.RADIUS);
        int hoverAlpha = Math.min(255, panelAlpha + 10);
        /*
         * 封面主题色只替换 RGB，透明度始终取用户设置的 panelAlpha，
         * 因此「主题色」与「不透明度」是叠加关系，改任一项都不会冲掉另一项。
         */
        Color fill = panelFillColor();
        g.setColor(new Color(fill.getRed(), fill.getGreen(), fill.getBlue(),
                hover != Hit.NONE ? hoverAlpha : panelAlpha));
        g.fill(panel);
        g.setColor(Theme.color().border);
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Double(1.0, 1.0, w - 2.0, h - 2.0,
                Theme.RADIUS - 1, Theme.RADIUS - 1));

        paintCover(g);
        paintText(g);
        paintButtons(g);
        paintProgress(g);
        g.dispose();
    }

    /** 展开态面板底色：优先封面主题色，其次主题原色（含悬停变体）。 */
    private Color panelFillColor() {
        if (coverTintEnabled && coverTint != null) {
            return coverTint;
        }
        return hover != Hit.NONE ? Theme.color().panelBgHover : Theme.color().panelBg;
    }

    private void paintCover(Graphics2D g) {
        Graphics2D c = (Graphics2D) g.create();
        RoundRectangle2D clip = new RoundRectangle2D.Double(COVER_X, COVER_Y, COVER_SIZE,
                COVER_SIZE, 14, 14);
        c.setClip(clip);
        Image image = cover;
        if (image != null) {
            c.drawImage(image, COVER_X, COVER_Y, COVER_SIZE, COVER_SIZE, null);
        } else {
            c.setColor(Theme.color().coverPlaceholder);
            c.fill(clip);
            Icons.musicNote(c, new Rectangle(COVER_X, COVER_Y, COVER_SIZE, COVER_SIZE),
                    new Color(255, 255, 255, 92));
        }
        boolean hoveringCover = hover == Hit.COLLAPSE;
        if (hoveringCover) {
            c.setColor(new Color(9, 10, 13, 140));
            c.fill(clip);
            Icons.collapse(c, new Rectangle(COVER_X + 12, COVER_Y + 12,
                    COVER_SIZE - 24, COVER_SIZE - 24), Color.WHITE);
        }
        c.dispose();
        g.setColor(hoveringCover ? new Color(255, 255, 255, 90) : new Color(255, 255, 255, 22));
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Double(COVER_X + 0.5, COVER_Y + 0.5, COVER_SIZE - 1.0,
                COVER_SIZE - 1.0, 14, 14));
    }

    private void paintText(Graphics2D g) {
        int maxWidth = previousRect.x - 10 - TEXT_X;
        if (maxWidth < 40) {
            maxWidth = 40;
        }
        String displayTitle = title.isEmpty() ? "未在播放" : title;

        Font titleFont = Theme.title();
        g.setFont(titleFont);
        g.setColor(Theme.color().titleText);
        int titleWidth = g.getFontMetrics().stringWidth(displayTitle);
        if (titleWidth <= maxWidth) {
            g.drawString(displayTitle, TEXT_X, TITLE_BASELINE);
        } else {
            Graphics2D tg = (Graphics2D) g.create();
            tg.clipRect(TEXT_X, TITLE_BASELINE - 20, maxWidth, 26);
            tg.drawString(displayTitle, TEXT_X - (int) Math.round(titleOffset), TITLE_BASELINE);
            tg.dispose();
        }

        if (!subtitle.isEmpty() || positionMs > 0) {
            g.setFont(Theme.subtitle());
            FontMetrics metrics = g.getFontMetrics();

            String timeText = formatTime();
            int timeWidth = timeText.isEmpty() ? 0 : metrics.stringWidth(timeText);
            int subtitleMax = maxWidth - (timeWidth == 0 ? 0 : timeWidth + 10);
            if (subtitleMax > 20 && !subtitle.isEmpty()) {
                g.setColor(Theme.color().subText);
                g.drawString(ellipsize(subtitle, metrics, subtitleMax), TEXT_X,
                        SUBTITLE_BASELINE);
            }
            if (timeWidth > 0) {
                g.setColor(Theme.color().subTextDim);
                g.drawString(timeText, TEXT_X + maxWidth - timeWidth, SUBTITLE_BASELINE);
            }
        }
    }

    private void paintButtons(Graphics2D g) {
        paintIconButton(g, previousRect, Hit.PREVIOUS, false,
                color -> Icons.previous(g, inset(previousRect, 3), color));
        paintIconButton(g, nextRect, Hit.NEXT, false,
                color -> Icons.next(g, inset(nextRect, 3), color));

        Color circleColor = pressed == Hit.TOGGLE_PLAY
                ? Theme.color().playBg
                : (hover == Hit.TOGGLE_PLAY ? Theme.color().playBgHover : Theme.color().playBg);
        g.setColor(circleColor);
        g.fill(Icons.circle(playRect));
        Rectangle playIcon = inset(playRect, 6);
        if (playing) {
            Icons.pause(g, playIcon, Theme.color().playFg);
        } else {
            Icons.play(g, playIcon, Theme.color().playFg);
        }

        boolean lyricsActive = lyricsEnabled && lyricsAvailable;
        paintIconButton(g, lyricsRect, Hit.TOGGLE_LYRICS, lyricsActive,
                color -> Icons.lyrics(g, inset(lyricsRect, 2), color, lyricsActive));
        paintIconButton(g, openPlayerRect, Hit.OPEN_PLAYER, false,
                color -> Icons.player(g, inset(openPlayerRect, 2), color));
        paintIconButton(g, closeRect, Hit.CLOSE, false,
                color -> Icons.close(g, inset(closeRect, 2), color));
    }

    private Color iconColor(Hit hit, boolean active) {
        if (hit == Hit.TOGGLE_LYRICS && !lyricsAvailable) {
            return Theme.color().iconDisabled;
        }
        if (hit == Hit.TOGGLE_LYRICS && active) {
            return hover == hit ? Theme.color().accent.brighter() : Theme.color().accent;
        }
        return hover == hit ? Theme.color().iconHover : Theme.color().icon;
    }

    private void paintIconButton(Graphics2D g, Rectangle rect, Hit hit, boolean active,
                                 java.util.function.Consumer<Color> painter) {
        if (hover == hit || pressed == hit) {
            g.setColor(pressed == hit ? Theme.color().pressedBg : Theme.color().hoverBg);
            g.fill(Icons.circle(rect));
        }
        painter.accept(iconColor(hit, active));
    }

    private void paintProgress(Graphics2D g) {
        if (durationMs <= 0) {
            return;
        }
        int x = 22;
        int width = Theme.WIDTH - x * 2;
        int y = Theme.HEIGHT - 8;
        double ratio = Math.min(1.0, Math.max(0.0, positionMs / (double) durationMs));

        g.setColor(Theme.color().track);
        g.fill(new RoundRectangle2D.Double(x, y, width, 3, 3, 3));
        g.setColor(effectiveProgressColor());
        g.fill(new RoundRectangle2D.Double(x, y, width * ratio, 3, 3, 3));
    }

    // ------------------------------------------------------------ 收起态绘制

    private void paintCompact(Graphics2D graphics) {
        double scale = compactScale();
        if (scale <= 0) {
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        g.translate((getWidth() - COMPACT_VB * scale) / 2.0,
                (getHeight() - COMPACT_VB * scale) / 2.0);
        g.scale(scale, scale);

        double size = COMPACT_VB;
        RoundRectangle2D bubble = new RoundRectangle2D.Double(1.5, 1.5, size - 3, size - 3,
                18, 18);

        g.setClip(bubble);
        if (cover != null) {
            g.drawImage(cover, 0, 0, (int) size, (int) size, null);
        } else {
            g.setColor(Theme.color().coverPlaceholder);
            g.fill(bubble);
            Icons.musicNote(g, new Rectangle(0, 0, (int) size, (int) size),
                    new Color(255, 255, 255, 110));
        }

        // 播放进度：贴近底边的一条细线
        if (durationMs > 0) {
            double ratio = Math.min(1.0, Math.max(0.0, positionMs / (double) durationMs));
            double inset = size * 0.16;
            double width = size - inset * 2;
            g.setColor(new Color(255, 255, 255, 70));
            g.fill(new RoundRectangle2D.Double(inset, size - 12, width, 3.4, 3.4, 3.4));
            g.setColor(effectiveProgressColor());
            g.fill(new RoundRectangle2D.Double(inset, size - 12, width * ratio, 3.4, 3.4, 3.4));
        } else if (playing) {
            g.setColor(effectiveProgressColor());
            g.fillOval((int) (size - 24), (int) (size - 24), 11, 11);
            g.setColor(new Color(12, 13, 17, 180));
            g.setStroke(new BasicStroke(2.4f));
            g.drawOval((int) (size - 24), (int) (size - 24), 11, 11);
        }

        if (compactHover) {
            paintCompactOverlay(g, size);
        }
        g.setClip(null);

        g.setColor(new Color(255, 255, 255, 46));
        g.setStroke(new BasicStroke(1.5f));
        g.draw(new RoundRectangle2D.Double(2.0, 2.0, size - 4, size - 4, 17, 17));
        g.dispose();
    }

    private void paintCompactOverlay(Graphics2D g, double size) {
        g.setColor(Theme.hoverScrim());
        g.fill(new RoundRectangle2D.Double(1.5, 1.5, size - 3, size - 3, 18, 18));

        // 顶部两枚：左侧打开 / 收起主播放器，右侧展开面板
        paintCompactButton(g, compactOpenRect, Hit.OPEN_PLAYER, false,
                color -> Icons.player(g, compactOpenRect, color));
        paintCompactButton(g, compactExpandRect, Hit.EXPAND, false,
                color -> Icons.expand(g, compactExpandRect, color));

        paintCompactButton(g, compactPrevRect, Hit.PREVIOUS, false,
                color -> Icons.previous(g, inset(compactPrevRect, 2), color));
        paintCompactButton(g, compactNextRect, Hit.NEXT, false,
                color -> Icons.next(g, inset(compactNextRect, 2), color));

        Color playCircle = pressed == Hit.TOGGLE_PLAY ? Theme.color().playBg
                : (hover == Hit.TOGGLE_PLAY ? Theme.color().playBgHover : Theme.color().playBg);
        g.setColor(playCircle);
        g.fill(Icons.circle(compactPlayRect));
        Rectangle icon = inset(compactPlayRect, 5);
        if (playing) {
            Icons.pause(g, icon, Theme.color().playFg);
        } else {
            Icons.play(g, icon, Theme.color().playFg);
        }
    }

    private void paintCompactButton(Graphics2D g, Rectangle rect, Hit hit, boolean active,
                                    java.util.function.Consumer<Color> painter) {
        if (hover == hit || pressed == hit) {
            g.setColor(pressed == hit ? Theme.color().pressedBg : new Color(255, 255, 255, 34));
            g.fill(Icons.circle(rect));
        }
        painter.accept(Theme.onScrimIcon(hover == hit));
    }

    /** 绘制到任意 Graphics（供开发期预览使用）。 */
    void paintPreview(Graphics2D g) {
        AlphaComposite composite = (AlphaComposite) g.getComposite();
        g.setComposite(AlphaComposite.SrcOver);
        paintComponent(g);
        g.setComposite(composite);
    }

    // ------------------------------------------------------------------ 工具

    private static Rectangle inset(Rectangle source, int amount) {
        return new Rectangle(source.x + amount, source.y + amount,
                source.width - amount * 2, source.height - amount * 2);
    }

    private static String ellipsize(String text, FontMetrics metrics, int maxWidth) {
        if (metrics.stringWidth(text) <= maxWidth) {
            return text;
        }
        String suffix = "…";
        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) / 2;
            String candidate = text.substring(0, mid) + suffix;
            if (metrics.stringWidth(candidate) <= maxWidth) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return text.substring(0, low) + suffix;
    }

    /** 生成 "1:23 / 4:05" 形式的时间文本。 */
    private String formatTime() {
        if (positionMs <= 0 && durationMs <= 0) {
            return "";
        }
        if (durationMs > 0) {
            return format(positionMs) + " / " + format(durationMs);
        }
        return format(positionMs);
    }

    private static String format(long millis) {
        long totalSeconds = Math.max(0, millis) / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes >= 60) {
            return String.format("%d:%02d:%02d", minutes / 60, minutes % 60, seconds);
        }
        return String.format("%d:%02d", minutes, seconds);
    }

    // -------------------------------------------------------------- 跑马灯

    private void syncMarquee() {
        boolean overflow = false;
        if (!compact && !title.isEmpty() && getWidth() > 0) {
            FontMetrics metrics = getFontMetrics(Theme.title());
            int maxWidth = previousRect.x - 10 - TEXT_X;
            overflow = metrics.stringWidth(title) > maxWidth;
        }
        if (overflow) {
            if (!marqueeTimer.isRunning()) {
                marqueeTimer.start();
            }
        } else {
            marqueeTimer.stop();
            titleOffset = 0;
        }
    }

    private void onMarqueeTick(ActionEvent event) {
        if (compact) {
            marqueeTimer.stop();
            return;
        }
        FontMetrics metrics = getFontMetrics(Theme.title());
        int maxWidth = previousRect.x - 10 - TEXT_X;
        int textWidth = metrics.stringWidth(title);
        int overflow = textWidth - maxWidth;
        if (overflow <= 0) {
            marqueeTimer.stop();
            titleOffset = 0;
            repaint();
            return;
        }
        long now = System.currentTimeMillis();
        if (now < titlePauseUntil) {
            return;
        }
        titleOffset += titleDirection * 0.9;
        if (titleOffset >= overflow) {
            titleOffset = overflow;
            titleDirection = -1;
            titlePauseUntil = now + 1400;
        } else if (titleOffset <= 0) {
            titleOffset = 0;
            titleDirection = 1;
            titlePauseUntil = now + 1400;
        }
        repaint(TEXT_X, TITLE_BASELINE - 22, maxWidth + 2, 28);
    }

    @Override
    public void removeNotify() {
        marqueeTimer.stop();
        super.removeNotify();
    }
}
