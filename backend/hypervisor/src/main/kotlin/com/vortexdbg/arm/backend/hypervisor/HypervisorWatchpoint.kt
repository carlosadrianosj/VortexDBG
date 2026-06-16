package com.vortexdbg.arm.backend.hypervisor

import capstone.Arm64_const
import capstone.api.Instruction
import capstone.api.arm64.OpInfo
import capstone.api.arm64.Operand
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.arm.backend.ReadHook
import com.vortexdbg.arm.backend.WriteHook
import org.slf4j.Logger
import org.slf4j.LoggerFactory

internal class HypervisorWatchpoint(callback: Any, begin: Long, end: Long, userData: Any?, slot: Int, isWrite: Boolean) : BreakRestorer {

    private val readHook: ReadHook?
    private val writeHook: WriteHook?
    private val begin: Long
    private val end: Long
    private val userData: Any?
    private val slot: Int
    private val isWrite: Boolean

    private val dbgwcr: Long
    private val dbgwvr: Long
    private val bytes: Long

    init {
        if (begin >= end) {
            throw IllegalArgumentException("Watchpoint begin must be less than end: begin=0x" + java.lang.Long.toHexString(begin) + ", end=0x" + java.lang.Long.toHexString(end))
        }

        val size = end - begin
        if ((size ushr 31) != 0L) {
            throw IllegalArgumentException("too large size=0x" + java.lang.Long.toHexString(size))
        }

        if (isWrite) {
            this.writeHook = callback as WriteHook
            this.readHook = null
        } else {
            this.readHook = callback as ReadHook
            this.writeHook = null
        }
        this.begin = begin
        this.end = end
        this.userData = userData
        this.slot = slot
        this.isWrite = isWrite

        val config = computeWatchpointConfig(begin, size, isWrite)
            ?: throw UnsupportedOperationException("Failed to find a power-of-2 aligned region for watchpoint: begin=0x" + java.lang.Long.toHexString(begin) + ", end=0x" + java.lang.Long.toHexString(end))
        this.dbgwcr = config[0]
        this.dbgwvr = config[1]
        this.bytes = config[2]
    }

    fun getSlot(): Int {
        return slot
    }

    fun matches(begin: Long, end: Long, isWrite: Boolean): Boolean {
        return this.begin == begin && this.end == end && this.isWrite == isWrite
    }

    /**
     * Coarse check using the hardware-aligned region (dbgwvr/bytes) to quickly
     * determine if an access overlaps the watchpoint's monitored range.
     */
    fun contains(address: Long, accessSize: Int, isWrite: Boolean): Boolean {
        var accessSize = accessSize
        if (isWrite xor this.isWrite) {
            return false
        }
        if (accessSize <= 0) {
            accessSize = 1
        }
        val accessEnd = address + accessSize
        return address < (dbgwvr + bytes) && accessEnd > dbgwvr
    }

    /**
     * Fine-grained callback dispatch using the exact user-specified range (begin/end),
     * invoked only after [contains] passes the coarse hardware-level check.
     */
    fun onHit(backend: Backend, address: Long, accessSize: Int, isWrite: Boolean, insn: Instruction) {
        val accessEnd = address + accessSize
        if (address >= end || accessEnd <= begin) {
            return
        }
        when (insn.getMnemonic()) {
            "ldp", "ldxp", "ldaxp", "stp", "stxp", "stlxp" -> {
                val halfSize = accessSize / 2
                val baseRegIndex = pairFirstRegIndex(insn.getMnemonic())
                notifySubAccess(backend, address, halfSize, isWrite, insn, baseRegIndex)
                notifySubAccess(backend, address + halfSize, halfSize, isWrite, insn, baseRegIndex + 1)
                return
            }
        }
        notifySubAccess(backend, address, accessSize, isWrite, insn, singleRegIndex(insn.getMnemonic()))
    }

    private fun notifySubAccess(backend: Backend, address: Long, size: Int, isWrite: Boolean, insn: Instruction, regIndex: Int) {
        if (address >= end || (address + size) <= begin) {
            return
        }
        if (isWrite) {
            val value = extractWriteValue(insn, backend, size, regIndex)
            writeHook!!.hook(backend, address, size, value, userData)
        } else {
            readHook!!.hook(backend, address, size, userData)
        }
    }

    override fun install(hypervisor: Hypervisor) {
        hypervisor.install_watchpoint(slot, dbgwcr, dbgwvr)
    }

    companion object {

        private val log: Logger = LoggerFactory.getLogger(HypervisorWatchpoint::class.java)

        /** DBGWCR: E=1 (enabled), PAC=0b10 (EL1 & EL0) */
        private const val DBGWCR_ENABLE = 0x5L
        /** DBGWCR LSC field: store only */
        private const val DBGWCR_LSC_STORE = 0b10L shl 3
        /** DBGWCR LSC field: load only */
        private const val DBGWCR_LSC_LOAD = 0b01L shl 3
        /** DBGWCR BAS field bit offset */
        private const val DBGWCR_BAS_SHIFT = 5
        /** DBGWCR MASK field bit offset */
        private const val DBGWCR_MASK_SHIFT = 24
        /** Full byte-address-select mask (all 8 bytes selected) */
        private const val DBGWCR_BAS_FULL = 0xFFL

        /**
         * Finds the smallest power-of-2 aligned region that covers [begin, begin+size),
         * and computes the DBGWCR/DBGWVR register values for the ARM watchpoint hardware.
         *
         * @return {dbgwcr, dbgwvr, bytes} or null if no suitable region found
         */
        private fun computeWatchpointConfig(begin: Long, size: Long, isWrite: Boolean): LongArray? {
            var dbgwcr = DBGWCR_ENABLE or (if (isWrite) DBGWCR_LSC_STORE else DBGWCR_LSC_LOAD)
            for (i in 2..31) {
                val bytes = 1 shl i
                val mask = bytes - 1
                val dbgwvr = begin and (mask.toLong().inv())
                val offset = begin - dbgwvr
                if (offset + size <= bytes) {
                    val bas: Long
                    val maskBits: Int
                    if (i <= 3) {
                        maskBits = 0
                        var b = 0L
                        var m = 0L
                        while (m < size) {
                            b = b or (1L shl (offset + m).toInt())
                            m++
                        }
                        bas = b
                    } else {
                        maskBits = i
                        bas = DBGWCR_BAS_FULL
                    }
                    dbgwcr = dbgwcr or (bas shl DBGWCR_BAS_SHIFT)
                    dbgwcr = dbgwcr or (maskBits.toLong() shl DBGWCR_MASK_SHIFT)

                    if (log.isDebugEnabled) {
                        log.debug("begin=0x{}, end=0x{}, dbgwvr=0x{}, dbgwcr=0x{}, offset={}, size={}, i={}", java.lang.Long.toHexString(begin), java.lang.Long.toHexString(begin + size), java.lang.Long.toHexString(dbgwvr), java.lang.Long.toHexString(dbgwcr), offset, size, i)
                    }
                    return longArrayOf(dbgwcr, dbgwvr, bytes.toLong())
                }
            }
            return null
        }

        private fun pairFirstRegIndex(mnemonic: String): Int {
            return when (mnemonic) {
                "stxp", "stlxp" -> 1
                else -> 0
            }
        }

        private fun singleRegIndex(mnemonic: String): Int {
            return when (mnemonic) {
                "stxr", "stlxr" -> 1
                else -> 0
            }
        }

        private fun extractWriteValue(insn: Instruction, backend: Backend, size: Int, regIndex: Int): Long {
            val opInfo = insn.getOperands() as OpInfo
            val ops = opInfo.getOperands()
            if (ops.size > regIndex && ops[regIndex].getType() == Arm64_const.ARM64_OP_REG) {
                val unicornReg = insn.mapToUnicornReg(ops[regIndex].getValue().getReg())
                val value = backend.reg_read(unicornReg).toLong()
                return when (size) {
                    1 -> value and 0xFFL
                    2 -> value and 0xFFFFL
                    4 -> value and 0xFFFFFFFFL
                    else -> value
                }
            }
            return 0
        }
    }
}
