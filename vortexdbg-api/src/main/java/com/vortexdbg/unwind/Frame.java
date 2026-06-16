package com.vortexdbg.unwind;

import com.vortexdbg.pointer.VortexdbgPointer;

public class Frame {

    public final VortexdbgPointer ip, fp;

    public Frame(VortexdbgPointer ip, VortexdbgPointer fp) {
        this.ip = ip;
        this.fp = fp;
    }

    final boolean isFinish() {
        return fp == null;
    }

}
