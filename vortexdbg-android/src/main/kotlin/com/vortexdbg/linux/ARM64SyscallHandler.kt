package com.vortexdbg.linux

import com.vortexdbg.AbstractEmulator
import com.vortexdbg.Emulator
import com.vortexdbg.LongJumpException
import com.vortexdbg.StopEmulatorException
import com.vortexdbg.Svc
import com.vortexdbg.arm.ARM
import com.vortexdbg.arm.ARMEmulator
import com.vortexdbg.arm.Arm64Svc
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.arm.backend.BackendException
import com.vortexdbg.arm.context.Arm64RegisterContext
import com.vortexdbg.arm.context.RegisterContext
import com.vortexdbg.file.FileIO
import com.vortexdbg.file.FileResult
import com.vortexdbg.file.IOResolver
import com.vortexdbg.file.linux.AndroidFileIO
import com.vortexdbg.file.linux.IOConstants
import com.vortexdbg.linux.android.AndroidResolver
import com.vortexdbg.linux.file.ByteArrayFileIO
import com.vortexdbg.linux.file.DriverFileIO
import com.vortexdbg.linux.file.LocalAndroidUdpSocket
import com.vortexdbg.linux.file.LocalSocketIO
import com.vortexdbg.linux.file.NetLinkSocket
import com.vortexdbg.linux.file.PipedSocketIO
import com.vortexdbg.linux.file.SocketIO
import com.vortexdbg.linux.file.TcpSocket
import com.vortexdbg.linux.file.UdpSocket
import com.vortexdbg.linux.struct.RLimit64
import com.vortexdbg.linux.struct.Stat64
import com.vortexdbg.linux.thread.MarshmallowThread
import com.vortexdbg.memory.Memory
import com.vortexdbg.memory.SvcMemory
import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.thread.PopContextException
import com.vortexdbg.thread.Task
import com.vortexdbg.thread.ThreadContextSwitchException
import com.vortexdbg.unix.IO
import com.vortexdbg.unix.UnixEmulator
import com.vortexdbg.utils.Inspector
import com.sun.jna.Pointer
import org.apache.commons.io.FilenameUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import unicorn.Arm64Const

import java.util.ArrayList
import java.util.concurrent.TimeUnit

/**
 * [unistd](http://androidxref.com/6.0.0_r5/xref/external/kernel-headers/original/uapi/asm-generic/unistd.h)
 */
class ARM64SyscallHandler(private val svcMemory: SvcMemory) : AndroidSyscallHandler() {

    @Suppress("UNCHECKED_CAST")
    override fun hook(backend: Backend, intno: Int, swi: Int, user: Any?) {
        val emulator = user as Emulator<AndroidFileIO>
        val pc = VortexdbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_PC)

        if (intno == ARMEmulator.EXCP_BKPT) { // brk
            createBreaker(emulator).brk(pc, if (pc == null) swi else (pc.getInt(0) shr 5) and 0xffff)
            return
        }
        if (intno == ARMEmulator.EXCP_UDEF) {
            createBreaker(emulator).debug("Undefined instruction (EXCP_UDEF) at $pc")
            return
        }

        if (intno != ARMEmulator.EXCP_SWI) {
            throw BackendException("intno=$intno")
        }

        val NR = backend.reg_read(Arm64Const.UC_ARM64_REG_X8).toInt()
        var syscall: String? = null
        var exception: Throwable? = null
        try {
            if (swi == 0 && NR == 0 && backend.reg_read(Arm64Const.UC_ARM64_REG_X16).toInt() == Svc.POST_CALLBACK_SYSCALL_NUMBER) { // postCallback
                val number = backend.reg_read(Arm64Const.UC_ARM64_REG_X12).toInt()
                val svc = svcMemory.getSvc(number)
                if (svc != null) {
                    svc.handlePostCallback(emulator)
                    return
                }
                backend.emu_stop()
                throw IllegalStateException("svc number: $swi")
            }
            if (swi == 0 && NR == 0 && backend.reg_read(Arm64Const.UC_ARM64_REG_X16).toInt() == Svc.PRE_CALLBACK_SYSCALL_NUMBER) { // preCallback
                val number = backend.reg_read(Arm64Const.UC_ARM64_REG_X12).toInt()
                val svc = svcMemory.getSvc(number)
                if (svc != null) {
                    svc.handlePreCallback(emulator)
                    return
                }
                backend.emu_stop()
                throw IllegalStateException("svc number: $swi")
            }
            if (swi != 0) {
                if (swi == Arm64Svc.SVC_MAX) {
                    throw PopContextException()
                }
                if (swi == Arm64Svc.SVC_MAX - 1) {
                    throw ThreadContextSwitchException()
                }
                val svc = svcMemory.getSvc(swi)
                if (svc != null) {
                    log.debug("swi={}, svc={}", swi, svc)
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, svc.handle(emulator))
                    return
                }
                backend.emu_stop()
                throw BackendException("svc number: $swi")
            }

            if (log.isTraceEnabled) {
                ARM.showRegs64(emulator, null)
            }

            if (handleSyscall(emulator, NR)) {
                return
            }

            when (NR) {
                17 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, getcwd(emulator))
                    return
                }
                19 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, eventfd2(emulator))
                    return
                }
                64 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, write(emulator))
                    return
                }
                221 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, execve(emulator))
                    return
                }
                62 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, lseek(emulator))
                    return
                }
                172 -> { // getpid
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, emulator.getPid())
                    return
                }
                178 -> { // gettid
                    val task = emulator.get<Task>(Task.TASK_KEY)
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, if (task == null) 0 else task.getId())
                    return
                }
                129 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, kill(emulator))
                    return
                }
                29 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, ioctl(emulator))
                    return
                }
                34 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, mkdirat(emulator))
                    return
                }
                35 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, unlinkat(emulator))
                    return
                }
                38 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, renameat(emulator))
                    return
                }
                47 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, fallocate(emulator))
                    return
                }
                53 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, fchmodat(emulator))
                    return
                }
                54 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, fchownat(emulator))
                    return
                }
                56 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, openat(emulator))
                    return
                }
                57 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, close(backend, emulator))
                    return
                }
                59 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, pipe2(emulator))
                    return
                }
                63 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, read(backend, emulator))
                    return
                }
                24 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, dup3(emulator))
                    return
                }
                43 -> {
                    val context = emulator.getContext<RegisterContext>()
                    val pathPointer = context.getPointerArg(0)
                    val buf = context.getPointerArg(1)
                    val path = pathPointer!!.getString(0)
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, statfs64(emulator, path, buf!!))
                    return
                }
                46 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, ftruncate(emulator))
                    return
                }
                134 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, sigaction(emulator))
                    return
                }
                72 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, pselect6(emulator))
                    return
                }
                78 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, readlinkat(emulator))
                    return
                }
                80 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, fstat(backend, emulator))
                    return
                }
                83 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, fdatasync(emulator))
                    return
                }
                96 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, set_tid_address(emulator))
                    return
                }
                98 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, futex(emulator))
                    return
                }
                220 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, clone(emulator))
                    return
                }
                160 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, uname(emulator))
                    return
                }
                132 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, sigaltstack(emulator))
                    return
                }
                135 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, sigprocmask(emulator))
                    return
                }
                32 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, flock(emulator))
                    return
                }
                66 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, writev(emulator))
                    return
                }
                101 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, nanosleep(emulator))
                    return
                }
                119 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, sched_setscheduler(emulator))
                    return
                }
                122 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, sched_setaffinity(emulator))
                    return
                }
                123 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, sched_getaffinity(emulator))
                    return
                }
                124 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, sched_yield(emulator))
                    return
                }
                136 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, rt_sigpending(emulator))
                    return
                }
                137 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, rt_sigtimedwait(emulator))
                    return
                }
                138 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, rt_sigqueue(emulator))
                    return
                }
                140 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, setpriority(emulator))
                    return
                }
                167 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, prctl(emulator))
                    return
                }
                169 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, gettimeofday(emulator))
                    return
                }
                73 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, ppoll(emulator))
                    return
                }
                173 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, getppid(emulator))
                    return
                }
                174, // getuid
                175 -> { // geteuid
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, 0)
                    return
                }
                200 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, bind(emulator))
                    return
                }
                201 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, listen(emulator))
                    return
                }
                214 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, brk(backend, emulator))
                    return
                }
                215 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, munmap(backend, emulator))
                    return
                }
                216 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, mremap(emulator))
                    return
                }
                61 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, getdents64(emulator))
                    return
                }
                233 -> {
                    syscall = "madvise"
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, 0)
                    return
                }
                25 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, fcntl(emulator))
                    return
                }
                222 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, mmap(backend, emulator))
                    return
                }
                226 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, mprotect(backend, emulator))
                    return
                }
                227 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, msync(emulator))
                    return
                }
                93 -> {
                    exit(emulator)
                    return
                }
                94 -> {
                    exit_group(emulator)
                    return
                }
                113 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, clock_gettime(emulator))
                    return
                }
                117 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, ptrace(emulator))
                    return
                }
                120 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, sched_getscheduler(emulator))
                    return
                }
                121 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, sched_getparam(emulator))
                    return
                }
                131 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, tgkill(emulator))
                    return
                }
                141 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, getpriority(emulator))
                    return
                }
                163 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, getrlimit64(emulator))
                    return
                }
                198 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, socket(emulator))
                    return
                }
                199 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, socketpair(emulator))
                    return
                }
                203 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, connect(emulator))
                    return
                }
                204 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, getsockname(emulator))
                    return
                }
                242 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, accept4(emulator))
                    return
                }
                205 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, getpeername(emulator))
                    return
                }
                206 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, sendto(emulator))
                    return
                }
                207 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, recvfrom(emulator))
                    return
                }
                208 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, setsockopt(emulator))
                    return
                }
                209 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, getsockopt(emulator))
                    return
                }
                228 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, mlock(emulator))
                    return
                }
                278 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, gerrandom(emulator))
                    return
                }
                79 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, fstatat64(emulator))
                    return
                }
                48 -> {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, faccessat(emulator))
                    return
                }
            }
        } catch (e: StopEmulatorException) {
            backend.emu_stop()
            return
        } catch (e: LongJumpException) {
            backend.emu_stop()
            throw e
        } catch (e: Throwable) {
            backend.emu_stop()
            exception = e
        }

        if (exception == null && handleUnknownSyscall(emulator, NR)) {
            return
        }

        log.warn("handleInterrupt intno={}, NR={}, svcNumber=0x{}, PC={}, LR={}, syscall={}", intno, NR, Integer.toHexString(swi), pc, VortexdbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_LR), syscall, exception)
        if (log.isDebugEnabled) {
            emulator.attach().debug("Unhandled syscall NR=$NR ($syscall) at $pc")
        }
        if (exception is RuntimeException) {
            throw exception
        }
    }

    private fun getrlimit64(emulator: Emulator<AndroidFileIO>): Long {
        val context = emulator.getContext<RegisterContext>()
        val resource = context.getIntArg(0)
        val ptr = context.getPointerArg(1)
        if (resource == RLIMIT_STACK) {
            val rlimit64 = RLimit64(ptr)
            val size = Memory.STACK_SIZE_OF_PAGE.toLong() * emulator.getPageAlign()
            rlimit64.rlim_cur = size
            rlimit64.rlim_max = size
            rlimit64.pack()
            return 0
        } else {
            throw UnsupportedOperationException("getrlimit64 resource=$resource, rlimit64=$ptr")
        }
    }

    private fun msync(emulator: Emulator<AndroidFileIO>): Long {
        val context = emulator.getContext<RegisterContext>()
        val addr = context.getPointerArg(0)
        val len = context.getIntArg(1)
        val flags = context.getIntArg(2)
        if (log.isDebugEnabled) {
            log.debug("msync addr={}, len={}, flags=0x{}", addr, len, Integer.toHexString(flags))
        }
        return 0
    }

    private fun fdatasync(emulator: Emulator<AndroidFileIO>): Long {
        val context = emulator.getContext<RegisterContext>()
        val fd = context.getIntArg(0)
        if (log.isDebugEnabled) {
            log.debug("fdatasync fd={}", fd)
        }
        return 0
    }

    private fun gerrandom(emulator: Emulator<*>): Long {
        val context = emulator.getContext<RegisterContext>()
        val buf = context.getPointerArg(0)
        val bufSize = context.getIntArg(1)
        val flags = context.getIntArg(2)
        return getrandom(buf!!, bufSize, flags).toLong()
    }

    private fun clone(emulator: Emulator<*>): Long {
        val context = emulator.getContext<Arm64RegisterContext>()
        val child_stack = context.getPointerArg(1)
        if (child_stack == null &&
                context.getPointerArg(2) == null) {
            // http://androidxref.com/6.0.1_r10/xref/bionic/libc/bionic/fork.cpp#47
            return fork(emulator) // vfork
        }

        val fn = context.getXLong(5)
        val arg = context.getXLong(6)
        if (child_stack != null && child_stack.getLong(0) == fn && child_stack.getLong(8) == arg) {
            // http://androidxref.com/6.0.1_r10/xref/bionic/libc/arch-arm/bionic/__bionic_clone.S#49
            return bionic_clone(emulator).toLong()
        } else {
            return pthread_clone(emulator).toLong()
        }
    }

    private fun pthread_clone(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val flags = context.getIntArg(0)
        var child_stack = context.getPointerArg(1)
        val list = ArrayList<String>()
        if ((flags and CLONE_VM) != 0) {
            list.add("CLONE_VM")
        }
        if ((flags and CLONE_FS) != 0) {
            list.add("CLONE_FS")
        }
        if ((flags and CLONE_FILES) != 0) {
            list.add("CLONE_FILES")
        }
        if ((flags and CLONE_SIGHAND) != 0) {
            list.add("CLONE_SIGHAND")
        }
        if ((flags and CLONE_PTRACE) != 0) {
            list.add("CLONE_PTRACE")
        }
        if ((flags and CLONE_VFORK) != 0) {
            list.add("CLONE_VFORK")
        }
        if ((flags and CLONE_PARENT) != 0) {
            list.add("CLONE_PARENT")
        }
        if ((flags and CLONE_THREAD) != 0) {
            list.add("CLONE_THREAD")
        }
        if ((flags and CLONE_NEWNS) != 0) {
            list.add("CLONE_NEWNS")
        }
        if ((flags and CLONE_SYSVSEM) != 0) {
            list.add("CLONE_SYSVSEM")
        }
        if ((flags and CLONE_SETTLS) != 0) {
            list.add("CLONE_SETTLS")
        }
        if ((flags and CLONE_PARENT_SETTID) != 0) {
            list.add("CLONE_PARENT_SETTID")
        }
        if ((flags and CLONE_CHILD_CLEARTID) != 0) {
            list.add("CLONE_CHILD_CLEARTID")
        }
        if ((flags and CLONE_DETACHED) != 0) {
            list.add("CLONE_DETACHED")
        }
        if ((flags and CLONE_UNTRACED) != 0) {
            list.add("CLONE_UNTRACED")
        }
        if ((flags and CLONE_CHILD_SETTID) != 0) {
            list.add("CLONE_CHILD_SETTID")
        }
        if ((flags and CLONE_STOPPED) != 0) {
            list.add("CLONE_STOPPED")
        }
        val threadId = incrementThreadId(emulator)

        val fn = child_stack!!.getPointer(0)
        child_stack = child_stack.share(8, 0)
        val arg = child_stack.getPointer(0)
        child_stack = child_stack.share(8, 0)

        if (threadDispatcherEnabled) {
            throw UnsupportedOperationException()
        }

        log.info("pthread_clone child_stack={}, thread_id={}, fn={}, arg={}, flags={}", child_stack, threadId, fn, arg, list)
        val log = LoggerFactory.getLogger(AbstractEmulator::class.java)
        if (log.isDebugEnabled) {
            emulator.attach().debug("pthread_clone thread_id=$threadId, fn=$fn")
        }
        return threadId
    }

    protected fun fork(emulator: Emulator<*>): Long {
        log.info("fork")
        emulator.getMemory().setErrno(UnixEmulator.ENOSYS)
        return -1
    }

    private fun bionic_clone(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val flags = context.getIntArg(0)
        val child_stack = context.getPointerArg(1)
        val pid = context.getPointerArg(2)
        val tls = context.getPointerArg(3)
        val ctid = context.getPointerArg(4)
        val fn = context.getPointerArg(5)
        val arg = context.getPointerArg(6)
        val list = ArrayList<String>()
        if ((flags and CLONE_VM) != 0) {
            list.add("CLONE_VM")
        }
        if ((flags and CLONE_FS) != 0) {
            list.add("CLONE_FS")
        }
        if ((flags and CLONE_FILES) != 0) {
            list.add("CLONE_FILES")
        }
        if ((flags and CLONE_SIGHAND) != 0) {
            list.add("CLONE_SIGHAND")
        }
        if ((flags and CLONE_PTRACE) != 0) {
            list.add("CLONE_PTRACE")
        }
        if ((flags and CLONE_VFORK) != 0) {
            list.add("CLONE_VFORK")
        }
        if ((flags and CLONE_PARENT) != 0) {
            list.add("CLONE_PARENT")
        }
        if ((flags and CLONE_THREAD) != 0) {
            list.add("CLONE_THREAD")
        }
        if ((flags and CLONE_NEWNS) != 0) {
            list.add("CLONE_NEWNS")
        }
        if ((flags and CLONE_SYSVSEM) != 0) {
            list.add("CLONE_SYSVSEM")
        }
        if ((flags and CLONE_SETTLS) != 0) {
            list.add("CLONE_SETTLS")
        }
        if ((flags and CLONE_PARENT_SETTID) != 0) {
            list.add("CLONE_PARENT_SETTID")
        }
        if ((flags and CLONE_CHILD_CLEARTID) != 0) {
            list.add("CLONE_CHILD_CLEARTID")
        }
        if ((flags and CLONE_DETACHED) != 0) {
            list.add("CLONE_DETACHED")
        }
        if ((flags and CLONE_UNTRACED) != 0) {
            list.add("CLONE_UNTRACED")
        }
        if ((flags and CLONE_CHILD_SETTID) != 0) {
            list.add("CLONE_CHILD_SETTID")
        }
        if ((flags and CLONE_STOPPED) != 0) {
            list.add("CLONE_STOPPED")
        }
        if (log.isDebugEnabled) {
            log.debug("bionic_clone child_stack={}, pid={}, tls={}, ctid={}, fn={}, arg={}, flags={}", child_stack, pid, tls, ctid, fn, arg, list)
        }
        val threadId = incrementThreadId(emulator)
        if (threadDispatcherEnabled) {
            if (verbose) {
                System.out.printf("bionic_clone fn=%s, LR=%s%n", fn, context.getLRPointer())
            }
            emulator.getThreadDispatcher().addThread(MarshmallowThread(emulator, fn!!, arg!!, ctid, threadId))
        }
        ctid!!.setInt(0, threadId)
        return threadId
    }

    private fun flock(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val fd = context.getIntArg(0)
        val operation = context.getIntArg(1)
        if (log.isDebugEnabled) {
            log.debug("flock fd={}, operation={}", fd, operation)
        }
        return 0
    }

    private fun execve(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val filename = context.getPointerArg(0)
        var argv: Pointer? = context.getPointerArg(1)
        var envp: Pointer? = context.getPointerArg(2)
        checkNotNull(filename)
        val args = ArrayList<String>()
        var pointer: Pointer?
        while ((argv!!.getPointer(0).also { pointer = it }) != null) {
            args.add(pointer!!.getString(0))
            argv = argv!!.share(8)
        }
        val env = ArrayList<String>()
        while ((envp!!.getPointer(0).also { pointer = it }) != null) {
            env.add(pointer!!.getString(0))
            envp = envp!!.share(8)
        }
        log.info("execve filename={}, args={}, env={}", filename.getString(0), args, env)
        emulator.getMemory().setErrno(UnixEmulator.EACCES)
        return -1
    }

    private fun bind(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val sockfd = context.getIntArg(0)
        val addr = context.getPointerArg(1)
        val addrlen = context.getIntArg(2)
        return bind(emulator, sockfd, addr!!, addrlen)
    }

    private fun listen(emulator: Emulator<AndroidFileIO>): Int {
        val context = emulator.getContext<RegisterContext>()
        val sockfd = context.getIntArg(0)
        val backlog = context.getIntArg(1)
        return listen(emulator, sockfd, backlog)
    }

    protected fun stat64(emulator: Emulator<AndroidFileIO>, pathname: String, statbuf: Pointer): Int {
        val result = resolve(emulator, pathname, IOConstants.O_RDONLY)
        if (result != null && result.isSuccess()) {
            return result.io!!.fstat(emulator, Stat64(statbuf))
        }

        if (verbose) {
            log.info("stat64 pathname={}", pathname)
        }
        emulator.getMemory().setErrno(if (result != null) result.errno else UnixEmulator.ENOENT)
        return -1
    }

    private fun getpeername(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val sockfd = context.getIntArg(0)
        val addr = context.getPointerArg(1)
        val addrlen = context.getPointerArg(2)
        if (log.isDebugEnabled) {
            log.debug("getpeername sockfd={}, addr={}, addrlen={}", sockfd, addr, addrlen)
        }

        val io = fdMap.get(sockfd)
        if (io == null) {
            emulator.getMemory().setErrno(UnixEmulator.EBADF)
            return -1
        }

        return io.getpeername(addr!!, addrlen!!)
    }

    private fun ppoll(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val fds = context.getPointerArg(0)
        val nfds = context.getIntArg(1)
        val tmo_p = context.getPointerArg(2)
        val sigmask = context.getPointerArg(3)
        var count = 0
        for (i in 0 until nfds) {
            val pollfd = fds!!.share(i * 8L)
            val fd = pollfd.getInt(0)
            val events = pollfd.getShort(4) // requested events
            if (log.isDebugEnabled) {
                log.debug("ppoll fds={}, nfds={}, tmo_p={}, sigmask={}, fd={}, events={}", fds, nfds, tmo_p, sigmask, fd, events)
            }
            if (fd < 0) {
                pollfd.setShort(6, 0.toShort())
            } else {
                var revents: Short = 0
                if ((events.toInt() and POLLOUT) != 0) {
                    revents = POLLOUT.toShort()
                } else if ((events.toInt() and POLLIN) != 0) {
                    revents = POLLIN.toShort()
                }
                pollfd.setShort(6, revents) // returned events
                count++
            }
        }
        return count
    }

    private fun sigprocmask(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val how = context.getIntArg(0)
        val set = context.getPointerArg(1)
        val oldset = context.getPointerArg(2)
        return sigprocmask(emulator, how, set!!, oldset!!)
    }

    private fun ftruncate(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val fd = context.getIntArg(0)
        val length = context.getIntArg(1)
        if (log.isDebugEnabled) {
            log.debug("ftruncate fd={}, length={}", fd, length)
        }
        val file = fdMap.get(fd) ?: throw UnsupportedOperationException()
        return file.ftruncate(length)
    }

    private fun sigaction(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val signum = context.getIntArg(0)
        val act = context.getPointerArg(1)
        val oldact = context.getPointerArg(2)

        return sigaction(emulator, signum, act!!, oldact!!)
    }

    private fun pselect6(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val nfds = context.getIntArg(0)
        val readfds = context.getPointerArg(1)
        val writefds = context.getPointerArg(2)
        val exceptfds = context.getPointerArg(3)
        val timeout = context.getPointerArg(4)
        val size = (nfds - 1) / 8 + 1
        if (log.isDebugEnabled) {
            log.debug("pselect6 nfds={}, readfds={}, writefds={}, exceptfds={}, timeout={}, LR={}", nfds, readfds, writefds, exceptfds, timeout, context.getLRPointer())
            if (readfds != null) {
                val data = readfds.getByteArray(0, size)
                Inspector.inspect(data, "readfds")
            }
            if (writefds != null) {
                val data = writefds.getByteArray(0, size)
                Inspector.inspect(data, "writefds")
            }
        }
        if (exceptfds != null) {
            emulator.getMemory().setErrno(UnixEmulator.ENOMEM)
            return -1
        }
        if (writefds != null) {
            val count = select(nfds, writefds, readfds, false)
            if (count > 0) {
                return count
            }
        }
        if (readfds != null) {
            val count = select(nfds, readfds, writefds, true)
            if (count == 0) {
                try {
                    TimeUnit.SECONDS.sleep(1)
                } catch (e: InterruptedException) {
                    throw IllegalStateException(e)
                }
            }
            return count
        }
        throw AbstractMethodError("pselect6 nfds=$nfds, readfds=null, writefds=$writefds, exceptfds=null, timeout=$timeout, LR=" + context.getLRPointer())
    }

    private fun recvfrom(emulator: Emulator<*>): Int {
        val backend = emulator.getBackend()
        val context = emulator.getContext<RegisterContext>()
        val sockfd = context.getIntArg(0)
        val buf = context.getPointerArg(1)
        val len = context.getIntArg(2)
        val flags = context.getIntArg(3)
        val src_addr = context.getPointerArg(4)
        val addrlen = context.getPointerArg(5)

        log.debug("recvfrom sockfd={}, buf={}, len={}, flags={}, src_addr={}, addrlen={}", sockfd, buf, len, flags, src_addr, addrlen)
        val file = fdMap.get(sockfd)
        if (file == null) {
            emulator.getMemory().setErrno(UnixEmulator.EBADF)
            return -1
        }
        return file.recvfrom(backend, buf!!, len, flags, src_addr!!, addrlen!!)
    }

    private fun sendto(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val sockfd = context.getIntArg(0)
        val buf = context.getPointerArg(1)
        val len = context.getIntArg(2)
        val flags = context.getIntArg(3)
        val dest_addr = context.getPointerArg(4)
        val addrlen = context.getIntArg(5)

        return sendto(emulator, sockfd, buf!!, len, flags, dest_addr!!, addrlen)
    }

    private fun connect(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val sockfd = context.getIntArg(0)
        val addr = context.getPointerArg(1)
        val addrlen = context.getIntArg(2)
        return connect(emulator, sockfd, addr!!, addrlen)
    }

    private fun getsockname(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val sockfd = context.getIntArg(0)
        val addr = context.getPointerArg(1)
        val addrlen = context.getPointerArg(2)
        if (log.isDebugEnabled) {
            log.debug("getsockname sockfd={}, addr={}, addrlen={}", sockfd, addr, addrlen)
        }
        val file = fdMap.get(sockfd)
        if (file == null) {
            emulator.getMemory().setErrno(UnixEmulator.EBADF)
            return -1
        }
        return file.getsockname(addr!!, addrlen!!)
    }

    private fun accept4(emulator: Emulator<AndroidFileIO>): Int {
        val context = emulator.getContext<RegisterContext>()
        val sockfd = context.getIntArg(0)
        val addr = context.getPointerArg(1)
        val addrlen = context.getPointerArg(2)
        val flags = context.getIntArg(3)
        return accept(emulator, sockfd, addr, addrlen, flags)
    }

    protected fun accept(emulator: Emulator<AndroidFileIO>, sockfd: Int, addr: Pointer?, addrlen: Pointer?, flags: Int): Int {
        if (log.isDebugEnabled) {
            log.debug("accept sockfd={}, addr={}, addrlen={}, flags={}", sockfd, addr, addrlen, flags)
        }

        val file = fdMap.get(sockfd)
        if (file == null) {
            emulator.getMemory().setErrno(UnixEmulator.EBADF)
            return -1
        }
        val newIO = file.accept(addr!!, addrlen!!)
        if (newIO == null) {
            return -1
        } else {
            val fd = getMinFd()
            fdMap.put(fd, newIO)
            return fd
        }
    }

    private fun getsockopt(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val sockfd = context.getIntArg(0)
        val level = context.getIntArg(1)
        val optname = context.getIntArg(2)
        val optval = context.getPointerArg(3)
        val optlen = context.getPointerArg(4)
        if (log.isDebugEnabled) {
            log.debug("getsockopt sockfd={}, level={}, optname={}, optval={}, optlen={}", sockfd, level, optname, optval, optlen)
        }

        val file = fdMap.get(sockfd)
        if (file == null) {
            emulator.getMemory().setErrno(UnixEmulator.EBADF)
            return -1
        }
        return file.getsockopt(level, optname, optval!!, optlen!!)
    }

    private fun setsockopt(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val sockfd = context.getIntArg(0)
        val level = context.getIntArg(1)
        val optname = context.getIntArg(2)
        val optval = context.getPointerArg(3)
        val optlen = context.getIntArg(4)
        if (log.isDebugEnabled) {
            log.debug("setsockopt sockfd={}, level={}, optname={}, optval={}, optlen={}", sockfd, level, optname, optval, optlen)
        }

        val file = fdMap.get(sockfd)
        if (file == null) {
            emulator.getMemory().setErrno(UnixEmulator.EBADF)
            return -1
        }
        return file.setsockopt(level, optname, optval!!, optlen)
    }

    private var sdk = 0

    override fun addIOResolver(resolver: IOResolver<AndroidFileIO>) {
        super.addIOResolver(resolver)

        if (resolver is AndroidResolver) {
            sdk = resolver.getSdk()
        }
    }

    /**
     * create AF_UNIX local SOCK_STREAM
     */
    protected fun createLocalSocketIO(emulator: Emulator<*>, sdk: Int): AndroidFileIO {
        return LocalSocketIO(emulator, sdk)
    }

    private fun socketpair(emulator: Emulator<AndroidFileIO>): Long {
        val context = emulator.getContext<RegisterContext>()
        val domain = context.getIntArg(0)
        val type = context.getIntArg(1) and 0x7ffff
        val protocol = context.getIntArg(2)
        val sv = context.getPointerArg(3)
        log.debug("socketpair domain={}, type={}, protocol={}, sv={}", domain, type, protocol, sv)

        if (protocol != SocketIO.AF_UNSPEC) {
            throw UnsupportedOperationException()
        }
        if (domain == SocketIO.AF_LOCAL) {
            when (type) {
                SocketIO.SOCK_STREAM, SocketIO.SOCK_SEQPACKET -> {
                    val fd0 = getMinFd()
                    val one = PipedSocketIO(emulator)
                    fdMap.put(fd0, one)
                    val fd1 = getMinFd()
                    val two = PipedSocketIO(emulator)
                    fdMap.put(fd1, two)
                    one.connectPeer(two)
                    sv!!.setInt(0, fd0)
                    sv.setInt(4, fd1)
                    return 0
                }
                else -> {
                }
            }
        }
        throw UnsupportedOperationException("domain=$domain, type=$type, LR=" + context.getLRPointer())
    }

    private fun socket(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val domain = context.getIntArg(0)
        val type = context.getIntArg(1) and 0x7ffff
        val protocol = context.getIntArg(2)
        log.debug("socket domain={}, type={}, protocol={}", domain, type, protocol)

        if (protocol == SocketIO.IPPROTO_ICMP) {
            throw UnsupportedOperationException()
        }

        val fd: Int
        when (domain) {
            SocketIO.AF_UNSPEC -> throw UnsupportedOperationException()
            SocketIO.AF_LOCAL -> when (type) {
                SocketIO.SOCK_STREAM -> {
                    fd = getMinFd()
                    fdMap.put(fd, createLocalSocketIO(emulator, sdk))
                    return fd
                }
                SocketIO.SOCK_DGRAM -> {
                    fd = getMinFd()
                    fdMap.put(fd, LocalAndroidUdpSocket(emulator))
                    return fd
                }
                else -> {
                    emulator.getMemory().setErrno(UnixEmulator.EACCES)
                    return -1
                }
            }
            SocketIO.AF_INET, SocketIO.AF_INET6 -> when (type) {
                SocketIO.SOCK_STREAM -> {
                    fd = getMinFd()
                    fdMap.put(fd, TcpSocket(emulator))
                    return fd
                }
                SocketIO.SOCK_DGRAM -> {
                    fd = getMinFd()
                    fdMap.put(fd, UdpSocket(emulator))
                    return fd
                }
                SocketIO.SOCK_RAW -> throw UnsupportedOperationException()
            }
            SocketIO.AF_NETLINK -> when (type) {
                SocketIO.SOCK_DGRAM -> {
                    fd = getMinFd()
                    fdMap.put(fd, NetLinkSocket(emulator))
                    return fd
                }
                SocketIO.SOCK_RAW -> throw UnsupportedOperationException()
                else -> throw UnsupportedOperationException()
            }
        }
        log.info("socket domain={}, type={}, protocol={}", domain, type, protocol)
        emulator.getMemory().setErrno(UnixEmulator.EAFNOSUPPORT)
        return -1
    }

    protected fun uname(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val buf = context.getPointerArg(0)
        if (log.isDebugEnabled) {
            log.debug("uname buf={}", buf)
        }

        val SYS_NMLN = 65

        val sysName = buf!!.share(0)
        sysName.setString(0, "Linux") /* Operating system name (e.g., "Linux") */

        val nodeName = sysName.share(SYS_NMLN.toLong())
        nodeName.setString(0, "localhost") /* Name within "some implementation-defined network" */

        val release = nodeName.share(SYS_NMLN.toLong())
        release.setString(0, "1.0.0-vortexdbg") /* Operating system release (e.g., "2.6.28") */

        val version = release.share(SYS_NMLN.toLong())
        version.setString(0, "#1 SMP PREEMPT Thu Apr 19 14:36:58 CST 2018") /* Operating system version */

        val machine = version.share(SYS_NMLN.toLong())
        machine.setString(0, "armv8l") /* Hardware identifier */

        val domainName = machine.share(SYS_NMLN.toLong())
        domainName.setString(0, "localdomain") /* NIS or YP domain name */

        return 0
    }

    private fun getppid(emulator: Emulator<AndroidFileIO>): Int {
        if (log.isDebugEnabled) {
            log.debug("getppid")
        }
        return emulator.getPid()
    }

    private fun exit_group(emulator: Emulator<*>) {
        val context = emulator.getContext<RegisterContext>()
        val status = context.getIntArg(0)
        if (log.isDebugEnabled) {
            log.debug("exit with code: {}", status, Exception("exit_group status=$status"))
        } else {
            println("exit with code: $status")
        }
        if (LoggerFactory.getLogger(AbstractEmulator::class.java).isDebugEnabled) {
            createBreaker(emulator).debug("exit_group status=$status")
        }
        emulator.getBackend().emu_stop()
    }

    private fun munmap(backend: Backend, emulator: Emulator<*>): Int {
        val timeInMillis = System.currentTimeMillis()
        val start = backend.reg_read(Arm64Const.UC_ARM64_REG_X0).toLong()
        val length = backend.reg_read(Arm64Const.UC_ARM64_REG_X1).toInt()
        emulator.getMemory().munmap(start, length)
        if (log.isDebugEnabled) {
            log.debug("munmap start=0x{}, length={}, offset={}", java.lang.Long.toHexString(start), length, System.currentTimeMillis() - timeInMillis)
        }
        return 0
    }

    private fun mremap(emulator: Emulator<*>): Long {
        val context = emulator.getContext<Arm64RegisterContext>()
        val old_address = context.getXPointer(0)
        val old_size = context.getXInt(1)
        val new_size = context.getXInt(2)
        val flags = context.getXInt(3)
        val new_address = context.getXPointer(4)
        if (log.isDebugEnabled) {
            log.debug("mremap old_address={}, old_size={}, new_size={}, flags={}, new_address={}", old_address, old_size, new_size, flags, new_address)
        }
        if (old_size == 0) {
            throw BackendException("old_size is zero")
        }
        val fixed = (flags and AndroidSyscallHandler.MREMAP_FIXED) != 0
        if ((flags and AndroidSyscallHandler.MREMAP_MAYMOVE) == 0) {
            throw BackendException("flags=$flags")
        }

        val memory = emulator.getMemory()
        val data = old_address.getByteArray(0, old_size)
        val prot = memory.munmap(old_address.toUIntPeer(), old_size)
        val address: Long
        if (fixed) {
            address = memory.mmap2(new_address.toUIntPeer(), new_size, prot, AndroidElfLoader.MAP_ANONYMOUS or AndroidElfLoader.MAP_FIXED, 0, 0)
        } else {
            address = memory.mmap2(0, new_size, prot, AndroidElfLoader.MAP_ANONYMOUS, 0, 0)
        }
        val pointer = VortexdbgPointer.pointer(emulator, address)
        checkNotNull(pointer)
        pointer.write(0, data, 0, data.size)
        return pointer.toUIntPeer()
    }

    private fun prctl(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val option = context.getIntArg(0)
        val arg2 = context.getLongArg(1)
        if (log.isDebugEnabled) {
            log.debug("prctl option=0x{}, arg2=0x{}, task={}", Integer.toHexString(option), java.lang.Long.toHexString(arg2), emulator.getThreadDispatcher().getRunningTask())
        }
        when (option) {
            PR_SET_NAME -> {
                val threadName = context.getPointerArg(1)
                if (log.isDebugEnabled) {
                    log.debug("prctl set thread name: {}", threadName!!.getString(0))
                }
                return 0
            }
            BIONIC_PR_SET_VMA -> {
                val addr = context.getPointerArg(2)
                val len = context.getIntArg(3)
                val pointer = context.getPointerArg(4)
                if (log.isDebugEnabled) {
                    log.debug("prctl set vma addr={}, len={}, pointer={}, name={}", addr, len, pointer, pointer!!.getString(0))
                }
                return 0
            }
            PR_SET_PTRACER -> {
                val pid = arg2.toInt()
                if (log.isDebugEnabled) {
                    log.debug("prctl set ptracer: {}", pid)
                }
                return 0
            }
            PR_SET_NO_NEW_PRIVS, PR_SET_THP_DISABLE -> return 0
            else -> throw UnsupportedOperationException("option=$option")
        }
    }

    private val nanoTime = System.nanoTime()

    protected fun clock_gettime(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val clk_id = context.getIntArg(0) and 0x7
        val tp = context.getPointerArg(1)
        val offset = if (clk_id == CLOCK_REALTIME) currentTimeMillis() * 1000000L else System.nanoTime() - nanoTime
        val tv_sec = offset / 1000000000L
        val tv_nsec = offset % 1000000000L
        if (log.isDebugEnabled) {
            log.debug("clock_gettime clk_id={}, tp={}, offset={}, tv_sec={}, tv_nsec={}", clk_id, tp, offset, tv_sec, tv_nsec)
        }
        when (clk_id) {
            CLOCK_REALTIME, CLOCK_MONOTONIC, CLOCK_THREAD_CPUTIME_ID, CLOCK_MONOTONIC_RAW, CLOCK_MONOTONIC_COARSE, CLOCK_BOOTTIME -> {
                tp!!.setLong(0, tv_sec)
                tp.setLong(8, tv_nsec)
                return 0
            }
        }
        if (log.isDebugEnabled) {
            emulator.attach().debug("Unsupported clock_gettime clk_id=$clk_id")
        }
        throw UnsupportedOperationException("clk_id=$clk_id")
    }

    protected fun ptrace(emulator: Emulator<*>): Long {
        val context = emulator.getContext<RegisterContext>()
        val request = context.getIntArg(0)
        val pid = context.getIntArg(1)
        val addr = context.getPointerArg(2)
        val data = context.getPointerArg(3)
        log.info("ptrace request=0x{}, pid={}, addr={}, data={}", Integer.toHexString(request), pid, addr, data)
        return 0
    }

    private fun fcntl(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val fd = context.getIntArg(0)
        val cmd = context.getIntArg(1)
        val arg = context.getIntArg(2)
        return fcntl(emulator, fd, cmd, arg.toLong())
    }

    private fun writev(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val fd = context.getIntArg(0)
        val iov = context.getPointerArg(1)
        val iovcnt = context.getIntArg(2)
        if (log.isDebugEnabled) {
            for (i in 0 until iovcnt) {
                val iov_base = iov!!.getPointer(i * 16L)
                val iov_len = iov.getLong(i * 16L + 8)
                val data = iov_base.getByteArray(0, iov_len.toInt())
                Inspector.inspect(data, "writev fd=$fd, iov=$iov, iov_base=$iov_base")
            }
        }

        val file = fdMap.get(fd)
        if (file == null) {
            emulator.getMemory().setErrno(UnixEmulator.EBADF)
            return -1
        }

        var count = 0
        for (i in 0 until iovcnt) {
            val iov_base = iov!!.getPointer(i * 16L)
            val iov_len = iov.getLong(i * 16L + 8)
            val data = iov_base.getByteArray(0, iov_len.toInt())
            count += file.write(data)
        }
        return count
    }

    private fun brk(backend: Backend, emulator: Emulator<*>): Long {
        val address = backend.reg_read(Arm64Const.UC_ARM64_REG_X0).toLong()
        if (log.isDebugEnabled) {
            log.debug("brk address=0x{}", java.lang.Long.toHexString(address))
        }
        return emulator.getMemory().brk(address).toLong()
    }

    private fun mprotect(backend: Backend, emulator: Emulator<*>): Int {
        val address = backend.reg_read(Arm64Const.UC_ARM64_REG_X0).toLong()
        val length = backend.reg_read(Arm64Const.UC_ARM64_REG_X1).toInt()
        val prot = backend.reg_read(Arm64Const.UC_ARM64_REG_X2).toInt()
        val pageAlign = emulator.getPageAlign().toLong()
        val alignedAddress = address / pageAlign * pageAlign
        val offset = address - alignedAddress

        val alignedLength = ARM.alignSize(length + offset, emulator.getPageAlign().toLong())
        if (log.isDebugEnabled) {
            log.debug("mprotect address=0x{}, alignedAddress=0x{}, offset={}, length={}, alignedLength={}, prot=0x{}", java.lang.Long.toHexString(address), java.lang.Long.toHexString(alignedAddress), offset, length, alignedLength, Integer.toHexString(prot))
        }
        return emulator.getMemory().mprotect(alignedAddress, alignedLength.toInt(), prot)
    }

    private fun mmap(backend: Backend, emulator: Emulator<*>): Long {
        val start = backend.reg_read(Arm64Const.UC_ARM64_REG_X0).toLong()
        val length = backend.reg_read(Arm64Const.UC_ARM64_REG_X1).toInt()
        val prot = backend.reg_read(Arm64Const.UC_ARM64_REG_X2).toInt()
        val flags = backend.reg_read(Arm64Const.UC_ARM64_REG_X3).toInt()
        val fd = backend.reg_read(Arm64Const.UC_ARM64_REG_X4).toInt()
        val offset = backend.reg_read(Arm64Const.UC_ARM64_REG_X5).toInt()
        if (offset % emulator.getPageAlign() != 0) {
            throw IllegalArgumentException("offset=0x" + java.lang.Long.toHexString(offset.toLong()))
        }

        val warning = length > 0x10000000
        if (log.isDebugEnabled || warning) {
            val msg = "mmap start=0x" + java.lang.Long.toHexString(start) + ", length=" + length + ", prot=0x" + Integer.toHexString(prot) + ", flags=0x" + Integer.toHexString(flags) + ", fd=" + fd + ", offset=" + offset
            if (warning) {
                log.warn(msg)
                if (log.isTraceEnabled) {
                    emulator.attach().debug("mmap warning")
                }
            } else {
                log.debug(msg)
            }
        }
        val mapped = emulator.getMemory().mmap2(start, length, prot, flags, fd, offset)
        if (log.isDebugEnabled) {
            log.debug("mmap start=0x{}, mapped=0x{}, length=0x{}, prot=0x{}, flags=0x{}, fd={}, offset={}, task={}", java.lang.Long.toHexString(start), java.lang.Long.toHexString(mapped), Integer.toHexString(length), Integer.toHexString(prot), Integer.toHexString(flags), fd, offset, emulator.getThreadDispatcher().getRunningTask())
        }
        return mapped
    }

    private fun gettimeofday(emulator: Emulator<*>): Int {
        val tv = VortexdbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_X0)
        val tz = VortexdbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_X1)
        return gettimeofday64(tv, tz)
    }

    private fun faccessat(emulator: Emulator<AndroidFileIO>): Int {
        val context = emulator.getContext<RegisterContext>()
        val dirfd = context.getIntArg(0)
        val pathname_p = context.getPointerArg(1)
        val oflags = context.getIntArg(2)
        val mode = context.getIntArg(3)
        val pathname = pathname_p!!.getString(0)
        if (log.isDebugEnabled) {
            log.debug("faccessat dirfd={}, pathname={}, oflags=0x{}, mode=0x{}", dirfd, pathname, Integer.toHexString(oflags), Integer.toHexString(mode))
        }
        val ret = faccessat(emulator, pathname)
        if (ret == -1 && verbose) {
            log.info("faccessat failed dirfd={}, pathname={}, oflags=0x{}, mode=0x{}", dirfd, pathname, Integer.toHexString(oflags), Integer.toHexString(mode))
        }
        return ret
    }

    private fun faccessat(emulator: Emulator<AndroidFileIO>, pathname: String): Int {
        val result = resolve(emulator, pathname, IOConstants.O_RDONLY)
        if (result != null && result.isSuccess()) {
            return 0
        }

        emulator.getMemory().setErrno(if (result != null) result.errno else UnixEmulator.EACCES)
        return -1
    }

    private fun fstatat64(emulator: Emulator<AndroidFileIO>): Int {
        val context = emulator.getContext<RegisterContext>()
        val dirfd = context.getIntArg(0)
        val pathname = context.getPointerArg(1)
        val statbuf = context.getPointerArg(2)
        val flags = context.getIntArg(3)
        val path = FilenameUtils.normalize(pathname!!.getString(0), true)
        if (log.isDebugEnabled) {
            log.debug("fstatat64 dirfd={}, pathname={}, statbuf={}, flags={}", dirfd, path, statbuf, flags)
        }
        if (dirfd == IO.AT_FDCWD && "" == path) {
            return stat64(emulator, ".", statbuf!!)
        }
        if (path.startsWith("/")) {
            return stat64(emulator, path, statbuf!!)
        } else {
            if (dirfd != IO.AT_FDCWD) {
                throw BackendException("dirfd=$dirfd")
            }

            log.warn("fstatat64 dirfd={}, pathname={}, statbuf={}, flags={}", dirfd, path, statbuf, flags)
            if (log.isDebugEnabled) {
                emulator.attach().debug("fstatat64 path=$path")
            }
            emulator.getMemory().setErrno(UnixEmulator.EACCES)
            return -1
        }
    }

    private fun openat(emulator: Emulator<AndroidFileIO>): Int {
        val context = emulator.getContext<RegisterContext>()
        val dirfd = context.getIntArg(0)
        val pathname_p = context.getPointerArg(1)
        val oflags = context.getIntArg(2)
        val mode = context.getIntArg(3)
        var pathname = pathname_p!!.getString(0)
        log.debug("openat dirfd={}, pathname={}, oflags=0x{}, mode={}", dirfd, pathname, Integer.toHexString(oflags), Integer.toHexString(mode))
        pathname = FilenameUtils.normalize(pathname, true)
        if ("/data/misc/zoneinfo/current/tzdata" == pathname || "/dev/pmsg0" == pathname) {
            emulator.getMemory().setErrno(UnixEmulator.ENOENT)
            return -UnixEmulator.ENOENT
        }
        if (pathname.startsWith("/")) {
            val fd = open(emulator, pathname, oflags)
            if (fd == -1) {
                if (verbose) {
                    log.info("openat dirfd={}, pathname={}, oflags=0x{}, mode={}", dirfd, pathname, Integer.toHexString(oflags), Integer.toHexString(mode))
                }
                return -emulator.getMemory().getLastErrno()
            } else {
                return fd
            }
        } else {
            if (dirfd != IO.AT_FDCWD) {
                throw BackendException()
            }

            val fd = open(emulator, pathname, oflags)
            if (fd == -1) {
                if (log.isTraceEnabled) {
                    emulator.attach().debug("openat failed: $pathname")
                }
                if (verbose) {
                    log.info("openat AT_FDCWD dirfd={}, pathname={}, oflags=0x{}, mode={}", dirfd, pathname, Integer.toHexString(oflags), Integer.toHexString(mode))
                }
                return -emulator.getMemory().getLastErrno()
            } else {
                return fd
            }
        }
    }

    private fun lseek(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val fd = context.getIntArg(0)
        val offset = context.getIntArg(1)
        val whence = context.getIntArg(2)
        val file = fdMap.get(fd)
        if (file == null) {
            emulator.getMemory().setErrno(UnixEmulator.EBADF)
            return -1
        }
        val pos = file.lseek(offset, whence)
        if (log.isDebugEnabled) {
            log.debug("lseek fd={}, offset={}, whence={}, pos={}", fd, offset, whence, pos)
        }
        return pos
    }

    private fun close(backend: Backend, emulator: Emulator<*>): Int {
        val fd = backend.reg_read(Arm64Const.UC_ARM64_REG_X0).toInt()
        if (log.isDebugEnabled) {
            log.debug("close fd={}", fd)
        }

        return close(emulator, fd)
    }

    private fun getdents64(emulator: Emulator<AndroidFileIO>): Int {
        val context = emulator.getContext<RegisterContext>()
        val fd = context.getIntArg(0)
        val dirp = context.getPointerArg(1)
        val size = context.getIntArg(2)
        if (log.isDebugEnabled) {
            log.debug("getdents64 fd={}, dirp={}, size={}", fd, dirp, size)
        }

        val io = fdMap.get(fd)
        if (io == null) {
            emulator.getMemory().setErrno(UnixEmulator.EBADF)
            return -1
        } else {
            dirp!!.setSize(size.toLong())
            return io.getdents64(dirp, size)
        }
    }

    private fun readlinkat(emulator: Emulator<AndroidFileIO>): Int {
        val context = emulator.getContext<RegisterContext>()
        val dirfd = context.getIntArg(0)
        val pathname = context.getPointerArg(1)
        val buf = context.getPointerArg(2)
        val bufSize = context.getIntArg(3)
        val path = pathname!!.getString(0)
        if (dirfd != IO.AT_FDCWD) {
            throw BackendException()
        }
        return readlink(emulator, path, buf!!, bufSize)
    }

    private fun fstat(backend: Backend, emulator: Emulator<*>): Int {
        val fd = backend.reg_read(Arm64Const.UC_ARM64_REG_X0).toInt()
        val stat = VortexdbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_X1)
        return fstat(emulator, fd, stat)
    }

    protected fun fstat(emulator: Emulator<*>, fd: Int, stat: Pointer): Int {
        val file = fdMap.get(fd)
        if (file == null) {
            if (log.isDebugEnabled) {
                log.debug("fstat fd={}, stat={}, errno=" + UnixEmulator.EBADF, fd, stat)
            }

            emulator.getMemory().setErrno(UnixEmulator.EBADF)
            return -1
        }
        if (log.isDebugEnabled) {
            log.debug("fstat file={}, stat={}, from={}", file, stat, emulator.getContext<RegisterContext>().getLRPointer())
        }
        return file.fstat(emulator, Stat64(stat))
    }

    private fun ioctl(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val fd = context.getIntArg(0)
        val request = context.getLongArg(1)
        val argp = context.getLongArg(2)
        if (log.isDebugEnabled) {
            log.debug("ioctl fd={}, request=0x{}, argp=0x{}", fd, java.lang.Long.toHexString(request), java.lang.Long.toHexString(argp))
        }

        val file = fdMap.get(fd)
        if (file == null) {
            emulator.getMemory().setErrno(UnixEmulator.EBADF)
            return -1
        }
        val ret = file.ioctl(emulator, request, argp)
        if (ret == -1) {
            emulator.getMemory().setErrno(UnixEmulator.ENOTTY)
        }
        return ret
    }

    private fun write(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val fd = context.getIntArg(0)
        val buffer = context.getPointerArg(1)
        val count = context.getIntArg(2)
        return write(emulator, fd, buffer!!, count)
    }

    private fun read(backend: Backend, emulator: Emulator<*>): Int {
        val fd = backend.reg_read(Arm64Const.UC_ARM64_REG_X0).toInt()
        val buffer = VortexdbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_X1)
        val count = backend.reg_read(Arm64Const.UC_ARM64_REG_X2).toInt()
        return read(emulator, fd, buffer, count)
    }

    private fun dup3(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val oldfd = context.getIntArg(0)
        val newfd = context.getIntArg(1)
        val flags = context.getIntArg(2)
        if (log.isDebugEnabled) {
            log.debug("dup3 oldfd={}, newfd={}, flags=0x{}", oldfd, newfd, Integer.toHexString(flags))
        }

        val old = fdMap.get(oldfd)
        if (old == null) {
            emulator.getMemory().setErrno(UnixEmulator.EBADF)
            return -1
        }

        if (oldfd == newfd) {
            return newfd
        }
        val removed = fdMap.remove(newfd)
        if (removed != null) {
            removed.close()
        }
        val _new = old.dup2() as AndroidFileIO
        fdMap.put(newfd, _new)
        return newfd
    }

    override fun createByteArrayFileIO(pathname: String, oflags: Int, data: ByteArray): AndroidFileIO {
        return ByteArrayFileIO(oflags, pathname, data)
    }

    override fun createDriverFileIO(emulator: Emulator<*>, oflags: Int, pathname: String): AndroidFileIO? {
        return DriverFileIO.create(emulator, oflags, pathname)
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(ARM64SyscallHandler::class.java)

        private const val RLIMIT_STACK = 3 /* max stack size */

        private const val CLONE_VM = 0x00000100
        private const val CLONE_FS = 0x00000200
        private const val CLONE_FILES = 0x00000400
        private const val CLONE_SIGHAND = 0x00000800
        private const val CLONE_PTRACE = 0x00002000
        private const val CLONE_VFORK = 0x00004000
        private const val CLONE_PARENT = 0x00008000
        private const val CLONE_THREAD = 0x00010000
        private const val CLONE_NEWNS = 0x00020000
        private const val CLONE_SYSVSEM = 0x00040000
        private const val CLONE_SETTLS = 0x00080000
        private const val CLONE_PARENT_SETTID = 0x00100000
        private const val CLONE_CHILD_CLEARTID = 0x00200000
        private const val CLONE_DETACHED = 0x00400000
        private const val CLONE_UNTRACED = 0x00800000
        private const val CLONE_CHILD_SETTID = 0x01000000
        private const val CLONE_STOPPED = 0x02000000

        private const val POLLIN = 0x0001
        private const val POLLOUT = 0x0004

        private const val PR_SET_NAME = 15
        private const val PR_SET_NO_NEW_PRIVS = 38
        private const val PR_SET_THP_DISABLE = 41
        private const val BIONIC_PR_SET_VMA = 0x53564d41
        private const val PR_SET_PTRACER = 0x59616d61

        private const val CLOCK_REALTIME = 0
        private const val CLOCK_MONOTONIC = 1
        private const val CLOCK_THREAD_CPUTIME_ID = 3
        private const val CLOCK_MONOTONIC_RAW = 4
        private const val CLOCK_MONOTONIC_COARSE = 6
        private const val CLOCK_BOOTTIME = 7
    }
}
