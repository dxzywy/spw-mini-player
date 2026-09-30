package com.spw.miniplayer;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * 从封面提取主题色。
 *
 * <p>用途有两个：
 * <ul>
 *   <li>展开态面板的背景色：把封面主色调和成一块「能衬住文字」的底色，
 *       再与用户设置的不透明度叠加（见 {@link MiniPlayerView#setPanelAlpha(int)}）；</li>
 *   <li>进度条强调色：从同一主色派生一条更亮、更饱和的颜色。</li>
 * </ul>
 *
 * <p>提取过程涉及逐像素扫描，<b>必须在后台线程调用</b>，不能落在 EDT 上。
 */
final class CoverTheme {

    /** 采样边长：足够代表整张封面，又能把扫描量控制在千级像素。 */
    private static final int SAMPLE_EDGE = 32;

    /** 主题色混入底色（面板原色）的比例，用于保持与宿主主题的整体观感一致。 */
    private static final float TINT_BASE_MIX = 0.22f;

    private CoverTheme() {
    }

    /**
     * 提取封面主色；无封面或提取失败返回 null。
     *
     * <p>做法是先缩到 {@value #SAMPLE_EDGE} 见方，再按 4bit/通道做直方图，
     * 用「像素数 × 饱和度权重 × 中间亮度权重」给每个色桶打分，
     * 取最高分色桶的均值——比单纯取平均色更接近人眼感知的主色。
     */
    static Color dominant(Image cover) {
        if (cover == null) {
            return null;
        }
        BufferedImage sample = sample(cover);
        if (sample == null) {
            return null;
        }
        Map<Integer, long[]> bins = new HashMap<>();
        for (int y = 0; y < SAMPLE_EDGE; y++) {
            for (int x = 0; x < SAMPLE_EDGE; x++) {
                int argb = sample.getRGB(x, y);
                if (((argb >>> 24) & 0xFF) < 128) {
                    continue;
                }
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                double luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
                // 接近纯黑 / 纯白的像素没有色相信息，直接跳过
                if (luminance < 0.05 || luminance > 0.95) {
                    continue;
                }
                int key = ((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4);
                long[] bucket = bins.get(key);
                if (bucket == null) {
                    bucket = new long[5];
                    bins.put(key, bucket);
                }
                bucket[0]++;
                bucket[1] += r;
                bucket[2] += g;
                bucket[3] += b;
                bucket[4] += (long) (Color.RGBtoHSB(r, g, b, null)[1] * 1000.0f);
            }
        }
        if (bins.isEmpty()) {
            return null;
        }

        long[] best = null;
        double bestScore = -1.0;
        for (long[] bucket : bins.values()) {
            double count = bucket[0];
            double saturation = bucket[4] / (1000.0 * count);
            int r = (int) (bucket[1] / count);
            int g = (int) (bucket[2] / count);
            int b = (int) (bucket[3] / count);
            double luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
            // 偏向「面积大 + 饱和度高 + 亮度居中」的色桶
            double score = count * (0.35 + 1.5 * saturation)
                    * (1.0 - Math.abs(luminance - 0.5) * 0.9);
            if (score > bestScore) {
                bestScore = score;
                best = bucket;
            }
        }
        if (best == null) {
            return null;
        }
        double count = best[0];
        return new Color((int) (best[1] / count), (int) (best[2] / count),
                (int) (best[3] / count));
    }

    /**
     * 把封面主色调和成可用的面板背景色。
     *
     * <p>关键是把亮度压进「能衬住文字」的区间（深色主题偏暗、浅色主题偏亮），
     * 并保持适度饱和；最后与面板原色混合 {@value #TINT_BASE_MIX}，
     * 避免不同专辑之间跳色过于突兀。返回的颜色不带 alpha，
     * 透明度由 {@link MiniPlayerView} 叠加用户设置的不透明度。
     */
    static Color panelTint(Color seed) {
        if (seed == null) {
            return null;
        }
        boolean dark = Theme.isDark();
        float[] hsb = Color.RGBtoHSB(seed.getRed(), seed.getGreen(), seed.getBlue(), null);
        // 浅色面板面积观感更「亮」，饱和度收紧一些，避免整块面板过于跳色
        float saturation = clamp(hsb[1] * 0.85f + 0.06f, 0.10f, dark ? 0.58f : 0.45f);
        float brightness = dark
                ? clamp(0.11f + hsb[2] * 0.42f, 0.13f, 0.30f)
                : clamp(0.68f + hsb[2] * 0.26f, 0.72f, 0.93f);
        Color tuned = Color.getHSBColor(hsb[0], saturation, brightness);
        return mix(tuned, Theme.color().panelBg, TINT_BASE_MIX);
    }

    /** 从封面主色派生进度条强调色（更亮更饱和，保证压在面板上仍然清楚）。 */
    static Color progressAccent(Color seed) {
        if (seed == null) {
            return null;
        }
        boolean dark = Theme.isDark();
        float[] hsb = Color.RGBtoHSB(seed.getRed(), seed.getGreen(), seed.getBlue(), null);
        float saturation = clamp(hsb[1] * 1.05f + 0.20f, 0.45f, 0.95f);
        float brightness = dark
                ? clamp(hsb[2] * 0.55f + 0.42f, 0.58f, 0.88f)
                : clamp(hsb[2] * 0.50f + 0.28f, 0.36f, 0.62f);
        return Color.getHSBColor(hsb[0], saturation, brightness);
    }

    /** 把封面缩到采样尺寸（后台线程）。 */
    private static BufferedImage sample(Image cover) {
        try {
            BufferedImage out = new BufferedImage(SAMPLE_EDGE, SAMPLE_EDGE,
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(cover, 0, 0, SAMPLE_EDGE, SAMPLE_EDGE, null);
            g.dispose();
            return out;
        } catch (Throwable error) {
            PluginLog.w("封面采样失败: " + error);
            return null;
        }
    }

    private static Color mix(Color source, Color target, float amount) {
        float ratio = clamp(amount, 0.0f, 1.0f);
        int r = Math.round(source.getRed() + (target.getRed() - source.getRed()) * ratio);
        int g = Math.round(source.getGreen() + (target.getGreen() - source.getGreen()) * ratio);
        int b = Math.round(source.getBlue() + (target.getBlue() - source.getBlue()) * ratio);
        return new Color(r, g, b);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
