package com.vortexdbg.unwind

import com.vortexdbg.pointer.VortexdbgPointer

class Frame(@JvmField val ip: VortexdbgPointer?, @JvmField val fp: VortexdbgPointer?) {

    fun isFinish(): Boolean {
        return fp == null
    }

}
