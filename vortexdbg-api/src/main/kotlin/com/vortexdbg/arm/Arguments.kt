package com.vortexdbg.arm

import com.vortexdbg.ByteArrayNumber
import com.vortexdbg.StringNumber
import com.vortexdbg.memory.Memory
import com.vortexdbg.pointer.VortexdbgPointer
import org.apache.commons.codec.binary.Hex
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.ArrayList

class Arguments internal constructor(memory: Memory, @JvmField val args: Array<Number>?) {

    @JvmField
    val pointers: MutableList<Number> = ArrayList(10)

    init {
        var i = 0
        while (args != null && i < args.size) {
            val arg = args[i]
            if (arg is StringNumber) {
                val pointer = memory.writeStackString(arg.value)
                if (log.isDebugEnabled) {
                    log.debug("map string arg{}: {} -> {}", i + 1, pointer, args[i])
                }
                args[i] = pointer.peer
                pointers.add(pointer.peer)
            } else if (arg is ByteArrayNumber) {
                val pointer = memory.writeStackBytes(arg.value)
                if (log.isDebugEnabled) {
                    log.debug("map bytes arg{}: {} -> {}", i + 1, pointer, Hex.encodeHexString(arg.value))
                }
                args[i] = pointer.peer
                pointers.add(pointer.peer)
            } else if (arg == null) {
                args[i] = 0
            }
            i++
        }
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(Arguments::class.java)
    }

}
