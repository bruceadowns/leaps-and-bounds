package com.jivesoftware.os.lab;

import com.google.common.primitives.UnsignedBytes;
import com.jivesoftware.os.lab.api.GetRaw;
import com.jivesoftware.os.lab.api.NextRawEntry;
import com.jivesoftware.os.lab.api.RawConcurrentReadableIndex;
import com.jivesoftware.os.lab.api.RawEntryStream;
import com.jivesoftware.os.lab.io.api.UIO;
import java.io.File;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 *
 * @author jonathan.colt
 */
public class IndexNGTest {

    @Test(enabled = true)
    public void testLeapDisk() throws Exception {

        ExecutorService destroy = Executors.newSingleThreadExecutor();
        File indexFiler = File.createTempFile("l-index", ".tmp");

        ConcurrentSkipListMap<byte[], byte[]> desired = new ConcurrentSkipListMap<>(UnsignedBytes.lexicographicalComparator());

        int count = 100;
        int step = 10;

        IndexRangeId indexRangeId = new IndexRangeId(1, 1);

        WriteLeapsAndBoundsIndex write = new WriteLeapsAndBoundsIndex(indexRangeId, new IndexFile(indexFiler.getAbsolutePath(), "rw", false),
            64, 10);

        IndexTestUtils.append(new Random(), write, 0, step, count, desired);
        write.close();

        assertions(new LeapsAndBoundsIndex(destroy, indexRangeId, new IndexFile(indexFiler.getAbsolutePath(), "r", false)), count, step, desired);
    }

    @Test(enabled = false)
    public void testMemory() throws Exception {

        ConcurrentSkipListMap<byte[], byte[]> desired = new ConcurrentSkipListMap<>(UnsignedBytes.lexicographicalComparator());

        int count = 10;
        int step = 10;

        RawMemoryIndex walIndex = new RawMemoryIndex(new SimpleRawEntry());

        IndexTestUtils.append(new Random(), walIndex, 0, step, count, desired);
        assertions(walIndex, count, step, desired);
    }

    @Test(enabled = false)
    public void testMemoryToDisk() throws Exception {

        ExecutorService destroy = Executors.newSingleThreadExecutor();
        ConcurrentSkipListMap<byte[], byte[]> desired = new ConcurrentSkipListMap<>(UnsignedBytes.lexicographicalComparator());

        int count = 10;
        int step = 10;

        RawMemoryIndex memoryIndex = new RawMemoryIndex(new SimpleRawEntry());

        IndexTestUtils.append(new Random(), memoryIndex, 0, step, count, desired);
        assertions(memoryIndex, count, step, desired);

        File indexFiler = File.createTempFile("c-index", ".tmp");
        IndexRangeId indexRangeId = new IndexRangeId(1, 1);
        WriteLeapsAndBoundsIndex disIndex = new WriteLeapsAndBoundsIndex(indexRangeId, new IndexFile(indexFiler.getAbsolutePath(), "rw", false),
            64, 10);

        disIndex.append(memoryIndex);
        disIndex.close();

        assertions(new LeapsAndBoundsIndex(destroy, indexRangeId, new IndexFile(indexFiler.getAbsolutePath(), "r", false)), count, step, desired);

    }

    private void assertions(RawConcurrentReadableIndex walIndex, int count, int step, ConcurrentSkipListMap<byte[], byte[]> desired) throws
        Exception {
        ArrayList<byte[]> keys = new ArrayList<>(desired.navigableKeySet());

        int[] index = new int[1];
        NextRawEntry rowScan = walIndex.reader(1024).rowScan();
        RawEntryStream stream = (rawEntry, offset, length) -> {
            System.out.println("rowScan:" + SimpleRawEntry.key(rawEntry));
            Assert.assertEquals(UIO.bytesLong(keys.get(index[0])), SimpleRawEntry.key(rawEntry));
            index[0]++;
            return true;
        };
        while (rowScan.next(stream));

        System.out.println("Point Get");
        for (int i = 0; i < count * step; i++) {
            long k = i;
            GetRaw getPointer = walIndex.reader(0).get();
            byte[] key = UIO.longBytes(k);
            stream = (rawEntry, offset, length) -> {

                System.out.println("Got: " + SimpleRawEntry.toString(rawEntry));
                if (rawEntry != null) {
                    byte[] rawKey = UIO.longBytes(SimpleRawEntry.key(rawEntry));
                    Assert.assertEquals(rawKey, key);
                    byte[] d = desired.get(key);
                    if (d == null) {
                        Assert.fail();
                    } else {
                        Assert.assertEquals(SimpleRawEntry.value(rawEntry), SimpleRawEntry.value(d));
                    }
                } else {
                    Assert.assertFalse(desired.containsKey(key));
                }
                return rawEntry != null;
            };

            Assert.assertEquals(getPointer.get(key, stream), desired.containsKey(key));
        }

        System.out.println("Ranges");
        for (int i = 0; i < keys.size() - 3; i++) {
            int _i = i;

            int[] streamed = new int[1];
            stream = (entry, offset, length) -> {
                if (entry != null) {
                    System.out.println("Streamed:" + SimpleRawEntry.toString(entry));
                    streamed[0]++;
                }
                return true;
            };

            System.out.println("Asked:" + UIO.bytesLong(keys.get(_i)) + " to " + UIO.bytesLong(keys.get(_i + 3)));
            NextRawEntry rangeScan = walIndex.reader(1024).rangeScan(keys.get(_i), keys.get(_i + 3));
            while (rangeScan.next(stream));
            Assert.assertEquals(3, streamed[0]);

        }

        for (int i = 0; i < keys.size() - 3; i++) {
            int _i = i;
            int[] streamed = new int[1];
            stream = (entry, offset, length) -> {
                if (entry != null) {
                    streamed[0]++;
                }
                return SimpleRawEntry.value(entry) != -1;
            };
            NextRawEntry rangeScan = walIndex.reader(1024).rangeScan(UIO.longBytes(UIO.bytesLong(keys.get(_i)) + 1), keys.get(_i + 3));
            while (rangeScan.next(stream));
            Assert.assertEquals(2, streamed[0]);

        }
    }
}
