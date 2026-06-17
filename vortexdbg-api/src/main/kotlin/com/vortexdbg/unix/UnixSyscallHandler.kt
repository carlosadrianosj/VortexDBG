package com.vortexdbg.unix

import com.vortexdbg.arm.context.RegisterContext

import com.vortexdbg.Emulator
import com.vortexdbg.Module
import com.vortexdbg.arm.backend.UnHook
import com.vortexdbg.debugger.Breaker
import com.vortexdbg.file.FileIO
import com.vortexdbg.file.FileResult
import com.vortexdbg.file.IOResolver
import com.vortexdbg.file.NewFileIO
import com.vortexdbg.spi.SyscallHandler
import com.vortexdbg.thread.MainTask
import com.vortexdbg.unix.struct.TimeVal32
import com.vortexdbg.unix.struct.TimeVal64
import com.vortexdbg.unix.struct.TimeZone
import com.vortexdbg.utils.Inspector
import com.sun.jna.Pointer
import org.slf4j.LoggerFactory
import java.io.DataOutput
import java.io.IOException
import java.util.ArrayList
import java.util.Arrays
import java.util.Calendar
import java.util.HashMap
import java.util.Random
import java.util.TreeMap
import java.util.regex.Pattern

abstract class UnixSyscallHandler<T : NewFileIO> : SyscallHandler<T> {

    private val resolvers: MutableList<IOResolver<T>> = ArrayList(5)

    @JvmField
    protected val fdMap: MutableMap<Int, T> = TreeMap()

    override fun getFileIO(fd: Int): FileIO? {
        return fdMap[fd]
    }

    override fun closeFileIO(fd: Int) {
        val io = fdMap.remove(fd)
        io?.close()
    }

    @JvmField
    protected var verbose: Boolean = false

    override fun setVerbose(verbose: Boolean) {
        this.verbose = verbose
    }

    private var fileListener: FileListener? = null

    override fun setFileListener(fileListener: FileListener) {
        this.fileListener = fileListener
    }

    override fun isVerbose(): Boolean {
        return verbose
    }

    private var breaker: Breaker? = null

    override fun setBreaker(breaker: Breaker) {
        this.breaker = breaker
    }

    protected fun createBreaker(emulator: Emulator<*>): Breaker {
        return if (breaker != null) breaker!! else emulator.attach()
    }

    protected open fun getMinFd(): Int {
        var last_fd = -1
        for (fd in fdMap.keys) {
            if (last_fd + 1 == fd) {
                last_fd = fd
            } else {
                break
            }
        }
        return last_fd + 1
    }

    override fun addFileIO(io: T): Int {
        val fd = getMinFd()
        fdMap[fd] = io
        return fd
    }

    override fun addIOResolver(resolver: IOResolver<T>) {
        if (!resolvers.contains(resolver)) {
            resolvers.add(0, resolver)
        }
    }

    protected fun resolve(emulator: Emulator<T>, pathname: String, oflags: Int): FileResult<T>? {
        var failResult: FileResult<T>? = null
        for (resolver in resolvers) {
            val result = resolver.resolve(emulator, pathname, oflags)
            if (result != null && result.isSuccess()) {
                emulator.getMemory().setErrno(0)
                return result
            } else if (result != null) {
                if (failResult == null || !failResult.isFallback()) {
                    failResult = result
                }
            }
        }
        if (failResult != null && !failResult.isFallback()) {
            return failResult
        }

        val result = emulator.getFileSystem().open(pathname, oflags)
        if (result != null && result.isSuccess()) {
            emulator.getMemory().setErrno(0)
            return result
        } else if (failResult == null) {
            failResult = result
        }

        val family = emulator.getFamily()
        if (pathname.endsWith(family.libraryExtension)) {
            for (module in emulator.getMemory().getLoadedModules()) {
                for (memRegion in module.getRegions()) {
                    if (pathname == memRegion.getName()) {
                        try {
                            emulator.getMemory().setErrno(0)
                            return FileResult.success(createByteArrayFileIO(pathname, oflags, memRegion.readLibrary()))
                        } catch (e: IOException) {
                            throw IllegalStateException(e)
                        }
                    }
                }
            }
        }

        if (failResult != null && failResult.isFallback()) {
            return FileResult.success(failResult.io)
        }

        if (pathname.startsWith("/proc/" + emulator.getPid() + "/fd/") || pathname.startsWith("/proc/self/fd/")) {
            val fd = pathname.substring(pathname.lastIndexOf("/") + 1).toInt()
            val file = fdMap[fd]
            if (file != null) {
                return FileResult.success(file)
            }
        }
        if (("/proc/" + emulator.getPid() + "/fd") == pathname || "/proc/self/fd" == pathname) {
            return createFdDir(oflags, pathname)
        }
        if (("/proc/" + emulator.getPid() + "/task/") == pathname || "/proc/self/task/" == pathname) {
            return createTaskDir(emulator, oflags, pathname)
        }

        return failResult
    }

    protected open fun createTaskDir(emulator: Emulator<T>, oflags: Int, pathname: String): FileResult<T> {
        throw UnsupportedOperationException(pathname)
    }

    protected open fun createFdDir(oflags: Int, pathname: String): FileResult<T> {
        throw UnsupportedOperationException(pathname)
    }

    protected abstract fun createByteArrayFileIO(pathname: String, oflags: Int, data: ByteArray): T

    protected open fun currentTimeMillis(): Long {
        return System.currentTimeMillis()
    }

    protected open fun gettimeofday(emulator: Emulator<*>, tv: Pointer, tz: Pointer?): Int {
        if (log.isDebugEnabled) {
            log.debug("gettimeofday tv={}, tz={}", tv, tz)
        }

        if (log.isDebugEnabled) {
            val before = tv.getByteArray(0, 8)
            Inspector.inspect(before, "gettimeofday tv=$tv")
        }
        if (tz != null && log.isDebugEnabled) {
            val before = tz.getByteArray(0, 8)
            Inspector.inspect(before, "gettimeofday tz")
        }

        val currentTimeMillis = currentTimeMillis()
        val tv_sec = currentTimeMillis / 1000
        val tv_usec = (currentTimeMillis % 1000) * 1000
        val timeVal = TimeVal32(tv)
        timeVal.tv_sec = tv_sec.toInt()
        timeVal.tv_usec = tv_usec.toInt()
        timeVal.pack()

        if (tz != null) {
            val calendar = Calendar.getInstance()
            val tz_minuteswest = -(calendar.get(Calendar.ZONE_OFFSET) + calendar.get(Calendar.DST_OFFSET)) / (60 * 1000)
            val timeZone = TimeZone(tz)
            timeZone.tz_minuteswest = tz_minuteswest
            timeZone.tz_dsttime = 0
            timeZone.pack()
        }

        if (log.isDebugEnabled) {
            val after = tv.getByteArray(0, 8)
            Inspector.inspect(after, "gettimeofday tv after tv_sec=$tv_sec, tv_usec=$tv_usec, tv=$tv")
        }
        if (tz != null && log.isDebugEnabled) {
            val after = tz.getByteArray(0, 8)
            Inspector.inspect(after, "gettimeofday tz after")
        }
        return 0
    }

    protected open fun gettimeofday64(tv: Pointer, tz: Pointer?): Int {
        if (log.isDebugEnabled) {
            log.debug("gettimeofday64 tv={}, tz={}", tv, tz)
        }

        if (log.isDebugEnabled) {
            val before = tv.getByteArray(0, 8)
            Inspector.inspect(before, "gettimeofday tv=$tv")
        }
        if (tz != null && log.isDebugEnabled) {
            val before = tz.getByteArray(0, 8)
            Inspector.inspect(before, "gettimeofday tz")
        }

        val currentTimeMillis = currentTimeMillis()
        val tv_sec = currentTimeMillis / 1000
        val tv_usec = (currentTimeMillis % 1000) * 1000
        val timeVal = TimeVal64(tv)
        timeVal.tv_sec = tv_sec
        timeVal.tv_usec = tv_usec
        timeVal.pack()

        if (tz != null) {
            val calendar = Calendar.getInstance()
            val tz_minuteswest = -(calendar.get(Calendar.ZONE_OFFSET) + calendar.get(Calendar.DST_OFFSET)) / (60 * 1000)
            val timeZone = TimeZone(tz)
            timeZone.tz_minuteswest = tz_minuteswest
            timeZone.tz_dsttime = 0
            timeZone.pack()
        }

        if (log.isDebugEnabled) {
            val after = tv.getByteArray(0, 8)
            Inspector.inspect(after, "gettimeofday tv after tv_sec=$tv_sec, tv_usec=$tv_usec, tv=$tv")
        }
        if (tz != null && log.isDebugEnabled) {
            val after = tz.getByteArray(0, 8)
            Inspector.inspect(after, "gettimeofday tz after")
        }
        return 0
    }

    protected open fun sigprocmask(emulator: Emulator<*>, how: Int, set: Pointer, oldset: Pointer): Int {
        if (log.isDebugEnabled) {
            log.debug("sigprocmask how={}, set={}, oldset={}", how, set, oldset)
        }
        emulator.getMemory().setErrno(UnixEmulator.EINVAL)
        return -1
    }

    protected fun read(emulator: Emulator<*>, fd: Int, buffer: Pointer, count: Int): Int {
        if (log.isDebugEnabled) {
            log.debug("read fd={}, buffer={}, count={}, from={}", fd, buffer, count, emulator.getContext<RegisterContext>().getLRPointer())
        }

        val file = fdMap[fd]
        if (file == null) {
            emulator.getMemory().setErrno(UnixEmulator.EBADF)
            return -1
        }
        val read = file.read(emulator.getBackend(), buffer, count)
        if (verbose && !file.isStdIO()) {
            System.out.printf("Read %d bytes from '%s'%n", read, file)
        }
        if (fileListener != null) {
            val bytes: ByteArray = if (read <= 0) {
                ByteArray(0)
            } else {
                buffer.getByteArray(0, read)
            }
            fileListener!!.onRead(emulator, file.toString(), bytes)
        }
        return read
    }

    protected fun pread(emulator: Emulator<*>, fd: Int, buffer: Pointer, count: Int, offset: Long): Int {
        if (log.isDebugEnabled) {
            log.debug("pread fd={}, buffer={}, count={}, offset={}, from={}", fd, buffer, count, offset, emulator.getContext<RegisterContext>().getLRPointer())
        }

        val file = fdMap[fd]
        if (file == null) {
            emulator.getMemory().setErrno(UnixEmulator.EBADF)
            return -1
        }
        val read = file.pread(emulator.getBackend(), buffer, count, offset)
        if (verbose) {
            System.out.printf("PRead %d bytes with offset %d from '%s'%n", read, offset, file)
        }
        return read
    }

    protected fun close(emulator: Emulator<*>, fd: Int): Int {
        val file = fdMap.remove(fd)
        return if (file != null) {
            file.close()
            if (verbose) {
                System.out.printf("File closed '%s' from %s%n", file, emulator.getContext<RegisterContext>().getLRPointer())
            }
            if (fileListener != null) {
                fileListener!!.onClose(emulator, file)
            }
            0
        } else {
            emulator.getMemory().setErrno(UnixEmulator.EBADF)
            -1
        }
    }

    override fun open(emulator: Emulator<T>, pathname: String, oflags: Int): Int {
        val minFd = this.getMinFd()

        val resolveResult = resolve(emulator, pathname, oflags)
        if (resolveResult != null && resolveResult.isSuccess()) {
            emulator.getMemory().setErrno(0)
            this.fdMap[minFd] = resolveResult.io!!
            if (verbose) {
                System.out.printf("File opened '%s' with oflags=0x%x from %s%n", resolveResult.io, oflags, emulator.getContext<RegisterContext>().getLRPointer())
            }
            if (fileListener != null) {
                fileListener!!.onOpenSuccess(emulator, pathname, resolveResult.io!!)
            }
            return minFd
        }

        val driverIO = createDriverFileIO(emulator, oflags, pathname)
        if (driverIO != null) {
            emulator.getMemory().setErrno(0)
            this.fdMap[minFd] = driverIO
            if (verbose) {
                System.out.printf("File opened '%s' with oflags=0x%x from %s%n", driverIO, oflags, emulator.getContext<RegisterContext>().getLRPointer())
            }
            if (fileListener != null) {
                fileListener!!.onOpenSuccess(emulator, pathname, driverIO)
            }
            return minFd
        }

        var result: FileResult<T>? = null
        if (resolveResult != null) {
            result = resolveResult
        }
        val errno = if (result != null) result.errno else UnixEmulator.ENOENT
        emulator.getMemory().setErrno(errno)
        if (verbose) {
            System.out.printf("File opened '%s' with oflags=0x%x errno is %d from %s%n", pathname, oflags, errno, emulator.getContext<RegisterContext>().getLRPointer())
        }
        return -1
    }

    protected abstract fun createDriverFileIO(emulator: Emulator<*>, oflags: Int, pathname: String): T?

    protected open fun fcntl(emulator: Emulator<*>, fd: Int, cmd: Int, arg: Long): Int {
        if (log.isDebugEnabled) {
            log.debug("fcntl fd={}, cmd={}, arg={}", fd, cmd, arg)
        }

        val file = fdMap[fd]
        if (file == null) {
            emulator.getMemory().setErrno(UnixEmulator.EBADF)
            return -1
        }
        return file.fcntl(emulator, cmd, arg)
    }

    protected open fun readlink(emulator: Emulator<*>, path: String, buf: Pointer, bufSize: Int): Int {
        var path = path
        if (log.isDebugEnabled) {
            log.debug("readlink path={}, buf={}, bufSize={}", path, buf, bufSize)
        }
        val matcher = FD_PATTERN.matcher(path)
        if (matcher.find()) {
            val fd = matcher.group(1).toInt()
            val io = fdMap[fd]
            if (io != null) {
                path = io.getPath()
            }
        }
        buf.setString(0, path)
        return path.length + 1
    }

    private val sigMap: MutableMap<Int, ByteArray> = HashMap()

    protected open fun sigaction(emulator: Emulator<*>, signum: Int, act: Pointer, oldact: Pointer): Int {
        val ACT_SIZE = 16
        return sigaction(emulator, signum, act, oldact, ACT_SIZE)
    }

    protected fun sigaction(emulator: Emulator<*>, signum: Int, act: Pointer?, oldact: Pointer?, sizeOfSigAction: Int): Int {
        var signum = signum
        var prefix = "Unknown"
        if (signum > 32) {
            signum -= 32
            prefix = "Real-time"
        }
        if (log.isDebugEnabled) {
            log.debug("sigaction signum={}, act={}, oldact={}, prefix={}", signum, act, oldact, prefix)
        }

        if (oldact != null) {
            val lastAct = sigMap[signum]
            val data = lastAct ?: ByteArray(sizeOfSigAction)
            oldact.write(0, data, 0, data.size)
        }

        when (signum) {
            SIGHUP, SIGINT, SIGQUIT, SIGILL, SIGTRAP, SIGABRT, SIGBUS, SIGFPE, SIGUSR1, SIGSEGV,
            SIGUSR2, SIGPIPE, SIGALRM, SIGTERM, SIGCHLD, SIGCONT, SIGTSTP, SIGTTIN, SIGTTOU,
            SIGWINCH, SIGSYS, SIGRTMIN -> {
                if (act != null) {
                    sigMap[signum] = act.getByteArray(0, sizeOfSigAction)
                }
                return 0
            }
        }

        createBreaker(emulator).debug("Unsupported sigaction signum=$signum")
        throw UnsupportedOperationException("signum=$signum")
    }

    protected fun bind(emulator: Emulator<*>, sockfd: Int, addr: Pointer, addrlen: Int): Int {
        if (log.isDebugEnabled) {
            val data = addr.getByteArray(0, addrlen)
            Inspector.inspect(data, "bind sockfd=$sockfd, addr=$addr, addrlen=$addrlen")
        }

        val file = fdMap[sockfd]
        if (file == null) {
            emulator.getMemory().setErrno(UnixEmulator.EBADF)
            return -1
        }
        return file.bind(addr, addrlen)
    }

    protected fun listen(emulator: Emulator<*>, sockfd: Int, backlog: Int): Int {
        if (log.isDebugEnabled) {
            log.debug("listen sockfd={}, backlog={}", sockfd, backlog)
        }

        val file = fdMap[sockfd]
        if (file == null) {
            emulator.getMemory().setErrno(UnixEmulator.EBADF)
            return -1
        }
        return file.listen(backlog)
    }

    protected fun connect(emulator: Emulator<*>, sockfd: Int, addr: Pointer, addrlen: Int): Int {
        if (log.isDebugEnabled) {
            val data = addr.getByteArray(0, addrlen)
            Inspector.inspect(data, "connect sockfd=$sockfd, addr=$addr, addrlen=$addrlen")
        }

        val file = fdMap[sockfd]
        if (file == null) {
            emulator.getMemory().setErrno(UnixEmulator.EBADF)
            return -1
        }
        return file.connect(addr, addrlen)
    }

    protected fun sendto(emulator: Emulator<*>, sockfd: Int, buf: Pointer, len: Int, flags: Int, dest_addr: Pointer, addrlen: Int): Int {
        val data = buf.getByteArray(0, len)
        if (log.isDebugEnabled) {
            Inspector.inspect(data, "sendto sockfd=$sockfd, buf=$buf, flags=$flags, dest_addr=$dest_addr, addrlen=$addrlen")
        }
        val file = fdMap[sockfd]
        if (file == null) {
            emulator.getMemory().setErrno(UnixEmulator.EBADF)
            return -1
        }
        return file.sendto(data, flags, dest_addr, addrlen)
    }

    protected fun write(emulator: Emulator<*>, fd: Int, buffer: Pointer, count: Int): Int {
        val data = buffer.getByteArray(0, count)
        if (log.isDebugEnabled) {
            Inspector.inspect(data, "write fd=$fd, buffer=$buffer, count=$count")
        }

        val file = fdMap[fd]
        if (file == null) {
            emulator.getMemory().setErrno(UnixEmulator.EBADF)
            return -1
        }
        val write = file.write(data)
        if (verbose && !file.isStdIO()) {
            System.out.printf("Write %d bytes to '%s'%n", write, file)
        }
        if (fileListener != null) {
            val bytes: ByteArray = if (write <= 0) {
                ByteArray(0)
            } else {
                Arrays.copyOf(data, write)
            }
            fileListener!!.onWrite(emulator, file.toString(), bytes)
        }
        return write
    }

    protected open fun getrandom(buf: Pointer, bufSize: Int, flags: Int): Int {
        val random = Random()
        val bytes = ByteArray(bufSize)
        random.nextBytes(bytes)
        buf.write(0, bytes, 0, bytes.size)
        if (log.isDebugEnabled) {
            log.debug(Inspector.inspectString(bytes, "getrandom buf=" + buf + ", bufSize=" + bufSize + ", flags=0x" + Integer.toHexString(flags)))
        }
        return bufSize
    }

    protected open fun handleSyscall(emulator: Emulator<*>, NR: Int): Boolean {
        return false
    }

    /**
     * handle unknown syscall
     * @param NR syscall number
     */
    protected open fun handleUnknownSyscall(emulator: Emulator<*>, NR: Int): Boolean {
        return false
    }

    @Throws(IOException::class)
    override fun serialize(out: DataOutput) {
        throw UnsupportedOperationException()
    }

    override fun onAttach(unHook: UnHook) {
    }

    override fun detach() {
        throw UnsupportedOperationException()
    }

    override fun destroy() {
        for (io in fdMap.values) {
            io.close()
        }
    }

    @JvmField
    protected var threadDispatcherEnabled: Boolean = false

    override fun setEnableThreadDispatcher(threadDispatcherEnabled: Boolean) {
        this.threadDispatcherEnabled = threadDispatcherEnabled
    }

    override fun createSignalHandlerTask(emulator: Emulator<*>, sig: Int): MainTask? {
        return null
    }

    companion object {
        private val log = LoggerFactory.getLogger(UnixSyscallHandler::class.java)

        private val FD_PATTERN = Pattern.compile("/proc/self/fd/(\\d+)")

        private const val SIGHUP = 1
        private const val SIGINT = 2
        private const val SIGQUIT = 3
        private const val SIGILL = 4
        private const val SIGTRAP = 5 /* Trace trap (POSIX).  */
        private const val SIGABRT = 6
        protected const val SIGBUS = 7 /* BUS error (4.2 BSD).  */
        private const val SIGFPE = 8 /* Floating-point exception (ANSI).  */
        private const val SIGUSR1 = 10
        private const val SIGSEGV = 11
        private const val SIGUSR2 = 12
        private const val SIGPIPE = 13
        private const val SIGALRM = 14
        private const val SIGTERM = 15
        protected const val SIGCHLD = 17
        private const val SIGCONT = 18
        private const val SIGTSTP = 20
        private const val SIGTTIN = 21
        private const val SIGTTOU = 22
        private const val SIGWINCH = 28
        private const val SIGSYS = 31 /* Bad system call.  */
        private const val SIGRTMIN = 32
    }
}
