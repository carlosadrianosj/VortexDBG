package com.vortexdbg

import com.vortexdbg.arm.ARMSvcMemory
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.arm.backend.BackendFactory
import com.vortexdbg.arm.backend.ReadHook
import com.vortexdbg.arm.backend.WriteHook
import com.vortexdbg.arm.context.RegisterContext
import com.vortexdbg.debugger.DebugServer
import com.vortexdbg.debugger.Debugger
import com.vortexdbg.debugger.DebuggerType
import com.vortexdbg.debugger.gdb.GdbStub
import com.vortexdbg.debugger.ida.AndroidServer
import com.vortexdbg.file.FileSystem
import com.vortexdbg.file.NewFileIO
import com.vortexdbg.listener.TraceCodeListener
import com.vortexdbg.listener.TraceReadListener
import com.vortexdbg.listener.TraceSystemMemoryWriteListener
import com.vortexdbg.listener.TraceWriteListener
import com.vortexdbg.memory.Memory
import com.vortexdbg.memory.SvcMemory
import com.vortexdbg.pointer.MemoryWriteListener
import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.spi.Dlfcn
import com.vortexdbg.thread.MainTask
import com.vortexdbg.thread.PopContextException
import com.vortexdbg.thread.RunnableTask
import com.vortexdbg.thread.ThreadContextSwitchException
import com.vortexdbg.thread.ThreadDispatcher
import com.vortexdbg.thread.UniThreadDispatcher
import com.vortexdbg.unix.UnixSyscallHandler
import com.vortexdbg.utils.Inspector
import com.sun.jna.Pointer
import org.apache.commons.io.FileUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import unicorn.Arm64Const
import unicorn.ArmConst

import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.management.ManagementFactory
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.HashMap
import java.util.Stack

/**
 * abstract emulator
 * Created by zhkl0228 on 2017/5/2.
 */

abstract class AbstractEmulator<T : NewFileIO>(
    is64Bit: Boolean,
    processName: String?,
    svcBase: Long,
    svcSize: Int,
    rootDir: File?,
    family: Family,
    backendFactories: Collection<BackendFactory>?
) : Emulator<T>, MemoryWriteListener {

    @JvmField
    protected val backend: Backend

    private val pid: Int

    @JvmField
    protected var timeout: Long = DEFAULT_TIMEOUT

    private val registerContext: RegisterContext

    private val fileSystem: FileSystem<T>

    @JvmField
    protected val svcMemory: SvcMemory

    private val family: Family

    @JvmField
    protected val dateFormat: DateFormat = SimpleDateFormat("[HH:mm:ss SSS]")

    private val processName: String

    private val threadDispatcher: ThreadDispatcher

    init {
        this.family = family

        var rootDirVar = rootDir
        var targetDir = File("target")
        if (!targetDir.exists()) {
            targetDir = FileUtils.getTempDirectory()
        }
        if (rootDirVar == null) {
            rootDirVar = File(targetDir, FileSystem.DEFAULT_ROOT_FS)
        }
        if (rootDirVar.isFile()) {
            throw IllegalArgumentException("rootDir must be directory: $rootDirVar")
        }
        if (!rootDirVar.exists() && !rootDirVar.mkdirs()) {
            throw IllegalStateException("mkdirs failed: $rootDirVar")
        }
        this.fileSystem = createFileSystem(rootDirVar)
        this.backend = BackendFactory.createBackend(this, is64Bit, backendFactories)
        this.processName = processName ?: "vortexdbg"
        this.registerContext = createRegisterContext(backend)

        val name = ManagementFactory.getRuntimeMXBean().name
        val pidStr = name.split("@")[0]
        this.pid = Integer.parseInt(pidStr) and 0x7fff

        this.svcMemory = ARMSvcMemory(svcBase, svcSize, this)
        this.threadDispatcher = createThreadDispatcher()

        this.backend.onInitialize()
    }

    protected open fun createThreadDispatcher(): ThreadDispatcher {
        return UniThreadDispatcher(this)
    }

    override fun getPageAlign(): Int {
        var pageSize = backend.getPageSize()
        if (pageSize == 0) {
            pageSize = getPageAlignInternal()
        }
        return pageSize
    }

    protected abstract fun getPageAlignInternal(): Int

    override fun getFamily(): Family {
        return family
    }

    override fun getSvcMemory(): SvcMemory {
        return svcMemory
    }

    override fun getFileSystem(): FileSystem<T> {
        return fileSystem
    }

    protected abstract fun createFileSystem(rootDir: File): FileSystem<T>

    override fun is64Bit(): Boolean {
        return getPointerSize() == 8
    }

    override fun is32Bit(): Boolean {
        return getPointerSize() == 4
    }

    protected abstract fun createRegisterContext(backend: Backend): RegisterContext

    @Suppress("UNCHECKED_CAST")
    override fun <V : RegisterContext> getContext(): V {
        return registerContext as V
    }

    protected abstract fun createMemory(syscallHandler: UnixSyscallHandler<T>, envs: Array<String>): Memory

    protected abstract fun createDyld(svcMemory: SvcMemory): Dlfcn

    protected abstract fun createSyscallHandler(svcMemory: SvcMemory): UnixSyscallHandler<T>

    protected abstract fun assemble(assembly: Iterable<String>): ByteArray

    private var debugger: Debugger? = null

    override fun attach(): Debugger {
        return attach(DebuggerType.CONSOLE)
    }

    override fun attach(type: DebuggerType): Debugger {
        if (debugger != null) {
            return debugger!!
        }

        when (type) {
            DebuggerType.GDB_SERVER -> debugger = GdbStub(this)
            DebuggerType.ANDROID_SERVER_V7 -> debugger = AndroidServer(this, DebugServer.IDA_PROTOCOL_VERSION_V7)
            DebuggerType.CONSOLE -> debugger = createConsoleDebugger()
            else -> debugger = createConsoleDebugger()
        }
        if (debugger == null) {
            throw UnsupportedOperationException()
        }

        this.backend.debugger_add(debugger!!, 1L, 0L, this)
        this.timeout = 0
        return debugger!!
    }

    protected abstract fun createConsoleDebugger(): Debugger?

    override fun getPid(): Int {
        return pid
    }

    override fun traceRead(begin: Long, end: Long): TraceHook {
        return traceRead(begin, end, null)
    }

    override fun traceRead(begin: Long, end: Long, listener: TraceReadListener?): TraceHook {
        val hook = TraceMemoryHook(true)
        if (listener != null) {
            hook.traceReadListener = listener
        }
        backend.hook_add_new(hook as ReadHook, begin, end, this)
        return hook
    }

    override fun traceWrite(begin: Long, end: Long): TraceHook {
        return traceWrite(begin, end, null)
    }

    private var traceSystemMemoryWriteBegin: Long = 0
    private var traceSystemMemoryWriteEnd: Long = 0
    private var traceSystemMemoryWrite = false
    private var traceSystemMemoryWriteListener: TraceSystemMemoryWriteListener? = null

    override fun setTraceSystemMemoryWrite(begin: Long, end: Long, listener: TraceSystemMemoryWriteListener) {
        traceSystemMemoryWrite = true
        traceSystemMemoryWriteBegin = begin
        traceSystemMemoryWriteEnd = end
        traceSystemMemoryWriteListener = listener
    }

    override fun onSystemWrite(addr: Long, data: ByteArray) {
        if (!traceSystemMemoryWrite) {
            return
        }
        val max = Math.max(addr, traceSystemMemoryWriteBegin)
        val min = Math.min(addr + data.size, traceSystemMemoryWriteEnd)
        if (max < min) {
            val buf = ByteArray((min - max).toInt())
            System.arraycopy(data, (max - addr).toInt(), buf, 0, buf.size)
            if (traceSystemMemoryWriteListener != null) {
                traceSystemMemoryWriteListener!!.onWrite(this, addr, buf)
            } else {
                val writer = StringWriter()
                writer.write("### System Memory WRITE at 0x" + java.lang.Long.toHexString(max) + "\n")
                Exception().printStackTrace(PrintWriter(writer))
                Inspector.inspect(buf, writer.toString())
            }
        }
    }

    override fun traceWrite(begin: Long, end: Long, listener: TraceWriteListener?): TraceHook {
        val hook = TraceMemoryHook(false)
        if (listener != null) {
            hook.traceWriteListener = listener
        }
        backend.hook_add_new(hook as WriteHook, begin, end, this)
        return hook
    }

    override fun traceRead(): TraceHook {
        return traceRead(1L, 0L)
    }

    override fun traceWrite(): TraceHook {
        return traceWrite(1L, 0L)
    }

    override fun traceCode(): TraceHook {
        return traceCode(1L, 0L)
    }

    override fun traceCode(begin: Long, end: Long): TraceHook {
        return traceCode(begin, end, null)
    }

    override fun traceCode(begin: Long, end: Long, listener: TraceCodeListener?): TraceHook {
        val hook = AssemblyCodeDumper(this, begin, end, listener)
        backend.hook_add_new(hook, begin, end, this)
        return hook
    }

    override fun setTimeout(timeout: Long) {
        this.timeout = timeout
    }

    private var running = false

    override fun isRunning(): Boolean {
        return running
    }

    override fun getThreadDispatcher(): ThreadDispatcher {
        return threadDispatcher
    }

    override fun emulateSignal(sig: Int): Boolean {
        val main = getSyscallHandler().createSignalHandlerTask(this, sig)
        if (main == null) {
            return false
        } else {
            val memory = getMemory()
            val spBackup = memory.getStackPoint()
            try {
                threadDispatcher.runMainForResult(main)
            } finally {
                memory.setStackPoint(spBackup)
            }
            return true
        }
    }

    protected fun runMainForResult(task: MainTask): Number? {
        notifyMcpExecutionStarted(task.getAddress())
        val memory = getMemory()
        val spBackup = memory.getStackPoint()
        try {
            return getThreadDispatcher().runMainForResult(task)
        } finally {
            memory.setStackPoint(spBackup)
        }
    }

    private fun notifyMcpExecutionStarted(address: Long) {
        if (debugger is com.vortexdbg.arm.AbstractARMDebugger) {
            (debugger as com.vortexdbg.arm.AbstractARMDebugger).notifyExecutionStarted(address)
        }
    }

    /**
     * @return `null`表示执行未完成，需要线程调度
     */
    @Throws(PopContextException::class)
    fun emulate(begin: Long, until: Long): Number? {
        var beginVar = begin
        if (running) {
            backend.emu_stop()
            throw IllegalStateException("running")
        }
        if (is32Bit()) {
            beginVar = beginVar and 0xffffffffL
        }

        val pointer = VortexdbgPointer.pointer(this, beginVar)
        var start: Long = 0
        var exitHook: Thread? = null
        try {
            if (log.isDebugEnabled) {
                log.debug("emulate {} started sp={}", pointer, getStackPointer())
            }
            start = System.currentTimeMillis()
            running = true
            if (log.isDebugEnabled) {
                exitHook = Thread {
                    backend.emu_stop()
                    val debugger = attach()
                    if (!debugger.isDebugging()) {
                        debugger.debug("Shutdown hook triggered")
                    }
                }
                Runtime.getRuntime().addShutdownHook(exitHook)
            }
            backend.emu_start(beginVar, until, 0L, 0L)
            return if (is64Bit()) {
                backend.reg_read(Arm64Const.UC_ARM64_REG_X0)
            } else {
                val r0 = backend.reg_read(ArmConst.UC_ARM_REG_R0)
                val r1 = backend.reg_read(ArmConst.UC_ARM_REG_R1)
                (r0.toInt().toLong() and 0xffffffffL) or ((r1.toInt().toLong() and 0xffffffffL) shl 32)
            }
        } catch (e: ThreadContextSwitchException) {
            e.syncReturnValue(this)
            if (log.isTraceEnabled) {
                e.printStackTrace(System.out)
            }
            return null
        } catch (e: PopContextException) {
            throw e
        } catch (e: RuntimeException) {
            return handleEmuException(e, pointer, start)
        } finally {
            if (exitHook != null) {
                Runtime.getRuntime().removeShutdownHook(exitHook)
            }
            running = false

            log.debug("emulate {} finished sp={}, offset={}ms", pointer, getStackPointer(), System.currentTimeMillis() - start)
        }
    }

    private fun handleEmuException(e: RuntimeException, pointer: Pointer, start: Long): Int {
        val enterDebug = log.isDebugEnabled
        if (enterDebug || !log.isWarnEnabled) {
            e.printStackTrace(System.out)
            attach().debug("Emulation exception: " + (if (e.message != null) e.message else e.javaClass.name))
        } else {
            var msg = e.message
            if (msg == null) {
                msg = e.javaClass.name
            }
            val runningTask = threadDispatcher.getRunningTask()
            log.warn("emulate {} exception sp={}, msg={}, offset={}ms{}", pointer, getStackPointer(), msg, System.currentTimeMillis() - start,
                if (runningTask == null) "" else (" @ $runningTask"))
        }
        return -1
    }

    abstract fun getStackPointer(): Pointer

    private var closed = false

    @Synchronized
    @Throws(IOException::class)
    override fun close() {
        if (closed) {
            throw IOException("Already closed.")
        }

        try {
            if (debugger != null) {
                debugger!!.close()
                debugger = null
            }

            closeInternal()

            backend.destroy()
        } finally {
            closed = true
        }
    }

    protected abstract fun closeInternal()

    override fun getBackend(): Backend {
        return backend
    }

    override fun getProcessName(): String {
        return processName
    }

    private val context: MutableMap<String, Any?> = HashMap()

    override fun set(key: String, value: Any?) {
        context[key] = value
    }

    @Suppress("UNCHECKED_CAST")
    override fun <V> get(key: String): V? {
        return context[key] as V?
    }

    protected abstract fun isPaddingArgument(): Boolean

    @Throws(IOException::class)
    override fun serialize(out: java.io.DataOutput) {
        out.writeUTF(javaClass.name)
        getMemory().serialize(out)
        getSvcMemory().serialize(out)
        getSyscallHandler().serialize(out)
        getDlfcn().serialize(out)
    }

    private class Context(private val ctx: Long, val off: Int) {
        fun restoreAndFree(backend: Backend) {
            backend.context_restore(ctx)
            backend.context_free(ctx)
        }
    }

    private val contextStack = Stack<Context>()

    override fun pushContext(off: Int) {
        val context = backend.context_alloc()
        backend.context_save(context)
        contextStack.push(Context(context, off))
    }

    override fun popContext(): Int {
        val ctx = contextStack.pop()
        ctx.restoreAndFree(backend)
        return ctx.off
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(AbstractEmulator::class.java)

        @JvmField
        val DEFAULT_TIMEOUT: Long = 0
    }

}
