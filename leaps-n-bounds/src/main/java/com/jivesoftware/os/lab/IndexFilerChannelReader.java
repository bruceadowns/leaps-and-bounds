package com.jivesoftware.os.lab;

import com.jivesoftware.os.lab.io.api.IReadable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * @author jonathan.colt
 */
public class IndexFilerChannelReader implements IReadable {

    private final IndexFile parent;
    private final FileChannel fc;
    private long fp;

    public IndexFilerChannelReader(IndexFile parent, FileChannel fc) {
        this.parent = parent;
        this.fc = fc;
    }

    @Override
    public Object lock() {
        return this;
    }

    @Override
    public void seek(long position) throws IOException {
        if (position < 0 || position > parent.length()) {
            throw new IOException("seek overflow " + position + " " + this);
        }
        fp = position;
    }

    @Override
    public long length() throws IOException {
        return parent.length();
    }

    @Override
    public long getFilePointer() throws IOException {
        return fp;
    }

    @Override
    public int read() throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(1);
        int read = fc.read(bb, fp);
        fp++;
        bb.position(0);
        return read != 1 ? -1 : bb.get();
    }

    @Override
    public int read(byte[] b) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(b);
        fc.read(bb, fp);
        fp += b.length;
        return bb.capacity();
    }

    @Override
    public int read(byte[] b, int _offset, int _len) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(_len);
        fc.read(bb, fp);
        fp += _len;
        bb.position(0);
        System.arraycopy(bb.array(), 0, b, _offset, _len);
        return _len;
    }

    @Override
    public void close() throws IOException {
        fc.close();
    }

}
