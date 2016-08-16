package com.jivesoftware.os.lab.guts;

import com.jivesoftware.os.lab.io.api.IAppendOnly;
import com.jivesoftware.os.lab.io.api.IReadable;
import com.jivesoftware.os.lab.io.api.UIO;
import java.io.IOException;
import java.util.Arrays;

/**
 *
 * @author jonathan.colt
 */
public class Footer {

    final int leapCount;
    final long count;
    final long keysSizeInBytes;
    final long valuesSizeInBytes;
    final byte[] minKey;
    final byte[] maxKey;
    final long keyFormat;
    final long valueFormat;
    final TimestampAndVersion maxTimestampAndVersion;

    public Footer(int leapCount,
        long count,
        long keysSizeInBytes,
        long valuesSizeInBytes,
        byte[] minKey,
        byte[] maxKey,
        long keyFormat,
        long valueFormat,
        TimestampAndVersion maxTimestampAndVersion
    ) {

        this.leapCount = leapCount;
        this.count = count;
        this.keysSizeInBytes = keysSizeInBytes;
        this.valuesSizeInBytes = valuesSizeInBytes;
        this.minKey = minKey;
        this.maxKey = maxKey;
        this.keyFormat = keyFormat;
        this.valueFormat = valueFormat;
        this.maxTimestampAndVersion = maxTimestampAndVersion;
    }

    @Override
    public String toString() {
        return "Footer{"
            + "leapCount=" + leapCount
            + ", count=" + count
            + ", keysSizeInBytes=" + keysSizeInBytes
            + ", valuesSizeInBytes=" + valuesSizeInBytes
            + ", minKey=" + Arrays.toString(minKey)
            + ", maxKey=" + Arrays.toString(maxKey)
            + ", keyFormat=" + keyFormat
            + ", valueFormat=" + valueFormat
            + ", maxTimestampAndVersion=" + maxTimestampAndVersion
            + '}';
    }

    void write(IAppendOnly writeable) throws IOException {
        int entryLength = 4 + 4 + 8 + 8 + 8 + 4 + (minKey == null ? 0 : minKey.length) + 4 + (maxKey == null ? 0 : maxKey.length) + 8 + 8 + 8 + 8 + 4;
        writeable.appendInt(entryLength);
        writeable.appendInt(leapCount);
        writeable.appendLong(count);
        writeable.appendLong(keysSizeInBytes);
        writeable.appendLong(valuesSizeInBytes);
        UIO.writeByteArray(writeable, minKey, "minKey");
        UIO.writeByteArray(writeable, maxKey, "maxKey");
        writeable.appendLong(maxTimestampAndVersion.maxTimestamp);
        writeable.appendLong(maxTimestampAndVersion.maxTimestampVersion);
        writeable.appendLong(keyFormat);
        writeable.appendLong(valueFormat);
        writeable.appendInt(entryLength);
    }

    static Footer read(IReadable readable) throws IOException {
        int entryLength = readable.readInt();
        int read = 4;
        int leapCount = readable.readInt();
        read += 4;
        long count = readable.readLong();
        read += 8;
        long keysSizeInBytes = readable.readLong();
        read += 8;
        long valuesSizeInBytes = readable.readLong();
        read += 8;
        byte[] minKey = UIO.readByteArray(readable, "minKey");
        read += 4 + minKey.length;
        byte[] maxKey = UIO.readByteArray(readable, "maxKey");
        read += 4 + maxKey.length;
        long maxTimestamp = readable.readLong();
        read += 8;
        long maxTimestampVersion = readable.readLong();
        read += 8;

        long keyFormat = 0;
        long valueFormat = 0;
        if (entryLength == read + 8 + 8 + 4) {
            keyFormat = readable.readLong();
            valueFormat = readable.readLong();
        }

        long el = readable.readInt();
        if (el != entryLength) {
            throw new RuntimeException("Encountered length corruption. " + el + " vs " + entryLength);
        }
        return new Footer(leapCount, count, keysSizeInBytes, valuesSizeInBytes, minKey, maxKey, keyFormat, valueFormat, new TimestampAndVersion(maxTimestamp,
            maxTimestampVersion));
    }

}
