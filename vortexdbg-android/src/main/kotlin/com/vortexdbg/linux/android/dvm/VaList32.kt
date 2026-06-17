package com.vortexdbg.linux.android.dvm

import com.vortexdbg.Emulator
import com.vortexdbg.pointer.VortexdbgPointer
import org.slf4j.LoggerFactory

import java.util.Arrays

class VaList32(emulator: Emulator<*>, vm: BaseVM, va_list: VortexdbgPointer, method: DvmMethod) : VaList(vm, method) {

    init {
        var pointer: VortexdbgPointer = va_list
        for (shorty in shorties) {
            when (shorty.getType()) {
                'L', 'B', 'C', 'I', 'S', 'Z' -> {
                    args.add(pointer.getInt(0L))
                    pointer = pointer.share(4L, 0L)
                }
                'D' -> {
                    val ptr = VortexdbgPointer.pointer(emulator, (pointer.toUIntPeer() + 7) and 0xfffffff8L)
                    assert(ptr != null)
                    args.add(ptr.getDouble(0L))
                    pointer = ptr.share(8L, 0L)
                }
                'F' -> {
                    val ptr = VortexdbgPointer.pointer(emulator, (pointer.toUIntPeer() + 7) and 0xfffffff8L)
                    assert(ptr != null)
                    args.add(ptr.getDouble(0L).toFloat())
                    pointer = ptr.share(8L, 0L)
                }
                'J' -> {
                    val ptr = VortexdbgPointer.pointer(emulator, (pointer.toUIntPeer() + 7) and 0xfffffff8L)
                    assert(ptr != null)
                    args.add(ptr.getLong(0L))
                    pointer = ptr.share(8L, 0L)
                }
                else -> throw IllegalStateException("c=" + shorty.getType())
            }
        }

        if (log.isDebugEnabled) {
            log.debug("VaList64 args={}, shorty={}", method.args, Arrays.toString(shorties))
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(VaList32::class.java)
    }
}
