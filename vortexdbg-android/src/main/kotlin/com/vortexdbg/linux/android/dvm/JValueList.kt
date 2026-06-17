package com.vortexdbg.linux.android.dvm

import com.vortexdbg.pointer.VortexdbgPointer
import com.sun.jna.Pointer
import org.slf4j.LoggerFactory

class JValueList(vm: BaseVM, jvalue: VortexdbgPointer, method: DvmMethod) : VaList(vm, method) {

    init {
        var pointer: Pointer = jvalue
        for (shorty in shorties) {
            when (shorty.getType()) {
                'L' -> {
                    val ptr = pointer.getPointer(0L) as VortexdbgPointer?
                    args.add(if (ptr == null) 0 else ptr.toUIntPeer().toInt())
                }
                'B' -> {
                    val value = pointer.getByte(0L)
                    args.add(value.toInt() and 0xff)
                }
                'Z' -> {
                    val value = pointer.getByte(0L)
                    args.add(value.toInt() and 1)
                }
                'C' -> {
                    val value = pointer.getChar(0L)
                    args.add(value.code)
                }
                'S' -> {
                    args.add(pointer.getShort(0L).toInt())
                }
                'I' -> {
                    args.add(pointer.getInt(0L))
                }
                'F' -> {
                    args.add(pointer.getDouble(0L).toFloat())
                }
                'D' -> {
                    args.add(pointer.getDouble(0L))
                }
                'J' -> {
                    args.add(pointer.getLong(0L))
                }
                else -> throw IllegalStateException("c=" + shorty.getType())
            }

            pointer = pointer.share(8)
        }

        if (log.isDebugEnabled) {
            log.debug("JValueList args={}, shorty={}", method.args, shorties)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(JValueList::class.java)
    }
}
