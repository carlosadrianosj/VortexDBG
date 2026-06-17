package net.fornwall.jelf

import com.vortexdbg.Emulator
import com.vortexdbg.Module
import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.unwind.Frame
import com.vortexdbg.unwind.Unwinder
import com.vortexdbg.utils.Inspector
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import unicorn.ArmConst

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayList
import java.util.Arrays

class ArmExIdx internal constructor(private val virtualAddress: Long, private val buffer: ByteBuffer) {

    init {
        this.buffer.order(ByteOrder.LITTLE_ENDIAN)
    }

    internal enum class arm_exbuf_cmd {
        ARM_EXIDX_CMD_FINISH,
        ARM_EXIDX_CMD_DATA_PUSH,
        ARM_EXIDX_CMD_DATA_POP,
        ARM_EXIDX_CMD_REG_POP,
        ARM_EXIDX_CMD_REG_TO_SP,
        ARM_EXIDX_CMD_VFP_POP,
        ARM_EXIDX_CMD_WREG_POP,
        ARM_EXIDX_CMD_WCGR_POP,
        ARM_EXIDX_CMD_RESERVED,
        ARM_EXIDX_CMD_REFUSED,
    }

    fun arm_exidx_step(emulator: Emulator<*>, unwinder: Unwinder, module: Module, fun_: Long, context: DwarfCursor): Frame? {
        var value = ARM_EXIDX_CANT_UNWIND

        buffer.position(0)
        var offset = virtualAddress
        var entry = 0
        while (buffer.hasRemaining()) {
            var key = buffer.getInt() shl 1 shr 1
            if (key == 0) {
                continue
            }
            key += offset.toInt()

            if (fun_ >= key) {
                offset += 8
                entry = key
                value = buffer.getInt()
            } else {
                break
            }
        }

        if (value == ARM_EXIDX_CANT_UNWIND) {
            return null
        }

        if (fun_ == entry.toLong()) { // first instruction of function
            val ip = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_LR)
            val fp = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_SP)
            val frame = unwinder.createFrame(ip, fp)
            if (frame != null) {
                context.ip = frame.ip!!.peer
            }
            return frame
        }

        var instruction: ByteArray
        val compact = (value and ARM_EXIDX_COMPACT) != 0
        var index: Int
        var bb: ByteBuffer
        if (compact) { // android_external_libunwind/src/arm/Gex_tables.c
            index = (value shr 24) and 0xf
            if (index != 0) {
                throw IllegalStateException("compact model must be Su16 / __aeabi_unwind_cpp_pr0")
            }
            bb = ByteBuffer.allocate(4)
            bb.putInt(value)
            instruction = Arrays.copyOfRange(bb.array(), 1, 4)
        } else {
            value = value shl 1 shr 1
            val addr = value + offset - 4
            val pointer = VortexdbgPointer.pointer(emulator, module.base + addr)
            assert(pointer != null)
            value = pointer!!.getInt(0)
            if ((value and ARM_EXIDX_COMPACT) == 0) {
                val personality = (value.toLong() shl 1 shr 1) + addr
                val data = pointer.getInt(4)
                val n = (data shr 24) and 0xff
                bb = ByteBuffer.allocate((n + 1) * 4)
                bb.putInt(data)
                for (i in 0 until n) {
                    bb.putInt(pointer.getInt(((i + 2) * 4).toLong()))
                }
                instruction = Arrays.copyOfRange(bb.array(), 1, bb.capacity())
                if (log.isDebugEnabled) {
                    log.debug("unwind generic model: {}, entry=0x{}, personality=0x{}", module, Integer.toHexString(entry), java.lang.Long.toHexString(personality))
                }
            } else {
                index = (value shr 24) and 0xf
                when (index) {
                    0 -> { // Su16 / __aeabi_unwind_cpp_pr0
                        bb = ByteBuffer.allocate(4)
                        bb.putInt(value)
                        instruction = Arrays.copyOfRange(bb.array(), 1, bb.capacity())
                    }
                    1, 2 -> { // Lu16 / Lu32 / __aeabi_unwind_cpp_pr1
                        val n = (value shr 16) and 0xff
                        bb = ByteBuffer.allocate((n + 1) * 4)
                        bb.putInt(value)
                        for (i in 0 until n) {
                            bb.putInt(pointer.getInt(((i + 1) * 4).toLong()))
                        }
                        instruction = Arrays.copyOfRange(bb.array(), 2, bb.capacity())
                    }
                    else -> throw UnsupportedOperationException("index=$index")
                }
            }
        }
        if (instruction.size > 0 && (instruction[instruction.size - 1].toInt() and 0xff) != ARM_EXTBL_OP_FINISH) {
            val tmp = ByteArray(instruction.size + 1)
            System.arraycopy(instruction, 0, tmp, 0, instruction.size)
            tmp[instruction.size] = ARM_EXTBL_OP_FINISH.toByte()
            instruction = tmp
        }
        if (log.isDebugEnabled) {
            log.debug(Inspector.inspectString(instruction, "unwind entry=0x" + Integer.toHexString(entry) + ", value=0x" + Integer.toHexString(value) + ", fun=0x" + java.lang.Long.toHexString(fun_) + ", module=" + module.name))
        }

        return arm_exidx_decode(emulator, instruction, unwinder, context)
    }

    private class arm_exbuf_data {
        @JvmField var cmd: arm_exbuf_cmd? = null
        @JvmField var data: Int = 0
    }

    private fun arm_exidx_decode(emulator: Emulator<*>, instruction: ByteArray, unwinder: Unwinder, context: DwarfCursor): Frame? {
        context.loc[UNW_ARM_PC] = null

        val edata = arm_exbuf_data()
        var i = 0
        while (i < instruction.size) {
            val op = instruction[i].toInt() and 0xff
            if ((op and 0xc0) == 0x00) {
                edata.cmd = arm_exbuf_cmd.ARM_EXIDX_CMD_DATA_POP
                edata.data = ((op and 0x3f) shl 2) + 4
            } else if ((op and 0xc0) == 0x40) {
                edata.cmd = arm_exbuf_cmd.ARM_EXIDX_CMD_DATA_PUSH
                edata.data = ((op and 0x3f) shl 2) + 4
            } else if ((op and 0xf0) == 0x80) {
                val op2 = instruction[++i].toInt() and 0xff
                if (op == 0x80 && op2 == 0x0) {
                    edata.cmd = arm_exbuf_cmd.ARM_EXIDX_CMD_REFUSED
                } else {
                    edata.cmd = arm_exbuf_cmd.ARM_EXIDX_CMD_REG_POP
                    edata.data = ((op and 0xf) shl 8) or op2
                    edata.data = edata.data shl 4
                }
            } else if ((op and 0xf0) == 0x90) {
                if (op == 0x9d || op == 0x9f) {
                    edata.cmd = arm_exbuf_cmd.ARM_EXIDX_CMD_RESERVED
                } else {
                    edata.cmd = arm_exbuf_cmd.ARM_EXIDX_CMD_REG_TO_SP
                    edata.data = op and 0xf
                }
            } else if ((op and 0xf0) == 0xa0) {
                val end = op and 0x7
                edata.data = (1 shl (end + 1)) - 1
                edata.data = edata.data shl 4
                if ((op and 0x8) != 0)
                    edata.data = edata.data or (1 shl 14)
                edata.cmd = arm_exbuf_cmd.ARM_EXIDX_CMD_REG_POP
            } else if (op == ARM_EXTBL_OP_FINISH) {
                edata.cmd = arm_exbuf_cmd.ARM_EXIDX_CMD_FINISH
            } else if (op == 0xb1) {
                val op2 = instruction[++i].toInt() and 0xff
                if (op2 == 0 || (op2 and 0xf0) != 0) {
                    edata.cmd = arm_exbuf_cmd.ARM_EXIDX_CMD_RESERVED
                } else {
                    edata.cmd = arm_exbuf_cmd.ARM_EXIDX_CMD_REG_POP
                    edata.data = op2 and 0xf
                }
            } else if (op == 0xb2) {
                var offset = 0
                var b: Byte
                var shift: Int = 0
                do {
                    b = instruction[++i]
                    offset = offset or ((b.toInt() and 0x7f) shl shift)
                    shift += 7
                } while ((b.toInt() and 0x80) != 0)
                edata.data = offset * 4 + 0x204
                edata.cmd = arm_exbuf_cmd.ARM_EXIDX_CMD_DATA_POP
            } else if (op == 0xb3 || op == 0xc8 || op == 0xc9) {
                edata.cmd = arm_exbuf_cmd.ARM_EXIDX_CMD_VFP_POP
                edata.data = instruction[++i].toInt() and 0xff
                if (op == 0xc8) {
                    edata.data = edata.data or ARM_EXIDX_VFP_SHIFT_16
                }
                if (op != 0xb3) {
                    edata.data = edata.data or ARM_EXIDX_VFP_DOUBLE
                }
            } else if ((op and 0xf8) == 0xb8 || (op and 0xf8) == 0xd0) {
                edata.cmd = arm_exbuf_cmd.ARM_EXIDX_CMD_VFP_POP
                edata.data = 0x80 or (op and 0x7)
                if ((op and 0xf8) == 0xd0) {
                    edata.data = edata.data or ARM_EXIDX_VFP_DOUBLE
                }
            } else if (op >= 0xc0 && op <= 0xc5) {
                edata.cmd = arm_exbuf_cmd.ARM_EXIDX_CMD_WREG_POP
                edata.data = 0xa0 or (op and 0x7)
            } else if (op == 0xc6) {
                edata.cmd = arm_exbuf_cmd.ARM_EXIDX_CMD_WREG_POP
                edata.data = instruction[++i].toInt() and 0xff
            } else if (op == 0xc7) {
                val op2 = instruction[++i].toInt() and 0xff
                if (op2 == 0 || (op2 and 0xf0) != 0) {
                    edata.cmd = arm_exbuf_cmd.ARM_EXIDX_CMD_RESERVED
                } else {
                    edata.cmd = arm_exbuf_cmd.ARM_EXIDX_CMD_WCGR_POP
                    edata.data = op2 and 0xf
                }
            } else {
                edata.cmd = arm_exbuf_cmd.ARM_EXIDX_CMD_RESERVED
            }

            if (!arm_exidx_apply_cmd(emulator, edata, context)) {
                return null
            }
            i++
        }

        val pc = context.loc[UNW_ARM_PC]
        if (pc != null) {
            return unwinder.createFrame(VortexdbgPointer.pointer(emulator, pc), VortexdbgPointer.pointer(emulator, context.cfa))
        }

        return null
    }

    private fun arm_exidx_apply_cmd(emulator: Emulator<*>, edata: arm_exbuf_data, context: DwarfCursor): Boolean {
        when (edata.cmd) {
            arm_exbuf_cmd.ARM_EXIDX_CMD_FINISH -> {
                /* Set LR to PC if not set already.  */
                if (context.loc[UNW_ARM_PC] == null) {
                    context.loc[UNW_ARM_PC] = context.loc[UNW_ARM_LR]
                }
                context.ip = context.loc[UNW_ARM_PC]!!
                if (log.isDebugEnabled) {
                    log.debug("finish")
                }
            }
            arm_exbuf_cmd.ARM_EXIDX_CMD_DATA_PUSH -> {
                context.cfa -= edata.data
                if (log.isDebugEnabled) {
                    log.debug("vsp = vsp - {}", edata.data)
                }
            }
            arm_exbuf_cmd.ARM_EXIDX_CMD_DATA_POP -> {
                context.cfa += edata.data
                if (log.isDebugEnabled) {
                    log.debug("vsp = vsp + {}", edata.data)
                }
            }
            arm_exbuf_cmd.ARM_EXIDX_CMD_REG_POP -> {
                val list: MutableList<String>?
                if (log.isDebugEnabled) {
                    list = ArrayList(16)
                } else {
                    list = null
                }
                for (m in 0 until 16) {
                    if ((edata.data and (1 shl m)) != 0) {
                        val reg = "r$m"
                        if (list != null) {
                            list.add(reg)
                        }

                        val sp = VortexdbgPointer.pointer(emulator, context.cfa)
                        assert(sp != null)
                        val value = sp!!.getInt(0).toLong() and 0xffffffffL
                        context.loc[m] = value
                        context.cfa += 4
                        if (log.isDebugEnabled) {
                            log.debug("pop {} -> 0x{}", reg, java.lang.Long.toHexString(value))
                        }
                    }
                }
                /* Set cfa in case the SP got popped. */
                if ((edata.data and (1 shl UNW_ARM_SP)) != 0) {
                    context.cfa = context.loc[UNW_ARM_SP]!!
                }
                if (log.isDebugEnabled && list != null) {
                    log.debug("pop {}", list.toString().replace('[', '{').replace(']', '}'))
                }
            }
            arm_exbuf_cmd.ARM_EXIDX_CMD_REG_TO_SP -> {
                val value = context.loc[edata.data]!!
                context.loc[UNW_ARM_SP] = value
                if (log.isDebugEnabled) {
                    log.debug("vsp = r{} [0x{}]", edata.data, java.lang.Long.toHexString(context.loc[UNW_ARM_SP]!!))
                }
                val sp = context.cfa
                context.cfa = value
                if (context.cfa == 0L) {
                    System.err.println("vsp is null: sp=0x" + java.lang.Long.toHexString(sp))
                    return false
                }
            }
            arm_exbuf_cmd.ARM_EXIDX_CMD_VFP_POP -> {
                val start = ((edata.data) shr 4) and 0xf
                val count = (edata.data) and 0xf
                val end = start + count
                for (m in start..end) {
                    context.cfa += 8
                }
                if ((edata.data and ARM_EXIDX_VFP_DOUBLE) == 0) {
                    context.cfa += 4
                }
                if (log.isDebugEnabled) {
                    log.debug("pop {D{}-D{}}", start, end)
                }
            }
            arm_exbuf_cmd.ARM_EXIDX_CMD_WREG_POP -> {
                val start = ((edata.data) shr 4) and 0xf
                val count = (edata.data) and 0xf
                val end = start + count
                for (m in start..end) {
                    context.cfa += 8
                }
            }
            arm_exbuf_cmd.ARM_EXIDX_CMD_WCGR_POP -> {
                for (m in 0 until 4) {
                    if ((edata.data and (1 shl m)) != 0) {
                        context.cfa += 4
                    }
                }
            }
            arm_exbuf_cmd.ARM_EXIDX_CMD_REFUSED, arm_exbuf_cmd.ARM_EXIDX_CMD_RESERVED -> {
                if (log.isDebugEnabled) {
                    log.debug("cmd={}", edata.cmd)
                }
                return false
            }
            else -> {
                log.warn("arm_exidx_decode cmd={}", edata.cmd)
                return false
            }
        }
        return true
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(ArmExIdx::class.java)

        private const val ARM_EXIDX_CANT_UNWIND = 0x00000001
        private val ARM_EXIDX_COMPACT = 0x80000000.toInt()
        private const val ARM_EXTBL_OP_FINISH = 0xb0

        private const val ARM_EXIDX_VFP_SHIFT_16 = 1 shl 16
        private const val ARM_EXIDX_VFP_DOUBLE = 1 shl 17

        private const val UNW_ARM_SP = 13
        private const val UNW_ARM_LR = 14
        private const val UNW_ARM_PC = 15
    }
}
