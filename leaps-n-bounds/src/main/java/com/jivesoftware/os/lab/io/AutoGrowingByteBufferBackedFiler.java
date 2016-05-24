package com.jivesoftware.os.lab.io;

import com.jivesoftware.os.lab.io.api.ByteBufferFactory;
import com.jivesoftware.os.lab.io.api.IAppendOnly;
import com.jivesoftware.os.lab.io.api.IFiler;
import com.jivesoftware.os.lab.io.api.UIO;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 *
 */
public class AutoGrowingByteBufferBackedFiler implements IFiler, IAppendOnly {

    public static final long MAX_BUFFER_SEGMENT_SIZE = UIO.chunkLength(30);
    public static final long MAX_POSITION = MAX_BUFFER_SEGMENT_SIZE * 100;

    private final long initialBufferSegmentSize;
    private final long maxBufferSegmentSize;
    private final ByteBufferFactory byteBufferFactory;

    private ByteBufferBackedFiler[] filers;
    private int fpFilerIndex;
    private long length = 0;

    private final int fShift;
    private final long fseekMask;

    public AutoGrowingByteBufferBackedFiler(long initialBufferSegmentSize,
        long maxBufferSegmentSize,
        ByteBufferFactory byteBufferFactory) throws IOException {
        this.initialBufferSegmentSize = initialBufferSegmentSize > 0 ? UIO.chunkLength(UIO.chunkPower(initialBufferSegmentSize, 0)) : -1;

        this.maxBufferSegmentSize = Math.min(UIO.chunkLength(UIO.chunkPower(maxBufferSegmentSize, 0)), MAX_BUFFER_SEGMENT_SIZE);

        this.byteBufferFactory = byteBufferFactory;
        this.filers = new ByteBufferBackedFiler[0];
        this.length = byteBufferFactory.length();

        // test power of 2
        if ((this.maxBufferSegmentSize & (this.maxBufferSegmentSize - 1)) == 0) {
            this.fShift = Long.numberOfTrailingZeros(this.maxBufferSegmentSize);
            this.fseekMask = this.maxBufferSegmentSize - 1;
        } else {
            throw new IllegalArgumentException("It's hard to ensure powers of 2");
        }
    }

    private AutoGrowingByteBufferBackedFiler(long maxBufferSegmentSize,
        ByteBufferFactory byteBufferFactory,
        ByteBufferBackedFiler[] filers,
        long length,
        int fShift,
        long fseekMask) {
        this.initialBufferSegmentSize = -1;
        this.maxBufferSegmentSize = maxBufferSegmentSize;
        this.byteBufferFactory = byteBufferFactory;
        this.filers = filers;
        this.fpFilerIndex = -1;
        this.length = length;
        this.fShift = fShift;
        this.fseekMask = fseekMask;
    }

    public AutoGrowingByteBufferBackedFiler duplicate(long startFP, long endFp) {
        ByteBufferBackedFiler[] duplicate = new ByteBufferBackedFiler[filers.length];
        for (int i = 0; i < duplicate.length; i++) {
            if ((i + 1) * maxBufferSegmentSize < startFP || (i - 1) * maxBufferSegmentSize > endFp) {
                continue;
            }
            duplicate[i] = new ByteBufferBackedFiler(filers[i].buffer.duplicate());
        }
        return new AutoGrowingByteBufferBackedFiler(maxBufferSegmentSize, byteBufferFactory, duplicate, length, fShift, fseekMask);
    }

    public AutoGrowingByteBufferBackedFiler duplicateNew(AutoGrowingByteBufferBackedFiler current) {
        ByteBufferBackedFiler[] duplicate = new ByteBufferBackedFiler[filers.length];
        System.arraycopy(current.filers, 0, duplicate, 0, current.filers.length - 1);
        for (int i = current.filers.length - 1; i < duplicate.length; i++) {
            duplicate[i] = new ByteBufferBackedFiler(filers[i].buffer.duplicate());
        }
        return new AutoGrowingByteBufferBackedFiler(maxBufferSegmentSize, byteBufferFactory, duplicate, length, fShift, fseekMask);
    }

    public AutoGrowingByteBufferBackedFiler duplicateAll() {
        ByteBufferBackedFiler[] duplicate = new ByteBufferBackedFiler[filers.length];
        for (int i = 0; i < duplicate.length; i++) {
            duplicate[i] = new ByteBufferBackedFiler(filers[i].buffer.duplicate());
        }
        return new AutoGrowingByteBufferBackedFiler(maxBufferSegmentSize, byteBufferFactory, duplicate, length, fShift, fseekMask);
    }

    final long ensure(long bytesToWrite) throws IOException {
        long fp = getFilePointer();
        long newFp = fp + bytesToWrite;
        if (newFp > length) {
            position(newFp);
            position(fp);
            return (newFp - length);
        }
        return 0;
    }

    final void position(long position) throws IOException {
        if (position > MAX_POSITION) {
            throw new IllegalStateException("Encountered a likely runaway file position! position=" + position);
        }
        int f = (int) (position >> fShift);
        long fseek = position & fseekMask;
        if (f >= filers.length) {
            int lastFilerIndex = filers.length - 1;
            if (lastFilerIndex > -1 && filers[lastFilerIndex].length() < maxBufferSegmentSize) {
                ByteBuffer reallocate = reallocate(lastFilerIndex, filers[lastFilerIndex].buffer, maxBufferSegmentSize);
                filers[lastFilerIndex] = new ByteBufferBackedFiler(reallocate);
            }

            int newLength = f + 1;
            ByteBufferBackedFiler[] newFilers = new ByteBufferBackedFiler[newLength];
            System.arraycopy(filers, 0, newFilers, 0, filers.length);
            for (int n = filers.length; n < newLength; n++) {
                if (n < newLength - 1) {
                    newFilers[n] = new ByteBufferBackedFiler(allocate(n, maxBufferSegmentSize));
                } else {
                    newFilers[n] = new ByteBufferBackedFiler(allocate(n, Math.max(fseek, initialBufferSegmentSize)));
                }
            }
            filers = newFilers;

        } else if (f == filers.length - 1 && fseek > filers[f].length()) {
            long newSize = byteBufferFactory.nextLength(f, filers[f].length(), fseek);
            ByteBuffer reallocate = reallocate(f, filers[f].buffer, Math.min(maxBufferSegmentSize, newSize));
            filers[f] = new ByteBufferBackedFiler(reallocate);
        }
        filers[f].seek(fseek);
        fpFilerIndex = f;
    }

    private ByteBuffer allocate(int index, long maxBufferSegmentSize) {
        return byteBufferFactory.allocate(index, maxBufferSegmentSize);
    }

    private ByteBuffer reallocate(int index, ByteBuffer oldBuffer, long newSize) {
        return byteBufferFactory.reallocate(index, oldBuffer, newSize);
    }

    @Override
    public final void seek(long position) throws IOException {
        position(position);
    }

    @Override
    public long skip(long skip) throws IOException {
        long fp = getFilePointer();
        position(fp + skip);
        return skip;
    }

    @Override
    public long length() throws IOException {
        return length;
        /*
        if (filers.length == 0) {
            return 0;
        }
        return ((filers.length - 1) * maxBufferSegmentSize) + filers[filers.length - 1].length();
        */
    }

    @Override
    public void setLength(long len) throws IOException {
        position(len);
        length = Math.max(len, length);
    }

    @Override
    public long getFilePointer() throws IOException {
        if (filers.length == 0) {
            return 0;
        }
        long fp = (fpFilerIndex * maxBufferSegmentSize) + filers[fpFilerIndex].getFilePointer();
        return fp;
    }

    @Override
    public void eof() throws IOException {
        position(length());
    }

    @Override
    public int read() throws IOException {
        int read = filers[fpFilerIndex].read();
        while (read == -1 && fpFilerIndex < filers.length - 1) {
            fpFilerIndex++;
            filers[fpFilerIndex].seek(0);
            read = filers[fpFilerIndex].read();
        }
        return read;
    }

    @Override
    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    @Override
    public int read(byte[] b, int offset, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        int remaining = len;
        int read = filers[fpFilerIndex].read(b, offset, remaining);
        if (read == -1) {
            read = 0;
        }
        offset += read;
        remaining -= read;
        while (remaining > 0 && fpFilerIndex < filers.length - 1) {
            fpFilerIndex++;
            filers[fpFilerIndex].seek(0);
            read = filers[fpFilerIndex].read(b, offset, remaining);
            if (read == -1) {
                read = 0;
            }
            offset += read;
            remaining -= read;
        }
        if (len == remaining) {
            return -1;
        }
        return offset;
    }

    @Override
    public boolean canSlice(int length) throws IOException {
        return filers[fpFilerIndex].canSlice(length);
    }

    @Override
    public ByteBuffer slice(int length) throws IOException {
        long remaining = filers[fpFilerIndex].length() - filers[fpFilerIndex].getFilePointer();
        if (remaining < length) {
            throw new IOException("Cannot slice bytes:" + length + " remaining:" + remaining);
        } else {
            return filers[fpFilerIndex].slice(length);
        }
    }

    @Override
    public void write(byte[] b, int offset, int len) throws IOException {
        append(b, offset, len);
    }
    
    @Override
    public void append(byte[] b, int offset, int len) throws IOException {
        long count = ensure(len);

        long canWrite = Math.min(len, filers[fpFilerIndex].length() - filers[fpFilerIndex].getFilePointer());
        filers[fpFilerIndex].write(b, offset, (int) canWrite);
        long remaingToWrite = len - canWrite;
        offset += canWrite;
        while (remaingToWrite > 0) {
            fpFilerIndex++;
            filers[fpFilerIndex].seek(0);
            canWrite = Math.min(remaingToWrite, filers[fpFilerIndex].length() - filers[fpFilerIndex].getFilePointer());
            filers[fpFilerIndex].write(b, offset, (int) canWrite);
            remaingToWrite -= canWrite;
            offset += canWrite;
        }

        length += count;
    }


//    @Override
//    public void write(ByteBuffer... byteBuffers) throws IOException {
//        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
//    }

    @Override
    public void close() throws IOException {
        if (filers.length > 0) {
            for (ByteBufferBackedFiler filer : filers) {
                filer.close();
            }
            ByteBuffer bb = filers[0].buffer;
            if (bb != null) {
                DirectBufferCleaner.clean(bb);
            }
        }
    }

    @Override
    public void flush(boolean fsync) throws IOException {
        for (ByteBufferBackedFiler filer : filers) {
            filer.flush(fsync);
        }
    }


}
