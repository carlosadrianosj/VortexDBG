package com.github.unidbg.file;

public interface StdoutCallback {

    /**
     * @return <code>true</code>表示打印
     */
    boolean notifyOut(byte[] data, boolean err);

}
