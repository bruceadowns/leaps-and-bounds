package com.jivesoftware.os.lab.api;

import com.jivesoftware.os.lab.io.api.IAppendOnly;
import com.jivesoftware.os.lab.io.api.IReadable;
import java.nio.ByteBuffer;
import java.util.Comparator;

/**
 *
 * @author jonathan.colt
 */
public interface Rawhide {

    Comparator<byte[]> getKeyComparator();

    Comparator<ByteBuffer> getByteBufferKeyComparator();

    byte[] merge(FormatTransformer currentReadKeyFormatTransormer,
        FormatTransformer currentReadValueFormatTransormer,
        byte[] currentRawEntry,
        FormatTransformer addingReadKeyFormatTransormer,
        FormatTransformer addingReadValueFormatTransormer,
        byte[] addingRawEntry,
        FormatTransformer mergedReadKeyFormatTransormer,
        FormatTransformer mergedReadValueFormatTransormer);

    boolean streamRawEntry(int index,
        FormatTransformer readKeyFormatTransormer,
        FormatTransformer readValueFormatTransormer,
        ByteBuffer rawEntry,
        ValueStream stream,
        boolean hydrateValues) throws Exception;

    byte[] toRawEntry(byte[] key,
        long timestamp,
        boolean tombstoned,
        long version,
        byte[] value) throws Exception;

    int rawEntryLength(IReadable readable) throws Exception;

    void writeRawEntry(FormatTransformer readKeyFormatTransormer,
        FormatTransformer readValueFormatTransormer,
        byte[] rawEntry,
        int offset,
        int length,
        FormatTransformer writeKeyFormatTransormer,
        FormatTransformer writeValueFormatTransormer,
        IAppendOnly appendOnly) throws Exception;

    byte[] key(FormatTransformer readKeyFormatTransormer,
        FormatTransformer readValueFormatTransormer,
        byte[] rawEntry,
        int offset,
        int length) throws Exception;

    ByteBuffer key(FormatTransformer readKeyFormatTransormer,
        FormatTransformer readValueFormatTransormer,
        ByteBuffer rawEntry) throws Exception;

    int compareKey(FormatTransformer readKeyFormatTransormer,
        FormatTransformer readValueFormatTransormer,
        ByteBuffer rawEntry,
        ByteBuffer compareKey);

    int compareKeyFromEntry(FormatTransformer readKeyFormatTransormer,
        FormatTransformer readValueFormatTransormer,
        IReadable readable,
        ByteBuffer compareKey)
        throws Exception;

    int compareKey(FormatTransformer aReadKeyFormatTransormer,
        FormatTransformer aReadValueFormatTransormer,
        ByteBuffer aRawEntry,
        FormatTransformer bReadKeyFormatTransormer,
        FormatTransformer bReadValueFormatTransormer,
        ByteBuffer bRawEntry);

    int compareKeys(ByteBuffer aKey, ByteBuffer bKey);

    long timestamp(FormatTransformer readKeyFormatTransormer,
        FormatTransformer readValueFormatTransormer,
        ByteBuffer rawEntry);

    long version(FormatTransformer readKeyFormatTransormer,
        FormatTransformer readValueFormatTransormer,
        ByteBuffer rawEntry);

    default boolean mightContain(long timestamp, long timestampVersion, long newerThanTimestamp, long newerThanTimestampVersion) {
        return compare(timestamp, timestampVersion, newerThanTimestamp, newerThanTimestampVersion) >= 0;
    }

    default boolean isNewerThan(long timestamp, long timestampVersion, long newerThanTimestamp, long newerThanTimestampVersion) {
        return compare(timestamp, timestampVersion, newerThanTimestamp, newerThanTimestampVersion) > 0;
    }

    default int compare(long timestamp, long timestampVersion, long otherTimestamp, long otherTimestampVersion) {
        int c = Long.compare(timestamp, otherTimestamp);
        if (c != 0) {
            return c;
        }
        return Long.compare(timestampVersion, otherTimestampVersion);
    }
}
