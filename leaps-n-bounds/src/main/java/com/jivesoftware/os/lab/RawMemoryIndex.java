package com.jivesoftware.os.lab;

import com.google.common.primitives.UnsignedBytes;
import com.jivesoftware.os.lab.api.GetRaw;
import com.jivesoftware.os.lab.api.MergeRawEntry;
import com.jivesoftware.os.lab.api.NextRawEntry;
import com.jivesoftware.os.lab.api.RawAppendableIndex;
import com.jivesoftware.os.lab.api.RawConcurrentReadableIndex;
import com.jivesoftware.os.lab.api.RawEntries;
import com.jivesoftware.os.lab.api.RawEntryStream;
import com.jivesoftware.os.lab.api.ReadIndex;
import com.jivesoftware.os.lab.io.api.UIO;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author jonathan.colt
 */
public class RawMemoryIndex implements RawAppendableIndex, RawConcurrentReadableIndex, RawEntries {

    private final ConcurrentSkipListMap<byte[], byte[]> index = new ConcurrentSkipListMap<>(UnsignedBytes.lexicographicalComparator());
    private final AtomicLong approximateCount = new AtomicLong();

    private final MergeRawEntry merger;

    private final int numBones = 1024;
    private final Semaphore rawBones = new Semaphore(numBones, true);

    public RawMemoryIndex(MergeRawEntry merger) {
        this.merger = merger;
    }

    @Override
    public IndexRangeId id() {
        return null;
    }

    @Override
    public boolean consume(RawEntryStream stream) throws Exception {
        for (Map.Entry<byte[], byte[]> e : index.entrySet()) {
            byte[] entry = e.getValue();
            if (!stream.stream(entry, 0, entry.length)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean append(RawEntries entries) throws Exception {
        return entries.consume((rawEntry, offset, length) -> {
            int keyLength = UIO.bytesInt(rawEntry);
            byte[] key = new byte[keyLength];
            System.arraycopy(rawEntry, 4, key, 0, keyLength);
            index.compute(key, (k, v) -> {
                if (v == null) {
                    approximateCount.incrementAndGet();
                    return rawEntry;
                } else {
                    return merger.merge(v, rawEntry);
                }
            });
            return true;
        });
    }

    @Override
    public ReadIndex reader(int bufferSize) throws Exception {
        return new ReadIndex() {

            @Override
            public GetRaw get() throws Exception {

                return new GetRaw() {

                    private boolean result;

                    @Override
                    public boolean get(byte[] key, RawEntryStream stream) throws Exception {
                        byte[] rawEntry = index.get(key);
                        if (rawEntry == null) {
                            return false;
                        } else {
                            result = stream.stream(rawEntry, 0, rawEntry.length);
                            return true;
                        }
                    }

                    @Override
                    public boolean result() {
                        return result;
                    }
                };
            }

            @Override
            public NextRawEntry rangeScan(byte[] from, byte[] to) throws Exception {
                ConcurrentNavigableMap<byte[], byte[]> subMap = index.subMap(from, true, to, false);
                Iterator<Map.Entry<byte[], byte[]>> iterator = subMap.entrySet().iterator();
                return (stream) -> {
                    if (iterator.hasNext()) {
                        Map.Entry<byte[], byte[]> next = iterator.next();
                        return stream.stream(next.getValue(), 0, next.getValue().length);
                    }
                    return false;
                };
            }

            @Override
            public NextRawEntry rowScan() throws Exception {
                Iterator<Map.Entry<byte[], byte[]>> iterator = index.entrySet().iterator();
                return (stream) -> {
                    if (iterator.hasNext()) {
                        Map.Entry<byte[], byte[]> next = iterator.next();
                        return stream.stream(next.getValue(), 0, next.getValue().length);
                    }
                    return false;
                };
            }

            @Override
            public void close() {
                throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
            }

            @Override
            public long count() {
                throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
            }

            @Override
            public boolean isEmpty() {
                throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
            }

            @Override
            public void release() {
                rawBones.release();
            }

            @Override
            public void acquire() throws Exception {
                rawBones.acquire();
            }
        };
    }

    @Override
    public void destroy() throws Exception {
        approximateCount.set(0);
        internalClear();
    }

    private void internalClear() throws Exception {
        rawBones.acquire(numBones);
        try {
            index.clear();
        } finally {
            rawBones.release(numBones);
        }
    }

    @Override
    public void close() throws Exception {
        internalClear();
        approximateCount.set(0);
    }

    @Override
    public boolean isEmpty() {
        return index.isEmpty();
    }

    @Override
    public long count() {
        return approximateCount.get();
    }

}
