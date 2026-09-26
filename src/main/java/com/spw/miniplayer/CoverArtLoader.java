package com.spw.miniplayer;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 封面读取。
 *
 * <p>宿主 1.18.x 的工坊 API 尚未提供封面查询入口，因此插件自行从音频文件
 * 中解析内嵌封面（ID3v2 / FLAC PICTURE / MP4 covr），失败时退回同目录下的
 * 常见封面文件名。
 */
final class CoverArtLoader {

    /** 单个标签/元数据块的读取上限，防止异常文件占用过多内存。 */
    private static final int MAX_TAG_BYTES = 24 * 1024 * 1024;
    /** MP4 moov 读取上限。 */
    private static final int MAX_MOOV_BYTES = 32 * 1024 * 1024;

    private static final String[] FOLDER_COVERS = {
            "cover", "folder", "front", "album", "albumart", "thumb", "artwork"
    };
    private static final String[] COVER_EXTS = {".jpg", ".jpeg", ".png", ".bmp", ".gif", ".webp"};

    private static final Map<String, Object> CACHE = new LinkedHashMap<String, Object>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
            return size() > 12;
        }
    };

    /** 表示「已确认没有封面」，避免反复扫描磁盘。 */
    private static final Object NONE = new Object();
    private static final Object LOADING = new Object();

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "spw-mini-cover");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    private CoverArtLoader() {
    }

    /** 同步取得已缓存封面；未加载过返回 null。 */
    static Image cached(String path) {
        if (path == null) {
            return null;
        }
        synchronized (CACHE) {
            Object value = CACHE.get(path);
            return value instanceof Image ? (Image) value : null;
        }
    }

    /**
     * 异步请求封面，完成后在 EDT 回调（回调参数可能为 null）。
     *
     * @param path   音频文件路径
     * @param size   目标边长（像素），用于缩放
     * @param onReady 结果回调，EDT 执行
     */
    static void request(String path, int size, Consumer<Image> onReady) {
        if (path == null || path.isEmpty()) {
            onReady.accept(null);
            return;
        }
        Object cached;
        synchronized (CACHE) {
            cached = CACHE.get(path);
        }
        if (cached instanceof Image) {
            SwingUtilities.invokeLater(() -> onReady.accept((Image) cached));
            return;
        }
        if (cached == NONE) {
            SwingUtilities.invokeLater(() -> onReady.accept(null));
            return;
        }
        if (cached == LOADING) {
            // 已在加载中，稍后再取缓存
            SwingUtilities.invokeLater(() -> onReady.accept(cached(path)));
            return;
        }
        synchronized (CACHE) {
            CACHE.put(path, LOADING);
        }
        POOL.execute(() -> {
            Image image = null;
            try {
                image = loadAndScale(path, size);
            } catch (Throwable error) {
                PluginLog.w("读取封面失败: " + error);
            }
            synchronized (CACHE) {
                CACHE.put(path, image == null ? NONE : image);
            }
            Image result = image;
            SwingUtilities.invokeLater(() -> onReady.accept(result));
        });
    }

    /** 同步加载并缩放封面（可能耗时，请勿在 EDT 调用）。 */
    static Image loadAndScale(String path, int size) {
        byte[] raw = null;
        File file = new File(path);
        if (file.isFile() && file.canRead()) {
            raw = extractEmbedded(file);
            if (raw == null) {
                raw = extractFromFolder(file);
            }
        }
        if (raw == null || raw.length == 0) {
            return null;
        }
        try {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(raw));
            if (decoded == null) {
                return null;
            }
            return toSquare(decoded, size);
        } catch (Throwable error) {
            PluginLog.w("解码封面图片失败: " + error);
            return null;
        }
    }

    /** 居中裁剪为正方形并高质量缩放。 */
    private static BufferedImage toSquare(BufferedImage source, int size) {
        int w = source.getWidth();
        int h = source.getHeight();
        int edge = Math.min(w, h);
        int x = (w - edge) / 2;
        int y = (h - edge) / 2;
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(source, 0, 0, size, size, x, y, x + edge, y + edge, null);
        g.dispose();
        return out;
    }

    // ------------------------------------------------------------------
    // 内嵌封面解析
    // ------------------------------------------------------------------

    static byte[] extractEmbedded(File file) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long length = raf.length();
            if (length < 16) {
                return null;
            }
            byte[] head = new byte[(int) Math.min(length, 16)];
            raf.seek(0);
            raf.readFully(head);

            if (head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
                byte[] cover = fromId3v2(raf, length);
                if (cover != null) {
                    return cover;
                }
            }
            if (head[0] == 'f' && head[1] == 'L' && head[2] == 'a' && head[3] == 'C') {
                byte[] cover = fromFlac(raf, length);
                if (cover != null) {
                    return cover;
                }
            }
            // MP4 / M4A：'ftyp' 从第 4 字节开始
            if (head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
                byte[] cover = fromMp4(raf, length);
                if (cover != null) {
                    return cover;
                }
            }
            // 部分格式（如 MP3 无 ID3v2）可能把 ID3 放在末尾，做一次尾部兜底
            if (length > 1024) {
                long tailStart = Math.max(0, length - 1024 * 1024);
                byte[] tail = new byte[(int) (length - tailStart)];
                raf.seek(tailStart);
                raf.readFully(tail);
                int idx = indexOf(tail, "ID3".getBytes(StandardCharsets.US_ASCII));
                if (idx >= 0 && idx + 10 <= tail.length) {
                    byte[] cover = parseId3v2(tail, idx);
                    if (cover != null) {
                        return cover;
                    }
                }
            }
        } catch (Throwable error) {
            PluginLog.w("解析内嵌封面异常: " + error);
        }
        return null;
    }

    /** 读取指定区间的字节。 */
    private static byte[] read(RandomAccessFile raf, long offset, int length) throws Exception {
        byte[] buffer = new byte[length];
        raf.seek(offset);
        raf.readFully(buffer);
        return buffer;
    }

    // --------------------------------------------------------------- ID3v2

    private static byte[] fromId3v2(RandomAccessFile raf, long fileLength) {
        try {
            int tagSize = 10 + synchsafe(read(raf, 6, 4), 0);
            if (tagSize > fileLength || tagSize > MAX_TAG_BYTES || tagSize < 10) {
                return null;
            }
            return parseId3v2(read(raf, 0, tagSize), 0);
        } catch (Throwable error) {
            PluginLog.w("解析 ID3v2 封面失败: " + error);
            return null;
        }
    }

    private static byte[] parseId3v2(byte[] data, int start) {
        if (start + 10 > data.length) {
            return null;
        }
        if (data[start] != 'I' || data[start + 1] != 'D' || data[start + 2] != '3') {
            return null;
        }
        int major = data[start + 3] & 0xFF;
        int flags = data[start + 5] & 0xFF;
        int tagSize = synchsafe(data, start + 6);
        int end = Math.min(data.length, start + 10 + tagSize);
        int pos = start + 10;

        // 跳过扩展头
        if ((flags & 0x40) != 0 && pos + 4 <= end) {
            if (major >= 4) {
                int extSize = synchsafe(data, pos);
                pos += Math.max(extSize, 6);
            } else {
                long extSize = readInt32(data, pos);
                pos += (int) extSize + 4;
            }
        }
        boolean v24 = major >= 4;
        while (pos + 10 <= end) {
            String id = new String(data, pos, 4, StandardCharsets.ISO_8859_1);
            if (id.charAt(0) == '\0') {
                break;
            }
            int frameSize = v24 ? synchsafe(data, pos + 4) : (int) readInt32(data, pos + 4);
            if (frameSize <= 0 || pos + 10 + frameSize > end) {
                break;
            }
            if ("APIC".equals(id)) {
                int cursor = pos + 10;
                int limit = pos + 10 + frameSize;
                if (cursor >= limit) {
                    return null;
                }
                int encoding = data[cursor] & 0xFF;
                cursor++;
                cursor = skipNullTerminated(data, cursor, limit, true);
                if (cursor < 0) {
                    return null;
                }
                cursor++; // 图片类型
                cursor = skipNullTerminated(data, cursor, limit, encoding == 0 || encoding == 3);
                if (cursor < 0) {
                    return null;
                }
                int start2 = cursor;
                return sniffImage(data, start2, limit);
            }
            pos += 10 + frameSize;
        }
        return null;
    }

    /** 跳过 ISO-8859-1(true) 或 UTF-16(false) 的 NUL 结尾字符串。 */
    private static int skipNullTerminated(byte[] data, int pos, int limit, boolean singleByte) {
        if (singleByte) {
            while (pos < limit) {
                if (data[pos] == 0) {
                    return pos + 1;
                }
                pos++;
            }
            return -1;
        }
        while (pos + 1 < limit) {
            if (data[pos] == 0 && data[pos + 1] == 0) {
                return pos + 2;
            }
            pos += 2;
        }
        return -1;
    }

    // ----------------------------------------------------------------- FLAC

    private static byte[] fromFlac(RandomAccessFile raf, long fileLength) {
        try {
            long pos = 4; // 跳过 "fLaC"
            while (pos + 4 <= fileLength) {
                byte[] header = read(raf, pos, 4);
                boolean last = (header[0] & 0x80) != 0;
                int type = header[0] & 0x7F;
                int blockLength = ((header[1] & 0xFF) << 16) | ((header[2] & 0xFF) << 8)
                        | (header[3] & 0xFF);
                long payloadPos = pos + 4;
                if (blockLength < 0 || payloadPos + blockLength > fileLength) {
                    return null;
                }
                if (type == 6 && blockLength > 0 && blockLength <= MAX_TAG_BYTES) {
                    byte[] block = read(raf, payloadPos, blockLength);
                    byte[] image = parseFlacPicture(block);
                    if (image != null) {
                        return image;
                    }
                }
                pos = payloadPos + blockLength;
                if (last) {
                    break;
                }
            }
        } catch (Throwable error) {
            PluginLog.w("解析 FLAC 封面失败: " + error);
        }
        return null;
    }

    private static byte[] parseFlacPicture(byte[] block) {
        int pos = 4; // 图片类型
        long mimeLen = readInt32(block, pos);
        pos += 4 + (int) mimeLen;
        if (pos < 0 || pos + 4 > block.length) {
            return null;
        }
        long descLen = readInt32(block, pos);
        pos += 4 + (int) descLen;
        pos += 16; // width/height/depth/colors
        if (pos < 0 || pos + 4 > block.length) {
            return null;
        }
        long dataLen = readInt32(block, pos);
        pos += 4;
        if (dataLen <= 0 || pos + dataLen > block.length) {
            return null;
        }
        return sniffImage(block, pos, (int) (pos + dataLen));
    }

    // ------------------------------------------------------------------ MP4

    private static byte[] fromMp4(RandomAccessFile raf, long fileLength) {
        try {
            long pos = 0;
            byte[] moov = null;
            while (pos + 8 <= fileLength) {
                byte[] header = read(raf, pos, 8);
                long size = readInt32(header, 0);
                String type = new String(header, 4, 4, StandardCharsets.ISO_8859_1);
                long headerSize = 8;
                if (size == 1) {
                    byte[] large = read(raf, pos + 8, 8);
                    size = ((long) readInt32(large, 0) << 32) | (readInt32(large, 4) & 0xFFFFFFFFL);
                    headerSize = 16;
                } else if (size == 0) {
                    size = fileLength - pos;
                }
                if (size < headerSize || pos + size > fileLength) {
                    break;
                }
                if ("moov".equals(type)) {
                    int payload = (int) Math.min(size - headerSize, MAX_MOOV_BYTES);
                    moov = read(raf, pos + headerSize, payload);
                    break;
                }
                pos += size;
            }
            if (moov == null) {
                return null;
            }
            int covr = findBox(moov, 0, moov.length, "covr");
            if (covr < 0) {
                return null;
            }
            int payloadStart = covr;
            int payloadEnd = moov.length;
            int dataBox = findBox(moov, payloadStart, payloadEnd, "data");
            int imageStart = dataBox >= 0 ? dataBox : payloadStart;
            return sniffImage(moov, imageStart, payloadEnd);
        } catch (Throwable error) {
            PluginLog.w("解析 MP4 封面失败: " + error);
        }
        return null;
    }

    /** 在 [from,to) 区间内递归查找 box，返回其 payload 起始偏移；未找到返回 -1。 */
    private static int findBox(byte[] data, int from, int to, String target) {
        int pos = from;
        while (pos + 8 <= to) {
            long size = readInt32(data, pos);
            String type = new String(data, pos + 4, 4, StandardCharsets.ISO_8859_1);
            int headerSize = 8;
            if (size == 1) {
                if (pos + 16 > to) {
                    return -1;
                }
                size = ((long) readInt32(data, pos + 8) << 32)
                        | (readInt32(data, pos + 12) & 0xFFFFFFFFL);
                headerSize = 16;
            } else if (size == 0) {
                size = to - pos;
            }
            if (size < headerSize || pos + size > to) {
                return -1;
            }
            int payloadStart = pos + headerSize;
            int payloadEnd = (int) (pos + size);
            if (target.equals(type)) {
                return payloadStart;
            }
            // 已知的容器 box 需要继续深入
            if (isContainer(type)) {
                int nested = findBox(data, payloadStart, payloadEnd, target);
                if (nested >= 0) {
                    return nested;
                }
            }
            pos = payloadEnd;
        }
        return -1;
    }

    private static boolean isContainer(String type) {
        switch (type) {
            case "moov":
            case "udta":
            case "trak":
            case "mdia":
            case "ilst":
            case "\u00a9nam":
            case "----":
                return true;
            default:
                return false;
        }
    }

    // -------------------------------------------------------------- 目录兜底

    private static byte[] extractFromFolder(File audioFile) {
        File dir = audioFile.getParentFile();
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        for (String base : FOLDER_COVERS) {
            for (String ext : COVER_EXTS) {
                File candidate = new File(dir, base + ext);
                if (candidate.isFile() && candidate.length() > 0) {
                    byte[] bytes = readFile(candidate, MAX_TAG_BYTES);
                    if (bytes != null) {
                        return bytes;
                    }
                }
            }
        }
        // 与音频同名的图片
        String name = audioFile.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        for (String ext : COVER_EXTS) {
            File candidate = new File(dir, stem + ext);
            if (candidate.isFile() && candidate.length() > 0) {
                byte[] bytes = readFile(candidate, MAX_TAG_BYTES);
                if (bytes != null) {
                    return bytes;
                }
            }
        }
        return null;
    }

    private static byte[] readFile(File file, int limit) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            int length = (int) Math.min(raf.length(), limit);
            if (length <= 0) {
                return null;
            }
            byte[] buffer = new byte[length];
            raf.readFully(buffer);
            return buffer;
        } catch (Throwable error) {
            return null;
        }
    }

    // ----------------------------------------------------------------- 工具

    /** 定位图片数据：找到魔数后截取到区间末尾（JPEG 会裁剪到 EOI）。 */
    private static byte[] sniffImage(byte[] data, int from, int to) {
        if (from < 0 || to > data.length || from >= to) {
            return null;
        }
        int start = -1;
        for (int i = from; i + 3 < to; i++) {
            int b0 = data[i] & 0xFF;
            int b1 = data[i + 1] & 0xFF;
            int b2 = data[i + 2] & 0xFF;
            int b3 = data[i + 3] & 0xFF;
            boolean jpeg = b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF;
            boolean png = b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47;
            boolean gif = b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38;
            boolean bmp = b0 == 0x42 && b1 == 0x4D;
            if (jpeg || png || gif || bmp) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return null;
        }
        int end = to;
        if ((data[start] & 0xFF) == 0xFF) {
            for (int i = to - 2; i > start; i--) {
                if ((data[i] & 0xFF) == 0xFF && (data[i + 1] & 0xFF) == 0xD9) {
                    end = i + 2;
                    break;
                }
            }
        }
        return Arrays.copyOfRange(data, start, end);
    }

    private static int synchsafe(byte[] data, int offset) {
        return ((data[offset] & 0x7F) << 21)
                | ((data[offset + 1] & 0x7F) << 14)
                | ((data[offset + 2] & 0x7F) << 7)
                | (data[offset + 3] & 0x7F);
    }

    private static long readInt32(byte[] data, int offset) {
        if (offset + 4 > data.length) {
            return 0;
        }
        return ((long) (data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (int i = 0; i + pattern.length <= data.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
