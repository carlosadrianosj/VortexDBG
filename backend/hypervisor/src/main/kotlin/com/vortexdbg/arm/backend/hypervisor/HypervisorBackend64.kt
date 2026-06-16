package com.vortexdbg.arm.backend.hypervisor

import capstone.api.Disassembler
import capstone.api.DisassemblerFactory
import capstone.api.Instruction
import capstone.api.arm64.OpInfo
import capstone.api.arm64.OpValue
import capstone.api.arm64.Operand
import com.alibaba.fastjson.util.IOUtils
import com.vortexdbg.Emulator
import com.vortexdbg.arm.ARMEmulator
import com.vortexdbg.arm.backend.*
import com.vortexdbg.arm.backend.HypervisorFactory
import com.vortexdbg.arm.backend.hypervisor.arm64.MemorySizeDetector
import com.vortexdbg.arm.backend.hypervisor.arm64.SimpleMemorySizeDetector
import com.vortexdbg.debugger.BreakPoint
import com.vortexdbg.debugger.BreakPointCallback
import com.vortexdbg.pointer.VortexdbgPointer
import com.sun.jna.Pointer
import keystone.Keystone
import keystone.KeystoneArchitecture
import keystone.KeystoneEncoded
import keystone.KeystoneMode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import unicorn.Arm64Const
import unicorn.UnicornConst

import java.util.*

class HypervisorBackend64(emulator: Emulator<*>, hypervisor: Hypervisor) : HypervisorBackend(emulator, hypervisor) {

    private var disassembler: Disassembler? = null
    private var keystone: Keystone? = null

    @Synchronized
    private fun createDisassembler(): Disassembler {
        if (disassembler == null) {
            this.disassembler = DisassemblerFactory.createArm64Disassembler()
            this.disassembler!!.setDetail(true)
        }
        return disassembler!!
    }

    @Synchronized
    private fun getKeystone(): Keystone {
        if (keystone == null) {
            this.keystone = Keystone(KeystoneArchitecture.Arm64, KeystoneMode.LittleEndian)
        }
        return keystone!!
    }

    override fun mem_map(address: Long, size: Long, perms: Int) {
        if (address == DARWIN_KERNEL_BASE) {
            throw BackendException()
        }

        super.mem_map(address, size, perms)
    }

    private var debugCallback: DebugHook? = null
    private var debugUserData: Any? = null
    private var debugBegin: Long = 0
    private var debugEnd: Long = 0

    override fun debugger_add(callback: DebugHook, begin: Long, end: Long, userData: Any?) {
        this.debugCallback = callback
        this.debugUserData = userData
        this.debugBegin = begin
        this.debugEnd = end
    }

    private val breakpoints: Array<HypervisorBreakPoint?>
    private val watchpoints: Array<HypervisorWatchpoint?>
    private val visitorStack: Deque<ExceptionVisitor> = ArrayDeque()

    private var singleStep: Int = 0

    init {
        breakpoints = arrayOfNulls(hypervisor.getBRPs())
        watchpoints = arrayOfNulls(hypervisor.getWRPs())
    }

    override fun setSingleStep(singleStep: Int) {
        this.singleStep = singleStep
        step()
    }

    override fun addBreakPoint(address: Long, callback: BreakPointCallback?, thumb: Boolean): BreakPoint {
        if (thumb) {
            throw IllegalStateException()
        }
        var freeSlot = -1
        for (i in breakpoints.indices) {
            if (breakpoints[i] != null && breakpoints[i]!!.getAddress() == address) {
                return breakpoints[i]!!
            }
            if (freeSlot == -1 && breakpoints[i] == null) {
                freeSlot = i
            }
        }
        if (freeSlot != -1) {
            val bp = HypervisorBreakPoint(freeSlot, address, callback)
            bp.install(hypervisor)
            breakpoints[freeSlot] = bp
            return bp
        }
        throw UnsupportedOperationException("Max BKPs: " + breakpoints.size)
    }

    override fun removeBreakPoint(address: Long): Boolean {
        for (i in breakpoints.indices) {
            if (breakpoints[i] != null && breakpoints[i]!!.getAddress() == address) {
                breakpoints[i] = null
                hypervisor.disable_hw_breakpoint(i)
                return true
            }
        }
        return false
    }

    override fun handleUnknownException(ec: Int, esr: Long, far: Long, virtualAddress: Long) {
        when (ec) {
            HypervisorCallback.EC_DATAABORT -> {
                val isv = (esr and HypervisorCallback.ARM_EL_ISV.toLong()) != 0L
                val isWrite = ((esr shr 6) and 1) != 0L
                val sas = ((esr shr 22) and 3).toInt()
                val accessSize = if (isv) 1 shl sas else 0
                if (log.isDebugEnabled) {
                    log.debug("handleDataAbort isWrite={}, accessSize={}, virtualAddress=0x{}", isWrite, accessSize, java.lang.Long.toHexString(virtualAddress))
                }
                if (eventMemHookNotifier != null) {
                    eventMemHookNotifier!!.notifyDataAbort(isWrite, accessSize, virtualAddress)
                }
            }
            HypervisorCallback.EC_INSNABORT -> {
                if (eventMemHookNotifier != null) {
                    eventMemHookNotifier!!.notifyInsnAbort(virtualAddress)
                }
            }
            else -> {
                log.warn("handleUnknownException ec=0x{}, virtualAddress=0x{}, esr=0x{}, far=0x{}", Integer.toHexString(ec), java.lang.Long.toHexString(virtualAddress), java.lang.Long.toHexString(esr), java.lang.Long.toHexString(far))
            }
        }
    }

    private var lastHitPointAddress: Long = -1

    override fun handleException(esr: Long, far: Long, elr: Long, cpsr: Long): Boolean {
        val ec = ((esr shr 26) and 0x3f).toInt()
        if (log.isDebugEnabled) {
            val x0 = reg_read(Arm64Const.UC_ARM64_REG_X0)
            log.debug("handleException syndrome=0x{}, far=0x{}, elr=0x{}, ec=0x{}, cpsr=0x{}, x0=0x{}", java.lang.Long.toHexString(esr), java.lang.Long.toHexString(far), java.lang.Long.toHexString(elr), Integer.toHexString(ec), java.lang.Long.toHexString(cpsr), java.lang.Long.toHexString(x0.toLong()))
        }
        if (lastHitPointAddress != elr &&
            (ec == HypervisorCallback.EC_SOFTWARESTEP || ec == HypervisorCallback.EC_BREAKPOINT || ec == HypervisorCallback.EC_WATCHPOINT)) {
            while (!visitorStack.isEmpty()) {
                if (visitorStack.pop().onException(hypervisor, ec, elr)) {
                    return true
                }
            }
            lastHitPointAddress = -1
        }
        when (ec) {
            HypervisorCallback.EC_AA64_SVC -> {
                val swi = (esr and 0xffff).toInt()
                callSVC(elr, swi)
                return true
            }
            HypervisorCallback.EC_AA64_BKPT -> {
                val bkpt = (esr and 0xffff).toInt()
                notifyInterruptHook(ARMEmulator.EXCP_BKPT, bkpt)
                return true
            }
            HypervisorCallback.EC_SOFTWARESTEP -> {
                onSoftwareStep(esr, elr, cpsr)
                return true
            }
            HypervisorCallback.EC_BREAKPOINT -> {
                lastHitPointAddress = elr
                handleBreakpoint(esr, elr)
                return true
            }
            HypervisorCallback.EC_WATCHPOINT -> {
                lastHitPointAddress = elr
                onWatchpoint(esr, far, elr)
                return true
            }
            HypervisorCallback.EC_INSNABORT -> {
                return exclusiveMonitorEscaper is CodeHookEscaper &&
                        (exclusiveMonitorEscaper as CodeHookEscaper).onInsnAbort()
            }
            HypervisorCallback.EC_DATAABORT -> {
                return handleDataAbort(ec, esr, far, elr)
            }
            HypervisorCallback.EC_SYSTEMREGISTERTRAP -> {
                return handleSystemRegisterTrap(esr, elr)
            }
            else -> {
                log.warn("handleException ec=0x{}", Integer.toHexString(ec))
                throw UnsupportedOperationException("handleException ec=0x" + Integer.toHexString(ec))
            }
        }
    }

    private fun handleBreakpoint(esr: Long, elr: Long) {
        notifyDebugEvent(esr, elr)
        for (i in breakpoints.indices) {
            val bp = breakpoints[i]
            if (bp != null && bp.getAddress() == elr) {
                hypervisor.disable_hw_breakpoint(i)
                visitorStack.push(ExceptionVisitor.breakRestorerVisitor(bp))
                step()
                break
            }
        }
    }

    private fun handleDataAbort(ec: Int, esr: Long, far: Long, elr: Long): Boolean {
        val isv = (esr and HypervisorCallback.ARM_EL_ISV.toLong()) != 0L
        val isWrite = ((esr shr 6) and 1) != 0L
        val sas = ((esr shr 22) and 3).toInt()
        val dfsc = (esr and 0x3f).toInt()
        val accessSize = if (isv) 1 shl sas else 0
        if (log.isDebugEnabled) {
            val s1ptw = ((esr shr 7) and 1) != 0L
            val len = 1 shl sas
            val srt = ((esr shr 16) and 0x1f).toInt()
            log.debug("handle EC_DATAABORT ec=0x{}, isv={}, isWrite={}, s1ptw={}, len={}, srt={}, dfsc=0x{}, vaddr=0x{}, elr=0x{}", Integer.toHexString(ec), isv, isWrite, s1ptw, len, srt, Integer.toHexString(dfsc), java.lang.Long.toHexString(far), java.lang.Long.toHexString(elr))
        }
        if (eventMemHookNotifier != null) {
            eventMemHookNotifier!!.notifyDataAbort(isWrite, accessSize, far)
        }
        return false
    }

    private fun handleSystemRegisterTrap(esr: Long, elr: Long): Boolean {
        /*
         *  Direction: Indicates the direction of the trapped instruction.
         *  0b0	Write access, including MSR instructions.
         *  0b1	Read access, including MRS instructions.
         */
        val isRead = (esr and 1) != 0L
        val CRm = ((esr ushr 1) and 0xf).toInt()
        val Rt = ((esr ushr 5) and 0x1f).toInt()
        val CRn = ((esr ushr 10) and 0xf).toInt()
        val Op1 = ((esr ushr 14).toInt() and 0x7)
        val Op2 = ((esr ushr 17).toInt() and 0x7)
        val Op0 = ((esr ushr 20).toInt() and 0x3)
        if (isRead) {
            if (CRm == 0 && CRn == 14 && Op1 == 3 && Op0 == 3
                && (Op2 == 1 /* CNTPCT_EL0 */ || Op2 == 2 /* CNTVCT_EL0 */)) {
                if (Rt < 31) {
                    hypervisor.reg_write64(Rt, 0)
                }
                hypervisor.reg_set_elr_el1(elr + 4)
                return true
            }
        }
        throw UnsupportedOperationException("EC_SYSTEMREGISTERTRAP isRead=$isRead, CRm=$CRm, CRn=$CRn, Op1=$Op1, Op2=$Op2, Op0=$Op0")
    }

    private fun step() {
        if (singleStep < 0) {
            singleStep = 0
        }
        hypervisor.enable_single_step(true)
    }

    private fun installWatchpoint(callback: Any, begin: Long, end: Long, userData: Any?, isWrite: Boolean) {
        var freeSlot = -1
        for (i in watchpoints.indices) {
            if (watchpoints[i] != null && watchpoints[i]!!.matches(begin, end, isWrite)) {
                return
            }
            if (freeSlot == -1 && watchpoints[i] == null) {
                freeSlot = i
            }
        }
        if (freeSlot != -1) {
            val wp = HypervisorWatchpoint(callback, begin, end, userData, freeSlot, isWrite)
            wp.install(hypervisor)
            watchpoints[freeSlot] = wp
            return
        }
        throw UnsupportedOperationException("Max WRPs: " + watchpoints.size)
    }

    override fun hook_add_new(callback: ReadHook, begin: Long, end: Long, userData: Any?) {
        installWatchpoint(callback, begin, end, userData, false)
    }

    override fun hook_add_new(callback: WriteHook, begin: Long, end: Long, userData: Any?) {
        installWatchpoint(callback, begin, end, userData, true)
    }

    private var lastWatchpointAddress: Long = -1
    private var lastWatchpointDataAddress: Long = -1

    private fun onWatchpoint(esr: Long, address: Long, elr: Long) {
        val pc = Objects.requireNonNull(VortexdbgPointer.pointer(emulator, elr))
        val code = pc.getByteArray(0, 4)
        val repeatWatchpoint = lastWatchpointAddress == elr
                && lastWatchpointDataAddress == address
                && isLoadExclusiveCode(pc.getInt(0))
        if (!repeatWatchpoint) {
            lastWatchpointAddress = elr
            lastWatchpointDataAddress = address
        }
        val write = ((esr shr 6) and 1) == 1L
        val status = (esr and 0x3f).toInt()
        if (log.isDebugEnabled) {
            val cm = ((esr shr 8) and 1) == 1L
            val wpt = ((esr shr 18) and 0x3f).toInt()
            val wptv = ((esr shr 17) and 1) == 1L
            log.debug("onWatchpoint write={}, address=0x{}, cm={}, wpt={}, wptv={}, status=0x{}", write, java.lang.Long.toHexString(address), cm, wpt, wptv, Integer.toHexString(status))
        }
        val insn = createDisassembler().disasm(code, elr, 1)[0]
        val accessSize = if (write) MEMORY_SIZE_DETECTOR.detectWriteSize(insn) else MEMORY_SIZE_DETECTOR.detectReadSize(insn)
        var hitWp: HypervisorWatchpoint? = null
        for (watchpoint in watchpoints) {
            if (watchpoint != null && watchpoint.contains(address, accessSize, write)) {
                hitWp = watchpoint
                break
            }
        }
        if (hitWp == null) {
            notifyInterruptHook(ARMEmulator.EXCP_BKPT, status)
        } else if (repeatWatchpoint) {
            if (exclusiveMonitorEscaper != null) {
                notifyInterruptHook(ARMEmulator.EXCP_BKPT, status)
            } else {
                exclusiveMonitorEscaper = WatchpointEscaper(hitWp)
                step()
            }
        } else {
            hitWp.onHit(this, address, accessSize, write, insn)
            hypervisor.disable_watchpoint(hitWp.getSlot())
            visitorStack.push(ExceptionVisitor.breakRestorerVisitor(hitWp))
            step()
        }
    }

    private fun isInDebugRange(address: Long): Boolean {
        return debugBegin >= debugEnd || (address >= debugBegin && address < debugEnd)
    }

    private fun notifyDebugEvent(esr: Long, address: Long) {
        if (debugCallback != null && isInDebugRange(address)) {
            debugCallback!!.onBreak(this, address, HypervisorBackend.INS_SIZE, debugUserData)
        } else {
            val status = (esr and 0x3f).toInt()
            notifyInterruptHook(ARMEmulator.EXCP_BKPT, status)
        }
    }

    /**
     * The local exclusive monitor gets cleared on every exception return, that is, on execution of the ERET instruction.
     *
     * from: [xen-arm-software-step-armv8-pc-stuck-on-instruction](https://xen-devel.narkive.com/wQw4F6GV/xen-arm-software-step-armv8-pc-stuck-on-instruction)
     * LDAXR sets the 'exclusive monitor' and STXR only succeeds if the exclusive
     * monitor is still set. If another CPU accesses the memory protected by the
     * exclusive monitor, the monitor is cleared. This is how the spinlock code knows
     * it has to re-read its value and try to take the lock again.
     * Changing exception level also clears the exclusive monitor, so taking
     * single-step exception between a LDAXR/STXR pair means the loop has to be retried.
     */
    private abstract inner class ExclusiveMonitorEscaper {
        private var loadExclusiveAddress: Long = -1
        private var loadExclusiveCount: Int = 0
        private val exclusiveRegionAddressList: MutableSet<Long> = LinkedHashSet()

        private fun resetRegionInfo() {
            loadExclusiveAddress = -1
            loadExclusiveCount = 0
            exclusiveRegionAddressList.clear()
        }

        fun onSoftwareStep(spsr: Long, address: Long) {
            val pointer = VortexdbgPointer.pointer(emulator, address)
            if (pointer == null) {
                hypervisor.reg_set_spsr_el1(spsr or Hypervisor.`PSTATE$SS`)
                return
            }
            updateExclusiveDetection(pointer.getInt(0), address)
            if (loadExclusiveCount >= 4 && address == loadExclusiveAddress) {
                if (tryEscapeExclusiveLoop(spsr, address)) {
                    return
                }
            }
            if (shouldAbandonEscape()) {
                onEscapeSuccess()
                return
            }
            if (notifyCallback(address)) {
                hypervisor.reg_set_spsr_el1(spsr or Hypervisor.`PSTATE$SS`)
            }
        }

        private fun updateExclusiveDetection(asm: Int, address: Long) {
            if (isLoadExclusiveCode(asm)) {
                if (loadExclusiveAddress == address) {
                    loadExclusiveCount++
                } else {
                    loadExclusiveCount = 0
                }
                loadExclusiveAddress = address
            } else {
                if (loadExclusiveAddress == -1L) {
                    resetRegionInfo()
                }
            }
            if (loadExclusiveCount >= 2) {
                exclusiveRegionAddressList.add(address)
            }
        }

        private fun tryEscapeExclusiveLoop(spsr: Long, address: Long): Boolean {
            var foundAddress: Long = 0
            for (pc in exclusiveRegionAddressList) {
                val ptr = Objects.requireNonNull(VortexdbgPointer.pointer(emulator, pc))
                val code = ptr.getByteArray(0, 4)
                val instruction = createDisassembler().disasm(code, pc, 1)[0]
                when (instruction.getMnemonic()) {
                    "stxr", "stlxr", "stxp", "stlxp", "stxrh", "stlxrh", "stxrb", "stlxrb" ->
                        foundAddress = pc
                }
            }
            if (foundAddress == 0L) {
                if (log.isWarnEnabled) {
                    val builder = StringBuilder()
                    for (pc in exclusiveRegionAddressList) {
                        val ptr = Objects.requireNonNull(VortexdbgPointer.pointer(emulator, pc))
                        val code = ptr.getByteArray(0, 4)
                        val instruction = createDisassembler().disasm(code, pc, 1)[0]
                        builder.append(String.format("0x%x: %s%n", instruction.getAddress(), instruction))
                    }
                    log.warn("No store-exclusive found in exclusive region, skipping escape: \n{}", builder)
                }
                resetRegionInfo()
                return false
            }
            resetRegionInfo()
            val breakAddress = foundAddress + 4
            for (i in breakpoints.indices) {
                if (breakpoints[i] == null) {
                    val n = i
                    visitorStack.push(object : ExceptionVisitor() {
                        override fun onException(hypervisor: Hypervisor, ec: Int, address: Long): Boolean {
                            if (ec == HypervisorCallback.EC_BREAKPOINT) {
                                notifyCallback(address)
                            }
                            breakpoints[n] = null
                            hypervisor.disable_hw_breakpoint(n)
                            onEscapeSuccess()
                            return true
                        }
                    })
                    notifyCallback(address)
                    val bp = HypervisorBreakPoint(n, breakAddress, null)
                    bp.install(hypervisor)
                    breakpoints[n] = bp
                    hypervisor.enable_single_step(false)
                    hypervisor.reg_set_spsr_el1(spsr or Hypervisor.`PSTATE$SS`)
                    return true
                }
            }
            log.warn("No free breakpoint slot for exclusive monitor escape, max BKPs: {}", breakpoints.size)
            resetRegionInfo()
            return false
        }

        /**
         * @return true to continue single-stepping, false to fast-forward (skip PSTATE.SS)
         */
        abstract fun notifyCallback(address: Long): Boolean
        abstract fun onEscapeSuccess()
        open fun shouldAbandonEscape(): Boolean { return false }
    }

    private inner class CodeHookEscaper(
        private val callback: CodeHook,
        private val begin: Long,
        private val end: Long,
        private val user: Any?
    ) : ExclusiveMonitorEscaper(), UnHook {
        private var reentrySlot: Int = -1
        private var savedPagePerms: MutableMap<Long, Int>? = null

        private fun isInRange(address: Long): Boolean {
            return begin >= end || (address >= begin && address < end)
        }

        override fun onEscapeSuccess() {
            step()
        }

        override fun notifyCallback(address: Long): Boolean {
            if (isInRange(address)) {
                callback.hook(this@HypervisorBackend64, address, 4, user)
                return true
            }
            return !tryFastForward()
        }

        private fun tryFastForward(): Boolean {
            if (begin >= end) {
                return false
            }
            val lr = reg_read(Arm64Const.UC_ARM64_REG_LR).toLong()
            if (lr >= begin && lr < end) {
                return tryFastForwardToAddress(lr)
            }
            if (tryFastForwardWithPageProtection()) {
                return true
            }
            return tryFastForwardToAddress(begin)
        }

        private fun tryFastForwardToAddress(target: Long): Boolean {
            for (i in breakpoints.indices) {
                if (breakpoints[i] == null) {
                    val n = i
                    reentrySlot = n
                    visitorStack.push(object : ExceptionVisitor() {
                        override fun onException(hypervisor: Hypervisor, ec: Int, address: Long): Boolean {
                            breakpoints[n] = null
                            hypervisor.disable_hw_breakpoint(n)
                            reentrySlot = -1
                            step()
                            return ec == HypervisorCallback.EC_BREAKPOINT
                        }
                    })
                    val bp = HypervisorBreakPoint(n, target, null)
                    bp.install(hypervisor)
                    breakpoints[n] = bp
                    hypervisor.enable_single_step(false)
                    return true
                }
            }
            return false
        }

        private fun tryFastForwardWithPageProtection(): Boolean {
            val ps = getPageSize()
            val pageMask = ps - 1L
            val interiorBegin = (begin + pageMask) and pageMask.inv()
            val interiorEnd = end and pageMask.inv()
            if (interiorBegin >= interiorEnd) {
                return false
            }
            val permsMap: MutableMap<Long, Int> = LinkedHashMap()
            var addr = interiorBegin
            while (addr < interiorEnd) {
                val perms = hypervisor.get_page_perms(addr)
                if (perms < 0 || (perms and UnicornConst.UC_PROT_EXEC) == 0) {
                    addr += ps
                    continue
                }
                permsMap[addr] = perms
                addr += ps
            }
            if (permsMap.isEmpty()) {
                return false
            }
            savedPagePerms = permsMap
            for (entry in permsMap.entries) {
                hypervisor.mem_protect(entry.key, ps.toLong(), entry.value and UnicornConst.UC_PROT_EXEC.inv())
            }
            hypervisor.enable_single_step(false)
            return true
        }

        fun onInsnAbort(): Boolean {
            if (savedPagePerms == null || savedPagePerms!!.isEmpty()) {
                return false
            }
            restorePageProtection()
            step()
            return true
        }

        private fun restorePageProtection() {
            if (savedPagePerms != null) {
                val ps = getPageSize()
                for (entry in savedPagePerms!!.entries) {
                    hypervisor.mem_protect(entry.key, ps.toLong(), entry.value)
                }
                savedPagePerms = null
            }
        }

        override fun unhook() {
            if (reentrySlot >= 0) {
                breakpoints[reentrySlot] = null
                hypervisor.disable_hw_breakpoint(reentrySlot)
                reentrySlot = -1
            }
            restorePageProtection()
            exclusiveMonitorEscaper = null
            hypervisor.enable_single_step(false)
        }
    }

    private inner class WatchpointEscaper(private val wp: HypervisorWatchpoint) : ExclusiveMonitorEscaper() {
        private var stepCount: Int = 0

        init {
            hypervisor.disable_watchpoint(wp.getSlot())
        }

        override fun notifyCallback(address: Long): Boolean {
            return true
        }

        override fun shouldAbandonEscape(): Boolean {
            return ++stepCount > MAX_ESCAPE_STEPS
        }

        override fun onEscapeSuccess() {
            hypervisor.enable_single_step(false)
            wp.install(hypervisor)
            lastWatchpointAddress = -1
            lastWatchpointDataAddress = -1
            exclusiveMonitorEscaper = null
        }
    }

    private var exclusiveMonitorEscaper: ExclusiveMonitorEscaper? = null

    override fun hook_add_new(callback: CodeHook, begin: Long, end: Long, userData: Any?) {
        if (exclusiveMonitorEscaper != null) {
            throw IllegalStateException()
        }
        val escaper = CodeHookEscaper(callback, begin, end, userData)
        this.exclusiveMonitorEscaper = escaper
        step()
        callback.onAttach(escaper)
    }

    private fun onSoftwareStep(esr: Long, address: Long, spsr: Long) {
        if (exclusiveMonitorEscaper != null) {
            exclusiveMonitorEscaper!!.onSoftwareStep(spsr, address)
            return
        }

        if (singleStep <= 0) {
            hypervisor.enable_single_step(false)
            return
        }
        if (--singleStep == 0) {
            hypervisor.enable_single_step(false)
            notifyDebugEvent(esr, address)
        } else {
            hypervisor.reg_set_spsr_el1(spsr or Hypervisor.`PSTATE$SS`)
        }
    }

    private fun handleCommRead(vaddr: Long, elr: Long, accessSize: Int): Boolean {
        val pc = Objects.requireNonNull(VortexdbgPointer.pointer(emulator, elr))
        val code = pc.getByteArray(0, 4)
        val insn = createDisassembler().disasm(code, elr, 1)[0]
        if (log.isDebugEnabled) {
            log.debug("handleCommRead vaddr=0x{}, elr=0x{}, asm={}", java.lang.Long.toHexString(vaddr), java.lang.Long.toHexString(elr), insn)
        }
        val opInfo = insn.getOperands() as OpInfo
        if (opInfo.isUpdateFlags() || opInfo.isWriteBack() || !insn.getMnemonic().startsWith("ldr") || vaddr < _COMM_PAGE64_BASE_ADDRESS) {
            if (eventMemHookNotifier != null) {
                eventMemHookNotifier!!.notifyDataAbort(false, accessSize, vaddr)
            }
            return false
        }
        val op = opInfo.getOperands()
        val offset = (vaddr - _COMM_PAGE64_BASE_ADDRESS).toInt()
        return when (offset) {
            0x38, // uint64_t max memory size
            0x40,
            0x48,
            0x4c,
            0x50,
            0x58,
            0x60,
            0x64,
            0x90 ->
                emulateCommPageLdr(insn, op, elr, 0)
            0x22, // uint8_t number of configured CPUs
            0x34, // uint8_t number of active CPUs (hw.activecpu)
            0x35, // uint8_t number of physical CPUs (hw.physicalcpu_max)
            0x36 -> // uint8_t number of logical CPUs (hw.logicalcpu_max)
                emulateCommPageLdr(insn, op, elr, 1)
            else ->
                throw UnsupportedOperationException("vaddr=0x" + java.lang.Long.toHexString(vaddr) + ", offset=0x" + java.lang.Long.toHexString(offset.toLong()))
        }
    }

    private fun emulateCommPageLdr(insn: Instruction, op: Array<Operand>, elr: Long, `val`: Number): Boolean {
        val value = op[0].getValue()
        reg_write(insn.mapToUnicornReg(value.getReg()), `val`)
        hypervisor.reg_set_elr_el1(elr + 4)
        return true
    }

    override fun enableVFP() {
        enableVFP(true)
    }

    override fun switchUserMode() {
    }

    override fun reg_write(regId: Int, value: Number) {
        try {
            if (regId >= Arm64Const.UC_ARM64_REG_X0 && regId <= Arm64Const.UC_ARM64_REG_X28) {
                hypervisor.reg_write64(regId - Arm64Const.UC_ARM64_REG_X0, value.toLong())
            } else if (regId >= Arm64Const.UC_ARM64_REG_W0 && regId <= Arm64Const.UC_ARM64_REG_W30) {
                hypervisor.reg_write64(regId - Arm64Const.UC_ARM64_REG_W0, value.toLong() and 0xFFFFFFFFL)
            } else {
                when (regId) {
                    Arm64Const.UC_ARM64_REG_SP ->
                        hypervisor.reg_set_sp64(value.toLong())
                    Arm64Const.UC_ARM64_REG_X29 ->
                        hypervisor.reg_write64(29, value.toLong())
                    Arm64Const.UC_ARM64_REG_LR ->
                        hypervisor.reg_write64(30, value.toLong())
                    Arm64Const.UC_ARM64_REG_TPIDR_EL0 ->
                        hypervisor.reg_set_tpidr_el0(value.toLong())
                    Arm64Const.UC_ARM64_REG_TPIDRRO_EL0 ->
                        hypervisor.reg_set_tpidrro_el0(value.toLong())
                    Arm64Const.UC_ARM64_REG_NZCV ->
                        hypervisor.reg_set_nzcv(value.toLong())
                    Arm64Const.UC_ARM64_REG_CPACR_EL1 ->
                        hypervisor.reg_set_cpacr_el1(value.toLong())
                    else ->
                        throw HypervisorException("regId=$regId")
                }
            }
        } catch (e: HypervisorException) {
            throw BackendException(e)
        }
    }

    override fun reg_read(regId: Int): Number {
        try {
            if (regId >= Arm64Const.UC_ARM64_REG_X0 && regId <= Arm64Const.UC_ARM64_REG_X28) {
                return hypervisor.reg_read64(regId - Arm64Const.UC_ARM64_REG_X0)
            } else if (regId >= Arm64Const.UC_ARM64_REG_W0 && regId <= Arm64Const.UC_ARM64_REG_W30) {
                return hypervisor.reg_read64(regId - Arm64Const.UC_ARM64_REG_W0) and 0xffffffffL
            } else {
                return when (regId) {
                    Arm64Const.UC_ARM64_REG_SP ->
                        hypervisor.reg_read_sp64()
                    Arm64Const.UC_ARM64_REG_X29 ->
                        hypervisor.reg_read64(29)
                    Arm64Const.UC_ARM64_REG_LR ->
                        hypervisor.reg_read64(30)
                    Arm64Const.UC_ARM64_REG_PC ->
                        hypervisor.reg_read_pc64()
                    Arm64Const.UC_ARM64_REG_NZCV ->
                        hypervisor.reg_read_nzcv()
                    Arm64Const.UC_ARM64_REG_CPACR_EL1 ->
                        hypervisor.reg_read_cpacr_el1()
                    else ->
                        throw HypervisorException("regId=$regId")
                }
            }
        } catch (e: HypervisorException) {
            throw BackendException(e)
        }
    }

    override fun addSoftBreakPoint(address: Long, svcNumber: Int, thumb: Boolean): ByteArray {
        val encoded = getKeystone().assemble("brk #$svcNumber")
        return encoded.getMachineCode()
    }

    @Synchronized
    override fun destroy() {
        super.destroy()

        IOUtils.close(disassembler)
        disassembler = null

        if (keystone != null) {
            keystone!!.close()
            keystone = null
        }
    }

    override fun context_alloc(): Long {
        return HypervisorFactory.context_alloc()
    }

    override fun context_save(context: Long) {
        hypervisor.context_save(context)
    }

    override fun context_restore(context: Long) {
        hypervisor.context_restore(context)
    }

    override fun context_free(context: Long) {
        HypervisorFactory.free(context)
    }

    override fun getCpuFeatures(): Map<String, Int> {
        return CPU_FEATURES
    }

    companion object {

        private val log: Logger = LoggerFactory.getLogger(HypervisorBackend64::class.java)

        private val MEMORY_SIZE_DETECTOR: MemorySizeDetector = SimpleMemorySizeDetector()

        private const val MAX_ESCAPE_STEPS = 200

        private val DARWIN_KERNEL_BASE = 0xffffff80001f0000uL.toLong()
        private val _COMM_PAGE64_BASE_ADDRESS = DARWIN_KERNEL_BASE + 0xc000L /* In TTBR0 */

        private fun isLoadExclusiveCode(asm: Int): Boolean {
            if ((asm and 0xbffffc00.toInt()) == 0x885ffc00.toInt()) { // ldaxr
                return true
            }
            if ((asm and 0xbffffc00.toInt()) == 0x885f7c00.toInt()) { // ldxr
                return true
            }
            if ((asm and 0xbfff8000.toInt()) == 0x887f8000.toInt()) { // ldaxp
                return true
            }
            if ((asm and 0xbfff8000.toInt()) == 0x887f0000.toInt()) { // ldxp
                return true
            }
            if ((asm and 0xfffffc00.toInt()) == 0x485ffc00) { // ldaxrh
                return true
            }
            if ((asm and 0xfffffc00.toInt()) == 0x485f7c00) { // ldxrh
                return true
            }
            if ((asm and 0xfffffc00.toInt()) == 0x085ffc00) { // ldaxrb
                return true
            }
            return (asm and 0xfffffc00.toInt()) == 0x085f7c00 // ldxrb
        }

        private val CPU_FEATURE_KEYS = arrayOf(
            "floatingpoint",
            "neon",
            "neon_fp16",
            "neon_hpfp",
            "arm64",
            "armv8_crc32",
            "armv8_1_atomics",
            "armv8_2_fhm",
            "armv8_2_sha3",
            "armv8_2_sha512",
            "armv8_3_compnum",
            "armv8_gpi",
            "ucnormal_mem",
            "arm.AdvSIMD",
            "arm.AdvSIMD_HPFPCvt",
            "arm.FP_SyncExceptions",
            "arm.FEAT_AES",
            "arm.FEAT_AFP",
            "arm.FEAT_BF16",
            "arm.FEAT_BTI",
            "arm.FEAT_CRC32",
            "arm.FEAT_CSSC",
            "arm.FEAT_CSV2",
            "arm.FEAT_CSV3",
            "arm.FEAT_DIT",
            "arm.FEAT_DotProd",
            "arm.FEAT_DPB",
            "arm.FEAT_DPB2",
            "arm.FEAT_EBF16",
            "arm.FEAT_ECV",
            "arm.FEAT_FCMA",
            "arm.FEAT_FHM",
            "arm.FEAT_FlagM",
            "arm.FEAT_FlagM2",
            "arm.FEAT_FP16",
            "arm.FEAT_FPAC",
            "arm.FEAT_FPACCOMBINE",
            "arm.FEAT_FRINTTS",
            "arm.FEAT_HBC",
            "arm.FEAT_I8MM",
            "arm.FEAT_JSCVT",
            "arm.FEAT_LRCPC",
            "arm.FEAT_LRCPC2",
            "arm.FEAT_LSE",
            "arm.FEAT_LSE2",
            "arm.FEAT_PACIMP",
            "arm.FEAT_PAuth",
            "arm.FEAT_PAuth2",
            "arm.FEAT_PMULL",
            "arm.FEAT_RDM",
            "arm.FEAT_RPRES",
            "arm.FEAT_SB",
            "arm.FEAT_SHA1",
            "arm.FEAT_SHA256",
            "arm.FEAT_SHA3",
            "arm.FEAT_SHA512",
            "arm.FEAT_SME",
            "arm.FEAT_SME2",
            "arm.FEAT_SME_F64F64",
            "arm.FEAT_SME_I16I64",
            "arm.FEAT_SPECRES",
            "arm.FEAT_SPECRES2",
            "arm.FEAT_SSBS",
            "arm.FEAT_WFxT",
        )

        private val CPU_FEATURES: Map<String, Int>

        init {
            val map = HashMap<String, Int>()
            for (key in CPU_FEATURE_KEYS) {
                val v = HypervisorFactory.sysctlInt("hw.optional.$key")
                if (v >= 0) {
                    map[key] = v
                }
            }
            CPU_FEATURES = Collections.unmodifiableMap(map)
        }
    }
}
