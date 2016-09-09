package com.jivesoftware.os.lab.guts;

import com.jivesoftware.os.lab.BolBuffer;
import com.jivesoftware.os.lab.guts.api.Scanner;

/**
 *
 * @author jonathan.colt
 */
public interface LABIndex {

    interface Compute {

        BolBuffer apply(BolBuffer existing);
    }

    void compute(BolBuffer keyBytes, BolBuffer valueBuffer, Compute computeFunction) throws Exception;

    BolBuffer get(BolBuffer key, BolBuffer valueBuffer) throws Exception;

    boolean contains(byte[] from, byte[] to) throws Exception;

    Scanner scanner(byte[] from, byte[] to) throws Exception;

    void clear() throws Exception;

    boolean isEmpty() throws Exception;

    byte[] firstKey() throws Exception;

    byte[] lastKey() throws Exception;

}