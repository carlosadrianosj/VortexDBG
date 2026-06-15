package com.vortexdbg.linux.android.dvm;

public interface Array<T> {

    int length();

    void setData(int start, T data);

}
