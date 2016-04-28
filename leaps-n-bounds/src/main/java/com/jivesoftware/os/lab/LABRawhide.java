package com.jivesoftware.os.lab;

import com.jivesoftware.os.lab.api.Rawhide;
import com.jivesoftware.os.lab.api.ValueStream;
import com.jivesoftware.os.lab.guts.IndexUtil;
import com.jivesoftware.os.lab.io.api.IAppendOnly;
import com.jivesoftware.os.lab.io.api.IReadable;
import com.jivesoftware.os.lab.io.api.UIO;
import java.io.IOException;

/**
 *
 * @author jonathan.colt
 */
public class LABRawhide implements Rawhide {

    @Override
    public byte[] merge(byte[] current, byte[] adding) {
        int currentKeyLength = UIO.bytesInt(current);
        int addingKeyLength = UIO.bytesInt(adding);

        long currentsTimestamp = UIO.bytesLong(current, 4 + currentKeyLength);
        long currentsVersion = UIO.bytesLong(current, 4 + currentKeyLength + 8 + 1);

        long addingsTimestamp = UIO.bytesLong(adding, 4 + addingKeyLength);
        long addingsVersion = UIO.bytesLong(adding, 4 + addingKeyLength + 8 + 1);

        return (currentsTimestamp > addingsTimestamp) || (currentsTimestamp == addingsTimestamp && currentsVersion > addingsVersion) ? current : adding;
    }

    @Override
    public long timestamp(byte[] rawEntry, int offset, int length) {
        int keyLength = UIO.bytesInt(rawEntry);
        return UIO.bytesLong(rawEntry, 4 + keyLength);
    }

    @Override
    public long version(byte[] rawEntry, int offset, int length) {
        int keyLength = UIO.bytesInt(rawEntry);
        return UIO.bytesLong(rawEntry, 4 + keyLength + 8 + 1);
    }

    @Override
    public boolean streamRawEntry(ValueStream stream, int index, byte[] rawEntry, int offset) throws Exception {
        if (rawEntry == null) {
            return stream.stream(index, null, -1, false, -1, null);
        }
        int o = offset;
        int keyLength = UIO.bytesInt(rawEntry, o);
        o += 4;
        byte[] k = new byte[keyLength];
        System.arraycopy(rawEntry, o, k, 0, keyLength);
        o += keyLength;
        long timestamp = UIO.bytesLong(rawEntry, o);
        o += 8;
        boolean tombstone = rawEntry[o] != 0;
        o++;
        long version = UIO.bytesLong(rawEntry, o);
        o += 8;
        if (tombstone) {
            return stream.stream(index, k, timestamp, tombstone, version, null);
        }

        int payloadLength = UIO.bytesInt(rawEntry, o);
        o += 4;
        byte[] payload = new byte[payloadLength];
        System.arraycopy(rawEntry, o, payload, 0, payloadLength);
        o += payloadLength;

        return stream.stream(index, k, timestamp, tombstone, version, payload);
    }

    @Override
    public byte[] toRawEntry(byte[] key, long timestamp, boolean tombstoned, long version, byte[] payload) throws IOException {

        byte[] rawEntry = new byte[LABUtils.rawArrayLength(key) + 8 + 1 + 8 + LABUtils.rawArrayLength(payload)];
        int o = 0;
        o = LABUtils.writeByteArray(key, rawEntry, o);
        UIO.longBytes(timestamp, rawEntry, o);
        o += 8;
        rawEntry[o] = tombstoned ? (byte) 1 : (byte) 0;
        o++;
        UIO.longBytes(version, rawEntry, o);
        o += 8;
        LABUtils.writeByteArray(payload, rawEntry, o);
        return rawEntry;
    }

    @Override
    public int entryLength(IReadable readable, byte[] lengthBuffer) throws Exception {
        int length = UIO.readInt(readable, "entryLength", lengthBuffer);
        return length - 4;
    }

    @Override
    public void writeRawEntry(byte[] rawEntry, int offset, int length, IAppendOnly appendOnly, byte[] lengthBuffer) throws Exception {
        int entryLength = 4 + length + 4;
        UIO.writeInt(appendOnly, entryLength, "entryLength", lengthBuffer);
        appendOnly.append(rawEntry, offset, length);
        UIO.writeInt(appendOnly, entryLength, "entryLength", lengthBuffer);
    }

    @Override
    public byte[] key(byte[] rawEntry, int offset, int length) {
        int keyLength = UIO.bytesInt(rawEntry, offset);
        byte[] key = new byte[keyLength];
        System.arraycopy(rawEntry, 4, key, 0, keyLength);
        return key;
    }

    @Override
    public int keyLength(byte[] rawEntry, int offset) {
        return UIO.bytesInt(rawEntry, offset);
    }

    @Override
    public int keyOffset(byte[] rawEntry, int offset) {
        return offset + 4;
    }

    @Override
    public int compareKey(byte[] rawEntry, int offset, byte[] compareKey, int compareOffset, int compareLength) {
        int keylength = UIO.bytesInt(rawEntry, offset);
        return IndexUtil.compare(rawEntry, offset + 4, keylength, compareKey, compareOffset, compareLength);
    }

    @Override
    public int compareKeyFromEntry(IReadable readable, byte[] compareKey, int compareOffset, int compareLength, byte[] intBuffer) throws Exception {
        readable.seek(readable.getFilePointer() + 4); // skip the entry length
        int keyLength = UIO.readInt(readable, "keyLength", intBuffer);
        return IndexUtil.compare(readable, keyLength, compareKey, compareOffset, compareLength);
    }

    @Override
    public boolean mightContain(long timestamp, long timestampVersion, long newerThanTimestamp, long newerThanTimestampVersion) {
        return compare(timestamp, timestampVersion, newerThanTimestamp, newerThanTimestampVersion) >= 0;
    }

    @Override
    public boolean isNewerThan(long timestamp, long timestampVersion, long newerThanTimestamp, long newerThanTimestampVersion) {
        return compare(timestamp, timestampVersion, newerThanTimestamp, newerThanTimestampVersion) > 0;
    }

    private static int compare(long timestamp, long timestampVersion, long otherTimestamp, long otherTimestampVersion) {
        int c = Long.compare(timestamp, otherTimestamp);
        if (c != 0) {
            return c;
        }
        return Long.compare(timestampVersion, otherTimestampVersion);
    }

}
