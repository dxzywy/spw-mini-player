package com.spw.miniplayer;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * 轻量音频时长探测。
 *
 * <p>1.18.x 的工坊 API 不提供时长信息，这里直接从文件头解析，
 * 覆盖 FLAC / M4A(MP4) / MP3 / WAV。无法解析时返回 -1，界面会隐藏进度条。
 */
final class MediaProbe {

    private MediaProbe() {
    }

    /** 返回时长（毫秒），未知返回 -1。 */
    static long durationMs(String path) {
        if (path == null || path.isEmpty()) {
            return -1;
        }
        File file = new File(path);
        if (!file.isFile() || file.length() < 32) {
            return -1;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long length = raf.length();
            byte[] head = new byte[(int) Math.min(length, 64)];
            raf.seek(0);
            raf.readFully(head);
            String tag = new String(head, 0, 4, StandardCharsets.ISO_8859_1);

            if ("fLaC".equals(tag)) {
                return flacDuration(raf, length);
            }
            if ("RIFF".equals(tag)) {
                return wavDuration(raf, length);
            }
            if ("OggS".equals(tag)) {
                return oggDuration(raf, length);
            }
            if (length > 12 && head[4] == 'f' && head[5] == 't' && head[6] == 'y'
                    && head[7] == 'p') {
                return mp4Duration(raf, length);
            }
            if (head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
                return mp3Duration(raf, length, synchsafe(head, 6) + 10);
            }
            // 无 ID3 头，尝试直接按 MPEG 帧解析
            if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0) {
                return mp3Duration(raf, length, 0);
            }
        } catch (Throwable error) {
            PluginLog.w("探测音频时长失败: " + error);
        }
        return -1;
    }

    // ------------------------------------------------------------------ FLAC

    private static long flacDuration(RandomAccessFile raf, long length) {
        try {
            long pos = 4;
            while (pos + 4 <= length) {
                byte[] header = new byte[4];
                raf.seek(pos);
                raf.readFully(header);
                boolean last = (header[0] & 0x80) != 0;
                int type = header[0] & 0x7F;
                int blockLength = ((header[1] & 0xFF) << 16) | ((header[2] & 0xFF) << 8)
                        | (header[3] & 0xFF);
                if (type == 0 && blockLength >= 34) {
                    byte[] streamInfo = new byte[34];
                    raf.seek(pos + 4);
                    raf.readFully(streamInfo);
                    long sampleRate = ((streamInfo[10] & 0xFF) << 12)
                            | ((streamInfo[11] & 0xFF) << 4)
                            | ((streamInfo[12] & 0xF0) >> 4);
                    long totalSamples = ((long) (streamInfo[13] & 0x0F) << 32)
                            | ((long) (streamInfo[14] & 0xFF) << 24)
                            | ((long) (streamInfo[15] & 0xFF) << 16)
                            | ((long) (streamInfo[16] & 0xFF) << 8)
                            | (streamInfo[17] & 0xFF);
                    if (sampleRate > 0 && totalSamples > 0) {
                        return totalSamples * 1000L / sampleRate;
                    }
                    return -1;
                }
                pos += 4 + blockLength;
                if (last) {
                    break;
                }
            }
        } catch (Throwable error) {
            PluginLog.w("解析 FLAC 时长失败: " + error);
        }
        return -1;
    }

    // ------------------------------------------------------------------- WAV

    private static long wavDuration(RandomAccessFile raf, long length) {
        try {
            long pos = 12;
            long byteRate = -1;
            long dataSize = -1;
            while (pos + 8 <= length) {
                byte[] header = new byte[8];
                raf.seek(pos);
                raf.readFully(header);
                String id = new String(header, 0, 4, StandardCharsets.ISO_8859_1);
                long size = readInt32LE(header, 4);
                if ("fmt ".equals(id)) {
                    byte[] fmt = new byte[(int) Math.min(size, 16)];
                    raf.seek(pos + 8);
                    raf.readFully(fmt);
                    byteRate = readInt32LE(fmt, 8);
                } else if ("data".equals(id)) {
                    dataSize = size;
                    break;
                }
                pos += 8 + size + (size % 2);
            }
            if (byteRate > 0 && dataSize > 0) {
                return dataSize * 1000L / byteRate;
            }
        } catch (Throwable error) {
            PluginLog.w("解析 WAV 时长失败: " + error);
        }
        return -1;
    }

    // ------------------------------------------------------------------- MP4

    private static long mp4Duration(RandomAccessFile raf, long length) {
        try {
            long pos = 0;
            while (pos + 8 <= length) {
                byte[] header = new byte[16];
                raf.seek(pos);
                raf.readFully(header, 0, 8);
                long size = readInt32BE(header, 0);
                String type = new String(header, 4, 4, StandardCharsets.ISO_8859_1);
                long headerSize = 8;
                if (size == 1) {
                    raf.readFully(header, 8, 8);
                    size = ((long) readInt32BE(header, 8) << 32)
                            | (readInt32BE(header, 12) & 0xFFFFFFFFL);
                    headerSize = 16;
                } else if (size == 0) {
                    size = length - pos;
                }
                if (size < headerSize || pos + size > length) {
                    break;
                }
                if ("moov".equals(type)) {
                    int payload = (int) Math.min(size - headerSize, 32L * 1024 * 1024);
                    byte[] moov = new byte[payload];
                    raf.seek(pos + headerSize);
                    raf.readFully(moov);
                    return parseMvhd(moov);
                }
                pos += size;
            }
        } catch (Throwable error) {
            PluginLog.w("解析 MP4 时长失败: " + error);
        }
        return -1;
    }

    private static long parseMvhd(byte[] moov) {
        int index = indexOf(moov, "mvhd".getBytes(StandardCharsets.ISO_8859_1));
        if (index < 0 || index + 4 > moov.length) {
            return -1;
        }
        int pos = index - 4 + 8; // 指向 mvhd 的 payload（跳过 box header）
        if (pos + 20 > moov.length) {
            return -1;
        }
        int version = moov[pos] & 0xFF;
        if (version == 1) {
            if (pos + 28 > moov.length) {
                return -1;
            }
            long timescale = readInt32BE(moov, pos + 20) & 0xFFFFFFFFL;
            long duration = ((long) readInt32BE(moov, pos + 24) << 32)
                    | (readInt32BE(moov, pos + 28) & 0xFFFFFFFFL);
            if (timescale > 0) {
                return duration * 1000L / timescale;
            }
        } else {
            long timescale = readInt32BE(moov, pos + 12) & 0xFFFFFFFFL;
            long duration = readInt32BE(moov, pos + 16) & 0xFFFFFFFFL;
            if (timescale > 0) {
                return duration * 1000L / timescale;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------- MP3

    private static long mp3Duration(RandomAccessFile raf, long length, long audioStart) {
        try {
            int probeLength = (int) Math.min(length - audioStart, 256 * 1024);
            if (probeLength <= 0) {
                return -1;
            }
            byte[] data = new byte[probeLength];
            raf.seek(audioStart);
            raf.readFully(data);

            int frameOffset = -1;
            for (int i = 0; i + 4 < data.length; i++) {
                if ((data[i] & 0xFF) == 0xFF && (data[i + 1] & 0xE0) == 0xE0
                        && (data[i + 1] & 0x18) != 0x08) {
                    frameOffset = i;
                    break;
                }
            }
            if (frameOffset < 0) {
                return -1;
            }
            int b1 = data[frameOffset + 1] & 0xFF;
            int b2 = data[frameOffset + 2] & 0xFF;
            int b3 = data[frameOffset + 3] & 0xFF;
            int versionBits = (b1 >> 3) & 0x03;
            int layerBits = (b1 >> 1) & 0x03;
            int bitrateIndex = (b2 >> 4) & 0x0F;
            int sampleRateIndex = (b2 >> 2) & 0x03;
            int channelMode = (b3 >> 6) & 0x03;

            int sampleRate = sampleRateFor(versionBits, sampleRateIndex);
            int bitrate = bitrateFor(versionBits, layerBits, bitrateIndex);
            if (sampleRate <= 0 || bitrate <= 0) {
                return -1;
            }
            int frameLength = 144 * bitrate * 1000 / sampleRate;
            if ((layerBits == 3 && versionBits == 3) || (layerBits == 1 && versionBits != 3)) {
                frameLength = 72 * bitrate * 1000 / sampleRate;
            } else if (layerBits == 1) {
                frameLength = 72 * bitrate * 1000 / sampleRate;
            }

            // Xing / Info 头（位于第一个帧的帧头之后，偏移取决于声道模式与版本）
            int xingOffset = frameOffset + 4
                    + ((versionBits == 3) ? (channelMode == 3 ? 17 : 32)
                    : (channelMode == 3 ? 9 : 17));
            if (xingOffset + 12 < data.length) {
                String marker = new String(data, xingOffset, 4, StandardCharsets.ISO_8859_1);
                if ("Xing".equals(marker) || "Info".equals(marker)) {
                    int flags = (int) readInt32BE(data, xingOffset + 4);
                    int cursor = xingOffset + 8;
                    if ((flags & 0x0001) != 0 && cursor + 4 <= data.length) {
                        long frames = readInt32BE(data, cursor) & 0xFFFFFFFFL;
                        int samplesPerFrame = (versionBits == 3) ? 1152 : 576;
                        if (frames > 0) {
                            return frames * samplesPerFrame * 1000L / sampleRate;
                        }
                    }
                }
            }

            long audioBytes = length - audioStart;
            // bitrate 单位是 kbps：bytes*8/(bitrate*1000) 秒 → ×1000 得毫秒
            return audioBytes * 8L / bitrate;
        } catch (Throwable error) {
            PluginLog.w("解析 MP3 时长失败: " + error);
        }
        return -1;
    }

    private static int sampleRateFor(int versionBits, int index) {
        int[][] table = {
                {11025, 12000, 8000},
                {0, 0, 0},
                {22050, 24000, 16000},
                {44100, 48000, 32000}
        };
        if (index < 0 || index > 2) {
            return -1;
        }
        return table[versionBits][index];
    }

    private static int bitrateFor(int versionBits, int layerBits, int index) {
        // layer: 3 = Layer I, 2 = Layer II, 1 = Layer III
        int[][][] tables = {
                // 保留
                {{0}, {0}, {0}, {0}},
                // MPEG 2 / 2.5
                {
                        {0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, 0},
                        {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0},
                        {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0},
                        {0}
                },
                // 保留
                {{0}, {0}, {0}, {0}},
                // MPEG 1
                {
                        {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0},
                        {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0},
                        {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0},
                        {0}
                }
        };
        if (versionBits < 0 || versionBits > 3 || layerBits < 1 || layerBits > 3) {
            return -1;
        }
        if (index < 0 || index > 15) {
            return -1;
        }
        return tables[versionBits][layerBits][index];
    }

    // ------------------------------------------------------------------- OGG

    private static long oggDuration(RandomAccessFile raf, long length) {
        try {
            int headLength = (int) Math.min(length, 4096);
            byte[] head = new byte[headLength];
            raf.seek(0);
            raf.readFully(head);
            int vorbis = indexOf(head, "vorbis".getBytes(StandardCharsets.ISO_8859_1));
            if (vorbis < 0 || vorbis + 12 > head.length) {
                return -1;
            }
            long sampleRate = readInt32LE(head, vorbis + 8);
            if (sampleRate <= 0) {
                return -1;
            }
            int tailLength = (int) Math.min(length, 64 * 1024);
            byte[] tail = new byte[tailLength];
            raf.seek(length - tailLength);
            raf.readFully(tail);
            int lastPage = -1;
            for (int i = tailLength - 27; i >= 0; i--) {
                if (tail[i] == 'O' && tail[i + 1] == 'g' && tail[i + 2] == 'g' && tail[i + 3] == 'S') {
                    lastPage = i;
                    break;
                }
            }
            if (lastPage < 0) {
                return -1;
            }
            long granule = readInt64LE(tail, lastPage + 6);
            if (granule <= 0) {
                return -1;
            }
            return granule * 1000L / sampleRate;
        } catch (Throwable error) {
            PluginLog.w("解析 OGG 时长失败: " + error);
        }
        return -1;
    }

    // ----------------------------------------------------------------- 工具

    private static int synchsafe(byte[] data, int offset) {
        return ((data[offset] & 0x7F) << 21)
                | ((data[offset + 1] & 0x7F) << 14)
                | ((data[offset + 2] & 0x7F) << 7)
                | (data[offset + 3] & 0x7F);
    }

    private static int readInt32BE(byte[] data, int offset) {
        if (offset + 4 > data.length) {
            return 0;
        }
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    private static int readInt32LE(byte[] data, int offset) {
        if (offset + 4 > data.length) {
            return 0;
        }
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    private static long readInt64LE(byte[] data, int offset) {
        if (offset + 8 > data.length) {
            return -1;
        }
        long value = 0;
        for (int i = 7; i >= 0; i--) {
            value = (value << 8) | (data[offset + i] & 0xFF);
        }
        return value;
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
