package com.jivesoftware.os.lab.io;

import com.jivesoftware.os.lab.io.api.ByteBufferFactory;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * TODO this implementation of ByteBufferFactory is inherently unsafe because its allocate() method is only capable of growing an existing buffer rather than
 * handing out a new one. Eventually we need to extend ByteBufferFactory to formalize notions of create(), open(), copy(), resize().
 *
 * @author jonathan.colt
 */
public class FileBackedMemMappedByteBufferFactory implements ByteBufferFactory {

    private final File file;
    private final long segmentSize;

    public FileBackedMemMappedByteBufferFactory(File file, long segmentSize) {
        this.file = file;
        this.segmentSize = segmentSize;
    }

    @Override
    public boolean exists() {
        return file.exists();
    }

    @Override
    public ByteBuffer allocate(int index, long length) {
        try {
            //System.out.println(String.format("Allocate key=%s length=%s for directories=%s", key, length, Arrays.toString(directories)));
            ensureDirectory(file.getParentFile());
            long segmentOffset = segmentSize * index;
            long requiredLength = segmentOffset + length;
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                if (requiredLength > raf.length()) {
                    raf.seek(requiredLength - 1);
                    raf.write(0);
                }
                raf.seek(segmentOffset);
                try (FileChannel channel = raf.getChannel()) {
                    return channel.map(FileChannel.MapMode.READ_WRITE, segmentOffset, Math.min(segmentSize, channel.size() - segmentOffset));
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public ByteBuffer reallocate(int index, ByteBuffer oldBuffer, long newSize) {
        //System.out.println(String.format("Reallocate key=%s newSize=%s for directories=%s", key, newSize, Arrays.toString(directories)));
        return allocate(index, newSize);
    }

    @Override
    public long length() {
        return file.length();
    }

    @Override
    public long nextLength(int index, long oldLength, long position) {
        long segmentOffset = segmentSize * index;
        return Math.min(segmentSize, file.length() - segmentOffset);
    }

    private void ensureDirectory(File directory) {
        if (!directory.exists()) {
            if (!directory.mkdirs()) {
                if (!directory.exists()) {
                    throw new RuntimeException("Failed to create directory: " + directory);
                }
            }
        }
    }

}