package com.spw.miniplayer;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 开发期自检 + 界面离屏渲染（不属于插件本体）。
 *
 * <p>1) 校验封面解析与时长探测；
 * 2) 把迷你播放器的若干状态渲染成 PNG，便于确认视觉效果。
 */
public final class DevPreview {

    private static final List<String> FAILURES = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path root = new File(args.length > 0 ? args[0] : "build/harness").toPath();
        Path media = root.resolve("media");
        Path out = root.resolve("preview");
        Files.createDirectories(media);
        Files.createDirectories(out);

        System.out.println("== 生成测试媒体 ==");
        Path coverJpg = createCoverJpeg(media.resolve("cover.jpg"), 320);
        Path embeddedMp3 = createMp3WithEmbeddedCover(media.resolve("embedded.mp3"), coverJpg, 3.0);
        Path flac = createFlac(media.resolve("exact.flac"), 44100, 3.0);
        Path wav = createWav(media.resolve("plain.wav"), 44100, 3.0);
        Path folderOnly = createWav(media.resolve("folder").resolve("album_track.wav"), 44100, 2.0);
        Files.copy(coverJpg, media.resolve("folder").resolve("folder.jpg"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        System.out.println("== 时长探测 ==");
        checkDuration(flac, 3000, 20, "FLAC");
        checkDuration(wav, 3000, 30, "WAV");
        long mp3Duration = MediaProbe.durationMs(embeddedMp3.toString());
        check(mp3Duration > 2000 && mp3Duration < 6000, "MP3 时长估算落在合理区间，实际=" + mp3Duration);
        checkDuration(folderOnly, 2000, 30, "folder WAV");

        System.out.println("== 内嵌封面解析 ==");
        byte[] id3Cover = CoverArtLoader.extractEmbedded(embeddedMp3.toFile());
        check(id3Cover != null && isJpeg(id3Cover), "从 ID3v2 中解出 JPEG 封面，字节数="
                + (id3Cover == null ? -1 : id3Cover.length));
        check(CoverArtLoader.extractEmbedded(flac.toFile()) == null, "无内嵌封面的 FLAC 返回 null");

        System.out.println("== 目录兜底封面 ==");
        Image folderCover = CoverArtLoader.loadAndScale(folderOnly.toString(), 128);
        check(folderCover != null, "同目录 folder.jpg 被用作封面");

        System.out.println("== 渲染界面 ==");
        render(media.resolve("embedded.mp3"), coverJpg, out.resolve("01-playing-lyrics-on.png"),
                true, true, true, true, "晴天", "周杰伦 · 叶惠美", 62_000, 269_000);
        render(media.resolve("embedded.mp3"), coverJpg, out.resolve("02-paused-lyrics-off.png"),
                false, false, true, true, "晴天", "周杰伦 · 叶惠美", 0, 0);
        render(media.resolve("embedded.mp3"), coverJpg, out.resolve("03-long-title.png"),
                true, false, true, true,
                "Someone Like You (Live at the Royal Albert Hall 2011)",
                "Adele · 21 (Deluxe Edition)", 134_000, 300_000);
        render(media.resolve("embedded.mp3"), coverJpg, out.resolve("04-no-cover.png"),
                false, true, true, false, "没有封面的歌曲", "未知艺术家 · 未知专辑", 12_000, 0);
        render(media.resolve("embedded.mp3"), coverJpg, out.resolve("05-lyrics-unavailable.png"),
                true, true, false, true, "宿主不支持桌面歌词时的样子", "演示 · 状态", 8_000, 180_000);
        render(media.resolve("embedded.mp3"), coverJpg, out.resolve("06-not-playing.png"),
                false, false, false, false, "", "", 0, 0);

        System.out.println("== 渲染封面悬浮窗 ==");
        renderCompact(media.resolve("embedded.mp3"), out.resolve("07-bubble-idle.png"),
                false, 78_000, 269_000);
        renderCompact(media.resolve("embedded.mp3"), out.resolve("08-bubble-hover.png"),
                true, 78_000, 269_000);
        renderCompact(null, out.resolve("09-bubble-no-cover.png"), false, 0, 0);

        System.out.println("== 宿主主题探测 ==");
        boolean themeFirst = HostThemeBridge.isDark();
        Thread.sleep(1800);
        boolean themeSecond = HostThemeBridge.isDark();
        System.out.println("  [i]    首次=" + themeFirst + "，注册表兜底后=" + themeSecond
                + "（宿主 " + (HostThemeBridge.isAvailable() ? "已对接" : "未接入，走系统兜底") + "）");
        check(themeSecond == HostThemeBridge.isDark(), "主题探测结果稳定可读");

        System.out.println("== 渲染浅色主题 ==");
        check(Theme.setDark(false), "切换到浅色配色");
        check(!Theme.isDark(), "当前为浅色配色");
        render(media.resolve("embedded.mp3"), coverJpg, out.resolve("10-light-playing.png"),
                true, true, true, true, "晴天", "周杰伦 · 叶惠美", 62_000, 269_000);
        renderCompact(media.resolve("embedded.mp3"), out.resolve("11-bubble-light-hover.png"),
                true, 78_000, 269_000);
        check(Theme.setDark(true), "切回深色配色");

        System.out.println();
        if (FAILURES.isEmpty()) {
            System.out.println("全部检查通过 ✅");
            System.exit(0);
        } else {
            System.out.println("存在失败项 ❌");
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

    private static void checkDuration(Path file, long expectedMs, long toleranceMs, String label) {
        long actual = MediaProbe.durationMs(file.toString());
        boolean ok = Math.abs(actual - expectedMs) <= toleranceMs;
        check(ok, label + " 时长 " + actual + "ms（期望 " + expectedMs + "±" + toleranceMs + "）");
    }

    private static boolean isJpeg(byte[] data) {
        return data != null && data.length > 3
                && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF;
    }

    // -------------------------------------------------------------- 渲染

    private static void render(Path audioPath, Path coverPath, Path target,
                               boolean playing, boolean lyricsOn, boolean lyricsAvailable,
                               boolean withCover, String title, String subtitle,
                               long position, long duration)
            throws Exception {
        MiniPlayerView view = new MiniPlayerView();
        view.setSize(Theme.WIDTH, Theme.HEIGHT);
        if (withCover) {
            Image cover = CoverArtLoader.loadAndScale(audioPath.toString(), 128);
            view.setCover(cover);
            // 默认开启「封面主题色背景」，预览图反映真实默认外观
            view.setCoverDominant(CoverTheme.dominant(cover));
        }
        view.setNowPlaying(title, subtitle);
        view.setPlaying(playing);
        view.setLyrics(lyricsOn, lyricsAvailable);
        view.setProgress(position, duration);

        BufferedImage canvas = new BufferedImage(Theme.WIDTH + 80, Theme.HEIGHT + 80,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(28, 32, 48), Theme.WIDTH + 80,
                Theme.HEIGHT + 80, new Color(120, 96, 140)));
        g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        g.setColor(new Color(255, 255, 255, 26));
        g.fillOval(-60, 120, 320, 320);

        g.setComposite(AlphaComposite.SrcOver);
        g.translate(40, 40);
        view.paintPreview(g);
        g.dispose();

        ImageIO.write(canvas, "png", target.toFile());
        System.out.println("  [OK]   " + target.getFileName());
    }

    /** 渲染收起后的封面悬浮窗。hover 为真时带上悬停控制层。 */
    private static void renderCompact(Path audioPath, Path target, boolean hover,
                                      long position, long duration) throws Exception {
        MiniPlayerView view = new MiniPlayerView();
        view.setSize(Theme.COMPACT_SIZE, Theme.COMPACT_SIZE);
        view.setCompact(true);
        if (audioPath != null) {
            view.setCover(CoverArtLoader.loadAndScale(audioPath.toString(), 192));
            view.setNowPlaying("晴天", "周杰伦");
        }
        view.setPlaying(true);
        view.setProgress(position, duration);
        if (hover) {
            view.setCompactHover(true);
            view.setHover(MiniPlayerView.Hit.TOGGLE_PLAY);
        }

        int size = Theme.COMPACT_SIZE;
        BufferedImage canvas = new BufferedImage(size + 72, size + 72,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(28, 32, 48),
                size + 72, size + 72, new Color(120, 96, 140)));
        g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        g.setColor(new Color(255, 255, 255, 24));
        g.fillOval(-40, 40, 220, 220);
        g.translate(36, 36);
        view.paintPreview(g);
        g.dispose();

        ImageIO.write(canvas, "png", target.toFile());
        System.out.println("  [OK]   " + target.getFileName()
                + (Theme.isDark() ? "" : "（浅色）"));
    }

    // ------------------------------------------------------- 测试媒体生成
    private static Path createCoverJpeg(Path target, int size) throws Exception {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(255, 138, 76), size, size,
                new Color(88, 62, 200)));
        g.fillRect(0, 0, size, size);
        g.setColor(new Color(255, 255, 255, 60));
        g.fillOval(size / 6, size / 6, size / 2, size / 2);
        g.setColor(new Color(255, 255, 255, 210));
        g.fillRoundRect(size / 4, size / 2, size / 2, size / 12, 12, 12);
        g.dispose();
        ImageIO.write(image, "jpg", target.toFile());
        return target;
    }

    /** 构造带 ID3v2.3 APIC 与一帧 MPEG1 Layer III 的 MP3。 */
    private static Path createMp3WithEmbeddedCover(Path target, Path cover, double seconds)
            throws Exception {
        byte[] image = Files.readAllBytes(cover);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(3);                              // 文本编码：UTF-8
        body.write("image/jpeg".getBytes(StandardCharsets.ISO_8859_1));
        body.write(0);                              // MIME 结束
        body.write(3);                              // 图片类型：封面
        body.write(0);                              // 描述结束
        body.write(image);
        byte[] frameBody = body.toByteArray();

        ByteArrayOutputStream tag = new ByteArrayOutputStream();
        tag.write("APIC".getBytes(StandardCharsets.ISO_8859_1));
        int size = frameBody.length;
        tag.write(new byte[]{(byte) ((size >> 24) & 0xFF), (byte) ((size >> 16) & 0xFF),
                (byte) ((size >> 8) & 0xFF), (byte) (size & 0xFF)});
        tag.write(new byte[]{0, 0});                // 帧标志
        tag.write(frameBody);
        byte[] tagBody = tag.toByteArray();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("ID3".getBytes(StandardCharsets.ISO_8859_1));
        out.write(new byte[]{3, 0, 0});
        int tagSize = tagBody.length;
        out.write(new byte[]{(byte) ((tagSize >> 21) & 0x7F), (byte) ((tagSize >> 14) & 0x7F),
                (byte) ((tagSize >> 7) & 0x7F), (byte) (tagSize & 0x7F)});
        out.write(tagBody);

        // 一帧 MPEG1 Layer III 128kbps 44.1kHz 立体声：FF FB 90 00
        int frameLength = 144 * 128000 / 44100;
        byte[] audioFrame = new byte[frameLength];
        audioFrame[0] = (byte) 0xFF;
        audioFrame[1] = (byte) 0xFB;
        audioFrame[2] = (byte) 0x90;
        audioFrame[3] = 0x00;
        int frames = (int) Math.round(seconds * 44100 / 1152.0);
        for (int i = 0; i < frames; i++) {
            out.write(audioFrame);
        }
        try (FileOutputStream stream = new FileOutputStream(target.toFile())) {
            stream.write(out.toByteArray());
        }
        return target;
    }

    /** 构造仅含合法 STREAMINFO 的 FLAC 文件头。 */
    private static Path createFlac(Path target, int sampleRate, double seconds) throws Exception {
        long totalSamples = Math.round(sampleRate * seconds);
        byte[] streamInfo = new byte[34];
        // 最小块 4096 / 最大块 4096 用位紧凑表示，这里只保证采样率与总采样数正确
        streamInfo[0] = 0x10;
        streamInfo[1] = 0x00;
        streamInfo[2] = 0x10;
        streamInfo[10] = (byte) ((sampleRate >> 12) & 0xFF);
        streamInfo[11] = (byte) ((sampleRate >> 4) & 0xFF);
        streamInfo[12] = (byte) (((sampleRate & 0x0F) << 4) | 0x02);
        streamInfo[13] = (byte) ((totalSamples >> 32) & 0x0F);
        streamInfo[14] = (byte) ((totalSamples >> 24) & 0xFF);
        streamInfo[15] = (byte) ((totalSamples >> 16) & 0xFF);
        streamInfo[16] = (byte) ((totalSamples >> 8) & 0xFF);
        streamInfo[17] = (byte) (totalSamples & 0xFF);
        try (FileOutputStream out = new FileOutputStream(target.toFile())) {
            out.write("fLaC".getBytes(StandardCharsets.ISO_8859_1));
            out.write(0x80);   // 最后一个元数据块 + 类型 0
            out.write(0x00);
            out.write(0x00);
            out.write(34);
            out.write(streamInfo);
        }
        return target;
    }

    /** 构造 16bit 立体声 PCM WAV。 */
    private static Path createWav(Path target, int sampleRate, double seconds) throws Exception {
        int channels = 2;
        int bits = 16;
        int byteRate = sampleRate * channels * bits / 8;
        int dataSize = (int) Math.round(byteRate * seconds);
        Files.createDirectories(target.getParent());
        try (RandomAccessFile raf = new RandomAccessFile(target.toFile(), "rw")) {
            raf.setLength(0);
            raf.write("RIFF".getBytes(StandardCharsets.ISO_8859_1));
            writeIntLE(raf, 36 + dataSize);
            raf.write("WAVE".getBytes(StandardCharsets.ISO_8859_1));
            raf.write("fmt ".getBytes(StandardCharsets.ISO_8859_1));
            writeIntLE(raf, 16);
            writeShortLE(raf, 1);
            writeShortLE(raf, channels);
            writeIntLE(raf, sampleRate);
            writeIntLE(raf, byteRate);
            writeShortLE(raf, channels * bits / 8);
            writeShortLE(raf, bits);
            raf.write("data".getBytes(StandardCharsets.ISO_8859_1));
            writeIntLE(raf, dataSize);
            raf.setLength(raf.getFilePointer() + dataSize);
        }
        return target;
    }

    private static void writeIntLE(RandomAccessFile raf, int value) throws Exception {
        raf.write(value & 0xFF);
        raf.write((value >> 8) & 0xFF);
        raf.write((value >> 16) & 0xFF);
        raf.write((value >> 24) & 0xFF);
    }

    private static void writeShortLE(RandomAccessFile raf, int value) throws Exception {
        raf.write(value & 0xFF);
        raf.write((value >> 8) & 0xFF);
    }
}
