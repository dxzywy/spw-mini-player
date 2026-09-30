package com.spw.miniplayer;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/**
 * 纯 Java2D 绘制的矢量图标，避免引入额外资源文件与依赖。
 *
 * <p>所有图标都在 24×24 的逻辑坐标系里定义，绘制时缩放到目标矩形，
 * 因此在任意 DPI 缩放下都保持清晰。
 */
final class Icons {

    private static final double VB = 24.0;

    private Icons() {
    }

    static void play(Graphics2D g, Rectangle box, Color color) {
        Graphics2D g2 = prepare(g, box);
        Path2D.Double p = new Path2D.Double();
        p.moveTo(8.6, 5.4);
        p.curveTo(8.6, 4.6, 9.5, 4.0, 10.2, 4.5);
        p.lineTo(19.2, 11.0);
        p.curveTo(19.9, 11.5, 19.9, 12.5, 19.2, 13.0);
        p.lineTo(10.2, 19.5);
        p.curveTo(9.5, 20.0, 8.6, 19.4, 8.6, 18.6);
        p.closePath();
        g2.setColor(color);
        g2.fill(p);
        g2.dispose();
    }

    static void pause(Graphics2D g, Rectangle box, Color color) {
        Graphics2D g2 = prepare(g, box);
        g2.setColor(color);
        g2.fill(new RoundRectangle2D.Double(7.6, 5.2, 3.4, 13.6, 1.8, 1.8));
        g2.fill(new RoundRectangle2D.Double(13.0, 5.2, 3.4, 13.6, 1.8, 1.8));
        g2.dispose();
    }

    static void previous(Graphics2D g, Rectangle box, Color color) {
        Graphics2D g2 = prepare(g, box);
        g2.setColor(color);
        g2.fill(new RoundRectangle2D.Double(5.6, 5.4, 2.6, 13.2, 1.3, 1.3));
        Path2D.Double p = new Path2D.Double();
        p.moveTo(19.0, 5.6);
        p.lineTo(19.0, 18.4);
        p.curveTo(19.0, 19.3, 18.0, 19.8, 17.3, 19.3);
        p.lineTo(9.6, 13.4);
        p.curveTo(9.0, 12.9, 9.0, 11.1, 9.6, 10.6);
        p.lineTo(17.3, 4.7);
        p.curveTo(18.0, 4.2, 19.0, 4.7, 19.0, 5.6);
        p.closePath();
        g2.fill(p);
        g2.dispose();
    }

    static void next(Graphics2D g, Rectangle box, Color color) {
        Graphics2D g2 = prepare(g, box);
        g2.setColor(color);
        g2.fill(new RoundRectangle2D.Double(15.8, 5.4, 2.6, 13.2, 1.3, 1.3));
        Path2D.Double p = new Path2D.Double();
        p.moveTo(5.0, 5.6);
        p.lineTo(5.0, 18.4);
        p.curveTo(5.0, 19.3, 6.0, 19.8, 6.7, 19.3);
        p.lineTo(14.4, 13.4);
        p.curveTo(15.0, 12.9, 15.0, 11.1, 14.4, 10.6);
        p.lineTo(6.7, 4.7);
        p.curveTo(6.0, 4.2, 5.0, 4.7, 5.0, 5.6);
        p.closePath();
        g2.fill(p);
        g2.dispose();
    }

    /**
     * 桌面歌词按钮：带尾巴的气泡 + 歌词行。
     *
     * @param active true 表示歌词已开启，使用强调色实心样式
     */
    static void lyrics(Graphics2D g, Rectangle box, Color color, boolean active) {
        Graphics2D g2 = prepare(g, box);
        Area bubble = new Area(new RoundRectangle2D.Double(3.2, 4.2, 17.6, 14.2, 4.2, 4.2));
        Path2D.Double tail = new Path2D.Double();
        tail.moveTo(8.0, 17.4);
        tail.lineTo(8.0, 21.4);
        tail.lineTo(12.4, 17.6);
        tail.closePath();
        bubble.add(new Area(tail));

        if (active) {
            g2.setColor(color);
            g2.fill(bubble);
        } else {
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(bubble);
        }
        Color lines = active ? new Color(18, 19, 24, 235) : color;
        g2.setColor(lines);
        g2.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new java.awt.geom.Line2D.Double(7.0, 8.6, 17.0, 8.6));
        g2.draw(new java.awt.geom.Line2D.Double(7.0, 12.0, 14.0, 12.0));
        if (!active) {
            g2.draw(new java.awt.geom.Line2D.Double(7.0, 15.0, 10.4, 15.0));
        }
        g2.dispose();
    }

    static void close(Graphics2D g, Rectangle box, Color color) {
        Graphics2D g2 = prepare(g, box);
        g2.setColor(color);
        g2.setStroke(new BasicStroke(2.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new java.awt.geom.Line2D.Double(8.2, 8.2, 15.8, 15.8));
        g2.draw(new java.awt.geom.Line2D.Double(15.8, 8.2, 8.2, 15.8));
        g2.dispose();
    }

    /** 无封面时的占位符：音符。 */
    static void musicNote(Graphics2D g, Rectangle box, Color color) {
        Graphics2D g2 = prepare(g, box);
        g2.setColor(color);
        g2.fill(new Ellipse2D.Double(6.2, 13.4, 5.6, 4.8));
        g2.fill(new Ellipse2D.Double(12.4, 11.6, 5.6, 4.8));
        g2.fill(new RoundRectangle2D.Double(10.6, 4.6, 1.7, 11.0, 0.8, 0.8));
        g2.fill(new RoundRectangle2D.Double(17.0, 2.8, 1.7, 11.0, 0.8, 0.8));
        Path2D.Double flag = new Path2D.Double();
        flag.moveTo(10.6, 4.6);
        flag.lineTo(18.7, 2.8);
        flag.lineTo(18.7, 6.0);
        flag.lineTo(10.6, 7.8);
        flag.closePath();
        g2.fill(flag);
        g2.dispose();
    }

    /** 展开（恢复小窗）图标：一个角标 + 一条离开的箭头。 */
    static void expand(Graphics2D g, Rectangle box, Color color) {
        Graphics2D g2 = prepare(g, box);
        g2.setColor(color);
        g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D.Double corner = new Path2D.Double();
        corner.moveTo(4.6, 13.2);
        corner.lineTo(4.6, 19.4);
        corner.lineTo(10.8, 19.4);
        g2.draw(corner);
        g2.draw(new java.awt.geom.Line2D.Double(9.4, 14.6, 19.2, 4.8));
        Path2D.Double head = new Path2D.Double();
        head.moveTo(13.4, 4.8);
        head.lineTo(19.2, 4.8);
        head.lineTo(19.2, 10.6);
        g2.draw(head);
        g2.dispose();
    }

    /** 收起（缩为封面悬浮窗）图标：expand 的镜像。 */
    static void collapse(Graphics2D g, Rectangle box, Color color) {
        Graphics2D g2 = prepare(g, box);
        g2.setColor(color);
        g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D.Double corner = new Path2D.Double();
        corner.moveTo(4.8, 10.8);
        corner.lineTo(4.8, 4.6);
        corner.lineTo(11.0, 4.6);
        g2.draw(corner);
        g2.draw(new java.awt.geom.Line2D.Double(4.6, 4.8, 13.4, 13.6));
        Path2D.Double head = new Path2D.Double();
        head.moveTo(19.2, 10.4);
        head.lineTo(19.2, 19.4);
        head.lineTo(10.2, 19.4);
        g2.draw(head);
        g2.dispose();
    }

    /**
     * 「打开播放器」图标：一扇窗口 + 窗口里的播放三角。
     *
     * <p>语义上区别于 {@link #expand(Graphics2D, Rectangle, Color)}（展开迷你面板），
     * 这里指的是唤起 Salt Player 主窗口。
     */
    static void player(Graphics2D g, Rectangle box, Color color) {
        Graphics2D g2 = prepare(g, box);
        g2.setColor(color);
        g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new RoundRectangle2D.Double(3.6, 4.6, 16.8, 14.8, 3.0, 3.0));
        // 标题栏分隔线
        g2.draw(new java.awt.geom.Line2D.Double(3.6, 8.4, 20.4, 8.4));
        Path2D.Double play = new Path2D.Double();
        play.moveTo(10.2, 10.6);
        play.lineTo(15.8, 13.6);
        play.lineTo(10.2, 16.6);
        play.closePath();
        g2.fill(play);
        g2.dispose();
    }

    /** 把 24×24 坐标系对齐到目标矩形，并开启抗锯齿。 */
    private static Graphics2D prepare(Graphics2D g, Rectangle box) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double scale = Math.min(box.width, box.height) / VB;
        g2.translate(box.x + (box.width - VB * scale) / 2.0,
                box.y + (box.height - VB * scale) / 2.0);
        g2.scale(scale, scale);
        return g2;
    }

    /** 以目标矩形中心绘制一个圆形底，用于按钮悬停反馈。 */
    static Shape circle(Rectangle box) {
        double d = Math.min(box.width, box.height);
        return new Ellipse2D.Double(
                box.x + (box.width - d) / 2.0,
                box.y + (box.height - d) / 2.0,
                d, d);
    }
}
