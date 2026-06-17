package com.vortexdbg.arm

import capstone.api.Instruction
import capstone.api.RegsAccess
import com.alibaba.fastjson.JSONObject
import com.vortexdbg.Emulator
import com.vortexdbg.Module
import com.vortexdbg.Symbol
import com.vortexdbg.Utils
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.arm.backend.BlockHook
import com.vortexdbg.arm.backend.ReadHook
import com.vortexdbg.arm.backend.UnHook
import com.vortexdbg.arm.backend.WriteHook
import com.vortexdbg.arm.context.RegisterContext
import com.vortexdbg.AssemblyCodeDumper
import com.vortexdbg.debugger.BreakPoint
import com.vortexdbg.debugger.BreakPointCallback
import com.vortexdbg.debugger.DebugListener
import com.vortexdbg.debugger.DebugRunnable
import com.vortexdbg.debugger.Debugger
import com.vortexdbg.debugger.FunctionCallListener
import com.vortexdbg.TraceMemoryHook
import com.vortexdbg.mcp.McpServer
import com.vortexdbg.memory.MemRegion
import com.vortexdbg.memory.Memory
import com.vortexdbg.memory.MemoryMap
import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.thread.RunnableTask
import com.vortexdbg.thread.Task
import com.vortexdbg.unix.struct.StdString
import com.vortexdbg.unwind.Unwinder
import com.vortexdbg.utils.Inspector
import com.github.zhkl0228.demumble.DemanglerFactory
import com.github.zhkl0228.demumble.GccDemangler
import com.sun.jna.Pointer
import keystone.Keystone
import keystone.KeystoneEncoded
import keystone.exceptions.AssembleFailedKeystoneException
import org.apache.commons.codec.binary.Hex
import org.apache.commons.io.FilenameUtils
import org.apache.commons.io.IOUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import unicorn.Arm64Const
import unicorn.ArmConst
import unicorn.UnicornConst
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Arrays
import java.util.Date
import java.util.LinkedHashMap
import java.util.Objects
import java.util.regex.Pattern

abstract class AbstractARMDebugger protected constructor(
    @JvmField protected val emulator: Emulator<*>
) : Debugger {

    private val breakMap: MutableMap<Long, BreakPoint> = LinkedHashMap()

    override fun getBreakPoints(): MutableMap<Long, BreakPoint> {
        return breakMap
    }

    @JvmField
    protected var mcpServer: McpServer? = null

    @JvmField
    @Volatile
    protected var scannerNeedsRefresh: Boolean = false

    private val unHookList: MutableList<UnHook> = ArrayList()

    override fun onAttach(unHook: UnHook) {
        unHookList.add(unHook)
    }

    override fun detach() {
        val iterator = unHookList.iterator()
        while (iterator.hasNext()) {
            iterator.next().unhook()
            iterator.remove()
        }
    }

    final override fun addBreakPoint(module: Module?, symbol: String): BreakPoint {
        val sym = module!!.findSymbolByName(symbol, false)
            ?: throw IllegalStateException("find symbol failed: $symbol")
        return addBreakPoint(module, sym.getValue())
    }

    final override fun addBreakPoint(module: Module?, symbol: String, callback: BreakPointCallback): BreakPoint {
        val sym = module!!.findSymbolByName(symbol, false)
            ?: throw IllegalStateException("find symbol failed: $symbol")
        return addBreakPoint(module, sym.getValue(), callback)
    }

    final override fun addBreakPoint(module: Module?, offset: Long): BreakPoint {
        val address = if (module == null) offset else module.base + offset
        return addBreakPoint(address)
    }

    final override fun addBreakPoint(module: Module?, offset: Long, callback: BreakPointCallback): BreakPoint {
        val address = if (module == null) offset else module.base + offset
        return addBreakPoint(address, callback)
    }

    override fun addBreakPoint(address: Long): BreakPoint {
        return addBreakPoint(address, null)
    }

    override fun addBreakPoint(address: Long, callback: BreakPointCallback?): BreakPoint {
        var addr = address
        val thumb = (addr and 1L) != 0L
        addr = addr and 1L.inv()

        if (log.isDebugEnabled) {
            log.debug("addBreakPoint address=0x{}", java.lang.Long.toHexString(addr))
        }
        val breakPoint = emulator.getBackend().addBreakPoint(addr, callback, thumb)
        breakMap[addr] = breakPoint
        return breakPoint
    }

    override fun traceFunctionCall(listener: FunctionCallListener) {
        traceFunctionCall(null, listener)
    }

    override fun traceFunctionCall(module: Module?, listener: FunctionCallListener) {
        throw UnsupportedOperationException()
    }

    protected abstract fun createKeystone(isThumb: Boolean): Keystone

    protected abstract fun resolveRegister(command: String, nameOut: Array<String?>): Int

    protected abstract fun resolveWriteRegister(command: String): Int

    protected abstract fun showWriteRegs(reg: Int)

    protected abstract fun showWriteHelp()

    fun handleWriteCommand(backend: Backend, line: String): Boolean {
        if (!line.startsWith("w") || "where" == line) {
            return false
        }
        val command: String
        val tokens = line.split("\\s+".toRegex()).toTypedArray()
        if (tokens.size < 2) {
            showWriteHelp()
            return true
        }
        val value: Long
        try {
            command = tokens[0]
            val str = tokens[1]
            value = Utils.parseNumber(str)
        } catch (e: NumberFormatException) {
            e.printStackTrace(System.err)
            return true
        }

        val reg = resolveWriteRegister(command)
        if (reg != -1) {
            backend.reg_write(reg, value)
            showWriteRegs(reg)
            return true
        }

        if (command.startsWith("wb0x") || command.startsWith("ws0x") || command.startsWith("wi0x") || command.startsWith("wl0x")) {
            var hex = command.substring(4).trim()
            if (hex.endsWith("L")) {
                hex = hex.substring(0, hex.length - 1)
            }
            val addr = java.lang.Long.parseLong(hex, 16)
            val pointer = VortexdbgPointer.pointer(emulator, addr)
            if (pointer != null) {
                if (command.startsWith("wb")) {
                    pointer.setByte(0, value.toByte())
                } else if (command.startsWith("ws")) {
                    pointer.setShort(0, value.toShort())
                } else if (command.startsWith("wi")) {
                    pointer.setInt(0, value.toInt())
                } else if (command.startsWith("wl")) {
                    pointer.setLong(0, value)
                }
                dumpMemory(pointer, 16, pointer.toString(), null)
            } else {
                println("$addr is null")
            }
            return true
        }
        return false
    }

    fun handleMemoryCommand(line: String): Boolean {
        if (!line.startsWith("m")) {
            return false
        }
        var command = line
        val tokens = line.split("\\s+".toRegex()).toTypedArray()
        var length = 0x70
        try {
            if (tokens.size >= 2) {
                command = tokens[0]
                val str = tokens[1]
                length = Utils.parseNumber(str).toInt()
            }
        } catch (ignored: NumberFormatException) {
        }
        var stringType: StringType? = null
        if (command.endsWith("std")) {
            stringType = StringType.std_string
            command = command.substring(0, command.length - 3)
        } else if (command.endsWith("s")) {
            stringType = StringType.nullTerminated
            command = command.substring(0, command.length - 1)
        }

        if (command.startsWith("m0x")) {
            var hex = command.substring(3).trim()
            if (hex.endsWith("L")) {
                hex = hex.substring(0, hex.length - 1)
            }
            val addr = java.lang.Long.parseLong(hex, 16)
            val pointer = VortexdbgPointer.pointer(emulator, addr)
            if (pointer != null) {
                dumpMemory(pointer, length, pointer.toString(), stringType)
            } else {
                println("$addr is null")
            }
            return true
        }

        val nameOut = arrayOfNulls<String>(1)
        val reg = resolveRegister(command, nameOut)
        if (reg != -1) {
            val pointer = VortexdbgPointer.register(emulator, reg)
            if (pointer != null) {
                dumpMemory(pointer, length, nameOut[0] + "=" + pointer, stringType)
            } else {
                println(nameOut[0] + " is null")
            }
            return true
        }
        return false
    }

    override fun removeBreakPoint(address: Long): Boolean {
        val addr = address and 1L.inv()

        return if (breakMap.containsKey(addr)) {
            breakMap.remove(addr)
            emulator.getBackend().removeBreakPoint(addr)
        } else {
            false
        }
    }

    private var listener: DebugListener? = null

    override fun setDebugListener(listener: DebugListener) {
        this.listener = listener
    }

    override fun onBreak(backend: Backend, address: Long, size: Int, user: Any?) {
        val breakPoint = breakMap[address]
        if (breakPoint != null && breakPoint.isTemporary()) {
            removeBreakPoint(address)
        }
        if (breakPoint != null) {
            val callback = breakPoint.getCallback()
            if (callback != null && callback.onHit(emulator, address)) {
                return
            }
        }
        try {
            if (listener == null || listener!!.canDebug(emulator, CodeHistory(address, size, ARM.isThumb(backend)))) {
                notifyBreakpointHit(address)
                cancelTrace()
                debugging = true
                if (mcpServer != null) mcpServer!!.setDebugIdle(true)
                loop(emulator, address, size, null)
            }
        } catch (e: Exception) {
            log.warn("process loop failed", e)
        } finally {
            if (mcpServer != null) mcpServer!!.setDebugIdle(false)
            debugging = false
        }
    }

    private fun cancelTrace() {
        if (traceHook != null) {
            traceHook!!.detach()
            traceHook = null
        }
        if (traceHookRedirectStream != null) {
            com.alibaba.fastjson.util.IOUtils.close(traceHookRedirectStream)
            traceHookRedirectStream = null
        }
        if (traceRead != null) {
            traceRead!!.detach()
            traceRead = null
        }
        if (traceReadRedirectStream != null) {
            com.alibaba.fastjson.util.IOUtils.close(traceReadRedirectStream)
            traceReadRedirectStream = null
        }
        if (traceWrite != null) {
            traceWrite!!.detach()
            traceWrite = null
        }
        if (traceWriteRedirectStream != null) {
            com.alibaba.fastjson.util.IOUtils.close(traceWriteRedirectStream)
            traceWriteRedirectStream = null
        }
    }

    private var debugging = false

    override fun isDebugging(): Boolean {
        return debugging
    }

    private var blockHooked = false
    private var breakNextBlock = false

    override fun hookBlock(backend: Backend, address: Long, size: Int, user: Any?) {
        if (breakNextBlock) {
            onBreak(backend, address, size, user)
            breakNextBlock = false
        }
    }

    final override fun hook(backend: Backend, address: Long, size: Int, user: Any?) {
        val emulator = user as Emulator<*>

        try {
            if (breakMnemonic != null) {
                val history = CodeHistory(address, size, ARM.isThumb(backend))
                val instructions = history.disassemble(emulator)
                if (instructions != null && instructions.isNotEmpty() && breakMnemonic == instructions[0].getMnemonic()) {
                    breakMnemonic = null
                    backend.setFastDebug(true)
                    cancelTrace()
                    debugging = true
                    loop(emulator, address, size, null)
                }
            }
        } catch (e: Exception) {
            log.warn("process hook failed", e)
        } finally {
            debugging = false
        }
    }

    override fun debug(reason: String) {
        val backend = emulator.getBackend()
        val address: Long = if (emulator.is32Bit()) {
            backend.reg_read(ArmConst.UC_ARM_REG_PC).toInt().toLong() and 0xffffffffL
        } else {
            backend.reg_read(Arm64Const.UC_ARM64_REG_PC).toLong()
        }
        notifyBreakpointHit(address, reason)
        try {
            cancelTrace()
            debugging = true
            if (mcpServer != null) mcpServer!!.setDebugIdle(true)
            loop(emulator, address, 4, null)
        } catch (e: Exception) {
            log.warn("debug failed", e)
        } finally {
            if (mcpServer != null) mcpServer!!.setDebugIdle(false)
            debugging = false
        }
    }

    protected fun setSingleStep(singleStep: Int) {
        emulator.getBackend().setSingleStep(singleStep)
    }

    private var breakMnemonic: String? = null

    @Throws(Exception::class)
    protected abstract fun loop(emulator: Emulator<*>, address: Long, size: Int, runnable: DebugRunnable<*>?)

    @JvmField
    protected var callbackRunning: Boolean = false

    @Volatile
    private var currentRunnable: DebugRunnable<*>? = null

    override fun hasRunnable(): Boolean {
        return currentRunnable != null
    }

    @Throws(Exception::class)
    override fun <T> run(runnable: DebugRunnable<T>?): T {
        if (runnable == null) {
            throw NullPointerException()
        }
        currentRunnable = runnable
        val ret: T
        try {
            callbackRunning = true
            if (mcpServer != null) mcpServer!!.setDebugIdle(false)
            ret = runnable.runWithArgs(null)
        } finally {
            callbackRunning = false
        }
        try {
            cancelTrace()
            debugging = true
            if (mcpServer != null) mcpServer!!.setDebugIdle(true)
            loop(emulator, -1, 0, runnable)
        } finally {
            if (mcpServer != null) mcpServer!!.setDebugIdle(false)
            debugging = false
        }
        return ret
    }

    protected enum class StringType {
        nullTerminated,
        std_string,
    }

    protected fun dumpMemory(pointer: Pointer, _length: Int, label: String, stringType: StringType?) {
        if (stringType != null) {
            if (stringType == StringType.nullTerminated) {
                var addr: Long = 0
                val baos = ByteArrayOutputStream()
                var foundTerminated = false
                while (true) {
                    val data = pointer.getByteArray(addr, 0x10)
                    val length = Utils.indexOfNullTerminator(data)
                    baos.write(data, 0, length)
                    addr += length

                    if (length < data.size) { // reach zero
                        foundTerminated = true
                        break
                    }

                    if (baos.size() > 0x10000) { // 64k
                        break
                    }
                }

                if (foundTerminated) {
                    Inspector.inspect(baos.toByteArray(), if (baos.size() >= 1024) (label + ", hex=" + Hex.encodeHexString(baos.toByteArray())) else (label + ", str=" + String(baos.toByteArray(), StandardCharsets.UTF_8)))
                } else {
                    Inspector.inspect(pointer.getByteArray(0, _length), "$label, find NULL-terminated failed")
                }
            } else if (stringType == StringType.std_string) {
                val string = StdString.createStdString(emulator, pointer)
                val size = string.getDataSize()
                val data = string.getData(emulator)
                Inspector.inspect(data, if (size >= 1024) (label + ", hex=" + Hex.encodeHexString(data) + ", std=" + String(data, StandardCharsets.UTF_8)) else label)
            } else {
                throw UnsupportedOperationException("stringType=$stringType")
            }
        } else {
            val sb = StringBuilder(label)
            val data = pointer.getByteArray(0, _length)
            if (_length == 4) {
                val buffer = ByteBuffer.wrap(data)
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                val value = buffer.getInt()
                sb.append(", value=0x").append(Integer.toHexString(value))
            } else if (_length == 8) {
                val buffer = ByteBuffer.wrap(data)
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                val value = buffer.getLong()
                sb.append(", value=0x").append(java.lang.Long.toHexString(value))
            } else if (_length == 16) {
                sb.append(", value=0x").append(ARM.newBigInteger(Arrays.copyOf(data, 0x10)).toString(16))
            }
            if (data.size >= 1024) {
                sb.append(", hex=").append(Hex.encodeHexString(data))
            }
            Inspector.inspect(data, sb.toString())
        }
    }

    private fun searchStack(data: ByteArray?) {
        if (data == null || data.isEmpty()) {
            System.err.println("search stack failed as empty data")
            return
        }

        val stack = emulator.getContext<RegisterContext>().getStackPointer()
        val backend = emulator.getBackend()
        val pointers = searchMemory(backend, stack.peer, emulator.getMemory().getStackBase(), data)
        println("Search stack from " + stack + " matches " + pointers.size + " count")
        for (pointer in pointers) {
            println("Stack matches: $pointer")
        }
    }

    private fun searchHeap(data: ByteArray?, prot: Int) {
        if (data == null || data.isEmpty()) {
            System.err.println("search heap failed as empty data")
            return
        }

        val list: MutableList<Pointer> = ArrayList()
        val backend = emulator.getBackend()
        for (map in emulator.getMemory().getMemoryMap()) {
            if ((map.prot and prot) != 0) {
                val pointers = searchMemory(backend, map.base, map.base + map.size, data)
                list.addAll(pointers)
            }
        }
        println("Search heap matches " + list.size + " count")
        for (pointer in list) {
            println("Heap matches: $pointer")
        }
    }

    private fun searchMemory(backend: Backend, start: Long, end: Long, data: ByteArray): Collection<Pointer> {
        val pointers: MutableList<Pointer> = ArrayList()
        var i = start
        val m = end - data.size
        while (i < m) {
            val oneByte = backend.mem_read(i, 1)
            if (data[0] != oneByte[0]) {
                i++
                continue
            }

            if (Arrays.equals(data, backend.mem_read(i, data.size.toLong()))) {
                pointers.add(VortexdbgPointer.pointer(emulator, i))
                i += (data.size - 1)
            }
            i++
        }
        return pointers
    }

    private var traceHook: AssemblyCodeDumper? = null
    private var traceHookRedirectStream: PrintStream? = null
    private var traceRead: TraceMemoryHook? = null
    private var traceReadRedirectStream: PrintStream? = null
    private var traceWrite: TraceMemoryHook? = null
    private var traceWriteRedirectStream: PrintStream? = null

    @Throws(IOException::class)
    private fun setupTraceMemory(backend: Backend, line: String, isRead: Boolean, traceSize: Int) {
        val type = if (isRead) "Read" else "Write"
        val typeLower = if (isRead) "read" else "write"
        val pattern = Pattern.compile("trace" + type + "\\s+(\\w+)\\s+(\\w+)")
        val matcher = pattern.matcher(line)
        val existingHook = if (isRead) traceRead else traceWrite
        if (existingHook != null) {
            existingHook.detach()
        }
        val hook = TraceMemoryHook(isRead)
        val begin: Long
        var end: Long
        if (matcher.find()) {
            begin = Utils.parseNumber(matcher.group(1))
            end = Utils.parseNumber(matcher.group(2))
            if (begin > end && end > 0 && end <= traceSize) {
                end += begin
            }
        } else {
            begin = 1
            end = 0
        }
        var redirectStream: PrintStream? = null
        if (begin >= end) {
            val traceFile = File("target/trace" + type + ".txt")
            if (!traceFile.exists() && !traceFile.createNewFile()) {
                throw IllegalStateException("createNewFile: $traceFile")
            }
            redirectStream = PrintStream(BufferedOutputStream(Files.newOutputStream(traceFile.toPath())), true)
            redirectStream.printf("[%s]Start trace%s%n", SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date()), type)
            hook.setRedirect(redirectStream)
            System.out.printf("Set trace all memory %s success with trace file: %s.%n", typeLower, traceFile.absolutePath)
        } else {
            val needTraceFile = end - begin > traceSize
            if (needTraceFile) {
                val traceFile = File(String.format("target/trace%s_0x%x-0x%x.txt", type, begin, end))
                if (!traceFile.exists() && !traceFile.createNewFile()) {
                    throw IllegalStateException("createNewFile: $traceFile")
                }
                redirectStream = PrintStream(BufferedOutputStream(Files.newOutputStream(traceFile.toPath())), true)
                redirectStream.printf("[%s]Start trace%s: 0x%x-0x%x%n", SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date()), type, begin, end)
                hook.setRedirect(redirectStream)
                System.out.printf("Set trace 0x%x->0x%x memory %s success with trace file: %s.%n", begin, end, typeLower, traceFile.absolutePath)
            } else {
                System.out.printf("Set trace 0x%x->0x%x memory %s success.%n", begin, end, typeLower)
            }
        }
        if (isRead) {
            traceRead = hook
            traceReadRedirectStream = redirectStream
            backend.hook_add_new(hook as ReadHook, begin, end, emulator)
        } else {
            traceWrite = hook
            traceWriteRedirectStream = redirectStream
            backend.hook_add_new(hook as WriteHook, begin, end, emulator)
        }
    }

    @Throws(Exception::class)
    fun handleCommon(backend: Backend, lineParam: String, addressParam: Long, size: Int, nextAddress: Long, runnable: DebugRunnable<*>?): Boolean {
        var line = lineParam
        var address = addressParam
        if ("help" == line) {
            showHelp(address)
            return false
        }
        if (handleMemoryCommand(line)) {
            return false
        }
        if ("where" == line) {
            Exception("here").printStackTrace(System.out)
            return false
        }
        if (line.startsWith("wx0x")) {
            val tokens = line.split("\\s+".toRegex()).toTypedArray()
            var hex = tokens[0].substring(4).trim()
            if (hex.endsWith("L")) {
                hex = hex.substring(0, hex.length - 1)
            }
            val addr = java.lang.Long.parseLong(hex, 16)
            val pointer = VortexdbgPointer.pointer(emulator, addr)
            if (pointer != null && tokens.size > 1) {
                val data = Hex.decodeHex(tokens[1].toCharArray())
                pointer.write(0, data, 0, data.size)
                dumpMemory(pointer, data.size, pointer.toString(), null)
            } else {
                println("$addr is null")
            }
            return false
        }
        if (emulator.isRunning() && "bt" == line) {
            try {
                emulator.getUnwinder().unwind()
            } catch (e: Throwable) {
                e.printStackTrace(System.err)
            }
            return false
        }
        if (handleBreakpointCommand(line, address)) {
            return false
        }
        when (line) {
            "blr" -> {
                val addr = if (emulator.is32Bit())
                    backend.reg_read(ArmConst.UC_ARM_REG_LR).toInt().toLong() and 0xffffffffL
                else
                    backend.reg_read(Arm64Const.UC_ARM64_REG_LR).toLong()
                addAndPrintBreakPoint(addr)
                return false
            }
            "r" -> {
                val addr = if (emulator.is32Bit())
                    backend.reg_read(ArmConst.UC_ARM_REG_PC).toInt().toLong() and 0xffffffffL
                else
                    backend.reg_read(Arm64Const.UC_ARM64_REG_PC).toLong()
                if (removeBreakPoint(addr)) {
                    val module = findModuleByAddress(emulator, addr)
                    println("Remove breakpoint: 0x" + java.lang.Long.toHexString(addr) + (if (module == null) "" else (" in " + module.name + " [0x" + java.lang.Long.toHexString(addr - module.base) + "]")))
                }
                return false
            }
            "b" -> {
                val addr = if (emulator.is32Bit())
                    backend.reg_read(ArmConst.UC_ARM_REG_PC).toInt().toLong() and 0xffffffffL
                else
                    backend.reg_read(Arm64Const.UC_ARM64_REG_PC).toLong()
                addAndPrintBreakPoint(addr)
                return false
            }
        }
        if (line.startsWith("run") && runnable != null) {
            val arg = line.substring(3).trim()
            try {
                callbackRunning = true
                if (mcpServer != null) mcpServer!!.setDebugIdle(false)
                if (arg.isNotEmpty()) {
                    val args = arg.split("\\s+".toRegex()).toTypedArray()
                    runnable.runWithArgs(args)
                } else {
                    runnable.runWithArgs(null)
                }
                notifyExecutionCompleted()
            } catch (e: Exception) {
                log.warn("runWithArgs failed: arg={}", arg, e)
                notifyExecutionError(e)
            } finally {
                callbackRunning = false
                if (mcpServer != null) mcpServer!!.setDebugIdle(true)
            }
            return false
        }
        when (line) {
            "exit", "quit", "q" -> // continue
                return true
            "gc" -> {
                println("Run System.gc();")
                System.gc()
                return false
            }
            "threads" -> {
                for (task in emulator.getThreadDispatcher().getTaskList()) {
                    println(task.getId().toString() + ": " + task)
                }
                return false
            }
        }
        if (line.startsWith("mcp")) {
            startMcpServer(line)
            return false
        }
        if ("_mcp" == line) {
            if (mcpServer != null) {
                mcpServer!!.executePendingOperation()
            }
            return false
        }
        if (runnable == null || callbackRunning) {
            if ("c" == line) { // continue
                return true
            }
        } else {
            if ("c" == line) {
                try {
                    callbackRunning = true
                    if (mcpServer != null) mcpServer!!.setDebugIdle(false)
                    runnable.runWithArgs(null)
                    cancelTrace()
                    notifyExecutionCompleted()
                    return false
                } finally {
                    callbackRunning = false
                    if (mcpServer != null) mcpServer!!.setDebugIdle(true)
                }
            }
        }
        if ("n" == line) {
            if (nextAddress == 0L) {
                println("Next address failed.")
                return false
            } else {
                addBreakPoint(nextAddress).setTemporary(true)
                return true
            }
        }
        if (line.startsWith("st")) { // search stack
            val index = line.indexOf(' ')
            if (index != -1) {
                val hex = line.substring(index + 1).trim()
                val data = Hex.decodeHex(hex.toCharArray())
                if (data.isNotEmpty()) {
                    searchStack(data)
                    return false
                }
            }
        }
        if (line.startsWith("shw")) { // search writable heap
            val index = line.indexOf(' ')
            if (index != -1) {
                val hex = line.substring(index + 1).trim()
                val data = Hex.decodeHex(hex.toCharArray())
                if (data.isNotEmpty()) {
                    searchHeap(data, UnicornConst.UC_PROT_WRITE)
                    return false
                }
            }
        }
        if (line.startsWith("shr")) { // search readable heap
            val index = line.indexOf(' ')
            if (index != -1) {
                val hex = line.substring(index + 1).trim()
                val data = Hex.decodeHex(hex.toCharArray())
                if (data.isNotEmpty()) {
                    searchHeap(data, UnicornConst.UC_PROT_READ)
                    return false
                }
            }
        }
        if (line.startsWith("shx")) { // search executable heap
            val index = line.indexOf(' ')
            if (index != -1) {
                val hex = line.substring(index + 1).trim()
                val data = Hex.decodeHex(hex.toCharArray())
                if (data.isNotEmpty()) {
                    searchHeap(data, UnicornConst.UC_PROT_EXEC)
                    return false
                }
            }
        }
        val traceSize = 0x10000
        if (line.startsWith("traceRead")) { // start trace memory read
            setupTraceMemory(backend, line, true, traceSize)
            return false
        }
        if (line.startsWith("traceWrite")) { // start trace memory write
            setupTraceMemory(backend, line, false, traceSize)
            return false
        }
        if ("traceAll" == line) {
            line = "trace 1 0"
        }
        if (line.startsWith("trace")) { // start trace instructions
            val memory = emulator.getMemory()
            val pattern = Pattern.compile("trace\\s+(\\w+)\\s+(\\w+)")
            val matcher = pattern.matcher(line)
            if (traceHook != null) {
                traceHook!!.detach()
            }
            traceHookRedirectStream = null
            val begin: Long
            val end: Long
            if (matcher.find()) {
                begin = Utils.parseNumber(matcher.group(1))
                var endValue = Utils.parseNumber(matcher.group(2))
                if (begin > endValue && endValue > 0 && endValue < traceSize) {
                    endValue += begin
                }
                end = endValue
                if (begin >= end) {
                    val traceFile = File("target/traceCode.txt")
                    if (!traceFile.exists() && (!traceFile.parentFile.exists() || !traceFile.createNewFile())) {
                        throw IllegalStateException("createNewFile: " + traceFile.absolutePath)
                    }
                    traceHookRedirectStream = PrintStream(BufferedOutputStream(Files.newOutputStream(traceFile.toPath())), true)
                    traceHookRedirectStream!!.printf("[%s]Start traceCode%n", SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date()))
                    System.out.printf("Set trace all instructions success with trace file: %s.%n", traceFile.absolutePath)
                } else {
                    val needTraceFile = end - begin > traceSize
                    if (needTraceFile) {
                        val traceFile = File(String.format("target/traceCode_0x%x-0x%x.txt", begin, end))
                        if (!traceFile.exists() && !traceFile.createNewFile()) {
                            throw IllegalStateException("createNewFile: $traceFile")
                        }
                        traceHookRedirectStream = PrintStream(BufferedOutputStream(Files.newOutputStream(traceFile.toPath())), true)
                        traceHookRedirectStream!!.printf("[%s]Start traceCode: 0x%x-0x%x%n", SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date()), begin, end)
                        System.out.printf("Set trace 0x%x->0x%x instructions success with trace file: %s.%n", begin, end, traceFile.absolutePath)
                    } else {
                        System.out.printf("Set trace 0x%x->0x%x instructions success.%n", begin, end)
                    }
                }
            } else {
                var redirect: String? = null
                var module = memory.findModuleByAddress(address)
                run {
                    val index = line.indexOf(' ')
                    if (index != -1) {
                        redirect = line.substring(index + 1).trim()
                    }
                }
                var traceFile: File? = null
                if (redirect != null && redirect!!.trim().isNotEmpty()) {
                    val check = memory.findModule(redirect!!)
                    if (check != null) {
                        module = check
                    } else {
                        val outFile = File(redirect!!.trim())
                        try {
                            if (!outFile.exists() && !outFile.createNewFile()) {
                                throw IllegalStateException("createNewFile: $outFile")
                            }
                            traceHookRedirectStream = PrintStream(BufferedOutputStream(Files.newOutputStream(outFile.toPath())), true)
                            traceHookRedirectStream!!.printf("[%s]Start trace %s%n", SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date()), if (module == null) "all" else module)
                            traceFile = outFile
                        } catch (e: IOException) {
                            System.err.println("Set trace redirect out file failed: $outFile")
                            return false
                        }
                    }
                }
                begin = if (module == null) 1 else module.base
                end = if (module == null) 0 else (module.base + module.size)
                println("Set trace " + (if (module == null) "all" else module) + " instructions success" + (if (traceFile == null) "." else (" with trace file: " + traceFile.absolutePath)))
            }
            traceHook = AssemblyCodeDumper(emulator, begin, end, null)
            if (traceHookRedirectStream != null) {
                traceHook!!.setRedirect(traceHookRedirectStream)
            }
            backend.hook_add_new(traceHook!!, begin, end, emulator)
            return false
        }
        if (line.startsWith("vm")) {
            val memory = emulator.getMemory()
            val maxLengthSoName = memory.getMaxLengthLibraryName()
            val sb = StringBuilder()
            var filter: String? = null
            run {
                val index = line.indexOf(' ')
                if (index != -1) {
                    filter = line.substring(index + 1).trim()
                }
            }
            var index = 0
            var filterAddress: Long = -1
            if (filter != null && filter!!.startsWith("0x")) {
                filterAddress = Utils.parseNumber(filter!!)
            }
            for (module in memory.getLoadedModules()) {
                if (filter == null || module.getPath().lowercase().contains(filter!!.lowercase()) || (filterAddress >= module.base && filterAddress < module.base + module.size)) {
                    sb.append(String.format("[%3s][%" + maxLengthSoName.length + "s] ", index++, FilenameUtils.getName(module.name)))
                    sb.append(String.format("[0x%0" + java.lang.Long.toHexString(memory.getMaxSizeOfLibrary()).length + "x-0x%x]", module.getBaseHeader(), module.base + module.size))
                    sb.append(module.getPath())
                    sb.append("\n")
                }
            }
            if (index == 0) {
                System.err.println("Find loaded library failed with filter: $filter")
            } else {
                println(sb)
            }
            return false
        }
        when (line) {
            "vbs" -> { // view breakpoints
                val memory = emulator.getMemory()
                val sb = StringBuilder("* means temporary bp:\n")
                val maxLengthSoName = memory.getMaxLengthLibraryName()
                for (entry in breakMap.entries) {
                    address = entry.key
                    val bp = entry.value
                    var ins: Instruction? = null
                    try {
                        val code = backend.mem_read(address, 4)
                        val insns = emulator.disassemble(address, code, bp.isThumb(), 1)
                        if (insns != null && insns.isNotEmpty()) {
                            ins = insns[0]
                        }
                    } catch (ignored: Exception) {
                    }

                    if (ins == null) {
                        sb.append(String.format("[%" + maxLengthSoName.toString().length + "s]", "0x" + java.lang.Long.toHexString(address)))
                        if (bp.isTemporary()) {
                            sb.append('*')
                        }
                    } else {
                        sb.append(ARM.assembleDetail(emulator, ins, address, bp.isThumb(), bp.isTemporary(), memory.getMaxLengthLibraryName().length))
                    }
                    sb.append("\n")
                }
                println(sb)
                return false
            }
            "stop" -> {
                backend.emu_stop()
                return true
            }
            "s", "si" -> {
                setSingleStep(1)
                return true
            }
            "nb" -> {
                if (!blockHooked) {
                    blockHooked = true
                    emulator.getBackend().hook_add_new(this as BlockHook, 1, 0, emulator)
                }
                breakNextBlock = true
                return true
            }
        }
        if (line.startsWith("s")) {
            try {
                setSingleStep(Integer.parseInt(line.substring(1)))
                return true
            } catch (e: NumberFormatException) {
                breakMnemonic = line.substring(1)
                backend.setFastDebug(false)
                return true
            }
        }
        if (line.startsWith("p")) {
            val originalAddress = address
            val assembly = line.substring(1).trim()
            val isThumb = ARM.isThumb(backend)
            try {
                createKeystone(isThumb).use { keystone ->
                    val encoded = keystone.assemble(assembly)
                    val code = encoded.getMachineCode()
                    address = address and 1L.inv()
                    if (code.size.toLong() != (nextAddress and 1L.inv()) - address) {
                        System.err.println("patch code failed: nextAddress=0x" + java.lang.Long.toHexString(nextAddress) + ", codeSize=" + code.size)
                        return false
                    }
                    val pointer = VortexdbgPointer.pointer(emulator, address)
                    assert(pointer != null)
                    pointer!!.write(0, code, 0, code.size)
                    disassemble(emulator, originalAddress, size, isThumb)
                    return false
                }
            } catch (e: AssembleFailedKeystoneException) {
                System.err.println("Assemble failed: $assembly")
                return false
            }
        }
        val module = emulator.getMemory().findModuleByAddress(address)
        if (module != null && line.startsWith("cc")) {
            val sizeBytes = Utils.parseNumber(line.substring(2).trim()).toInt() and 1.inv()
            if (sizeBytes >= 2) {
                val insns = emulator.disassemble(address and 1L.inv(), sizeBytes, Short.MAX_VALUE.toLong())
                val sb = StringBuilder()
                if (emulator.is32Bit()) {
                    sb.append("    \"").append("push {r7, lr}").append("\\n").append('"').append("\n\n")
                } else {
                    sb.append("    \"").append("sub sp, sp, #0x10").append("\\n").append('"').append('\n')
                    sb.append("    \"").append("stp x29, x30, [sp]").append("\\n").append('"').append("\n\n")
                }
                var lastRegWrite: String? = null
                for (insn in insns) {
                    val regsAccess = insn.regsAccess()
                    if (regsAccess != null) {
                        val regsWrite = regsAccess.getRegsWrite()
                        if (regsWrite != null && regsWrite.size == 1) {
                            lastRegWrite = insn.regName(regsWrite[0].toInt())
                        }
                    }
                    val asm = "    \"" + insn + "\\n\""
                    sb.append(String.format("%-50s", asm))
                    sb.append(" // 0x").append(java.lang.Long.toHexString(insn.getAddress()))
                    sb.append(" offset 0x").append(java.lang.Long.toHexString(insn.getAddress() - (address and 1L.inv())))
                    sb.append("\n")
                }
                sb.append('\n')

                if (emulator.is32Bit()) {
                    if (lastRegWrite != null && "r0" != lastRegWrite) {
                        sb.append("    \"").append("mov r0, ").append(lastRegWrite).append("\\n").append('"').append('\n')
                    }
                    sb.append("    \"").append("pop {r7, pc}").append("\\n").append('"')
                } else {
                    if (lastRegWrite != null && "x0" != lastRegWrite && "w0" != lastRegWrite) {
                        sb.append("    \"").append("mov ").append(if (lastRegWrite!!.startsWith("x")) "x0" else "w0").append(", ").append(lastRegWrite).append("\\n").append('"').append('\n')
                    }
                    sb.append("    \"").append("ldp x29, x30, [sp]").append("\\n").append('"').append('\n')
                    sb.append("    \"").append("add sp, sp, #0x10").append("\\n").append('"').append('\n')
                    sb.append("    \"").append("ret").append("\\n").append('"')
                }
                Objects.requireNonNull(javaClass.getResourceAsStream("/cc.c")).use { inputStream ->
                    var template = IOUtils.toString(inputStream, StandardCharsets.UTF_8)
                    if (emulator.is64Bit()) {
                        template = template.replace("\$(ARCH_SPEC)", "-m64 -arch arm64")
                    } else {
                        template = template.replace("\$(ARCH_SPEC)", "-m32 -arch armv7")
                    }
                    System.err.println(template.replace("\$(REPLACE_ASM)", sb.toString()))
                }
            } else {
                System.err.println("Usage: cc (size bytes)")
            }
            return false
        }

        showHelp(address)
        return false
    }

    fun handleBreakpointCommand(lineParam: String, currentAddress: Long): Boolean {
        var line = lineParam
        if (!line.startsWith("b0x")) {
            return false
        }
        try {
            if (line.endsWith("L")) {
                line = line.substring(0, line.length - 1)
            }
            var addr = java.lang.Long.parseLong(line.substring(3), 16) and (if (emulator.is32Bit()) 0xffffffffL else -0x2L)
            var module: Module? = null
            if (addr < Memory.MMAP_BASE && (findModuleByAddress(emulator, currentAddress).also { module = it }) != null) {
                addr += module!!.base
            }
            addBreakPoint(addr)
            if (module == null) {
                module = findModuleByAddress(emulator, addr)
            }
            println("Add breakpoint: 0x" + java.lang.Long.toHexString(addr) + (if (module == null) "" else (" in " + module!!.name + " [0x" + java.lang.Long.toHexString(addr - module!!.base) + "]")))
            return true
        } catch (ignored: NumberFormatException) {
        }
        return false
    }

    private fun addAndPrintBreakPoint(addr: Long) {
        addBreakPoint(addr)
        val module = findModuleByAddress(emulator, addr)
        println("Add breakpoint: 0x" + java.lang.Long.toHexString(addr) + (if (module == null) "" else (" in " + module.name + " [0x" + java.lang.Long.toHexString(addr - module.base) + "]")))
    }

    protected open fun showHelp(address: Long) {
        println("c: continue")
        println("n: step over")
        if (emulator.isRunning()) {
            println("bt: back trace")
        }
        println()
        println("st hex: search stack")
        println("shw hex: search writable heap")
        println("shr hex: search readable heap")
        println("shx hex: search executable heap")
        println()
        println("nb: break at next block")
        println("s|si: step into")
        println("s[decimal]: execute specified amount instruction")
    }

    fun showCommonHelp(address: Long) {
        println("wx(address) <hex>: write bytes to memory at specified address, address must start with 0x")
        println()
        println("b(address): add temporarily breakpoint, address must start with 0x, can be module offset")
        println("b: add breakpoint of register PC")
        println("r: remove breakpoint of register PC")
        println("blr: add temporarily breakpoint of register LR")
        println()
        println("p (assembly): patch assembly at PC address")
        println("where: show java stack trace")
        println()
        println("trace [begin end]: Set trace instructions")
        println("traceRead [begin end]: Set trace memory read")
        println("traceWrite [begin end]: Set trace memory write")
        println("vm: view loaded modules")
        println("vbs: view breakpoints")
        println("d|dis: show disassemble")
        println("d(0x): show disassemble at specify address")
        println("stop: stop emulation")
        println("run [arg]: run test")
        println("gc: Run System.gc()")
        println("threads: show thread list")
        println("mcp [port]: start MCP server for AI tool integration (default port 9239)")


        val module = emulator.getMemory().findModuleByAddress(address)
        if (module != null) {
            if (emulator.is32Bit()) {
                System.out.printf("cc size: convert asm from 0x%x - 0x%x + size bytes to c function%n", address, address)
            } else {
                System.out.printf("cc (size): convert asm from (0x%x) to (0x%x + size) bytes to c function%n", address, address)
            }
        }
    }

    private fun appendSymbolInfo(sb: StringBuilder, emulator: Emulator<*>, address: Long) {
        val module = findModuleByAddress(emulator, address)
        val symbol = module?.findClosestSymbolByAddress(address, false)
        if (symbol != null && address - symbol.getAddress() <= Unwinder.SYMBOL_SIZE) {
            val demangler = DemanglerFactory.createDemangler()
            sb.append(demangler.demangle(symbol.getName())).append(" + 0x").append(java.lang.Long.toHexString(address - (symbol.getAddress() and 1L.inv()))).append("\n")
        }
    }

    /**
     * @return next address
     */
    fun disassemble(emulator: Emulator<*>, address: Long, size: Int, thumb: Boolean): Long {
        var next: Long = 0
        var on = false
        val maxLength = emulator.getMemory().getMaxLengthLibraryName().length
        val sb = StringBuilder()
        appendSymbolInfo(sb, emulator, address)
        var nextAddr = address - size
        for (history in Arrays.asList(
            CodeHistory(address - size, size, thumb),
            CodeHistory(address, size, thumb)
        )) {
            val instructions = history.disassemble(emulator)
            if (instructions != null) {
                for (ins in instructions) {
                    if (ins.getAddress() == address) {
                        sb.append("=> *")
                        on = true
                    } else {
                        sb.append("    ")
                        if (on) {
                            next = ins.getAddress()
                            on = false
                        }
                    }
                    sb.append(ARM.assembleDetail(emulator, ins, ins.getAddress(), history.thumb, on, maxLength)).append('\n')
                    nextAddr += ins.getBytes().size
                }
            }
        }
        val insns = emulator.disassemble(nextAddr, 4 * 15, 15)
        if (insns != null) {
            for (ins in insns) {
                if (nextAddr == address) {
                    sb.append("=> *")
                    on = true
                } else {
                    sb.append("    ")
                    if (on) {
                        next = nextAddr
                        on = false
                    }
                }
                sb.append(ARM.assembleDetail(emulator, ins, nextAddr, thumb, on, maxLength)).append('\n')
                nextAddr += ins.getSize()
            }
        }
        println(sb)
        if (on) {
            next = nextAddr
        }
        if (thumb) {
            next = next or 1L
        }
        return next
    }

    final override fun disassembleBlock(emulator: Emulator<*>, address: Long, thumb: Boolean) {
        val sb = StringBuilder()
        appendSymbolInfo(sb, emulator, address)
        var nextAddr = address
        val pointer = VortexdbgPointer.pointer(emulator, address)
        assert(pointer != null)
        val code = pointer!!.getByteArray(0, 4 * 10)
        val insns = emulator.disassemble(nextAddr, code, thumb, 0)
        for (ins in insns) {
            sb.append("    ")
            sb.append(ARM.assembleDetail(emulator, ins, nextAddr, thumb, false, emulator.getMemory().getMaxLengthLibraryName().length)).append('\n')
            nextAddr += ins.getSize()
        }
        println(sb)
    }

    final override fun brk(pc: VortexdbgPointer?, svcNumber: Int) {
        if (pc != null) {
            removeBreakPoint(pc.peer)
        }
        debug()
    }

    override fun addMcpTool(name: String, description: String, vararg paramNames: String) {
        if (mcpServer != null) {
            mcpServer!!.addCustomTool(name, description, *paramNames)
        } else {
            pendingMcpTools.add(PendingMcpTool(name, description, arrayOf(*paramNames)))
        }
    }

    private val pendingMcpTools: MutableList<PendingMcpTool> = ArrayList()

    private class PendingMcpTool(@JvmField val name: String, @JvmField val description: String, @JvmField val paramNames: Array<String>)

    private fun startMcpServer(line: String) {
        if (mcpServer != null) {
            val p = mcpServer!!.getPort()
            println("MCP server already running on port $p")
            printMcpConfig(p, mcpServerIndex)
            return
        }
        var port = 9239
        val portStr = line.substring(3).trim()
        if (portStr.isNotEmpty()) {
            try {
                port = Integer.parseInt(portStr)
            } catch (ignored: NumberFormatException) {
            }
        }
        val maxRetries = 10
        for (i in 0 until maxRetries) {
            try {
                mcpServer = McpServer(emulator, port)
                for (tool in pendingMcpTools) {
                    mcpServer!!.addCustomTool(tool.name, tool.description, *tool.paramNames)
                }
                pendingMcpTools.clear()
                mcpServer!!.start()
                scannerNeedsRefresh = true
                mcpServer!!.setDebugIdle(true)
                mcpServerIndex = i
                println("MCP server started on port $port")
                printMcpConfig(port, i)
                return
            } catch (e: IOException) {
                mcpServer = null
                if (i < maxRetries - 1) {
                    println("Port " + port + " is in use, trying " + (port + 1) + "...")
                    port++
                } else {
                    System.err.println("Failed to start MCP server: " + e.message)
                }
            }
        }
    }

    private var mcpServerIndex = 0

    private fun printMcpConfig(port: Int, index: Int) {
        val serverName = if (index == 0) "vortexdbg-mcp-server" else "vortexdbg-mcp-server-$index"
        println("Add to Cursor MCP settings:")
        println("{")
        println("  \"mcpServers\": {")
        println("    \"" + serverName + "\": {")
        println("      \"type\": \"sse\",")
        println("      \"url\": \"http://localhost:$port/sse\"")
        println("    }")
        println("  }")
        println("}")
    }

    private fun notifyBreakpointHit(address: Long) {
        notifyBreakpointHit(address, null)
    }

    private fun notifyBreakpointHit(address: Long, reason: String?) {
        if (mcpServer == null) return
        val data = JSONObject(true)
        data.put("event", "breakpoint_hit")
        data.put("pc", "0x" + java.lang.Long.toHexString(address))
        if (reason != null) {
            data.put("reason", reason)
        }
        val runningTask = emulator.getThreadDispatcher().getRunningTask()
        if (runningTask is Task) {
            data.put("tid", runningTask.getId())
            data.put("is_main_thread", runningTask.isMainThread())
        }
        val module = emulator.getMemory().findModuleByAddress(address)
        if (module != null) {
            data.put("module", module.name)
            data.put("offset", "0x" + java.lang.Long.toHexString(address - module.base))
        }
        mcpServer!!.queueEvent(data)
        mcpServer!!.broadcastNotification("breakpoint_hit", data)
    }

    fun notifyExecutionCompleted() {
        if (mcpServer == null) return
        val data = JSONObject(true)
        data.put("event", "execution_completed")
        mcpServer!!.queueEvent(data)
        mcpServer!!.broadcastNotification("execution_completed", data)
    }

    private fun notifyExecutionError(e: Exception) {
        if (mcpServer == null) return
        val data = JSONObject(true)
        data.put("event", "execution_error")
        data.put("error", e.javaClass.name + ": " + (if (e.message != null) e.message else e.toString()))
        mcpServer!!.queueEvent(data)
        mcpServer!!.broadcastNotification("execution_error", data)
    }

    fun notifyExecutionStarted(address: Long) {
        if (mcpServer == null) return
        val module = emulator.getMemory().findModuleByAddress(address) ?: return
        val data = JSONObject(true)
        data.put("event", "execution_started")
        data.put("pc", "0x" + java.lang.Long.toHexString(address))
        data.put("module", module.name)
        data.put("offset", "0x" + java.lang.Long.toHexString(address - module.base))
        mcpServer!!.queueEvent(data)
        mcpServer!!.broadcastNotification("execution_started", data)
    }

    override fun close() {
        if (mcpServer != null) {
            mcpServer!!.stop()
            mcpServer = null
        }
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(AbstractARMDebugger::class.java)

        @JvmStatic
        fun findModuleByAddress(emulator: Emulator<*>, address: Long): Module? {
            val memory = emulator.getMemory()
            var module = memory.findModuleByAddress(address)
            if (module == null) {
                val region = emulator.getSvcMemory().findRegion(address)
                if (region != null) {
                    var name = region.getName()
                    val maxLength = memory.getMaxLengthLibraryName().length
                    if (name.length > maxLength) {
                        name = name.substring(name.length - maxLength)
                    }
                    module = object : Module(name, region.begin, region.end - region.begin, emptyMap<String, Module>(), emptyList<MemRegion>(), null) {
                        override fun callFunction(emulator: Emulator<*>, offset: Long, vararg args: Any?): Number {
                            throw UnsupportedOperationException()
                        }

                        override fun findSymbolByName(name: String, withDependencies: Boolean): Symbol? {
                            throw UnsupportedOperationException()
                        }

                        override fun findClosestSymbolByAddress(address: Long, fast: Boolean): Symbol? {
                            return null
                        }

                        override fun callEntry(emulator: Emulator<*>, vararg args: String): Int {
                            throw UnsupportedOperationException()
                        }

                        override fun getPath(): String {
                            throw UnsupportedOperationException()
                        }

                        override fun registerSymbol(symbolName: String, address: Long) {
                            throw UnsupportedOperationException()
                        }

                        override fun virtualMemoryAddressToFileOffset(offset: Long): Int {
                            throw UnsupportedOperationException()
                        }
                    }
                }
            }
            return module
        }
    }

}
