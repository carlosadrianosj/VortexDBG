package com.vortexdbg.memory;

public interface MMapListener {

    void onMap(long address, long size, int perms);

    int onProtect(long address, long size, int perms);

    void onUnmap(long address, long size);

}
