package com.jivesoftware.os.lab.io.api;

import java.io.IOException;

/**
 *
 * @author jonathan.colt
 */
public interface IAppendOnly extends ICloseable, IFilePointer {

    /**
     *
     * @param b
     * @param _offset
     * @param _len
     * @throws IOException
     */
    public void write(byte b[], int _offset, int _len) throws IOException;

    /**
     *
     * @throws IOException
     */
    public void flush(boolean fsync) throws IOException;
}