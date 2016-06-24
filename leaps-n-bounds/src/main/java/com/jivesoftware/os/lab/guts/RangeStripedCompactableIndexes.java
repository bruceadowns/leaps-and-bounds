package com.jivesoftware.os.lab.guts;

import com.jivesoftware.os.jive.utils.collections.bah.LRUConcurrentBAHLinkedHash;
import com.jivesoftware.os.lab.api.ConcurrentSplitException;
import com.jivesoftware.os.lab.api.FormatTransformer;
import com.jivesoftware.os.lab.api.FormatTransformerProvider;
import com.jivesoftware.os.lab.api.Keys;
import com.jivesoftware.os.lab.api.RawEntryFormat;
import com.jivesoftware.os.lab.api.Rawhide;
import com.jivesoftware.os.lab.guts.api.MergerBuilder;
import com.jivesoftware.os.lab.guts.api.NextRawEntry;
import com.jivesoftware.os.lab.guts.api.ReadIndex;
import com.jivesoftware.os.lab.guts.api.SplitterBuilder;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.io.FileUtils;

/**
 * @author jonathan.colt
 */
public class RangeStripedCompactableIndexes {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();

    private final AtomicLong largestStripeId = new AtomicLong();
    private final AtomicLong largestIndexId = new AtomicLong();
    private final ExecutorService destroy;
    private final File root;
    private final String indexName;
    private final boolean useMemMap;
    private final int entriesBetweenLeaps;
    private final Object copyIndexOnWrite = new Object();
    private volatile ConcurrentSkipListMap<byte[], FileBackMergableIndexs> indexes;
    private final long splitWhenKeysTotalExceedsNBytes;
    private final long splitWhenValuesTotalExceedsNBytes;
    private final long splitWhenValuesAndKeysTotalExceedsNBytes;
    private final FormatTransformerProvider formatTransformerProvider;
    private final Rawhide rawhide;
    private final LRUConcurrentBAHLinkedHash<Leaps> leapsCache;
    private final Semaphore appendSemaphore = new Semaphore(Short.MAX_VALUE, true);
    private final AtomicReference<RawEntryFormat> rawhideFormat;

    public RangeStripedCompactableIndexes(ExecutorService destroy,
        File root,
        String indexName,
        boolean useMemMap,
        int entriesBetweenLeaps,
        long splitWhenKeysTotalExceedsNBytes,
        long splitWhenValuesTotalExceedsNBytes,
        long splitWhenValuesAndKeysTotalExceedsNBytes,
        FormatTransformerProvider formatTransformerProvider,
        Rawhide rawhide,
        AtomicReference<RawEntryFormat> rawhideFormat,
        LRUConcurrentBAHLinkedHash<Leaps> leapsCache) throws Exception {

        this.destroy = destroy;
        this.root = root;
        this.indexName = indexName;
        this.useMemMap = useMemMap;
        this.entriesBetweenLeaps = entriesBetweenLeaps;
        this.splitWhenKeysTotalExceedsNBytes = splitWhenKeysTotalExceedsNBytes;
        this.splitWhenValuesTotalExceedsNBytes = splitWhenValuesTotalExceedsNBytes;
        this.splitWhenValuesAndKeysTotalExceedsNBytes = splitWhenValuesAndKeysTotalExceedsNBytes;
        this.formatTransformerProvider = formatTransformerProvider;
        this.rawhide = rawhide;
        this.rawhideFormat = rawhideFormat;
        this.leapsCache = leapsCache;
        this.indexes = new ConcurrentSkipListMap<>(rawhide);

        File indexRoot = new File(root, indexName);
        File[] stripeDirs = indexRoot.listFiles();
        if (stripeDirs != null) {
            Map<File, Stripe> stripes = new HashMap<>();
            for (File stripeDir : stripeDirs) {
                Stripe stripe = loadStripe(stripeDir);
                if (stripe != null) {
                    stripes.put(stripeDir, stripe);
                }
            }

            @SuppressWarnings("unchecked")
            Map.Entry<File, Stripe>[] entries = stripes.entrySet().toArray(new Map.Entry[0]);
            for (int i = 0; i < entries.length; i++) {
                if (entries[i] == null) {
                    continue;
                }
                for (int j = i + 1; j < entries.length; j++) {
                    if (entries[j] == null) {
                        continue;
                    }
                    if (entries[i].getValue().keyRange.contains(entries[j].getValue().keyRange)) {
                        FileUtils.forceDelete(entries[j].getKey());
                        entries[j] = null;
                    }
                }
            }

            for (int i = 0; i < entries.length; i++) {
                Map.Entry<File, Stripe> entry = entries[i];
                if (entry != null) {
                    long stripeId = Long.parseLong(entry.getKey().getName());
                    if (largestStripeId.get() < stripeId) {
                        largestStripeId.set(stripeId);
                    }

                    indexes.put(entry.getValue().keyRange.start, new FileBackMergableIndexs(destroy,
                        largestStripeId,
                        largestIndexId,
                        root,
                        indexName,
                        stripeId,
                        entry.getValue().mergeableIndexes));
                }
            }
        }
    }

    public TimestampAndVersion maxTimeStampAndVersion() {
        TimestampAndVersion max = TimestampAndVersion.NULL;
        for (FileBackMergableIndexs indexs : indexes.values()) {
            TimestampAndVersion other = indexs.compactableIndexes.maxTimeStampAndVersion();
            if (rawhide.isNewerThan(other.maxTimestamp, other.maxTimestampVersion, max.maxTimestamp, max.maxTimestampVersion)) {
                max = other;
            }
        }
        return max;
    }

    @Override
    public String toString() {
        return "RangeStripedCompactableIndexes{"
            + "largestStripeId=" + largestStripeId
            + ", largestIndexId=" + largestIndexId
            + ", root=" + root
            + ", indexName=" + indexName
            + ", useMemMap=" + useMemMap
            + ", entriesBetweenLeaps=" + entriesBetweenLeaps
            + ", splitWhenKeysTotalExceedsNBytes=" + splitWhenKeysTotalExceedsNBytes
            + ", splitWhenValuesTotalExceedsNBytes=" + splitWhenValuesTotalExceedsNBytes
            + ", splitWhenValuesAndKeysTotalExceedsNBytes=" + splitWhenValuesAndKeysTotalExceedsNBytes
            + '}';
    }

    private Stripe loadStripe(File stripeRoot) throws Exception {
        if (stripeRoot.isDirectory()) {
            File activeDir = new File(stripeRoot, "active");
            if (activeDir.exists()) {
                TreeSet<IndexRangeId> ranges = new TreeSet<>();
                File[] listFiles = activeDir.listFiles();
                if (listFiles != null) {
                    for (File file : listFiles) {
                        String rawRange = file.getName();
                        String[] range = rawRange.split("-");
                        long start = Long.parseLong(range[0]);
                        long end = Long.parseLong(range[1]);
                        long generation = Long.parseLong(range[2]);

                        ranges.add(new IndexRangeId(start, end, generation));
                        if (largestIndexId.get() < end) {
                            largestIndexId.set(end); //??
                        }
                    }
                }

                IndexRangeId active = null;
                TreeSet<IndexRangeId> remove = new TreeSet<>();
                for (IndexRangeId range : ranges) {
                    if (active == null || !active.intersects(range)) {
                        active = range;
                    } else {
                        LOG.debug("Destroying index for overlaping range:{}", range);
                        remove.add(range);
                    }
                }

                for (IndexRangeId range : remove) {
                    File file = range.toFile(activeDir);
                    FileUtils.deleteQuietly(file);
                }
                ranges.removeAll(remove);

                /**
                 0/1-1-0 a,b,c,d -append
                 0/2-2-0 x,y,z - append
                 0/1-2-1 a,b,c,d,x,y,z - merge
                 0/3-3-0 -a,-b - append
                 0/1-3-2 c,d,x,y,z - merge
                 - split
                 1/1-3-2 c,d
                 2/1-3-2 x,y,z
                 - delete 0/*
                 */
                CompactableIndexes mergeableIndexes = new CompactableIndexes(rawhide);
                KeyRange keyRange = null;
                for (IndexRangeId range : ranges) {
                    File file = range.toFile(activeDir);
                    if (file.length() == 0) {
                        file.delete();
                        continue;
                    }
                    IndexFile indexFile = new IndexFile(file, "rw", useMemMap);
                    LeapsAndBoundsIndex lab = new LeapsAndBoundsIndex(destroy, range, indexFile, formatTransformerProvider, rawhide, leapsCache);
                    if (lab.minKey() != null && lab.maxKey() != null) {
                        if (keyRange == null) {
                            keyRange = new KeyRange(rawhide, lab.minKey(), lab.maxKey());
                        } else {
                            keyRange.join(new KeyRange(rawhide, lab.minKey(), lab.maxKey()));
                        }
                        if (!mergeableIndexes.append(lab)) {
                            throw new RuntimeException("Bueller");
                        }
                    } else {
                        indexFile.close();
                        indexFile.delete();
                    }
                }
                if (keyRange != null) {
                    return new Stripe(keyRange, mergeableIndexes);
                }
            }
        }
        return null;
    }

    private class FileBackMergableIndexs implements SplitterBuilder, MergerBuilder {

        final ExecutorService destroy;
        final AtomicLong largestStripeId;
        final AtomicLong largestIndexId;

        final File root;
        final String indexName;
        final long stripeId;
        final CompactableIndexes compactableIndexes;

        public FileBackMergableIndexs(ExecutorService destroy,
            AtomicLong largestStripeId,
            AtomicLong largestIndexId,
            File root,
            String indexName,
            long stripeId,
            CompactableIndexes mergeableIndexes) throws IOException {

            this.destroy = destroy;
            this.largestStripeId = largestStripeId;
            this.largestIndexId = largestIndexId;
            this.compactableIndexes = mergeableIndexes;

            this.root = root;
            this.indexName = indexName;
            this.stripeId = stripeId;

            File indexRoot = new File(root, indexName);
            File stripeRoot = new File(indexRoot, String.valueOf(stripeId));

            File mergingRoot = new File(stripeRoot, "merging");
            File commitingRoot = new File(stripeRoot, "commiting");
            File splittingRoot = new File(stripeRoot, "splitting");

            FileUtils.deleteQuietly(mergingRoot);
            FileUtils.deleteQuietly(commitingRoot);
            FileUtils.deleteQuietly(splittingRoot);
        }

        void append(RawMemoryIndex rawMemoryIndex, byte[] minKey, byte[] maxKey, boolean fsync) throws Exception {

            LeapsAndBoundsIndex lab = flushMemoryIndexToDisk(rawMemoryIndex, minKey, maxKey, largestIndexId.incrementAndGet(), 0, fsync);
            compactableIndexes.append(lab);
        }

        private LeapsAndBoundsIndex flushMemoryIndexToDisk(RawMemoryIndex index,
            byte[] minKey,
            byte[] maxKey,
            long nextIndexId,
            int generation,
            boolean fsync) throws Exception {

            File indexRoot = new File(root, indexName);
            File stripeRoot = new File(indexRoot, String.valueOf(stripeId));
            File activeRoot = new File(stripeRoot, "active");
            File commitingRoot = new File(stripeRoot, "commiting");
            FileUtils.forceMkdir(commitingRoot);

            LOG.debug("Commiting memory index ({}) to on disk index: {}", index.count(), activeRoot);

            int maxLeaps = calculateIdealMaxLeaps(index.count(), entriesBetweenLeaps);
            IndexRangeId indexRangeId = new IndexRangeId(nextIndexId, nextIndexId, generation);
            File commitingIndexFile = indexRangeId.toFile(commitingRoot);
            FileUtils.deleteQuietly(commitingIndexFile);
            IndexFile indexFile = new IndexFile(commitingIndexFile, "rw", useMemMap);
            LABAppendableIndex appendableIndex = null;
            try {
                RawEntryFormat format = rawhideFormat.get();
                FormatTransformer writeKeyFormatTransformer = formatTransformerProvider.write(format.getKeyFormat());
                FormatTransformer writeValueFormatTransformer = formatTransformerProvider.write(format.getValueFormat());
                appendableIndex = new LABAppendableIndex(indexRangeId,
                    indexFile,
                    maxLeaps,
                    entriesBetweenLeaps,
                    rawhide,
                    writeKeyFormatTransformer,
                    writeValueFormatTransformer,
                    format);
                appendableIndex.append((stream) -> {
                    ReadIndex reader = index.acquireReader();
                    try {
                        NextRawEntry rangeScan = reader.rangeScan(minKey, maxKey);
                        while (rangeScan.next(stream) == NextRawEntry.Next.more) ;
                        return true;
                    } finally {
                        reader.release();
                    }
                });
                appendableIndex.closeAppendable(fsync);
            } catch (Exception x) {
                try {
                    if (appendableIndex != null) {
                        appendableIndex.close();
                    } else {
                        indexFile.close(); // sigh
                    }
                    indexFile.delete();
                } catch (Exception xx) {
                    LOG.error("Failed while trying to cleanup during a failure.", xx);
                }
                throw x;
            }

            File commitedIndexFile = indexRangeId.toFile(activeRoot);
            return moveIntoPlace(commitingIndexFile, commitedIndexFile, indexRangeId, fsync);
        }

        private LeapsAndBoundsIndex moveIntoPlace(File commitingIndexFile, File commitedIndexFile, IndexRangeId indexRangeId, boolean fsync) throws Exception {
            FileUtils.forceMkdir(commitedIndexFile.getParentFile());
            Files.move(commitingIndexFile.toPath(), commitedIndexFile.toPath(), StandardCopyOption.ATOMIC_MOVE);
            IndexFile indexFile = new IndexFile(commitedIndexFile, "r", useMemMap);
            LeapsAndBoundsIndex reopenedIndex = new LeapsAndBoundsIndex(destroy, indexRangeId, indexFile, formatTransformerProvider, rawhide, leapsCache);
            reopenedIndex.flush(fsync);  // Sorry
            // TODO Files.fsync index when java 9 supports it.
            return reopenedIndex;
        }

        boolean tx(int index, byte[] fromKey, byte[] toKey, ReaderTx tx) throws Exception {
            return compactableIndexes.tx(index, fromKey, toKey, tx);
        }

        long count() throws Exception {
            return compactableIndexes.count();
        }

        void close() throws Exception {
            compactableIndexes.close();
        }

        int debt(int minMergeDebt) {
            return compactableIndexes.debt(minMergeDebt);
        }

        Callable<Void> compactor(int minMergeDebt, boolean fsync) throws Exception {
            return compactableIndexes.compactor(
                splitWhenKeysTotalExceedsNBytes,
                splitWhenValuesTotalExceedsNBytes,
                splitWhenValuesAndKeysTotalExceedsNBytes,
                this,
                minMergeDebt,
                fsync,
                this);
        }

        @Override
        public Callable<Void> build(boolean fsync, SplitterBuilderCallback callback) throws Exception {

            File indexRoot = new File(root, indexName);
            File stripeRoot = new File(indexRoot, String.valueOf(stripeId));
            File mergingRoot = new File(stripeRoot, "merging");
            File commitingRoot = new File(stripeRoot, "commiting");
            File splittingRoot = new File(stripeRoot, "splitting");
            FileUtils.forceMkdir(mergingRoot);
            FileUtils.forceMkdir(commitingRoot);
            FileUtils.forceMkdir(splittingRoot);

            long nextStripeIdLeft = largestStripeId.incrementAndGet();
            long nextStripeIdRight = largestStripeId.incrementAndGet();
            FileBackMergableIndexs self = this;
            return () -> {
                appendSemaphore.acquire(Short.MAX_VALUE);
                try {
                    RawEntryFormat format = rawhideFormat.get();
                    FormatTransformer writeKeyFormatTransformer = formatTransformerProvider.write(format.getKeyFormat());
                    FormatTransformer writeValueFormatTransformer = formatTransformerProvider.write(format.getValueFormat());
                
                    return callback.call((IndexRangeId id, long worstCaseCount) -> {
                        int maxLeaps = calculateIdealMaxLeaps(worstCaseCount, entriesBetweenLeaps);
                        File splitIntoDir = new File(splittingRoot, String.valueOf(nextStripeIdLeft));
                        FileUtils.deleteQuietly(splitIntoDir);
                        FileUtils.forceMkdir(splitIntoDir);
                        File splittingIndexFile = id.toFile(splitIntoDir);
                        LOG.debug("Creating new index for split: {}", splittingIndexFile);
                        IndexFile indexFile = new IndexFile(splittingIndexFile, "rw", useMemMap);
                        LABAppendableIndex writeLeapsAndBoundsIndex = new LABAppendableIndex(id,
                            indexFile,
                            maxLeaps,
                            entriesBetweenLeaps,
                            rawhide,
                            writeKeyFormatTransformer,
                            writeValueFormatTransformer,
                            format);
                        return writeLeapsAndBoundsIndex;
                    }, (IndexRangeId id, long worstCaseCount) -> {
                        int maxLeaps = calculateIdealMaxLeaps(worstCaseCount, entriesBetweenLeaps);
                        File splitIntoDir = new File(splittingRoot, String.valueOf(nextStripeIdRight));
                        FileUtils.deleteQuietly(splitIntoDir);
                        FileUtils.forceMkdir(splitIntoDir);
                        File splittingIndexFile = id.toFile(splitIntoDir);
                        LOG.debug("Creating new index for split: {}", splittingIndexFile);
                        IndexFile indexFile = new IndexFile(splittingIndexFile, "rw", useMemMap);
                        LABAppendableIndex writeLeapsAndBoundsIndex = new LABAppendableIndex(id,
                            indexFile,
                            maxLeaps,
                            entriesBetweenLeaps,
                            rawhide,
                            writeKeyFormatTransformer,
                            writeValueFormatTransformer,
                            format);
                        return writeLeapsAndBoundsIndex;
                    }, (ids) -> {
                        File left = new File(indexRoot, String.valueOf(nextStripeIdLeft));
                        File leftActive = new File(left, "active");
                        FileUtils.forceMkdir(leftActive.getParentFile());
                        File right = new File(indexRoot, String.valueOf(nextStripeIdRight));
                        File rightActive = new File(right, "active");
                        FileUtils.forceMkdir(rightActive.getParentFile());
                        LOG.debug("Commiting split:{} became left:{} right:{}", stripeRoot, left, right);

                        try {
                            Files.move(new File(splittingRoot, String.valueOf(nextStripeIdLeft)).toPath(),
                                leftActive.toPath(),
                                StandardCopyOption.ATOMIC_MOVE);
                            Files.move(new File(splittingRoot, String.valueOf(nextStripeIdRight)).toPath(),
                                rightActive.toPath(),
                                StandardCopyOption.ATOMIC_MOVE);

                            Stripe leftStripe = loadStripe(left);
                            Stripe rightStripe = loadStripe(right);
                            synchronized (copyIndexOnWrite) {
                                ConcurrentSkipListMap<byte[], FileBackMergableIndexs> copyOfIndexes = new ConcurrentSkipListMap<>(rawhide);
                                copyOfIndexes.putAll(indexes);

                                for (Iterator<Map.Entry<byte[], FileBackMergableIndexs>> iterator = copyOfIndexes.entrySet().iterator(); iterator.hasNext();) {
                                    Map.Entry<byte[], FileBackMergableIndexs> next = iterator.next();
                                    if (next.getValue() == self) {
                                        iterator.remove();
                                        break;
                                    }
                                }
                                if (leftStripe != null && leftStripe.keyRange != null && leftStripe.keyRange.start != null) {
                                    copyOfIndexes.put(leftStripe.keyRange.start,
                                        new FileBackMergableIndexs(destroy, largestStripeId, largestIndexId, root, indexName, nextStripeIdLeft,
                                            leftStripe.mergeableIndexes));
                                }

                                if (rightStripe != null && rightStripe.keyRange != null && rightStripe.keyRange.start != null) {
                                    copyOfIndexes.put(rightStripe.keyRange.start,
                                        new FileBackMergableIndexs(destroy, largestStripeId, largestIndexId, root, indexName, nextStripeIdRight,
                                            rightStripe.mergeableIndexes));
                                }
                                indexes = copyOfIndexes;
                            }
                            compactableIndexes.destroy();
                            FileUtils.deleteQuietly(mergingRoot);
                            FileUtils.deleteQuietly(commitingRoot);
                            FileUtils.deleteQuietly(splittingRoot);

                            LOG.debug("Completed split:{} became left:{} right:{}", stripeRoot, left, right);
                            return null;
                        } catch (Exception x) {
                            FileUtils.deleteQuietly(left);
                            FileUtils.deleteQuietly(right);
                            LOG.error("Failed to split:{} became left:{} right:{}", new Object[]{stripeRoot, left, right}, x);
                            throw x;
                        }
                    }, fsync);

                } finally {
                    appendSemaphore.release(Short.MAX_VALUE);
                }
            };

        }

        @Override
        public Callable<Void> build(int minimumRun, boolean fsync, MergerBuilderCallback callback) throws Exception {
            File indexRoot = new File(root, indexName);
            File stripeRoot = new File(indexRoot, String.valueOf(stripeId));
            File activeRoot = new File(stripeRoot, "active");
            File mergingRoot = new File(stripeRoot, "merging");
            FileUtils.forceMkdir(mergingRoot);

            RawEntryFormat format = rawhideFormat.get();
            FormatTransformer writeKeyFormatTransformer = formatTransformerProvider.write(format.getKeyFormat());
            FormatTransformer writeValueFormatTransformer = formatTransformerProvider.write(format.getValueFormat());
                
            return callback.build(minimumRun, fsync, (id, count) -> {
                int maxLeaps = calculateIdealMaxLeaps(count, entriesBetweenLeaps);
                File mergingIndexFile = id.toFile(mergingRoot);
                FileUtils.deleteQuietly(mergingIndexFile);
                IndexFile indexFile = new IndexFile(mergingIndexFile, "rw", useMemMap);
                LABAppendableIndex writeLeapsAndBoundsIndex = new LABAppendableIndex(id,
                    indexFile,
                    maxLeaps,
                    entriesBetweenLeaps,
                    rawhide,
                    writeKeyFormatTransformer,
                    writeValueFormatTransformer,
                    format);
                return writeLeapsAndBoundsIndex;
            }, (ids) -> {
                File mergedIndexFile = ids.get(0).toFile(mergingRoot);
                File file = ids.get(0).toFile(activeRoot);
                FileUtils.deleteQuietly(file);
                return moveIntoPlace(mergedIndexFile, file, ids.get(0), fsync);
            });
        }

    }

    private static class Stripe {

        KeyRange keyRange;
        CompactableIndexes mergeableIndexes;

        public Stripe(KeyRange keyRange, CompactableIndexes mergeableIndexes) {
            this.keyRange = keyRange;
            this.mergeableIndexes = mergeableIndexes;
        }

    }

    public void append(RawMemoryIndex rawMemoryIndex, boolean fsync) throws Exception {
        appendSemaphore.acquire();
        try {
            byte[] minKey = rawMemoryIndex.minKey();
            byte[] maxKey = rawMemoryIndex.maxKey();

            if (indexes.isEmpty()) {
                long stripeId = largestStripeId.incrementAndGet();
                FileBackMergableIndexs index = new FileBackMergableIndexs(destroy,
                    largestStripeId,
                    largestIndexId,
                    root,
                    indexName,
                    stripeId,
                    new CompactableIndexes(rawhide));
                index.append(rawMemoryIndex, null, null, fsync);
                synchronized (copyIndexOnWrite) {
                    ConcurrentSkipListMap<byte[], FileBackMergableIndexs> copyOfIndexes = new ConcurrentSkipListMap<>(rawhide);
                    copyOfIndexes.putAll(indexes);
                    copyOfIndexes.put(minKey, index);
                    indexes = copyOfIndexes;
                }
                return;
            }

            SortedMap<byte[], FileBackMergableIndexs> tailMap = indexes.tailMap(minKey);
            if (tailMap.isEmpty()) {
                tailMap = indexes.tailMap(indexes.lastKey());
            } else {
                byte[] priorKey = indexes.lowerKey(tailMap.firstKey());
                if (priorKey == null) {
                    FileBackMergableIndexs moved;
                    synchronized (copyIndexOnWrite) {
                        ConcurrentSkipListMap<byte[], FileBackMergableIndexs> copyOfIndexes = new ConcurrentSkipListMap<>(rawhide);
                        copyOfIndexes.putAll(indexes);
                        moved = copyOfIndexes.remove(tailMap.firstKey());
                        copyOfIndexes.put(minKey, moved);
                        indexes = copyOfIndexes;
                    }
                    tailMap = indexes.tailMap(minKey);
                } else {
                    tailMap = indexes.tailMap(priorKey);
                }
            }

            Map.Entry<byte[], FileBackMergableIndexs> priorEntry = null;
            for (Map.Entry<byte[], FileBackMergableIndexs> currentEntry : tailMap.entrySet()) {
                if (priorEntry == null) {
                    priorEntry = currentEntry;
                } else {
                    if (rawMemoryIndex.containsKeyInRange(priorEntry.getKey(), currentEntry.getKey())) {
                        priorEntry.getValue().append(rawMemoryIndex, priorEntry.getKey(), currentEntry.getKey(), fsync);
                    }
                    priorEntry = currentEntry;
                    if (rawhide.compare(maxKey, currentEntry.getKey()) < 0) {
                        priorEntry = null;
                        break;
                    }
                }
            }
            if (priorEntry != null && rawMemoryIndex.containsKeyInRange(priorEntry.getKey(), null)) {
                priorEntry.getValue().append(rawMemoryIndex, priorEntry.getKey(), null, fsync);
            }

        } finally {
            appendSemaphore.release();
        }

    }

    public boolean pointTx(Keys keys,
        long newerThanTimestamp,
        long newerThanTimestampVersion,
        ReaderTx tx) throws Exception {

        return keys.keys((int index, byte[] key, int offset, int length) -> {
            rangeTx(index, key, key, newerThanTimestamp, newerThanTimestampVersion, tx);
            return true;
        });
    }

    public boolean rangeTx(int index,
        byte[] from,
        byte[] to,
        long newerThanTimestamp,
        long newerThanTimestampVersion,
        ReaderTx tx) throws Exception {

        THE_INSANITY:
        while (true) {
            ConcurrentSkipListMap<byte[], FileBackMergableIndexs> stackCopy = indexes;
            if (stackCopy.isEmpty()) {
                return tx.tx(index, from, to, new ReadIndex[0]);
            }
            SortedMap<byte[], FileBackMergableIndexs> map;
            if (from != null && to != null) {
                byte[] floorKey = stackCopy.floorKey(from);
                if (floorKey != null) {
                    map = stackCopy.subMap(floorKey, true, to, Arrays.equals(from, to));
                } else {
                    map = stackCopy.headMap(to, Arrays.equals(from, to));
                }
            } else if (from != null) {
                byte[] floorKey = stackCopy.floorKey(from);
                if (floorKey != null) {
                    map = stackCopy.tailMap(floorKey);
                } else {
                    map = stackCopy;
                }
            } else if (to != null) {
                map = stackCopy.headMap(to);
            } else {
                map = stackCopy;
            }

            if (map.isEmpty()) {
                return tx.tx(index, from, to, new ReadIndex[0]);
            } else {
                @SuppressWarnings("unchecked")
                Entry<byte[], FileBackMergableIndexs>[] entries = map.entrySet().toArray(new Entry[0]);
                for (int i = 0; i < entries.length; i++) {
                    Entry<byte[], FileBackMergableIndexs> entry = entries[i];
                    byte[] start = i == 0 ? from : entries[i].getKey();
                    byte[] end = i < entries.length - 1 ? entries[i + 1].getKey() : to;
                    FileBackMergableIndexs mergableIndex = entry.getValue();
                    try {
                        TimestampAndVersion timestampAndVersion = mergableIndex.compactableIndexes.maxTimeStampAndVersion();
                        if (rawhide.mightContain(timestampAndVersion.maxTimestamp,
                            timestampAndVersion.maxTimestampVersion,
                            newerThanTimestamp,
                            newerThanTimestampVersion)) {
                            if (!mergableIndex.tx(index, start, end, tx)) {
                                return false;
                            }
                        }
                    } catch (ConcurrentSplitException cse) {
                        from = (i == 0) ? from : entry.getKey(); // Sorry! Dont rewind from when from is haha
                        continue THE_INSANITY;
                    }
                }
            }
            return true;
        }

    }

    public int debt(int minMergeDebt) throws Exception {
        int maxDebt = 0;
        for (FileBackMergableIndexs index : indexes.values()) {
            maxDebt = Math.max(index.debt(minMergeDebt), maxDebt);
        }
        return maxDebt;
    }

    public List<Callable<Void>> buildCompactors(int minimumRun,
        boolean fsync) throws Exception {
        List<Callable<Void>> compactors = null;
        for (FileBackMergableIndexs index : indexes.values()) {
            Callable<Void> compactor = index.compactor(minimumRun, fsync);
            if (compactor != null) {
                if (compactors == null) {
                    compactors = new ArrayList<>();
                }
                compactors.add(compactor);
            }
        }
        return compactors;
    }

    public boolean isEmpty() throws Exception {
        return indexes.isEmpty();
    }

    public long count() throws Exception {
        long count = 0;
        for (FileBackMergableIndexs index : indexes.values()) {
            count = index.count();
        }
        return count;
    }

    public void close() throws Exception {
        for (FileBackMergableIndexs index : indexes.values()) {
            index.close();
        }
    }


     public static int calculateIdealMaxLeaps(long entryCount, int entriesBetweenLeaps) {
        int approximateLeapCount = (int) Math.max(1, entryCount / entriesBetweenLeaps);
        int maxLeaps = (int) (Math.log(approximateLeapCount) / Math.log(2));
        return 1 + maxLeaps;
    }

}
