package com.vortexdbg.linux

import com.vortexdbg.AbstractEmulator
import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.BackendException
import com.vortexdbg.arm.context.RegisterContext
import com.vortexdbg.file.FileResult
import com.vortexdbg.file.linux.AndroidFileIO
import com.vortexdbg.file.linux.IOConstants
import com.vortexdbg.linux.file.DirectoryFileIO
import com.vortexdbg.linux.file.EventFD
import com.vortexdbg.linux.file.PipedReadFileIO
import com.vortexdbg.linux.file.PipedWriteFileIO
import com.vortexdbg.linux.signal.SigAction
import com.vortexdbg.linux.signal.SignalFunction
import com.vortexdbg.linux.signal.SignalTask
import com.vortexdbg.linux.struct.StatFS
import com.vortexdbg.linux.struct.StatFS32
import com.vortexdbg.linux.struct.StatFS64
import com.vortexdbg.linux.thread.FutexIndefinitelyWaiter
import com.vortexdbg.linux.thread.FutexNanoSleepWaiter
import com.vortexdbg.linux.thread.FutexWaiter
import com.vortexdbg.linux.thread.MarshmallowThread
import com.vortexdbg.linux.thread.NanoSleepWaiter
import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.signal.SigSet
import com.vortexdbg.signal.SignalOps
import com.vortexdbg.signal.UnixSigSet
import com.vortexdbg.spi.SyscallHandler
import com.vortexdbg.thread.MainTask
import com.vortexdbg.thread.RunnableTask
import com.vortexdbg.thread.Task
import com.vortexdbg.thread.ThreadContextSwitchException
import com.vortexdbg.thread.ThreadDispatcher
import com.vortexdbg.thread.ThreadTask
import com.vortexdbg.unix.IO
import com.vortexdbg.unix.UnixEmulator
import com.vortexdbg.unix.UnixSyscallHandler
import com.vortexdbg.unix.struct.TimeSpec
import com.vortexdbg.utils.Inspector
import com.sun.jna.Pointer
import net.dongliu.apk.parser.utils.Pair
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.io.File
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.ArrayList
import java.util.HashMap

abstract class AndroidSyscallHandler : UnixSyscallHandler<AndroidFileIO>(), SyscallHandler<AndroidFileIO> {

    private var sched_cpu_mask: ByteArray? = null

    fun mlock(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val addr = context.getPointerArg(0)
        val len = context.getIntArg(1)
        if (log.isDebugEnabled) {
            log.debug("mlock addr={}, len={}", addr, len)
        }
        return 0
    }

    fun munlock(emulator: Emulator<AndroidFileIO>): Int {
        val context = emulator.getContext<RegisterContext>()
        val addr = context.getPointerArg(0)
        val len = context.getIntArg(1)
        if (log.isDebugEnabled) {
            log.debug("munlock addr={}, len={}", addr, len)
        }
        return 0
    }

    fun sched_setaffinity(emulator: Emulator<AndroidFileIO>): Long {
        val context = emulator.getContext<RegisterContext>()
        val pid = context.getIntArg(0)
        val cpusetsize = context.getIntArg(1)
        val mask = context.getPointerArg(2)
        if (mask != null) {
            sched_cpu_mask = mask.getByteArray(0L, cpusetsize)
        }
        if (log.isDebugEnabled) {
            log.debug(Inspector.inspectString(null, "sched_setaffinity pid=" + pid + ", cpusetsize=" + cpusetsize + ", mask=" + mask, sched_cpu_mask, Inspector.WPE))
        }
        return 0
    }

    fun sched_getaffinity(emulator: Emulator<AndroidFileIO>): Long {
        val context = emulator.getContext<RegisterContext>()
        val pid = context.getIntArg(0)
        val cpusetsize = context.getIntArg(1)
        val mask = context.getPointerArg(2)
        var ret = 0
        val cpuMask = sched_cpu_mask
        if (mask != null && cpuMask != null) {
            mask.write(0L, cpuMask, 0, cpusetsize)
            ret = cpusetsize
        }
        if (log.isDebugEnabled) {
            log.debug(Inspector.inspectString(null, "sched_getaffinity pid=" + pid + ", cpusetsize=" + cpusetsize + ", mask=" + mask, sched_cpu_mask, Inspector.WPE))
        }
        return ret.toLong()
    }

    fun eventfd2(emulator: Emulator<*>): Int {
        val ctx = emulator.getContext<RegisterContext>()
        val initval = ctx.getIntArg(0)
        val flags = ctx.getIntArg(1)
        if (log.isDebugEnabled) {
            log.debug("eventfd2 initval={}, flags=0x{}", initval, Integer.toHexString(flags))
        }
        if ((flags and EFD_CLOEXEC) != 0) {
            throw UnsupportedOperationException("eventfd2 flags=0x" + Integer.toHexString(flags))
        }
        val nonblock = (flags and EFD_NONBLOCK) != 0
        val semaphore = (flags and EFD_SEMAPHORE) != 0
        val fileIO: AndroidFileIO = EventFD(initval, semaphore, nonblock)
        val minFd = this.getMinFd()
        this.fdMap[minFd] = fileIO
        if (verbose) {
            System.out.printf("eventfd(%d) with flags=0x%x fd=%d from %s%n", initval, flags, minFd, emulator.getContext<RegisterContext>().getLRPointer())
        }
        return minFd
    }

    protected open fun sched_setscheduler(emulator: Emulator<AndroidFileIO>): Int {
        val context = emulator.getContext<RegisterContext>()
        val pid = context.getIntArg(0)
        val policy = context.getIntArg(1)
        val param = context.getPointerArg(2)
        if (log.isDebugEnabled) {
            log.debug("sched_setscheduler pid={}, policy={}, param={}", pid, policy, param)
        }
        return 0
    }

    protected open fun getcwd(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val buf = context.getPointerArg(0)
        val size = context.getIntArg(1)
        val workDir = emulator.getFileSystem().createWorkDir()
        val path = workDir.getPath()
        if (log.isDebugEnabled) {
            log.debug("getcwd buf={}, size={}, path={}", buf, size, path)
        }
        buf!!.setString(0L, ".")
        return buf.peer.toInt()
    }

    protected open fun sched_getscheduler(emulator: Emulator<AndroidFileIO>): Int {
        val context = emulator.getContext<RegisterContext>()
        val pid = context.getIntArg(0)
        if (log.isDebugEnabled) {
            log.debug("sched_getscheduler pid={}", pid)
        }
        return SCHED_OTHER
    }

    protected open fun sched_getparam(emulator: Emulator<AndroidFileIO>): Int {
        val context = emulator.getContext<RegisterContext>()
        val pid = context.getIntArg(0)
        val param = context.getPointerArg(1)
        if (log.isDebugEnabled) {
            log.debug("sched_getparam pid={}, param={}", pid, param)
        }
        param!!.setInt(0L, ANDROID_PRIORITY_NORMAL)
        return 0
    }

    protected open fun sched_yield(emulator: Emulator<AndroidFileIO>): Int {
        if (log.isDebugEnabled) {
            log.debug("sched_yield")
        }
        if (emulator.getThreadDispatcher().getTaskCount() <= 1) {
            return 0
        } else {
            throw ThreadContextSwitchException().setReturnValue(0L)
        }
    }

    protected open fun getpriority(emulator: Emulator<AndroidFileIO>): Int {
        val context = emulator.getContext<RegisterContext>()
        val which = context.getIntArg(0)
        val who = context.getIntArg(1)
        if (log.isDebugEnabled) {
            log.debug("getpriority which={}, who={}", which, who)
        }
        return ANDROID_PRIORITY_NORMAL
    }

    protected open fun setpriority(emulator: Emulator<AndroidFileIO>): Int {
        val context = emulator.getContext<RegisterContext>()
        val which = context.getIntArg(0)
        val who = context.getIntArg(1)
        val prio = context.getIntArg(2)
        if (log.isDebugEnabled) {
            log.debug("setpriority which={}, who={}, prio={}", which, who, prio)
        }
        return 0
    }

    override fun sigprocmask(emulator: Emulator<*>, how: Int, set: Pointer, oldset: Pointer): Int {
        val task = emulator.get<Task>(Task.TASK_KEY)
        val signalOps: SignalOps = if (task!!.isMainThread()) emulator.getThreadDispatcher() else task
        val old = signalOps.getSigMaskSet()
        if (oldset != null && old != null) {
            if (emulator.is32Bit()) {
                oldset.setInt(0L, old.getMask().toInt())
            } else {
                oldset.setLong(0L, old.getMask())
            }
        }
        if (set == null) {
            return 0
        }
        val mask = if (emulator.is32Bit()) set.getInt(0L).toLong() else set.getLong(0L)
        when (how) {
            SIG_BLOCK -> {
                if (old == null) {
                    val sigSet: SigSet = UnixSigSet(mask)
                    val sigPendingSet: SigSet = UnixSigSet(0)
                    signalOps.setSigMaskSet(sigSet)
                    signalOps.setSigPendingSet(sigPendingSet)
                } else {
                    old.blockSigSet(mask)
                }
                return 0
            }
            SIG_UNBLOCK -> {
                if (old != null) {
                    old.unblockSigSet(mask)
                }
                return 0
            }
            SIG_SETMASK -> {
                val sigSet: SigSet = UnixSigSet(mask)
                val sigPendingSet: SigSet = UnixSigSet(0)
                signalOps.setSigMaskSet(sigSet)
                signalOps.setSigPendingSet(sigPendingSet)
                return 0
            }
        }
        return super.sigprocmask(emulator, how, set, oldset)
    }

    protected open fun rt_sigpending(emulator: Emulator<AndroidFileIO>): Int {
        val context = emulator.getContext<RegisterContext>()
        val set = context.getPointerArg(0)
        if (log.isDebugEnabled) {
            log.debug("rt_sigpending set={}", set)
        }
        val task = emulator.get<Task>(Task.TASK_KEY)
        val signalOps: SignalOps = if (task!!.isMainThread()) emulator.getThreadDispatcher() else task
        val sigSet = signalOps.getSigPendingSet()
        if (set != null && sigSet != null) {
            if (emulator.is32Bit()) {
                set.setInt(0L, sigSet.getMask().toInt())
            } else {
                set.setLong(0L, sigSet.getMask())
            }
        }
        return 0
    }

    protected open fun futex(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val uaddr = context.getPointerArg(0)
        val futex_op = context.getIntArg(1)
        val `val` = context.getIntArg(2)
        val old = uaddr!!.getInt(0L)
        val isPrivate = (futex_op and FUTEX_PRIVATE_FLAG) != 0
        val cmd = futex_op and FUTEX_CMD_MASK
        if (log.isDebugEnabled) {
            log.debug("futex uaddr={}, isPrivate={}, cmd={}, val=0x{}, old=0x{}, LR={}", uaddr, isPrivate, cmd, Integer.toHexString(`val`), Integer.toHexString(old), context.getLRPointer())
        }

        val task = emulator.get<Task>(Task.TASK_KEY)
        when (cmd) {
            FUTEX_WAIT -> {
                if (old != `val`) {
                    return -UnixEmulator.EAGAIN
                }
                val timeout = context.getPointerArg(3)
                val timeSpec = if (timeout == null) null else TimeSpec.createTimeSpec(emulator, timeout)
                val mtype = `val` and MUTEX_TYPE_MASK
                val shared = `val` and MUTEX_SHARED_MASK
                if (log.isDebugEnabled) {
                    log.debug("futex FUTEX_WAIT mtype=0x{}, shared={}, timeSpec={}, test={}, task={}", Integer.toHexString(mtype), shared, timeSpec, mtype or shared, task)
                }
                val runningTask = emulator.getThreadDispatcher().getRunningTask()
                if (threadDispatcherEnabled && runningTask != null) {
                    if (timeSpec == null) {
                        runningTask.setWaiter(emulator, FutexIndefinitelyWaiter(uaddr, `val`))
                    } else {
                        runningTask.setWaiter(emulator, FutexNanoSleepWaiter(uaddr, `val`, timeSpec))
                    }
                    throw ThreadContextSwitchException()
                }
                if (threadDispatcherEnabled && emulator.getThreadDispatcher().getTaskCount() > 1) {
                    throw ThreadContextSwitchException().setReturnValue((-ETIMEDOUT).toLong())
                } else {
                    return 0
                }
            }
            FUTEX_WAKE -> {
                if (log.isDebugEnabled) {
                    log.debug("futex FUTEX_WAKE val=0x{}, old={}, task={}", Integer.toHexString(`val`), old, task)
                }
                if (emulator.getThreadDispatcher().getTaskCount() <= 1) {
                    return 0
                }
                var count = 0
                for (t in emulator.getThreadDispatcher().getTaskList()) {
                    val waiter = t.getWaiter()
                    if (waiter is FutexWaiter) {
                        if (waiter.wakeUp(uaddr)) {
                            if (++count >= `val`) {
                                break
                            }
                        }
                    }
                }
                if (count > 0) {
                    throw ThreadContextSwitchException().setReturnValue(count.toLong())
                }
                if (threadDispatcherEnabled && task != null) {
                    throw ThreadContextSwitchException().setReturnValue(1L)
                }
                return 0
            }
            FUTEX_CMP_REQUEUE -> {
                if (log.isDebugEnabled) {
                    log.debug("futex FUTEX_CMP_REQUEUE val=0x{}, old={}, task={}", Integer.toHexString(`val`), old, task)
                }
                return 0
            }
            else -> {
                if (log.isDebugEnabled) {
                    emulator.attach().debug("Unsupported futex_op=0x" + Integer.toHexString(futex_op))
                }
                throw AbstractMethodError("futex_op=0x" + Integer.toHexString(futex_op))
            }
        }
    }

    protected open fun rt_sigtimedwait(emulator: Emulator<AndroidFileIO>): Int {
        val context = emulator.getContext<RegisterContext>()
        val set = context.getPointerArg(0)
        val info = context.getPointerArg(1)
        val timeout = context.getPointerArg(2)
        val sigsetsize = context.getIntArg(3)
        val mask = if (emulator.is32Bit()) set!!.getInt(0L).toLong() else set!!.getLong(0L)
        val task = emulator.get<Task>(Task.TASK_KEY)
        val sigSet: SigSet = UnixSigSet(mask)
        val signalOps: SignalOps = if (task!!.isMainThread()) emulator.getThreadDispatcher() else task
        val sigPendingSet = signalOps.getSigPendingSet()
        if (sigPendingSet != null) {
            for (signum in sigSet) {
                if (sigPendingSet.containsSigNumber(signum)) {
                    sigPendingSet.removeSigNumber(signum)
                    return signum
                }
            }
        }
        if (!task.isMainThread()) {
            throw ThreadContextSwitchException().setReturnValue((-UnixEmulator.EINTR).toLong())
        }
        log.info("rt_sigtimedwait set={}, info={}, timeout={}, sigsetsize={}, sigSet={}, task={}", set, info, timeout, sigsetsize, sigSet, task)
        val log = LoggerFactory.getLogger(AbstractEmulator::class.java)
        if (log.isDebugEnabled) {
            emulator.attach().debug("rt_sigtimedwait sigSet=$sigSet")
        }
        return 0
    }

    protected open fun rt_sigqueue(emulator: Emulator<AndroidFileIO>): Int {
        val context = emulator.getContext<RegisterContext>()
        val tgid = context.getIntArg(0)
        val sig = context.getIntArg(1)
        val info = context.getPointerArg(2)
        if (log.isDebugEnabled) {
            log.debug("rt_sigqueue tgid={}, sig={}", tgid, sig)
        }
        val task = emulator.get<Task>(Task.TASK_KEY)
        // 检查pid是有匹配进程存在
        if (!(tgid == 0 || tgid == -1 || Math.abs(tgid) == emulator.getPid())) {
            return -UnixEmulator.ESRCH
        }
        // 检查进程是否存在, 无需发送信号
        if (sig == 0) {
            return 0
        }
        if (sig < 0 || sig > 64) {
            return -UnixEmulator.EINVAL
        }
        if (task != null) {
            val sigAction = sigActionMap[sig]
            return processSignal(emulator.getThreadDispatcher(), sig, task, sigAction, info)
        }
        throw UnsupportedOperationException()
    }

    override fun createFdDir(oflags: Int, pathname: String): FileResult<AndroidFileIO> {
        val list = ArrayList<DirectoryFileIO.DirectoryEntry>()
        for (entry in fdMap.entries) {
            list.add(DirectoryFileIO.DirectoryEntry(DirectoryFileIO.DirentType.DT_LNK, entry.key.toString()))
        }
        return FileResult.success(DirectoryFileIO(oflags, pathname, *list.toTypedArray()))
    }

    override fun createTaskDir(emulator: Emulator<AndroidFileIO>, oflags: Int, pathname: String): FileResult<AndroidFileIO> {
        return FileResult.success(DirectoryFileIO(oflags, pathname, DirectoryFileIO.DirectoryEntry(false, Integer.toString(emulator.getPid()))))
    }

    protected open fun statfs64(emulator: Emulator<AndroidFileIO>, path: String, buf: Pointer): Long {
        val result = resolve(emulator, path, IOConstants.O_RDONLY)
        if (result == null) {
            log.info("statfs64 buf={}, path={}", buf, path)
            emulator.getMemory().setErrno(UnixEmulator.ENOENT)
            return -1
        }
        if (result.isSuccess()) {
            val statFS: StatFS = if (emulator.is64Bit()) StatFS64(buf) else StatFS32(buf)
            val ret = result.io!!.statfs(statFS)
            if (ret != 0) {
                log.info("statfs64 buf={}, path={}, ret={}", buf, path, ret)
            } else {
                if (verbose) {
                    System.out.printf("File statfs '%s' from %s%n", result.io, emulator.getContext<RegisterContext>().getLRPointer())
                }
                if (log.isDebugEnabled) {
                    log.debug("statfs64 buf={}, path={}", buf, path)
                }
            }
            return ret.toLong()
        } else {
            log.info("statfs64 buf={}, path={}", buf, path)
            emulator.getMemory().setErrno(result.errno)
            return -1
        }
    }

    protected open fun pipe2(emulator: Emulator<*>): Int {
        try {
            val context = emulator.getContext<RegisterContext>()
            val pipefd = context.getPointerArg(0)
            val flags = context.getIntArg(1)
            val writefd = getMinFd()
            val pair = getPipePair(emulator, writefd)
            this.fdMap[writefd] = pair.getLeft()
            val readfd = getMinFd()
            this.fdMap[readfd] = pair.getRight()
            pipefd!!.setInt(0L, readfd)
            pipefd.setInt(4L, writefd)
            if (log.isDebugEnabled) {
                log.debug("pipe2 pipefd={}, flags=0x{}, readfd={}, writefd={}", pipefd, flags, readfd, writefd)
            }
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
        return 0
    }

    @Throws(IOException::class)
    protected open fun getPipePair(emulator: Emulator<*>, writefd: Int): Pair<AndroidFileIO, AndroidFileIO> {
        val inputStream = PipedInputStream()
        val outputStream = PipedOutputStream(inputStream)
        val writeIO: AndroidFileIO = PipedWriteFileIO(outputStream, writefd)
        val readIO: AndroidFileIO = PipedReadFileIO(inputStream, writefd)
        log.info("Return default pipe pair.")
        return Pair(writeIO, readIO)
    }

    protected open fun fchmodat(emulator: Emulator<AndroidFileIO>): Int {
        val context = emulator.getContext<RegisterContext>()
        val dirfd = context.getIntArg(0)
        val pathname_p = context.getPointerArg(1)
        val mode = context.getIntArg(2)
        val flags = context.getIntArg(3)
        val pathname = pathname_p!!.getString(0L)
        if (log.isDebugEnabled) {
            log.debug("fchmodat dirfd={}, pathname={}, mode=0x{}, flags=0x{}", dirfd, pathname, Integer.toHexString(mode), Integer.toHexString(flags))
        }
        return 0
    }

    protected open fun fchownat(emulator: Emulator<AndroidFileIO>): Int {
        val context = emulator.getContext<RegisterContext>()
        val dirfd = context.getIntArg(0)
        val pathname_p = context.getPointerArg(1)
        val owner = context.getIntArg(2)
        val group = context.getIntArg(3)
        val flags = context.getIntArg(4)
        val pathname = pathname_p!!.getString(0L)
        if (log.isDebugEnabled) {
            log.debug("fchownat dirfd={}, pathname={}, owner={}, group={}, flags=0x{}", dirfd, pathname, owner, group, Integer.toHexString(flags))
        }
        return 0
    }

    protected open fun mkdirat(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val dirfd = context.getIntArg(0)
        val pathname_p = context.getPointerArg(1)
        val mode = context.getIntArg(2)
        val pathname = pathname_p!!.getString(0L)
        if (log.isDebugEnabled) {
            log.debug("mkdirat dirfd={}, pathname={}, mode={}", dirfd, pathname, Integer.toHexString(mode))
        }
        if (dirfd != IO.AT_FDCWD) {
            throw BackendException()
        }
        if (emulator.getFileSystem().mkdir(pathname, mode)) {
            if (log.isDebugEnabled) {
                log.debug("mkdir pathname={}, mode={}", pathname, mode)
            }
            return 0
        } else {
            log.info("mkdir pathname={}, mode={}", pathname, mode)
            emulator.getMemory().setErrno(UnixEmulator.EACCES)
            return -1
        }
    }

    fun select(nfds: Int, checkfds: Pointer, clearfds: Pointer?, checkRead: Boolean): Int {
        var count = 0
        for (i in 0 until nfds) {
            var mask = checkfds.getInt((i / 32).toLong())
            if (((mask shr i) and 1) == 1) {
                val io = fdMap[i]
                if (!checkRead || io!!.canRead()) {
                    count++
                } else {
                    mask = mask and (1 shl i).inv()
                    checkfds.setInt((i / 32).toLong(), mask)
                }
            }
        }
        if (count > 0) {
            if (clearfds != null) {
                for (i in 0 until nfds) {
                    clearfds.setInt((i / 32).toLong(), 0)
                }
            }
        }
        return count
    }

    protected open fun sigaltstack(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val ss = context.getPointerArg(0)
        val old_ss = context.getPointerArg(1)
        if (log.isDebugEnabled) {
            log.debug("sigaltstack ss={}, old_ss={}", ss, old_ss)
        }
        return 0
    }

    protected open fun renameat(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val olddirfd = context.getIntArg(0)
        val oldpath = context.getPointerArg(1)!!.getString(0L)
        val newdirfd = context.getIntArg(2)
        val newpath = context.getPointerArg(3)!!.getString(0L)
        val ret = emulator.getFileSystem().rename(oldpath, newpath)
        if (ret != 0) {
            log.info("renameat olddirfd={}, oldpath={}, newdirfd={}, newpath={}", olddirfd, oldpath, newdirfd, newpath)
        } else {
            log.debug("renameat olddirfd={}, oldpath={}, newdirfd={}, newpath={}", olddirfd, oldpath, newdirfd, newpath)
        }
        return 0
    }

    protected open fun unlinkat(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val dirfd = context.getIntArg(0)
        val pathname = context.getPointerArg(1)
        val flags = context.getIntArg(2)
        emulator.getFileSystem().unlink(pathname!!.getString(0L))
        if (log.isDebugEnabled) {
            log.info("unlinkat dirfd={}, pathname={}, flags={}", dirfd, pathname.getString(0L), flags)
        }
        return 0
    }

    protected open fun exit(emulator: Emulator<AndroidFileIO>) {
        val context = emulator.getContext<RegisterContext>()
        val status = context.getIntArg(0)
        val task = emulator.get<Task>(Task.TASK_KEY)
        if (task is ThreadTask) {
            task.setExitStatus(status)
            throw ThreadContextSwitchException().setReturnValue(0L)
        }
        println("exit status=$status")
        if (LoggerFactory.getLogger(AbstractEmulator::class.java).isDebugEnabled) {
            emulator.attach().debug("exit status=$status")
        }
        emulator.getBackend().emu_stop()
    }

    private val sigActionMap: MutableMap<Int, SigAction?> = HashMap()

    override fun createSignalHandlerTask(emulator: Emulator<*>, sig: Int): MainTask? {
        val action = sigActionMap[sig]
        if (action != null) {
            return SignalFunction(emulator, sig, action)
        }
        return super.createSignalHandlerTask(emulator, sig)
    }

    override fun sigaction(emulator: Emulator<*>, signum: Int, act: Pointer, oldact: Pointer): Int {
        val action = SigAction.create(emulator, act)
        val oldAction = SigAction.create(emulator, oldact)
        if (log.isDebugEnabled) {
            log.debug("sigaction signum={}, action={}, oldAction={}", signum, action, oldAction)
        }
        if (SIGKILL == signum || SIGSTOP == signum) {
            if (oldAction != null) {
                oldAction.setSaHandler(SIG_ERR.toLong())
                oldAction.pack()
            }
            return -UnixEmulator.EINVAL
        }
        val lastAction = sigActionMap.put(signum, action)
        if (oldAction != null) {
            if (lastAction == null) {
                oldact.write(0L, ByteArray(oldAction.size()), 0, oldAction.size())
            } else {
                oldAction.setSaHandler(lastAction.getSaHandler())
                oldAction.setSaRestorer(lastAction.getSaRestorer())
                oldAction.setFlags(lastAction.getFlags())
                oldAction.setMask(lastAction.getMask())
                oldAction.pack()
            }
        }
        return 0
    }

    protected open fun kill(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val pid = context.getIntArg(0)
        val sig = context.getIntArg(1)
        if (log.isDebugEnabled) {
            log.debug("kill pid={}, sig={}", pid, sig)
        }
        if (sig == 0) {
            return 0
        }
        if (sig < 0 || sig > 64) {
            return -UnixEmulator.EINVAL
        }
        val task = emulator.get<Task>(Task.TASK_KEY)
        if ((pid == 0 || pid == emulator.getPid()) && task != null) {
            val action = sigActionMap[sig]
            return processSignal(emulator.getThreadDispatcher(), sig, task, action, null)
        }
        throw UnsupportedOperationException("kill pid=" + pid + ", sig=" + sig + ", LR=" + context.getLRPointer())
    }

    private fun processSignal(threadDispatcher: ThreadDispatcher, sig: Int, task: Task, action: SigAction?, sig_info: Pointer?): Int {
        if (action != null) {
            val signalOps: SignalOps = if (task.isMainThread()) threadDispatcher else task
            val sigMaskSet = signalOps.getSigMaskSet()
            val sigPendingSet = signalOps.getSigPendingSet()
            if (sigMaskSet == null || !sigMaskSet.containsSigNumber(sig)) {
                task.addSignalTask(SignalTask(sig, action, sig_info))
                throw ThreadContextSwitchException().setReturnValue(0L)
            } else if (sigPendingSet != null) {
                sigPendingSet.addSigNumber(sig)
            }
        }
        return 0
    }

    protected open fun tgkill(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val tgid = context.getIntArg(0)
        val tid = context.getIntArg(1)
        val sig = context.getIntArg(2)
        if (log.isDebugEnabled) {
            log.debug("tgkill tgid={}, tid={}, sig={}", tgid, tid, sig)
        }
        if (sig == 0) {
            return 0
        }
        if (sig < 0 || sig > 64) {
            return -UnixEmulator.EINVAL
        }
        val action = sigActionMap[sig]
        if (threadDispatcherEnabled &&
                emulator.getThreadDispatcher().sendSignal(tid, sig, if (action == null || action.getSaHandler() == 0L) null else SignalTask(sig, action))) {
            throw ThreadContextSwitchException().setReturnValue(0L)
        }
        return 0
    }

    protected open fun set_tid_address(emulator: Emulator<AndroidFileIO>): Int {
        val context = emulator.getContext<RegisterContext>()
        val tidptr = context.getPointerArg(0)
        if (log.isDebugEnabled) {
            log.debug("set_tid_address tidptr={}", tidptr)
        }
        val task = emulator.get<Task>(Task.TASK_KEY)
        if (task is MarshmallowThread) {
            task.set_tid_address(tidptr)
        }
        return 0
    }

    private var threadId = 0

    protected fun incrementThreadId(emulator: Emulator<*>): Int {
        if (threadId == 0) {
            threadId = emulator.getPid()
        }
        return (++threadId) and 0xffff // http://androidxref.com/6.0.1_r10/xref/bionic/libc/bionic/pthread_mutex.cpp#215
    }

    protected open fun nanosleep(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val req = context.getPointerArg(0)
        val rem = context.getPointerArg(1)
        val timeSpec = TimeSpec.createTimeSpec(emulator, req)
        if (log.isDebugEnabled) {
            log.debug("nanosleep req={}, rem={}, timeSpec={}", req, rem, timeSpec)
        }
        val runningTask = emulator.getThreadDispatcher().getRunningTask()
        if (threadDispatcherEnabled && runningTask != null) {
            runningTask.setWaiter(emulator, NanoSleepWaiter(emulator, rem, timeSpec!!))
            throw ThreadContextSwitchException().setReturnValue(0L)
        } else {
            try {
                java.lang.Thread.sleep(timeSpec!!.toMillis())
            } catch (ignored: InterruptedException) {
            }
            return 0
        }
    }

    protected open fun fallocate(emulator: Emulator<AndroidFileIO>): Int {
        val context = emulator.getContext<RegisterContext>()
        val fd = context.getIntArg(0)
        val mode = context.getIntArg(1)
        val offset = context.getIntArg(2)
        val len = context.getIntArg(3)
        if (log.isDebugEnabled) {
            log.debug("fallocate fd={}, mode=0x{}, offset={}, len={}", fd, Integer.toHexString(mode), offset, len)
        }
        return 0
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(AndroidSyscallHandler::class.java)

        const val MREMAP_MAYMOVE = 1
        const val MREMAP_FIXED = 2

        private val EFD_SEMAPHORE = 1
        private val EFD_NONBLOCK = IOConstants.O_NONBLOCK
        private val EFD_CLOEXEC = IOConstants.O_CLOEXEC

        private const val SCHED_OTHER = 0

        private const val ANDROID_PRIORITY_NORMAL = 0 /* most threads run at normal priority */

        private const val SIG_BLOCK = 0
        private const val SIG_UNBLOCK = 1
        private const val SIG_SETMASK = 2

        private const val FUTEX_CMD_MASK = 0x7f
        private const val FUTEX_PRIVATE_FLAG = 0x80
        private const val MUTEX_SHARED_MASK = 0x2000
        private const val MUTEX_TYPE_MASK = 0xc000
        private const val FUTEX_WAIT = 0
        private const val FUTEX_WAKE = 1
        private const val FUTEX_FD = 2
        private const val FUTEX_REQUEUE = 3
        private const val FUTEX_CMP_REQUEUE = 4

        const val ETIMEDOUT = 110

        private const val SIGKILL = 9
        private const val SIGSTOP = 19
        private const val SIG_ERR = -1
    }

}
