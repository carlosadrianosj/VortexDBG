package com.vortexdbg.linux.android.dvm

import com.vortexdbg.Emulator

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class ArmVarArg32(emulator: Emulator<*>, vm: BaseVM, method: DvmMethod) : ArmVarArg(emulator, vm, method) {

    init {
        var offset = 0
        for (shorty in shorties) {
            when (shorty.getType()) {
                'L', 'B', 'C', 'I', 'S', 'Z' -> {
                    args.add(getInt(offset++))
                }
                'D' -> {
                    if (offset % 2 == 0) {
                        offset++
                    }
                    val buffer = ByteBuffer.allocate(8)
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    buffer.putInt(getInt(offset++))
                    buffer.putInt(getInt(offset++))
                    buffer.flip()
                    args.add(buffer.getDouble())
                }
                'F' -> {
                    if (offset % 2 == 0) {
                        offset++
                    }
                    val buffer = ByteBuffer.allocate(8)
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    buffer.putInt(getInt(offset++))
                    buffer.putInt(getInt(offset++))
                    buffer.flip()
                    args.add(buffer.getDouble().toFloat())
                }
                'J' -> {
                    if (offset % 2 == 0) {
                        offset++
                    }
                    val buffer = ByteBuffer.allocate(8)
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    buffer.putInt(getInt(offset++))
                    buffer.putInt(getInt(offset++))
                    buffer.flip()
                    args.add(buffer.getLong())
                }
                else -> throw IllegalStateException("c=" + shorty.getType())
            }
        }
    }
}
