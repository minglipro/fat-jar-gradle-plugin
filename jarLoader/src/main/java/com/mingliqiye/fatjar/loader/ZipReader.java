package com.mingliqiye.fatjar.loader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * 只读的随机访问 ZIP 解析器。
 *
 * <p>不把 entry 内容读进内存：启动时只解析中央目录，真正 loadClass / openStream 时
 * 才按 offset 读对应的一段字节（STORED 直接返回，DEFLATED 用 Inflater 解压）。
 * 这样嵌套 jar 不需要整体驻留堆内存。</p>
 *
 * <p>嵌套 jar 必须在外层以 <b>STORED</b> 方式写入，才能直接在文件里定位它的字节区间；
 * 旧的 DEFLATED 嵌套 jar 会退化为整体读入内存（兼容路径）。</p>
 *
 * <p>支持 ZIP64 的中央目录；不支持加密、分卷、数据描述符以外的特殊格式。</p>
 */
final class ZipReader {

    static final int STORED = 0;

    private static final int EOCD_SIG = 0x06054b50;
    private static final int ZIP64_EOCD_SIG = 0x06064b50;
    private static final int ZIP64_LOCATOR_SIG = 0x07064b50;
    private static final int CENTRAL_SIG = 0x02014b50;
    private static final int LOCAL_SIG = 0x04034b50;

    private final DataSource source;
    private final Map<String, Entry> entries;

    private ZipReader(DataSource source, Map<String, Entry> entries) {
        this.source = source;
        this.entries = entries;
    }

    static ZipReader open(DataSource source) throws IOException {
        long length = source.length();
        int tailSize = (int) Math.min(length, 65557L + 22L);
        ByteBuffer tail = ByteBuffer.allocate(tailSize).order(ByteOrder.LITTLE_ENDIAN);
        source.readFully(tail, length - tailSize);
        byte[] tailBytes = tail.array();
        int eocdInTail = findEocd(tailBytes);
        if (eocdInTail < 0) {
            throw new IOException("Not a zip archive: end of central directory not found");
        }

        ByteBuffer eocd = ByteBuffer.wrap(tailBytes, eocdInTail, tailBytes.length - eocdInTail)
                .order(ByteOrder.LITTLE_ENDIAN);
        eocd.getInt();
        eocd.getShort();
        eocd.getShort();
        int entriesThisDisk = eocd.getShort() & 0xFFFF;
        int totalEntries = eocd.getShort() & 0xFFFF;
        long centralSize = eocd.getInt() & 0xFFFFFFFFL;
        long centralOffset = eocd.getInt() & 0xFFFFFFFFL;

        long eocdPosition = length - tailSize + eocdInTail;
        if (totalEntries == 0xFFFF || centralSize == 0xFFFFFFFFL || centralOffset == 0xFFFFFFFFL) {
            long[] zip64 = readZip64(source, eocdPosition);
            totalEntries = (int) Math.min(zip64[0], Integer.MAX_VALUE);
            centralSize = zip64[1];
            centralOffset = zip64[2];
        }
        if (centralSize > Integer.MAX_VALUE) {
            throw new IOException("Central directory too large: " + centralSize);
        }
        Map<String, Entry> entries = readCentralDirectory(source, centralOffset, centralSize);
        return new ZipReader(source, entries);
    }

    boolean contains(String name) {
        return entries.containsKey(name);
    }

    Entry entry(String name) {
        return entries.get(name);
    }

    Collection<String> names() {
        return Collections.unmodifiableSet(entries.keySet());
    }

    /** 读取 entry 的完整（未压缩）内容。 */
    byte[] read(String name) throws IOException {
        Entry entry = entries.get(name);
        if (entry == null) {
            throw new IOException("No such entry: " + name);
        }
        if (entry.compressedSize() > Integer.MAX_VALUE || entry.uncompressedSize() > Integer.MAX_VALUE) {
            throw new IOException("Entry too large to read into memory: " + name);
        }
        byte[] compressed = new byte[(int) entry.compressedSize()];
        source.readFully(ByteBuffer.wrap(compressed), dataOffset(entry));
        if (entry.method() == STORED) {
            return compressed;
        }
        if (entry.method() == 8) {
            return inflate(compressed, (int) entry.uncompressedSize(), name);
        }
        throw new IOException("Unsupported compression method " + entry.method() + " for " + name);
    }

    /** entry 数据在 source 内的起始偏移（已跳过 local header）。 */
    long dataOffset(String name) throws IOException {
        Entry entry = entries.get(name);
        if (entry == null) {
            throw new IOException("No such entry: " + name);
        }
        return dataOffset(entry);
    }

    private long dataOffset(Entry entry) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
        source.readFully(header, entry.localHeaderOffset());
        header.flip();
        if (header.getInt() != LOCAL_SIG) {
            throw new IOException("Bad local header for " + entry.name());
        }
        header.position(26);
        int nameLength = header.getShort() & 0xFFFF;
        int extraLength = header.getShort() & 0xFFFF;
        return entry.localHeaderOffset() + 30L + nameLength + extraLength;
    }

    private static byte[] inflate(byte[] compressed, int uncompressedSize, String name) throws IOException {
        if (uncompressedSize == 0) {
            return new byte[0];
        }
        byte[] result = new byte[uncompressedSize];
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(compressed);
            int offset = 0;
            while (offset < result.length) {
                int read = inflater.inflate(result, offset, result.length - offset);
                if (read == 0) {
                    if (inflater.finished() || inflater.needsInput()) {
                        break;
                    }
                }
                offset += read;
            }
            if (offset != result.length) {
                throw new IOException("Truncated entry " + name + ": expected " + uncompressedSize
                        + " bytes, got " + offset);
            }
            return result;
        } catch (DataFormatException ex) {
            throw new IOException("Cannot inflate entry " + name, ex);
        } finally {
            inflater.end();
        }
    }

    private static int findEocd(byte[] bytes) {
        for (int i = bytes.length - 22; i >= 0; i--) {
            if ((bytes[i] & 0xFF) == 0x50 && (bytes[i + 1] & 0xFF) == 0x4B
                    && (bytes[i + 2] & 0xFF) == 0x05 && (bytes[i + 3] & 0xFF) == 0x06) {
                int commentLength = (bytes[i + 20] & 0xFF) | ((bytes[i + 21] & 0xFF) << 8);
                if (i + 22 + commentLength == bytes.length) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static long[] readZip64(DataSource source, long eocdPosition) throws IOException {
        ByteBuffer locator = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        source.readFully(locator, eocdPosition - 20);
        locator.flip();
        if (locator.getInt() != ZIP64_LOCATOR_SIG) {
            throw new IOException("ZIP64 locator not found");
        }
        locator.getInt();
        long zip64EocdOffset = locator.getLong();

        ByteBuffer eocd = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN);
        source.readFully(eocd, zip64EocdOffset);
        eocd.flip();
        if (eocd.getInt() != ZIP64_EOCD_SIG) {
            throw new IOException("Bad ZIP64 end of central directory");
        }
        eocd.position(32);
        long totalEntries = eocd.getLong();
        long centralSize = eocd.getLong();
        long centralOffset = eocd.getLong();
        return new long[]{totalEntries, centralSize, centralOffset};
    }

    private static Map<String, Entry> readCentralDirectory(DataSource source, long offset, long size)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate((int) size).order(ByteOrder.LITTLE_ENDIAN);
        source.readFully(buffer, offset);
        buffer.flip();

        Map<String, Entry> entries = new LinkedHashMap<>();
        while (buffer.remaining() >= 46) {
            if (buffer.getInt() != CENTRAL_SIG) {
                throw new IOException("Bad central directory entry signature");
            }
            buffer.position(buffer.position() + 4); // version made by / needed
            buffer.getShort(); // flags
            int method = buffer.getShort() & 0xFFFF;
            buffer.position(buffer.position() + 4); // time / date
            buffer.getInt(); // crc
            long compressedSize = buffer.getInt() & 0xFFFFFFFFL;
            long uncompressedSize = buffer.getInt() & 0xFFFFFFFFL;
            int nameLength = buffer.getShort() & 0xFFFF;
            int extraLength = buffer.getShort() & 0xFFFF;
            int commentLength = buffer.getShort() & 0xFFFF;
            buffer.position(buffer.position() + 8); // disk start / internal / external attrs
            long localHeaderOffset = buffer.getInt() & 0xFFFFFFFFL;

            byte[] nameBytes = new byte[nameLength];
            buffer.get(nameBytes);
            byte[] extra = new byte[extraLength];
            buffer.get(extra);
            buffer.position(buffer.position() + commentLength);

            if (compressedSize == 0xFFFFFFFFL || uncompressedSize == 0xFFFFFFFFL
                    || localHeaderOffset == 0xFFFFFFFFL) {
                long[] zip64 = readZip64Extra(extra, compressedSize, uncompressedSize, localHeaderOffset);
                compressedSize = zip64[0];
                uncompressedSize = zip64[1];
                localHeaderOffset = zip64[2];
            }
            String name = new String(nameBytes, StandardCharsets.UTF_8);
            entries.put(name, new Entry(name, method, compressedSize, uncompressedSize, localHeaderOffset));
        }
        return entries;
    }

    private static long[] readZip64Extra(byte[] extra, long compressedSize, long uncompressedSize,
            long localHeaderOffset) throws IOException {
        int position = 0;
        while (position + 4 <= extra.length) {
            int id = (extra[position] & 0xFF) | ((extra[position + 1] & 0xFF) << 8);
            int length = (extra[position + 2] & 0xFF) | ((extra[position + 3] & 0xFF) << 8);
            position += 4;
            if (id == 0x0001) {
                int cursor = position;
                if (uncompressedSize == 0xFFFFFFFFL) {
                    uncompressedSize = readLong(extra, cursor);
                    cursor += 8;
                }
                if (compressedSize == 0xFFFFFFFFL) {
                    compressedSize = readLong(extra, cursor);
                    cursor += 8;
                }
                if (localHeaderOffset == 0xFFFFFFFFL) {
                    localHeaderOffset = readLong(extra, cursor);
                }
                return new long[]{compressedSize, uncompressedSize, localHeaderOffset};
            }
            position += length;
        }
        throw new IOException("Missing ZIP64 extra field");
    }

    private static long readLong(byte[] bytes, int offset) throws IOException {
        if (offset + 8 > bytes.length) {
            throw new IOException("Truncated ZIP64 extra field");
        }
        long value = 0;
        for (int i = 7; i >= 0; i--) {
            value = (value << 8) | (bytes[offset + i] & 0xFF);
        }
        return value;
    }

    /** 一个 entry 的中央目录信息。 */
    record Entry(String name, int method, long compressedSize, long uncompressedSize,
            long localHeaderOffset) {
    }

    /** ZIP 数据来源：整个文件的一段，或一段内存。 */
    interface DataSource {

        long length();

        void readFully(ByteBuffer buffer, long position) throws IOException;
    }

    /** 文件区间（支持嵌套 jar 的 base offset），并发安全的定位读。 */
    static final class ChannelSource implements DataSource {

        private final FileChannel channel;
        private final long baseOffset;
        private final long length;

        ChannelSource(FileChannel channel, long baseOffset, long length) {
            this.channel = channel;
            this.baseOffset = baseOffset;
            this.length = length;
        }

        static ChannelSource open(Path path) throws IOException {
            FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
            return new ChannelSource(channel, 0, channel.size());
        }

        @Override
        public long length() {
            return length;
        }

        FileChannel channel() {
            return channel;
        }

        @Override
        public void readFully(ByteBuffer buffer, long position) throws IOException {
            long absolute = baseOffset + position;
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer, absolute);
                if (read < 0) {
                    throw new IOException("Unexpected end of file");
                }
                absolute += read;
            }
        }
    }

    /** 内存区间，用于兼容旧的 DEFLATED 嵌套 jar。 */
    static final class ByteArraySource implements DataSource {

        private final byte[] bytes;

        ByteArraySource(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public long length() {
            return bytes.length;
        }

        @Override
        public void readFully(ByteBuffer buffer, long position) throws IOException {
            int start = (int) position;
            if (start < 0 || start + buffer.remaining() > bytes.length) {
                throw new IOException("Unexpected end of data");
            }
            buffer.put(bytes, start, buffer.remaining());
        }
    }
}
