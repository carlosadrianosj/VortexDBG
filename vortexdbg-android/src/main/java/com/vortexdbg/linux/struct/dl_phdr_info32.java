package com.vortexdbg.linux.struct;

import com.vortexdbg.pointer.VortexdbgStructure;
import com.sun.jna.Pointer;

import java.util.Arrays;
import java.util.List;

public class dl_phdr_info32 extends VortexdbgStructure {

    public dl_phdr_info32(Pointer p) {
        super(p);
    }

    public int dlpi_addr; // ptr
    public int dlpi_name; // ptr
    public int dlpi_phdr; // ptr
    public short dlpi_phnum;

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("dlpi_addr", "dlpi_name", "dlpi_phdr", "dlpi_phnum");
    }

}
