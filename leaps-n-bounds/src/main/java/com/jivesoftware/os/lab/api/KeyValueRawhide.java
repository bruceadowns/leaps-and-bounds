package com.jivesoftware.os.lab.api;

import com.jivesoftware.os.lab.LABUtils;
import com.jivesoftware.os.lab.guts.IndexUtil;
import com.jivesoftware.os.lab.io.api.IAppendOnly;
import com.jivesoftware.os.lab.io.api.IReadable;
import com.jivesoftware.os.lab.io.api.UIO;
import java.nio.ByteBuffer;
import java.util.Comparator;

/**
 *
 * @author jonathan.colt
 */
public class KeyValueRawhide implements Rawhide {

    public static final String NAME = "keyValueRawhide";

    public static final KeyValueRawhide SINGLETON = new KeyValueRawhide();

    private KeyValueRawhide() {
    }

    @Override
    public byte[] merge(FormatTransformer currentReadKeyFormatTransormer,
        FormatTransformer currentReadValueFormatTransormer,
        byte[] currentRawEntry,
        FormatTransformer addingReadKeyFormatTransormer,
        FormatTransformer addingReadValueFormatTransormer,
        byte[] addingRawEntry,
        FormatTransformer mergedReadKeyFormatTransormer,
        FormatTransformer mergedReadValueFormatTransormer) {
        return addingRawEntry;
    }

    @Override
    public boolean streamRawEntry(int index,
        FormatTransformer readKeyFormatTransormer,
        FormatTransformer readValueFormatTransormer,
        ByteBuffer rawEntry,
        ValueStream stream,
        boolean hydrateValues) throws Exception {
        if (rawEntry == null) {
            return stream.stream(index, null, -1, false, -1, null);
        }
        rawEntry.clear();
        int keyLength = rawEntry.getInt();
        rawEntry.limit(4 + keyLength);
        ByteBuffer key = rawEntry.slice();

        ByteBuffer payload = null;
        if (hydrateValues) {
            rawEntry.position(4 + keyLength);
            rawEntry.limit(4 + keyLength + 4);
            int payloadLength = rawEntry.getInt();
            rawEntry.limit(4 + keyLength + 4 + payloadLength);
            payload = rawEntry.slice();
        }
        return stream.stream(index, key, 0, false, 0, payload);
    }

    @Override
    public byte[] toRawEntry(byte[] key, long timestamp, boolean tombstoned, long version, byte[] payload) throws Exception {
        byte[] rawEntry = new byte[LABUtils.rawArrayLength(key) + LABUtils.rawArrayLength(payload)];
        int o = 0;
        o += LABUtils.writeByteArray(key, rawEntry, o);
        LABUtils.writeByteArray(payload, rawEntry, o);
        return rawEntry;
    }

    @Override
    public int rawEntryLength(IReadable readable) throws Exception {
        return readable.readInt();
    }

    @Override
    public void writeRawEntry(FormatTransformer readKeyFormatTransormer,
        FormatTransformer readValueFormatTransormer,
        byte[] rawEntry,
        int offset,
        int length,
        FormatTransformer writeKeyFormatTransormer,
        FormatTransformer writeValueFormatTransormer,
        IAppendOnly appendOnly) throws Exception {
        UIO.writeByteArray(appendOnly, rawEntry, offset, length, "entry");
    }

    @Override
    public byte[] key(FormatTransformer readKeyFormatTransormer,
        FormatTransformer readValueFormatTransormer,
        byte[] rawEntry,
        int offset,
        int length) {
        return LABUtils.readByteArray(rawEntry, offset);
    }

    @Override
    public ByteBuffer key(FormatTransformer readKeyFormatTransormer,
        FormatTransformer readValueFormatTransormer,
        ByteBuffer rawEntry
    ) {
        rawEntry.clear();
        int keyLength = rawEntry.getInt();
        rawEntry.limit(4 + keyLength);
        return rawEntry.slice();
    }

    @Override
    public int compareKey(FormatTransformer readKeyFormatTransormer,
        FormatTransformer readValueFormatTransormer,
        ByteBuffer rawEntry,
        ByteBuffer compareKey
    ) {
        return IndexUtil.compare(key(readKeyFormatTransormer, readValueFormatTransormer, rawEntry), compareKey);
    }

    @Override
    public int compareKeyFromEntry(FormatTransformer readKeyFormatTransormer,
        FormatTransformer readValueFormatTransormer,
        IReadable readable,
        ByteBuffer compareKey) throws Exception {
        readable.seek(readable.getFilePointer() + 4); // skip the entry length
        int keyLength = readable.readInt();
        return IndexUtil.compare(readable, keyLength, compareKey);
    }

    @Override
    public int compareKeys(ByteBuffer aKey, ByteBuffer bKey) {
        return IndexUtil.compare(aKey, bKey);
    }

    @Override
    public Comparator<ByteBuffer> getByteBufferKeyComparator() {
        return IndexUtil::compare;
    }

    @Override
    public Comparator<byte[]> getKeyComparator() {
        return (byte[] o1, byte[] o2) -> IndexUtil.compare(o1, 0, o1.length, o2, 0, o2.length);
    }

    @Override
    public int compareKey(FormatTransformer aReadKeyFormatTransormer,
        FormatTransformer aReadValueFormatTransormer,
        ByteBuffer aRawEntry,
        FormatTransformer bReadKeyFormatTransormer,
        FormatTransformer bReadValueFormatTransormer,
        ByteBuffer bRawEntry) {

        if (aRawEntry == null && bRawEntry == null) {
            return 0;
        } else if (aRawEntry == null) {
            return -bRawEntry.capacity();
        } else if (bRawEntry == null) {
            return aRawEntry.capacity();
        } else {
            return IndexUtil.compare(
                key(aReadKeyFormatTransormer, aReadValueFormatTransormer, aRawEntry),
                key(bReadKeyFormatTransormer, bReadValueFormatTransormer, bRawEntry)
            );
        }
    }

    @Override
    public long timestamp(FormatTransformer readKeyFormatTransormer, FormatTransformer readValueFormatTransormer, ByteBuffer rawEntry) {
        return 0;
    }

    @Override
    public long version(FormatTransformer readKeyFormatTransormer, FormatTransformer readValueFormatTransormer, ByteBuffer rawEntry) {
        return 0;
    }

    @Override
    public boolean isNewerThan(long timestamp, long timestampVersion, long newerThanTimestamp, long newerThanTimestampVersion) {
        return true;
    }

    @Override
    public boolean mightContain(long timestamp, long timestampVersion, long newerThanTimestamp, long newerThanTimestampVersion) {
        return (timestamp != -1 && timestampVersion != -1);
    }
}
