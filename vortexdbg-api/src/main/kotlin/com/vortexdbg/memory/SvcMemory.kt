package com.vortexdbg.memory

import com.vortexdbg.Svc
import com.vortexdbg.pointer.VortexdbgPointer

interface SvcMemory : StackMemory {

    fun allocate(size: Int, label: String): VortexdbgPointer

    fun allocateSymbolName(name: String): VortexdbgPointer

    fun registerSvc(svc: Svc): VortexdbgPointer

    fun getSvc(svcNumber: Int): Svc?

    fun findRegion(addr: Long): MemRegion?

    fun getBase(): Long
    fun getSize(): Int

}
