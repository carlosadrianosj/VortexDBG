package com.vortexdbg.linux.android.dvm

open class Enumeration(vm: VM, value: List<out DvmObject<*>>?) : DvmObject<List<*>?>(vm.resolveClass("java/util/Enumeration"), value) {

    private val iterator: Iterator<DvmObject<*>>? = value?.iterator()

    open fun hasMoreElements(): Boolean {
        return iterator != null && iterator.hasNext()
    }

    open fun nextElement(): DvmObject<*> {
        return iterator!!.next()
    }

}
