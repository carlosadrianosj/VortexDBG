package com.vortexdbg.debugger.ida

import com.vortexdbg.Emulator
import com.vortexdbg.Module
import com.vortexdbg.ModuleListener
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.arm.backend.BackendException
import com.vortexdbg.arm.context.Arm32RegisterContext
import com.vortexdbg.arm.context.Arm64RegisterContext
import com.vortexdbg.debugger.AbstractDebugServer
import com.vortexdbg.debugger.DebugServer
import com.vortexdbg.debugger.ida.event.AttachExecutableEvent
import com.vortexdbg.debugger.ida.event.DetachEvent
import com.vortexdbg.debugger.ida.event.LoadExecutableEvent
import com.vortexdbg.debugger.ida.event.LoadModuleEvent
import com.vortexdbg.memory.MemRegion
import com.vortexdbg.memory.Memory
import com.vortexdbg.memory.SvcMemory
import com.vortexdbg.utils.Inspector
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import unicorn.Arm64Const
import unicorn.ArmConst
import unicorn.UnicornConst

import java.nio.ByteBuffer
import java.util.ArrayList
import java.util.Arrays
import java.util.Collections
import java.util.Queue
import java.util.concurrent.LinkedBlockingQueue

class AndroidServer(emulator: Emulator<*>, private val protocolVersion: Byte) :
    AbstractDebugServer(emulator), ModuleListener {

    init {
        emulator.getMemory().addModuleListener(this)
    }

    private fun notifyDebugEvent() {
        sendAck(0x1.toByte())
    }

    private fun sendAck(vararg bytes: Byte) {
        sendPacket(0x0, bytes)
    }

    private fun sendPacket(type: Int, data: ByteArray) {
        val buffer = ByteBuffer.allocate(data.size + 5)
        buffer.putInt(data.size)
        buffer.put(type.toByte())
        buffer.put(data)
        sendData(buffer.array())
    }

    private fun sendProcessWillTerminated(exitStatus: Int) {
        val buffer = ByteBuffer.allocate(0x20)
        buffer.put(Utils.pack_dd(0x2))
        buffer.put(Utils.pack_dd(emulator.getPid().toLong()))
        buffer.put(Utils.pack_dd(emulator.getPid().toLong()))
        buffer.put(Utils.pack_dd(0x0))
        buffer.put(Utils.pack_dd(0x1))
        buffer.put(Utils.pack_dd(0x0))
        buffer.put(Utils.pack_dd(exitStatus.toLong()))
        sendPacket(0x4, Utils.flipBuffer(buffer))
    }

    override fun onServerStart() {
    }

    override fun onLoaded(emulator: Emulator<*>, module: Module) {
        if (log.isDebugEnabled) {
            log.debug("onLoaded module={}", module)
        }
    }

    override fun processInput(input: ByteBuffer) {
        input.flip()

        while (input.hasRemaining()) {
            val length = input.getInt()
            val type = input.get().toInt() and 0xff
            if (length > input.remaining()) {
                throw IllegalStateException("processInput length=" + length + ", type=0x" + Integer.toHexString(type))
            }

            val data = ByteArray(length)
            input.get(data)
            processCommand(type, data)
        }

        input.clear()
    }

    private fun processCommand(type: Int, data: ByteArray) {
        if (log.isDebugEnabled) {
            log.debug(Inspector.inspectString(data, "processCommand type=0x" + Integer.toHexString(type)))
        }

        if (type == 0x0 && data.size == 0) { // ack
            return
        }

        val buffer = ByteBuffer.wrap(data)
        when (type) {
            0x0 -> notifyDebugEvent()
            0x5 -> {
                ackDebuggerEvent()
            }
            0xa -> {
                val value = Utils.unpack_dd(buffer)
                val b = Utils.unpack_dd(buffer)
                if (log.isDebugEnabled) {
                    log.debug("processCommand value=0x{}, b={}", java.lang.Long.toHexString(value), b)
                }
                sendAck(0x5.toByte())
            }
            0xb -> {
                notifyDebuggerDetached()
            }
            0xc -> {
                requestRunningProcesses()
            }
            0xe -> requestTerminateProcess()
            0xf -> {
                requestAttach(buffer)
            }
            0x10 -> requestDetach()
            0x11 -> syncDebuggerEvent(buffer)
            0x12 -> requestPauseProcess()
            0x13 -> requestSymbols(buffer)
            0x14 -> onDebuggerEvent(buffer)
            0x18 -> requestMemoryRegions(buffer)
            0x19 -> requestReadMemory(buffer)
            0x1b -> requestBreakPointAction(buffer)
            0x1f -> requestReadRegisters(buffer)
            0x20 -> requestResetProgramCounter(buffer)
            0x22 -> parseSignal(buffer)
            else -> {
                log.warn(Inspector.inspectString(data, "Not handler command type=0x" + Integer.toHexString(type)))
                sendAck()
            }
        }
    }

    private fun requestResetProgramCounter(buffer: ByteBuffer) {
        val tid = Utils.unpack_dd(buffer)
        val b1 = Utils.unpack_dd(buffer)
        val b2 = Utils.unpack_dd(buffer)
        val pc = Utils.unpack_dq(buffer)
        if (log.isDebugEnabled) {
            log.debug("requestResetProgramCounter tid={}, b1={}, b2={}, pc=0x{}", tid, b1, b2, java.lang.Long.toHexString(pc))
        }
        notifyDebugEvent()
    }

    private fun requestPauseProcess() {
        if (log.isDebugEnabled) {
            log.debug("requestPauseProcess")
        }
        sendAck()
    }

    private var processExitStatus: Int = 0

    private fun ackDebuggerEvent() {
        if (log.isDebugEnabled) {
            log.debug("ackDebuggerEvent")
        }
    }

    private fun notifyDebuggerDetached() {
        if (log.isDebugEnabled) {
            log.debug("notifyDebuggerDetached")
        }

        sendAck()
        shutdownServer()
        if (processExitStatus != 0) {
            System.exit(processExitStatus)
        } else {
            resumeRun()
        }
    }

    private fun requestBreakPointAction(buffer: ByteBuffer) {
        val action = Utils.unpack_dd(buffer) // 0表示删除断点，1表示设置断点
        if (action == 0L) {
            val b2 = Utils.unpack_dd(buffer)
            var address = Utils.unpack_dq(buffer)
            val size = Utils.unpack_dd(buffer)
            val backup = ByteArray(size.toInt())
            buffer.get(backup)
            val b3 = Utils.unpack_dd(buffer)
            val value = Utils.unpack_dq(buffer)

            if (log.isDebugEnabled) {
                log.debug(Inspector.inspectString(backup, "requestBreakPointAction action=" + action + ", b2=" + b2 + ", address=0x" + java.lang.Long.toHexString(address) +
                        ", size=" + size + ", b3=" + b3 + ", value=0x" + java.lang.Long.toHexString(value)))
            }

            address -= 1
            removeBreakPoint(address)

            val newBuf = ByteBuffer.allocate(0x10)
            newBuf.put(Utils.pack_dd(0x1))
            newBuf.put(Utils.pack_dd(0x1))
            newBuf.put(Utils.pack_dd(0x0))
            sendAck(*Utils.flipBuffer(newBuf))
        } else if (action == 1L) {
            val b2 = Utils.unpack_dd(buffer)
            var address = Utils.unpack_dq(buffer)
            val b3 = Utils.unpack_dd(buffer)
            val size = Utils.unpack_dd(buffer)
            val value = Utils.unpack_dq(buffer)

            if (log.isDebugEnabled) {
                log.debug("requestBreakPointAction action={}, b2={}, address=0x{}, b3={}, size={}, value=0x{}", action, b2, java.lang.Long.toHexString(address), b3, size, java.lang.Long.toHexString(value))
            }

            address -= 1
            addBreakPoint(address)

            val newBuf = ByteBuffer.allocate(0x10)
            newBuf.put(Utils.pack_dd(0x1))
            newBuf.put(Utils.pack_dd(0x1))
            newBuf.put(Utils.pack_dd(0x0))
            val backend = emulator.getBackend()
            val data = backend.mem_read(address and (1L.inv()), size)
            newBuf.put(Utils.pack_dd(data.size.toLong()))
            newBuf.put(data)
            sendAck(*Utils.flipBuffer(newBuf))
        } else {
            throw UnsupportedOperationException("action=$action")
        }
    }

    private fun requestTerminateProcess() {
        if (log.isDebugEnabled) {
            log.debug("requestTerminateProcess")
        }

        notifyDebugEvent()
        sendProcessWillTerminated(TERMINATE_PROCESS_STATUS)
    }

    private fun requestDetach() {
        if (log.isDebugEnabled) {
            log.debug("requestDetach")
        }

        eventQueue.add(DetachEvent())
        notifyDebugEvent()
    }

    private fun requestMemoryRegions(buffer: ByteBuffer) {
        if (log.isDebugEnabled) {
            log.debug("requestMemoryRegions buffer={}", buffer)
        }

        val memory = emulator.getMemory()
        val modules = memory.getLoadedModules()
        val list = ArrayList<MemRegion>(modules.size)
        for (module in modules) {
            list.addAll(module.getRegions())
        }
        val svcMemory = emulator.getSvcMemory()
        list.add(MemRegion.create(svcMemory.getBase(), svcMemory.getSize(), UnicornConst.UC_PROT_READ or UnicornConst.UC_PROT_EXEC, "[svc]"))
        list.add(MemRegion.create(memory.getStackBase() - memory.getStackSize(), memory.getStackSize(), UnicornConst.UC_PROT_READ or UnicornConst.UC_PROT_WRITE, "[stack]"))
        Collections.sort(list)

        val newBuf = ByteBuffer.allocate(0x100 * list.size)
        newBuf.put(Utils.pack_dd(0x5))
        newBuf.put(Utils.pack_dd(list.size.toLong()))
        for (region in list) {
            newBuf.put(Utils.pack_dq(0x1))
            newBuf.put(Utils.pack_dq(region.begin + 1))
            val size = region.end - region.begin
            newBuf.put(Utils.pack_dq(size + 1))
            var mask = 1 shl 4 // data
            if ((region.perms and UnicornConst.UC_PROT_READ) != 0) {
                mask = mask or (1 shl 2)
            }
            if ((region.perms and UnicornConst.UC_PROT_WRITE) != 0) {
                mask = mask or (1 shl 1)
            }
            if ((region.perms and UnicornConst.UC_PROT_EXEC) != 0) {
                mask = mask or 1
            }
            newBuf.put(mask.toByte())
            Utils.writeCString(newBuf, region.getName())
            newBuf.put(0.toByte())
        }
        sendAck(*Utils.flipBuffer(newBuf))
    }

    private fun requestReadRegisters(buffer: ByteBuffer) {
        val tid = Utils.unpack_dd(buffer)
        val b = Utils.unpack_dd(buffer)
        if (log.isDebugEnabled) {
            log.debug("requestReadRegisters tid=0x{}, b={}", java.lang.Long.toHexString(tid), b)
        }

        if (emulator.is32Bit()) {
            val context = emulator.getContext<Arm32RegisterContext>()
            val newBuf = ByteBuffer.allocate(0x100)
            newBuf.put(Utils.pack_dd(0x1))
            for (value in Arrays.asList(context.getR0Int(),
                    context.getR1Int(), context.getR2Int(),
                    context.getR3Int(), context.getR4Int(),
                    context.getR5Int(), context.getR6Int(),
                    context.getR7Int(), context.getR8Int(),
                    context.getR9Int(), context.getR10Int(),
                    context.getIntByReg(ArmConst.UC_ARM_REG_FP),
                    context.getIntByReg(ArmConst.UC_ARM_REG_IP),
                    context.getIntByReg(ArmConst.UC_ARM_REG_SP),
                    context.getIntByReg(ArmConst.UC_ARM_REG_LR),
                    context.getIntByReg(ArmConst.UC_ARM_REG_PC),
                    context.getIntByReg(ArmConst.UC_ARM_REG_CPSR))) {
                newBuf.put(Utils.pack_dd(0x1))
                newBuf.put(Utils.pack_dq((value.toLong() and 0xffffffffL) + 1))
            }
            sendAck(*Utils.flipBuffer(newBuf))
        } else {
            val context = emulator.getContext<Arm64RegisterContext>()
            val newBuf = ByteBuffer.allocate(0x200)
            newBuf.put(Utils.pack_dd(0x1))
            for (i in 0 until 29) {
                val regId = Arm64Const.UC_ARM64_REG_X0 + i
                newBuf.put(Utils.pack_dd(0x1))
                newBuf.put(Utils.pack_dq(context.getLongByReg(regId) + 1))
            }
            for (value in Arrays.asList(context.getLongByReg(Arm64Const.UC_ARM64_REG_X29),
                    context.getLongByReg(Arm64Const.UC_ARM64_REG_X30),
                    context.getLongByReg(Arm64Const.UC_ARM64_REG_SP),
                    context.getLongByReg(Arm64Const.UC_ARM64_REG_PC),
                    context.getLongByReg(Arm64Const.UC_ARM64_REG_NZCV))) {
                newBuf.put(Utils.pack_dd(0x1))
                newBuf.put(Utils.pack_dq(value + 1))
            }
            sendAck(*Utils.flipBuffer(newBuf))
        }
    }

    private fun requestSymbols(buffer: ByteBuffer) {
        if (log.isDebugEnabled) {
            log.debug("requestSymbols buffer={}", buffer)
        }
        sendAck()
    }

    private fun requestReadMemory(buffer: ByteBuffer) {
        val address = Utils.unpack_dq(buffer)
        val size = Utils.unpack_dd(buffer)
        if (log.isDebugEnabled) {
            log.debug("requestReadMemory address=0x{}, size={}", java.lang.Long.toHexString(address), size)
        }
        try {
            val backend = emulator.getBackend()
            val data = backend.mem_read(address - 1, size)
            val newBuf = ByteBuffer.allocate(data.size + 0x10)
            newBuf.put(Utils.pack_dd(size))
            newBuf.put(data)
            sendAck(*Utils.flipBuffer(newBuf))
        } catch (e: BackendException) {
            if (log.isDebugEnabled) {
                log.debug("read memory failed: address=0x{}", java.lang.Long.toHexString(address), e)
            }
            sendAck()
        }
    }

    private fun parseSignal(buffer: ByteBuffer) {
        val size = Utils.unpack_dd(buffer)
        for (i in 0 until size) {
            val index = Utils.unpack_dd(buffer)
            val mask = Utils.unpack_dd(buffer)
            val sig = Utils.readCString(buffer)
            val desc = Utils.readCString(buffer)
            if (log.isDebugEnabled) {
                log.debug("signal index={}, mask=0x{}, sig={}, desc={}", index, java.lang.Long.toHexString(mask), sig, desc)
            }
        }
        sendAck()
    }

    private val eventQueue: Queue<DebuggerEvent> = LinkedBlockingQueue()

    private fun syncDebuggerEvent(buffer: ByteBuffer) {
        val b = Utils.unpack_dd(buffer)
        if (log.isDebugEnabled) {
            log.debug("syncDebuggerEvent b={}", b)
        }

        val event = eventQueue.poll()
        if (event == null) {
            sendAck(0x0.toByte())
        } else {
            val packet = event.pack(emulator)
            sendAck(*packet)
        }
    }

    private fun onDebuggerEvent(buffer: ByteBuffer) {
        val type = Utils.unpack_dd(buffer).toInt()
        when (type) {
            0x1, 0x400 -> notifyProcessEvent(buffer, type)
            0x10 -> notifyProcessSingleStep(buffer)
            0x2 -> notifyProcessExit(buffer)
            0x80 -> notifyLoadModule(buffer)
            0x800 -> notifyProcessStatus(buffer)
            else -> log.warn("onDebuggerEvent type=0x{}", Integer.toHexString(type))
        }
        notifyDebugEvent()
    }

    private fun notifyProcessSingleStep(buffer: ByteBuffer) {
        val pid = Utils.unpack_dd(buffer)
        val tid = Utils.unpack_dd(buffer)
        val pc = Utils.unpack_dq(buffer)
        if (log.isDebugEnabled) {
            log.debug("notifyProcessSingleStep pid={}, tid={}, pc=0x{}", pid, tid, java.lang.Long.toHexString(pc))
        }

        resumeRun()
    }

    private fun notifyProcessStatus(buffer: ByteBuffer) {
        val pid = Utils.unpack_dd(buffer)
        val tid = Utils.unpack_dd(buffer)
        val b1 = Utils.unpack_dd(buffer)
        val b2 = Utils.unpack_dd(buffer)
        val b3 = Utils.unpack_dd(buffer)
        if (log.isDebugEnabled) {
            log.debug("notifyProcessStatus pid={}, tid={}, b1={}, b2={}, b3={}", pid, tid, b1, b2, b3)
        }
    }

    private fun notifyProcessExit(buffer: ByteBuffer) {
        val pid = Utils.unpack_dd(buffer)
        val tid = Utils.unpack_dd(buffer)
        val b1 = Utils.unpack_dd(buffer)
        val b2 = Utils.unpack_dd(buffer)
        val b3 = Utils.unpack_dd(buffer)
        val exitStatus = Utils.unpack_dd(buffer)
        if (log.isDebugEnabled) {
            log.debug("notifyProcessExit pid={}, tid={}, b1={}, b2={}, b3={}, exitStatus={}", pid, tid, b1, b2, b3, exitStatus)
        }
        this.processExitStatus = exitStatus.toInt()
    }

    /**
     * @param type 0x1表示attach成功，0x400表示要求继续执行
     */
    private fun notifyProcessEvent(buffer: ByteBuffer, type: Int) {
        val pid = Utils.unpack_dd(buffer)
        val tid = Utils.unpack_dd(buffer)
        val pc = Utils.unpack_dq(buffer)
        val b2 = Utils.unpack_dd(buffer)
        val executable = Utils.readCString(buffer)
        val base = Utils.unpack_dq(buffer)
        val size = Utils.unpack_dq(buffer)
        val test = Utils.unpack_dq(buffer)
        if (log.isDebugEnabled) {
            log.debug("notifyProcessEvent type=0x{}, pid={}, tid={}, pc=0x{}, b2={}, executable={}, base=0x{}, size=0x{}, test=0x{}", Integer.toHexString(type), pid, tid, java.lang.Long.toHexString(pc), b2, executable, java.lang.Long.toHexString(base), java.lang.Long.toHexString(size), java.lang.Long.toHexString(test))
        }

        if (type == 0x400) {
            resumeRun()
        }
    }

    private fun notifyLoadModule(buffer: ByteBuffer) {
        val pid = Utils.unpack_dd(buffer)
        val tid = Utils.unpack_dd(buffer)
        val address = Utils.unpack_dq(buffer)
        val s1 = Utils.unpack_dd(buffer)
        val path = Utils.readCString(buffer)
        val base = Utils.unpack_dq(buffer)
        val size = Utils.unpack_dq(buffer)
        val l1 = Utils.unpack_dq(buffer)
        if (log.isDebugEnabled) {
            log.debug("notifyLoadModule pid={}, tid={}, address=0x{}, s1={}, path={}, base=0x{}, size=0x{}, l1=0x{}", pid, tid, java.lang.Long.toHexString(address), s1, path, java.lang.Long.toHexString(base), java.lang.Long.toHexString(size), java.lang.Long.toHexString(l1))
        }
    }

    private fun requestAttach(buffer: ByteBuffer) {
        val pid = Utils.unpack_dd(buffer)
        val value = Utils.unpack_dd(buffer).toInt()
        val b = Utils.unpack_dd(buffer)
        if (log.isDebugEnabled) {
            log.debug("requestAttach pid={}, value={}, b={}", pid, value, b)
        }

        val modules = ArrayList(emulator.getMemory().getLoadedModules())
        Collections.reverse(modules)
        for (module in modules) {
            eventQueue.offer(LoadModuleEvent(module))
        }

        val newBuf = ByteBuffer.allocate(16)
        newBuf.put(0x1.toByte())
        newBuf.put(emulator.getPointerSize().toByte())
        Utils.writeCString(newBuf, "linux")
        sendAck(*Utils.flipBuffer(newBuf))

        eventQueue.add(LoadExecutableEvent())
        eventQueue.add(AttachExecutableEvent())
    }

    private fun requestRunningProcesses() {
        val buffer = ByteBuffer.allocate(64)
        buffer.put(0x1.toByte())
        buffer.put(0x1.toByte()) // process count
        buffer.put(Utils.pack_dd(emulator.getPid().toLong()))
        Utils.writeCString(buffer, "[" + emulator.getPointerSize() * 8 + "] " + DebugServer.DEBUG_EXEC_NAME)
        sendAck(*Utils.flipBuffer(buffer))
    }

    override fun onHitBreakPoint(emulator: Emulator<*>, address: Long) {
        if (log.isDebugEnabled) {
            log.debug("onHitBreakPoint address=0x{}", java.lang.Long.toHexString(address))
        }

        if (isDebuggerConnected()) {
            val buffer = ByteBuffer.allocate(0x20)
            buffer.put(Utils.pack_dd(0x10))
            buffer.put(Utils.pack_dd(emulator.getPid().toLong()))
            buffer.put(Utils.pack_dd(emulator.getPid().toLong()))
            buffer.put(Utils.pack_dq(address + 1))
            buffer.put(Utils.pack_dq(0x0))
            if (emulator.is32Bit()) {
                buffer.put(Utils.pack_dd(0x1))
                buffer.put(Utils.pack_dd(0x0))
                buffer.put(Utils.pack_dd(0x1))
            } else {
                buffer.put(Utils.pack_dd(0x0))
                buffer.put(Utils.pack_dd(0x0))
                buffer.put(Utils.pack_dd(0x0))
            }
            sendPacket(0x4, Utils.flipBuffer(buffer))
        }
    }

    override fun onDebuggerExit(): Boolean {
        if (log.isDebugEnabled) {
            log.debug("onDebuggerExit")
        }
        sendProcessWillTerminated(0)
        return false
    }

    override fun onDebuggerConnected() {
        sendPacket(0x3, byteArrayOf(
                protocolVersion,
                DebugServer.IDA_DEBUGGER_ID,
                emulator.getPointerSize().toByte()
        ))
    }

    override fun toString(): String {
        return "IDA android"
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(AndroidServer::class.java)

        private const val TERMINATE_PROCESS_STATUS = 9
    }
}
