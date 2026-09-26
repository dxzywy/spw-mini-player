package com.spw.miniplayer;

import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;

/**
 * 可贴边隐藏的小窗。
 *
 * <p>展开态是完整的迷你播放器面板；鼠标离开后自动收起为
 * 「封面悬浮窗」——只显示当前歌曲封面的一枚圆形方窗，可拖动改变位置，
 * 单击恢复面板，悬停时浮出播放控制与展开按钮。
 */
final class MiniWindow {

    /** 贴边方向。 */
    enum Edge {
        LEFT, RIGHT, TOP, BOTTOM, FREE
    }

    /** 判定吸附到屏幕边缘的距离。 */
    private static final int SNAP_DISTANCE = 56;
    /** 展开 / 收起动画时长。 */
    private static final long ANIM_MS = 160;
    /** 动画帧间隔：30fps 足够顺滑，且把重绘压力减半。 */
    private static final int ANIM_FRAME_MS = 33;
    /** 判定「点击」而非「拖动」的位移阈值。 */
    private static final int CLICK_SLOP = 4;

    private final JWindow window;
    private final MiniPlayerView view;
    private final MiniPlayerController controller;

    private Edge edge = Edge.RIGHT;
    private boolean autoHide = true;
    private boolean compact;
    private boolean shown;
    private boolean dragging;
    private boolean dragMoved;
    private int hideDelayMs = 600;
    private boolean mouseInside;

    private Point dragOriginScreen;
    private Rectangle dragOriginBounds;
    private Point initialPosition;
    private boolean pressConsumed;
    private MiniPlayerView.Hit pressedHit = MiniPlayerView.Hit.NONE;
    private long mouseAwaySince;

    private Rectangle animFrom;
    private Rectangle animTo;
    private long animStart;
    private final Timer animTimer;
    private final Timer pollTimer;

    MiniWindow(MiniPlayerController controller) {
        this.controller = controller;
        this.window = new JWindow();
        this.view = new MiniPlayerView();
        window.setContentPane(view);
        window.setAlwaysOnTop(true);
        window.setAutoRequestFocus(false);
        window.setFocusableWindowState(false);
        window.setSize(Theme.WIDTH, Theme.HEIGHT);
        applyBackground();
        try {
            window.setType(Window.Type.UTILITY);
        } catch (Throwable ignored) {
            // 部分平台不支持，忽略
        }
        installMouseHandlers();

        animTimer = new Timer(ANIM_FRAME_MS, event -> onAnimationTick());
        animTimer.setCoalesce(true);
        pollTimer = new Timer(140, event -> onPollTick());
        pollTimer.setCoalesce(true);
    }

    MiniPlayerView view() {
        return view;
    }

    boolean isShown() {
        return shown;
    }

    boolean isCompact() {
        return compact;
    }

    Edge currentEdge() {
        return edge;
    }

    void setAutoHide(boolean autoHide) {
        this.autoHide = autoHide;
        if (autoHide && shown) {
            pollTimer.start();
        } else if (!autoHide) {
            pollTimer.stop();
        }
    }

    void setHideDelay(int millis) {
        this.hideDelayMs = Math.max(100, millis);
    }

    void setAlwaysOnTop(boolean alwaysOnTop) {
        try {
            window.setAlwaysOnTop(alwaysOnTop);
        } catch (Throwable error) {
            PluginLog.w("设置置顶失败: " + error);
        }
    }

    /** 恢复上次记录的贴边方向。 */
    void setDefaultEdge(Edge value) {
        if (value != null) {
            this.edge = value;
        }
    }

    /** 恢复上次记录的坐标（可为 null，表示使用屏幕居中）。 */
    void setInitialPosition(Integer x, Integer y) {
        if (x != null && y != null) {
            this.initialPosition = new Point(x, y);
        }
    }

    // -------------------------------------------------------------- 状态切换

    /** 显示小窗（展开态）。 */
    void show(boolean restoreEdge) {
        shown = true;
        compact = false;
        view.setCompact(false);
        view.setCompactHover(false);
        window.setVisible(true);
        Rectangle usable = usableBounds();
        if (restoreEdge) {
            edge = edge == Edge.FREE ? Edge.RIGHT : edge;
        }
        if (initialPosition != null) {
            window.setLocation(initialPosition.x, initialPosition.y);
        } else {
            // 默认摆在屏幕纵向居中位置，避免贴到任务栏或标题栏
            window.setLocation(usable.x,
                    usable.y + Math.max(0, (usable.height - Theme.HEIGHT) / 2));
        }
        window.setBounds(expandedTarget(edge));
        mouseAwaySince = System.currentTimeMillis();
        if (autoHide) {
            pollTimer.start();
        }
        window.toFront();
    }

    /** 彻底隐藏（含封面悬浮窗）。 */
    void hide() {
        shown = false;
        compact = false;
        animTimer.stop();
        pollTimer.stop();
        animFrom = null;
        animTo = null;
        view.setCompactHover(false);
        view.stopAnimations();
        window.setVisible(false);
    }

    void toggle() {
        if (shown) {
            hide();
        } else {
            show(true);
        }
    }

    /** 收起到封面悬浮窗。 */
    void collapse() {
        if (!shown || compact) {
            return;
        }
        compact = true;
        view.setCompact(true);
        view.setCompactHover(false);
        Rectangle target = compactTarget(edge);
        animTimer.stop();
        animateTo(target);
        controller.onCollapsed(edge, target);
    }

    /** 从封面悬浮窗恢复面板。 */
    void expand() {
        if (!shown || !compact) {
            return;
        }
        compact = false;
        view.setCompact(false);
        view.setCompactHover(false);
        mouseAwaySince = System.currentTimeMillis();
        animateTo(expandedTarget(edge));
    }

    /** 移动到指定贴边位置。 */
    void dockTo(Edge target, boolean immediately) {
        if (target == null || target == Edge.FREE || !shown) {
            return;
        }
        this.edge = target;
        compact = false;
        view.setCompact(false);
        Rectangle bounds = compact ? compactTarget(target) : expandedTarget(target);
        if (immediately) {
            animTimer.stop();
            window.setBounds(bounds);
        } else {
            animateTo(bounds);
        }
    }

    void ensureOnScreen() {
        Rectangle usable = usableBounds();
        Rectangle bounds = window.getBounds();
        int width = bounds.width > 0 ? bounds.width : Theme.WIDTH;
        int height = bounds.height > 0 ? bounds.height : Theme.HEIGHT;
        int x = Math.min(Math.max(bounds.x, usable.x), usable.x + usable.width - width);
        int y = Math.min(Math.max(bounds.y, usable.y), usable.y + usable.height - height);
        window.setBounds(x, y, width, height);
    }

    void dispose() {
        animTimer.stop();
        pollTimer.stop();
        animFrom = null;
        animTo = null;
        view.stopAnimations();
        window.setVisible(false);
        window.dispose();
    }

    // ------------------------------------------------------------ 几何计算

    private Rectangle currentBounds() {
        Rectangle bounds = window.getBounds();
        if (bounds.width <= 0 || bounds.height <= 0) {
            return new Rectangle(bounds.x, bounds.y, Theme.WIDTH, Theme.HEIGHT);
        }
        return bounds;
    }

    private GraphicsConfiguration configuration() {
        GraphicsConfiguration gc = window.getGraphicsConfiguration();
        if (gc != null) {
            return gc;
        }
        return GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
    }

    /** 物理屏幕范围，用于判断是否贴近边缘。 */
    private Rectangle physicalBounds() {
        return configuration().getBounds();
    }

    /** 扣除任务栏后的可用范围，用于摆放窗口。 */
    private Rectangle usableBounds() {
        GraphicsConfiguration gc = configuration();
        Rectangle bounds = gc.getBounds();
        try {
            Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
            return new Rectangle(bounds.x + insets.left, bounds.y + insets.top,
                    bounds.width - insets.left - insets.right,
                    bounds.height - insets.top - insets.bottom);
        } catch (Throwable ignored) {
            return bounds;
        }
    }

    /** 面板（展开态）的贴边目标位置。 */
    private Rectangle expandedTarget(Edge target) {
        Rectangle usable = usableBounds();
        Rectangle current = currentBounds();
        int w = Theme.WIDTH;
        int h = Theme.HEIGHT;
        int maxY = usable.y + usable.height - h;
        int maxX = usable.x + usable.width - w;
        switch (target) {
            case RIGHT:
                return new Rectangle(usable.x + usable.width - w, clamp(current.y, usable.y, maxY),
                        w, h);
            case LEFT:
                return new Rectangle(usable.x, clamp(current.y, usable.y, maxY), w, h);
            case TOP:
                return new Rectangle(clamp(current.x, usable.x, maxX), usable.y, w, h);
            case BOTTOM:
                return new Rectangle(clamp(current.x, usable.x, maxX),
                        usable.y + usable.height - h, w, h);
            default:
                return new Rectangle(clamp(current.x, usable.x, maxX),
                        clamp(current.y, usable.y, maxY), w, h);
        }
    }

    /** 封面悬浮窗的贴边目标位置（始终完整可见）。 */
    private Rectangle compactTarget(Edge target) {
        Rectangle usable = usableBounds();
        Rectangle current = currentBounds();
        int size = Theme.COMPACT_SIZE;
        int margin = Theme.COMPACT_MARGIN;
        int maxX = usable.x + usable.width - size - margin;
        int maxY = usable.y + usable.height - size - margin;
        int pivotX = clamp(current.x + current.width / 2 - size / 2, usable.x + margin, maxX);
        int pivotY = clamp(current.y + current.height / 2 - size / 2, usable.y + margin, maxY);
        switch (target) {
            case RIGHT:
                return new Rectangle(usable.x + usable.width - size - margin, pivotY, size, size);
            case LEFT:
                return new Rectangle(usable.x + margin, pivotY, size, size);
            case TOP:
                return new Rectangle(pivotX, usable.y + margin, size, size);
            case BOTTOM:
                return new Rectangle(pivotX, usable.y + usable.height - size - margin, size, size);
            default:
                return new Rectangle(pivotX, pivotY, size, size);
        }
    }

    private static int clamp(int value, int min, int max) {
        if (max < min) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    // -------------------------------------------------------------- 动画

    private void animateTo(Rectangle target) {
        Rectangle from = window.getBounds();
        if (from.equals(target)) {
            return;
        }
        /*
         * 只对「位置」做动画，尺寸一次到位。
         * 原因是半透明 + 置顶的无边框窗口在 Windows 上每次改尺寸都会重建分层窗口，
         * 以 60fps 连续缩放会把 EDT 拖住（展开时尤为明显），因此这里刻意不做尺寸动画。
         */
        if (from.width != target.width || from.height != target.height) {
            window.setBounds(from.x, from.y, target.width, target.height);
            view.revalidate();
            from = window.getBounds();
            if (from.equals(target)) {
                return;
            }
        }
        animFrom = from;
        animTo = target;
        animStart = System.currentTimeMillis();
        if (!animTimer.isRunning()) {
            animTimer.start();
        }
    }

    private void onAnimationTick() {
        if (animFrom == null || animTo == null) {
            animTimer.stop();
            return;
        }
        double progress = (System.currentTimeMillis() - animStart) / (double) ANIM_MS;
        if (progress >= 1.0) {
            progress = 1.0;
        }
        double eased = 1.0 - Math.pow(1.0 - progress, 3.0);
        int x = (int) Math.round(animFrom.x + (animTo.x - animFrom.x) * eased);
        int y = (int) Math.round(animFrom.y + (animTo.y - animFrom.y) * eased);
        // 只移动位置，尺寸保持不变
        window.setLocation(x, y);
        if (progress >= 1.0) {
            animTimer.stop();
            animFrom = null;
            animTo = null;
        }
    }

    // ---------------------------------------------------------- 自动收起

    private void onPollTick() {
        if (!shown) {
            return;
        }
        boolean inside = mouseInside || window.getMousePosition(true) != null;
        if (compact) {
            if (!inside) {
                view.setCompactHover(false);
            }
            mouseAwaySince = 0;
            return;
        }
        if (!autoHide || dragging || animTimer.isRunning()) {
            mouseAwaySince = 0;
            return;
        }
        long now = System.currentTimeMillis();
        if (inside) {
            mouseAwaySince = 0;
        } else if (edge != Edge.FREE) {
            if (mouseAwaySince == 0) {
                mouseAwaySince = now;
            } else if (now - mouseAwaySince > hideDelayMs) {
                collapse();
            }
        }
    }

    // -------------------------------------------------------------- 交互

    private void installMouseHandlers() {
        view.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                MiniPlayerView.Hit hit = view.hitTest(event.getPoint());
                if (hit != MiniPlayerView.Hit.NONE) {
                    pressConsumed = true;
                    pressedHit = hit;
                    view.setPressed(hit);
                    return;
                }
                pressConsumed = false;
                dragging = true;
                dragMoved = false;
                mouseAwaySince = 0;
                animTimer.stop();
                dragOriginScreen = event.getLocationOnScreen();
                dragOriginBounds = window.getBounds();
                view.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                view.setCursor(Cursor.getDefaultCursor());
                if (pressConsumed) {
                    MiniPlayerView.Hit hit = view.hitTest(event.getPoint());
                    MiniPlayerView.Hit target = pressedHit;
                    pressConsumed = false;
                    pressedHit = MiniPlayerView.Hit.NONE;
                    view.setPressed(MiniPlayerView.Hit.NONE);
                    if (hit == target && hit != MiniPlayerView.Hit.NONE) {
                        controller.onButton(hit);
                    }
                    return;
                }
                if (!dragging) {
                    return;
                }
                dragging = false;
                dragOriginScreen = null;
                if (compact) {
                    if (dragMoved) {
                        resolveEdgeAfterDrag();
                    } else {
                        expand();
                    }
                } else {
                    resolveEdgeAfterDrag();
                }
            }

            @Override
            public void mouseEntered(MouseEvent event) {
                mouseInside = true;
                if (compact) {
                    view.setCompactHover(true);
                }
            }

            @Override
            public void mouseExited(MouseEvent event) {
                mouseInside = false;
                if (compact) {
                    view.setCompactHover(false);
                }
            }
        });
        view.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent event) {
                if (dragOriginScreen == null || dragOriginBounds == null) {
                    return;
                }
                Point point = event.getLocationOnScreen();
                int dx = point.x - dragOriginScreen.x;
                int dy = point.y - dragOriginScreen.y;
                if (Math.abs(dx) > CLICK_SLOP || Math.abs(dy) > CLICK_SLOP) {
                    dragMoved = true;
                    // 拖动过程中不显示悬浮按钮，避免误触
                    view.setCompactHover(false);
                }
                window.setLocation(dragOriginBounds.x + dx, dragOriginBounds.y + dy);
            }

            @Override
            public void mouseMoved(MouseEvent event) {
                if (compact) {
                    view.setCompactHover(true);
                }
                view.setHover(view.hitTest(event.getPoint()));
            }
        });
    }

    /** 拖动结束后判断是否吸附到屏幕边缘。 */
    private void resolveEdgeAfterDrag() {
        Rectangle physical = physicalBounds();
        Rectangle bounds = window.getBounds();

        Edge target = Edge.FREE;
        if (physical.x + physical.width - (bounds.x + bounds.width) <= SNAP_DISTANCE) {
            target = Edge.RIGHT;
        } else if (bounds.x - physical.x <= SNAP_DISTANCE) {
            target = Edge.LEFT;
        } else if (bounds.y - physical.y <= SNAP_DISTANCE) {
            target = Edge.TOP;
        } else if (physical.y + physical.height - (bounds.y + bounds.height) <= SNAP_DISTANCE) {
            target = Edge.BOTTOM;
        }

        Rectangle target2;
        if (target == Edge.FREE) {
            edge = Edge.FREE;
            Rectangle usable = usableBounds();
            int width = bounds.width;
            int height = bounds.height;
            int x = clamp(bounds.x, usable.x, usable.x + usable.width - width);
            int y = clamp(bounds.y, usable.y, usable.y + usable.height - height);
            target2 = new Rectangle(x, y, width, height);
        } else {
            edge = target;
            target2 = compact ? compactTarget(target) : expandedTarget(target);
        }
        animateTo(target2);
        controller.onEdgeChanged(edge, target2);
    }

    // -------------------------------------------------------------- 外观

    private void applyBackground() {
        boolean translucent = false;
        try {
            GraphicsDevice device = configuration().getDevice();
            translucent = device.isWindowTranslucencySupported(
                    GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
        } catch (Throwable ignored) {
            // 视为不支持
        }
        if (translucent) {
            window.setBackground(new Color(0, 0, 0, 0));
        } else {
            window.setBackground(Theme.color().opaqueBackdrop);
        }
    }

    /** 应用宿主主题（重新计算不透明白底并重绘）。 */
    void applyTheme() {
        applyBackground();
        view.repaint();
    }

    void runOnEdt(Runnable runnable) {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
        } else {
            SwingUtilities.invokeLater(runnable);
        }
    }
}
