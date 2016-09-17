package com.jivesoftware.os.lab.guts;

import com.jivesoftware.os.lab.io.BolBuffer;
import com.jivesoftware.os.lab.api.exceptions.LABIndexClosedException;
import com.jivesoftware.os.lab.io.api.IAppendOnly;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author jonathan.colt
 */
public class AppendOnlyFile {

    private final static MetricLogger LOG = MetricLoggerFactory.getLogger();

    private final File file;
    private RandomAccessFile randomAccessFile;
    private FileChannel channel;
    private final AtomicLong size;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    public AppendOnlyFile(File file) throws IOException {
        this.file = file;
        this.randomAccessFile = new RandomAccessFile(file, "rw");
        this.channel = randomAccessFile.getChannel();
        this.size = new AtomicLong(randomAccessFile.length());
    }

    public String getFileName() {
        return file.toString();
    }

    public void delete() {
        file.delete();
    }

    public boolean isClosed() {
        return closed.get();
    }

    public void flush(boolean fsync) throws IOException {
        if (fsync) {
            randomAccessFile.getFD().sync();
        }
    }

    public IAppendOnly appender() throws Exception {
        if (closed.get()) {
            throw new LABIndexClosedException("Cannot get an appender from an index that is already closed.");
        }
        DataOutputStream writer = new DataOutputStream(new FileOutputStream(file, true));
        return new IAppendOnly() {
            @Override
            public void appendByte(byte b) throws IOException {
                writer.writeByte(b);
                size.addAndGet(1);
            }

            @Override
            public void appendShort(short s) throws IOException {
                writer.writeShort(s);
                size.addAndGet(2);
            }

            @Override
            public void appendInt(int i) throws IOException {
                writer.writeInt(i);
                size.addAndGet(4);
            }

            @Override
            public void appendLong(long l) throws IOException {
                writer.writeLong(l);
                size.addAndGet(8);
            }

            @Override
            public void append(byte[] b, int _offset, int _len) throws IOException {
                writer.write(b, _offset, _len);
                size.addAndGet(_len);
            }

            @Override
            public void append(BolBuffer bolBuffer) throws IOException {
                byte[] copy = bolBuffer.copy();
                append(copy, 0, copy.length);
            }

            @Override
            public void flush(boolean fsync) throws IOException {
                writer.flush();
                AppendOnlyFile.this.flush(fsync);
            }

            @Override
            public void close() throws IOException {
                writer.close();
            }

            @Override
            public long length() throws IOException {
                return AppendOnlyFile.this.length();
            }

            @Override
            public long getFilePointer() throws IOException {
                return length();
            }

        };
    }

    @Override
    public String toString() {
        return "IndexFile{"
            + "fileName=" + file
            + ", size=" + size
            + '}';
    }

    public void close() throws IOException {
        synchronized (closed) {
            if (closed.compareAndSet(false, true)) {
                randomAccessFile.close();
            }
        }
    }

    public long length() throws IOException {
        return size.get();
    }

    private void ensureOpen() throws IOException {
        if (closed.get()) {
            throw new IOException("Cannot ensureOpen on an index that is already closed.");
        }
        if (!channel.isOpen()) {
            synchronized (closed) {
                if (closed.get()) {
                    throw new IOException("Cannot ensureOpen on an index that is already closed.");
                }
                if (!channel.isOpen()) {
                    try {
                        randomAccessFile.close();
                    } catch (IOException e) {
                        LOG.error("Failed to close existing random access file while reacquiring channel");
                    }
                    randomAccessFile = new RandomAccessFile(file, "rw");
                    channel = randomAccessFile.getChannel();
                }
            }
        }
    }

    FileChannel getFileChannel() throws IOException {
        ensureOpen();
        return channel;
    }
}