package com.vortexdbg.linux.android.dvm.api;

import com.vortexdbg.Emulator;
import com.vortexdbg.linux.android.dvm.DvmObject;
import com.vortexdbg.linux.android.dvm.VM;
import com.vortexdbg.pointer.UnidbgPointer;
import com.sun.jna.Pointer;

public class Asset extends DvmObject<String> {

    public Asset(VM vm, String value) {
        super(vm.resolveClass("android/content/res/Asset"), value);
    }

    public void open(Emulator<?> emulator, byte[] data) {
        Pointer pointer = allocateMemoryBlock(emulator, data.length + 8);
        pointer.setInt(0, 0); // index
        pointer.setInt(4, data.length);
        pointer.write(8, data, 0, data.length);
    }

    public void close() {
        freeMemoryBlock(null);
    }

    public UnidbgPointer getBuffer() {
        return memoryBlock.getPointer().share(8, 0);
    }

    public int getLength() {
        return memoryBlock.getPointer().getInt(4);
    }

    public byte[] read(int count) {
        Pointer pointer = memoryBlock.getPointer();
        int index = pointer.getInt(0);
        int length = pointer.getInt(4);
        Pointer data = pointer.share(8, 0);
        int remaining = length - index;
        int read = Math.min(remaining, count);
        pointer.setInt(0, index + read);
        return data.getByteArray(index, read);
    }

}
