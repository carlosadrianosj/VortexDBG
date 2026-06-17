package com.vortexdbg.linux.android.dvm

import com.vortexdbg.Emulator
import com.vortexdbg.pointer.VortexdbgPointer
import com.sun.jna.Pointer
import org.slf4j.LoggerFactory

import java.util.Arrays

class VaList64(emulator: Emulator<*>, vm: BaseVM, va_list: VortexdbgPointer, method: DvmMethod) : VaList(vm, method) {

    init {
        var base_p = va_list.getLong(0L)
        val base_integer = va_list.getLong(8L)
        val base_float = va_list.getLong(16L)
        var mask_integer = va_list.getInt(24L)
        var mask_float = va_list.getInt(28L)

        for (shorty in shorties) {
            when (shorty.getType()) {
                'B', 'C', 'I', 'S', 'Z' -> {
                    val pointer: Pointer
                    if ((mask_integer and 0x80000000.toInt()) != 0) {
                        if (mask_integer + 8 <= 0) {
                            pointer = VortexdbgPointer.pointer(emulator, base_integer + mask_integer)
                            mask_integer += 8
                        } else {
                            pointer = VortexdbgPointer.pointer(emulator, base_p)
                            mask_integer += 8
                            base_p = (base_p + 11) and 0xfffffffffffffff8uL.toLong()
                        }
                    } else {
                        pointer = VortexdbgPointer.pointer(emulator, base_p)
                        base_p = (base_p + 11) and 0xfffffffffffffff8uL.toLong()
                    }
                    assert(pointer != null)
                    args.add(pointer.getInt(0L))
                }
                'D' -> {
                    val pointer: Pointer
                    if ((mask_float and 0x80000000.toInt()) != 0) {
                        if (mask_float + 16 <= 0) {
                            pointer = VortexdbgPointer.pointer(emulator, base_float + mask_float)
                            mask_float += 16
                        } else {
                            pointer = VortexdbgPointer.pointer(emulator, base_p)
                            mask_float += 16
                            base_p = (base_p + 15) and 0xfffffffffffffff8uL.toLong()
                        }
                    } else {
                        pointer = VortexdbgPointer.pointer(emulator, base_p)
                        base_p = (base_p + 15) and 0xfffffffffffffff8uL.toLong()
                    }
                    assert(pointer != null)
                    args.add(pointer.getDouble(0L))
                }
                'F' -> {
                    val pointer: Pointer
                    if ((mask_float and 0x80000000.toInt()) != 0) {
                        if (mask_float + 16 <= 0) {
                            pointer = VortexdbgPointer.pointer(emulator, base_float + mask_float)
                            mask_float += 16
                        } else {
                            pointer = VortexdbgPointer.pointer(emulator, base_p)
                            mask_float += 16
                            base_p = (base_p + 15) and 0xfffffffffffffff8uL.toLong()
                        }
                    } else {
                        pointer = VortexdbgPointer.pointer(emulator, base_p)
                        base_p = (base_p + 15) and 0xfffffffffffffff8uL.toLong()
                    }
                    assert(pointer != null)
                    args.add(pointer.getDouble(0L).toFloat())
                }
                'J' -> {
                    val pointer: Pointer
                    if ((mask_integer and 0x80000000.toInt()) != 0) {
                        if (mask_integer + 8 <= 0) {
                            pointer = VortexdbgPointer.pointer(emulator, base_integer + mask_integer)
                            mask_integer += 8
                        } else {
                            pointer = VortexdbgPointer.pointer(emulator, base_p)
                            mask_integer += 8
                            base_p = (base_p + 15) and 0xfffffffffffffff8uL.toLong()
                        }
                    } else {
                        pointer = VortexdbgPointer.pointer(emulator, base_p)
                        base_p = (base_p + 15) and 0xfffffffffffffff8uL.toLong()
                    }
                    assert(pointer != null)
                    args.add(pointer.getLong(0L))
                }
                'L' -> {
                    val pointer: Pointer
                    if ((mask_integer and 0x80000000.toInt()) != 0) {
                        if (mask_integer + 8 <= 0) {
                            pointer = VortexdbgPointer.pointer(emulator, base_integer + mask_integer)
                            mask_integer += 8
                        } else {
                            pointer = VortexdbgPointer.pointer(emulator, base_p)
                            mask_integer += 8
                            base_p = (base_p + 15) and 0xfffffffffffffff8uL.toLong()
                        }
                    } else {
                        pointer = VortexdbgPointer.pointer(emulator, base_p)
                        base_p = (base_p + 15) and 0xfffffffffffffff8uL.toLong()
                    }
                    assert(pointer != null)
                    args.add(pointer.getInt(0L))
                }
                else -> throw IllegalStateException("c=" + shorty.getType())
            }
        }

        if (log.isDebugEnabled) {
            log.debug("VaList64 base_p=0x{}, base_integer=0x{}, base_float=0x{}, mask_integer=0x{}, mask_float=0x{}, args={}, shorty={}", java.lang.Long.toHexString(base_p), java.lang.Long.toHexString(base_integer), java.lang.Long.toHexString(base_float), java.lang.Long.toHexString((mask_integer.toLong()) and 0xffffffffL), java.lang.Long.toHexString((mask_float.toLong()) and 0xffffffffL), method.args, Arrays.toString(shorties))
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(VaList64::class.java)
    }
}
