package com.vortexdbg.linux

import com.vortexdbg.AbstractEmulator
import com.vortexdbg.Emulator
import com.vortexdbg.LongJumpException
import com.vortexdbg.StopEmulatorException
import com.vortexdbg.Svc
import com.vortexdbg.arm.ARM
import com.vortexdbg.arm.ARMEmulator
import com.vortexdbg.arm.ArmSvc
import com.vortexdbg.arm.ThumbSvc
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.arm.backend.BackendException
import com.vortexdbg.arm.context.Arm32RegisterContext
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
import com.vortexdbg.linux.file.SocketIO
import com.vortexdbg.linux.file.TcpSocket
import com.vortexdbg.linux.file.UdpSocket
import com.vortexdbg.linux.struct.Stat32
import com.vortexdbg.linux.struct.SysInfo32
import com.vortexdbg.linux.thread.KitKatThread
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
import unicorn.ArmConst

import java.util.ArrayList
import java.util.concurrent.TimeUnit

/**
 * [unistd](http://androidxref.com/6.0.0_r5/xref/bionic/libc/kernel/uapi/asm-arm/asm/unistd.h)
 */
class ARM32SyscallHandler(private val svcMemory: SvcMemory) : AndroidSyscallHandler() {

    @Suppress("UNCHECKED_CAST")
    override fun hook(backend: Backend, intno: Int, swi: Int, user: Any?) {
        val emulator = user as Emulator<AndroidFileIO>
        val pc = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_PC)
        val bkpt: Int
        if (pc == null) {
            bkpt = swi
        } else {
            if (ARM.isThumb(backend)) {
                bkpt = pc.getShort(0).toInt() and 0xff
            } else {
                val instruction = pc.getInt(0L)
                bkpt = (instruction and 0xf) or (((instruction shr 8) and 0xfff) shl 4)
            }
        }

        if (intno == ARMEmulator.EXCP_BKPT) { // bkpt
            createBreaker(emulator).brk(pc, bkpt)
            return
        }
        if (intno == ARMEmulator.EXCP_UDEF) {
            createBreaker(emulator).debug("Undefined instruction (EXCP_UDEF) at $pc")
            return
        }

        if (intno != ARMEmulator.EXCP_SWI) {
            throw BackendException("intno=$intno")
        }

        val NR = backend.reg_read(ArmConst.UC_ARM_REG_R7).toInt()
        var syscall: String? = null
        var exception: Throwable? = null
        try {
            if (swi == 0 && NR == 0 && (backend.reg_read(ArmConst.UC_ARM_REG_R5).toInt()) == Svc.POST_CALLBACK_SYSCALL_NUMBER) { // postCallback
                val number = backend.reg_read(ArmConst.UC_ARM_REG_R4).toInt()
                val svc = svcMemory.getSvc(number)
                if (svc != null) {
                    svc.handlePostCallback(emulator)
                    return
                }
                backend.emu_stop()
                throw IllegalStateException("svc number: $swi")
            }
            if (swi == 0 && NR == 0 && (backend.reg_read(ArmConst.UC_ARM_REG_R5).toInt()) == Svc.PRE_CALLBACK_SYSCALL_NUMBER) { // preCallback
                val number = backend.reg_read(ArmConst.UC_ARM_REG_R4).toInt()
                val svc = svcMemory.getSvc(number)
                if (svc != null) {
                    svc.handlePreCallback(emulator)
                    return
                }
                backend.emu_stop()
                throw IllegalStateException("svc number: $swi")
            }
            if (swi != 0) {
                if (swi == (if (ARM.isThumb(backend)) ThumbSvc.SVC_MAX else ArmSvc.SVC_MAX)) {
                    throw PopContextException()
                }
                if (swi == (if (ARM.isThumb(backend)) ThumbSvc.SVC_MAX else ArmSvc.SVC_MAX) - 1) {
                    throw ThreadContextSwitchException()
                }
                val svc = svcMemory.getSvc(swi)
                if (svc != null) {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, svc.handle(emulator).toInt())
                    return
                }
                backend.emu_stop()
                throw IllegalStateException("svc number: $swi")
            }

            if (log.isTraceEnabled) {
                ARM.showThumbRegs(emulator)
            }

            if (handleSyscall(emulator, NR)) {
                return
            }

            when (NR) {
                1 -> {
                    exit(emulator)
                    return
                }
                2 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, fork(emulator))
                    return
                }
                3 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, read(emulator))
                    return
                }
                4 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, write(emulator))
                    return
                }
                5 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, open(emulator))
                    return
                }
                6 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, close(emulator))
                    return
                }
                10 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, unlink(emulator))
                    return
                }
                11 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, execve(emulator))
                    return
                }
                19 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, lseek(emulator))
                    return
                }
                26 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, ptrace(emulator))
                    return
                }
                20 -> { // getpid
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, emulator.getPid())
                    return
                }
                224 -> { // gettid
                    val task = emulator.get<Task>(Task.TASK_KEY)
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, if (task == null) 0 else task.getId())
                    return
                }
                33 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, access(emulator))
                    return
                }
                36 -> { // sync: causes all pending modifications to filesystem metadata and cached file data to be written to the underlying filesystems.
                    return
                }
                37 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, kill(emulator))
                    return
                }
                38 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, rename(emulator))
                    return
                }
                39 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, mkdir(emulator))
                    return
                }
                41 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, dup(emulator))
                    return
                }
                42 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, pipe(emulator))
                    return
                }
                45 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, brk(emulator))
                    return
                }
                54 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, ioctl(emulator))
                    return
                }
                57 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, setpgid(emulator))
                    return
                }
                60 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, umask(emulator))
                    return
                }
                63 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, dup2(backend, emulator))
                    return
                }
                64 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, getppid(emulator))
                    return
                }
                67 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, sigaction(emulator))
                    return
                }
                73 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, rt_sigpending(emulator))
                    return
                }
                78 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, gettimeofday(emulator))
                    return
                }
                85 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, readlink(emulator))
                    return
                }
                88 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, reboot(backend, emulator))
                    return
                }
                91 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, munmap(backend, emulator))
                    return
                }
                93 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, ftruncate(backend))
                    return
                }
                94 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, fchmod(backend))
                    return
                }
                96 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, getpriority(emulator))
                    return
                }
                97 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, setpriority(emulator))
                    return
                }
                103 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, syslog(backend, emulator))
                    return
                }
                104 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, setitimer(emulator))
                    return
                }
                116 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, sysinfo(emulator))
                    return
                }
                118 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, fsync(backend))
                    return
                }
                120 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, clone(emulator))
                    return
                }
                122 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, uname(emulator))
                    return
                }
                125 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, mprotect(backend, emulator))
                    return
                }
                126, 175 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, sigprocmask(emulator))
                    return
                }
                132 -> {
                    syscall = "getpgid"
                }
                136 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, personality(backend))
                    return
                }
                140 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, llseek(backend, emulator))
                    return
                }
                142 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, newselect(backend, emulator))
                    return
                }
                143 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, flock(backend))
                    return
                }
                146 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, writev(backend, emulator))
                    return
                }
                147 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, getsid(emulator))
                    return
                }
                150 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, mlock(emulator))
                    return
                }
                151 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, munlock(emulator))
                    return
                }
                155 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, sched_getparam(emulator))
                    return
                }
                156 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, sched_setscheduler(emulator))
                    return
                }
                157 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, sched_getscheduler(emulator))
                    return
                }
                158 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, sched_yield(emulator))
                    return
                }
                162 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, nanosleep(emulator))
                    return
                }
                163 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, mremap(emulator))
                    return
                }
                168, 336 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, poll(backend, emulator))
                    return
                }
                172 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, prctl(backend, emulator))
                    return
                }
                176 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, rt_sigpending(emulator))
                    return
                }
                177 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, rt_sigtimedwait(emulator))
                    return
                }
                178 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, rt_sigqueue(emulator))
                    return
                }
                180 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, pread64(emulator))
                    return
                }
                183 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, getcwd(emulator))
                    return
                }
                186 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, sigaltstack(emulator))
                    return
                }
                192 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, mmap2(backend, emulator))
                    return
                }
                194 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, ftruncate(backend))
                    return
                }
                195 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, stat64(emulator))
                    return
                }
                196 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, lstat(emulator))
                    return
                }
                197 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, fstat(backend, emulator))
                    return
                }
                199, // getuid
                200, // getgid
                201, // geteuid
                202 -> { // getegid
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, 0)
                    return
                }
                205 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, getgroups(backend, emulator))
                    return
                }
                208 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, setresuid32(backend))
                    return
                }
                210 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, setresgid32(backend))
                    return
                }
                214 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, setgid32(emulator))
                    return
                }
                217 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, getdents64(emulator))
                    return
                }
                220 -> {
                    syscall = "madvise"
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, 0)
                    return
                }
                221 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, fcntl(backend, emulator))
                    return
                }
                230 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, lgetxattr(backend, emulator))
                    return
                }
                238 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, tkill(emulator))
                    return
                }
                240 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, futex(emulator))
                    return
                }
                241 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, sched_setaffinity(emulator))
                    return
                }
                242 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, sched_getaffinity(emulator))
                    return
                }
                248 -> {
                    exit_group(emulator)
                    return
                }
                256 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, set_tid_address(emulator))
                    return
                }
                263 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, clock_gettime(backend, emulator))
                    return
                }
                266 -> {
                    val context = emulator.getContext<RegisterContext>()
                    val pathPointer = context.getPointerArg(0)
                    val size = context.getIntArg(1)
                    val buf = context.getPointerArg(2)!!.setSize(size.toLong())
                    val path = pathPointer!!.getString(0L)
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, statfs64(emulator, path, buf).toInt())
                    return
                }
                268 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, tgkill(emulator))
                    return
                }
                269 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, utimes(emulator))
                    return
                }
                281 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, socket(backend, emulator))
                    return
                }
                282 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, bind(emulator))
                    return
                }
                283 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, connect(backend, emulator))
                    return
                }
                284 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, listen(emulator))
                    return
                }
                285 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, accept(emulator))
                    return
                }
                286 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, getsockname(backend, emulator))
                    return
                }
                287 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, getpeername(backend, emulator))
                    return
                }
                290 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, sendto(backend, emulator))
                    return
                }
                292 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, recvfrom(emulator))
                    return
                }
                293 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, shutdown(backend, emulator))
                    return
                }
                294 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, setsockopt(backend, emulator))
                    return
                }
                295 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, getsockopt(backend, emulator))
                    return
                }
                322 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, openat(emulator))
                    return
                }
                323 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, mkdirat(emulator))
                    return
                }
                327 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, fstatat64(backend, emulator))
                    return
                }
                328 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, unlinkat(emulator))
                    return
                }
                332 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, readlinkat(emulator))
                    return
                }
                333 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, fchmodat(emulator))
                    return
                }
                329 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, renameat(emulator))
                    return
                }
                334 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, faccessat(backend, emulator))
                    return
                }
                335 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, pselect6(emulator))
                    return
                }
                345 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, getcpu(emulator))
                    return
                }
                348 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, utimensat(backend, emulator))
                    return
                }
                356 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, eventfd2(emulator))
                    return
                }
                352 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, fallocate(emulator))
                    return
                }
                358 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, dup3(emulator))
                    return
                }
                359 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, pipe2(emulator))
                    return
                }
                366 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, accept4(emulator))
                    return
                }
                384 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, getrandom(emulator))
                    return
                }
                0xf0002 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, cacheflush(backend, emulator))
                    return
                }
                0xf0005 -> {
                    backend.reg_write(ArmConst.UC_ARM_REG_R0, set_tls(backend, emulator))
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

        log.warn("handleInterrupt intno={}, NR={}, svcNumber=0x{}, PC={}, LR={}, syscall={}", intno, NR, Integer.toHexString(swi), pc, emulator.getContext<RegisterContext>().getLRPointer(), syscall, exception)

        if (exception is RuntimeException) {
            throw exception
        }
    }

    private fun getrandom(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val buf = context.getPointerArg(0)
        val bufSize = context.getIntArg(1)
        val flags = context.getIntArg(2)
        return getrandom(buf!!, bufSize, flags)
    }

    private fun clone(emulator: Emulator<*>): Int {
        val context = emulator.getContext<Arm32RegisterContext>()
        val child_stack = context.getPointerArg(1)
        if (child_stack == null &&
                context.getPointerArg(2) == null) {
            // http://androidxref.com/6.0.1_r10/xref/bionic/libc/bionic/fork.cpp#47
            return fork(emulator) // vfork
        }

        val fn = context.getR5Int()
        val arg = context.getR6Int()
        if (child_stack != null && child_stack.getInt(0L) == fn && child_stack.getInt(4L) == arg) {
            // http://androidxref.com/6.0.1_r10/xref/bionic/libc/arch-arm/bionic/__bionic_clone.S#49
            return bionic_clone(emulator)
        } else {
            return pthread_clone(emulator)
        }
    }

    private fun tkill(emulator: Emulator<AndroidFileIO>): Int {
        val context = emulator.getContext<RegisterContext>()
        val tid = context.getIntArg(0)
        val sig = context.getIntArg(1)
        if (log.isDebugEnabled) {
            log.debug("tkill tid={}, sig={}", tid, sig)
        }
        return 0
    }

    private fun setpgid(emulator: Emulator<AndroidFileIO>): Int {
        val context = emulator.getContext<RegisterContext>()
        val pid = context.getIntArg(0)
        val pgid = context.getIntArg(1)
        if (log.isDebugEnabled) {
            log.debug("setpgid pid={}, pgid={}", pid, pgid)
        }
        return 0
    }

    private fun getsid(emulator: Emulator<AndroidFileIO>): Int {
        val context = emulator.getContext<RegisterContext>()
        val pid = context.getIntArg(0)
        if (log.isDebugEnabled) {
            log.debug("getsid pid={}", pid)
        }
        return emulator.getPid()
    }

    private fun readlinkat(emulator: Emulator<AndroidFileIO>): Int {
        val context = emulator.getContext<RegisterContext>()
        val dirfd = context.getIntArg(0)
        val pathname = context.getPointerArg(1)
        val buf = context.getPointerArg(2)
        val bufSize = context.getIntArg(3)
        val path = pathname!!.getString(0L)
        if (dirfd != IO.AT_FDCWD) {
            throw BackendException()
        }
        return readlink(emulator, path, buf!!, bufSize)
    }

    private fun readlink(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val pathname = context.getPointerArg(0)
        val buf = context.getPointerArg(1)
        val bufSize = context.getIntArg(2)
        val path = pathname!!.getString(0L)
        return readlink(emulator, path, buf!!, bufSize)
    }

    private fun getppid(emulator: Emulator<*>): Int {
        if (log.isDebugEnabled) {
            log.debug("getppid")
        }
        return emulator.getPid()
    }

    private fun getcpu(emulator: Emulator<*>): Int {
        val context = emulator.getContext<Arm32RegisterContext>()
        val cpu = context.getR0Pointer()
        val node = context.getR1Pointer()
        val tcache = context.getR2Pointer()
        if (log.isDebugEnabled) {
            log.debug("getcpu cpu={}, node={}, tcache={}", cpu, node, tcache)
        }
        if (cpu != null) {
            cpu.setInt(0L, 0)
        }
        if (node != null) {
            node.setInt(0L, 0)
        }
        return 0
    }

    private fun sysinfo(emulator: Emulator<*>): Int {
        val context = emulator.getContext<Arm32RegisterContext>()
        val info = context.getR0Pointer()
        if (log.isDebugEnabled) {
            log.debug("sysinfo info={}", info)
        }
        val sysInfo32 = SysInfo32(info)
        sysInfo32.pack()
        return 0
    }

    private fun mremap(emulator: Emulator<*>): Int {
        val context = emulator.getContext<Arm32RegisterContext>()
        val old_address = context.getR0Pointer()
        val old_size = context.getR1Int()
        val new_size = context.getR2Int()
        val flags = context.getR3Int()
        val new_address = context.getR4Pointer()
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
        val data = old_address.getByteArray(0L, old_size)
        val prot = memory.munmap(old_address.toUIntPeer(), old_size)
        val address: Long
        if (fixed) {
            address = memory.mmap2(new_address.toUIntPeer(), new_size, prot, AndroidElfLoader.MAP_ANONYMOUS or AndroidElfLoader.MAP_FIXED, 0, 0)
        } else {
            address = memory.mmap2(0, new_size, prot, AndroidElfLoader.MAP_ANONYMOUS, 0, 0)
        }
        val pointer = VortexdbgPointer.pointer(emulator, address)
        checkNotNull(pointer)
        pointer.write(0L, data, 0, data.size)
        return pointer.toUIntPeer().toInt()
    }

    protected fun ptrace(emulator: Emulator<*>): Int {
        val backend = emulator.getBackend()
        val request = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        val pid = backend.reg_read(ArmConst.UC_ARM_REG_R1).toInt()
        val addr = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R2)
        val data = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R3)
        log.info("ptrace request=0x{}, pid={}, addr={}, data={}", Integer.toHexString(request), pid, addr, data)
        return 0
    }

    private fun utimes(emulator: Emulator<*>): Int {
        val filename = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R0)
        val times = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R1)
        if (log.isDebugEnabled) {
            log.debug("utimes filename={}, times={}", filename.getString(0L), times)
        }
        return 0
    }

    private fun utimensat(backend: Backend, emulator: Emulator<*>): Int {
        val dirfd = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        val pathname = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R1)
        val times = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R2)
        val flags = backend.reg_read(ArmConst.UC_ARM_REG_R3).toInt()
        if (log.isDebugEnabled) {
            log.debug("utimensat dirfd={}, pathname={}, times={}, flags={}", dirfd, pathname.getString(0L), times, flags)
        }
        return 0
    }

    private fun fsync(backend: Backend): Int {
        val fd = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        if (log.isDebugEnabled) {
            log.debug("fsync fd={}", fd)
        }
        return 0
    }

    private fun rename(emulator: Emulator<*>): Int {
        val context = emulator.getContext<Arm32RegisterContext>()
        val oldpath = context.getR0Pointer()
        val newpath = context.getR1Pointer()
        log.info("rename oldpath={}, newpath={}", oldpath.getString(0L), newpath.getString(0L))
        return 0
    }

    private fun unlink(emulator: Emulator<*>): Int {
        val pathname = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R0)
        val path = FilenameUtils.normalize(pathname.getString(0L), true)
        log.info("unlink path={}", path)
        return 0
    }

    private fun pipe(emulator: Emulator<*>): Int {
        val pipefd = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R0)
        val readfd = pipefd.getInt(0L)
        val writefd = pipefd.getInt(4L)
        log.info("pipe readfd={}, writefd={}", readfd, writefd)
        emulator.getMemory().setErrno(UnixEmulator.EFAULT)
        return -1
    }

    private fun set_tls(backend: Backend, emulator: Emulator<*>): Int {
        val tls = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R0)
        if (log.isDebugEnabled) {
            log.debug("set_tls: {}", tls)
        }
        backend.reg_write(ArmConst.UC_ARM_REG_C13_C0_3, tls.peer)
        return 0
    }

    private fun cacheflush(backend: Backend, emulator: Emulator<*>): Int {
        val begin = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R0)
        val end = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R1)
        val cache = backend.reg_read(ArmConst.UC_ARM_REG_R2).toInt()
        if (log.isDebugEnabled) {
            log.debug("cacheflush begin={}, end={}, cache={}", begin, end, cache)
        }
        return 0
    }

    protected fun fork(emulator: Emulator<*>): Int {
        log.info("fork")
        val log = LoggerFactory.getLogger(AbstractEmulator::class.java)
        if (log.isDebugEnabled) {
            createBreaker(emulator).debug("fork() called")
        }
        emulator.getMemory().setErrno(UnixEmulator.ENOSYS)
        return -1
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

        val fn = child_stack!!.getPointer(0L)
        child_stack = child_stack.share(4, 0)
        val arg = child_stack.getPointer(0L)
        child_stack = child_stack.share(4, 0)

        if (threadDispatcherEnabled) {
            if (verbose) {
                System.out.printf("pthread_clone fn=%s%n", fn)
            }
            emulator.getThreadDispatcher().addThread(KitKatThread(threadId, emulator.getReturnAddress(), child_stack, fn, arg))
            return threadId
        }

        log.info("pthread_clone child_stack={}, thread_id={}, fn={}, arg={}, flags={}", child_stack, threadId, fn, arg, list)
        val log = LoggerFactory.getLogger(AbstractEmulator::class.java)
        if (log.isDebugEnabled) {
            emulator.attach().debug("pthread_clone thread_id=$threadId, fn=$fn")
        }
        return threadId
    }

    private fun bionic_clone(emulator: Emulator<*>): Int {
        val context = emulator.getContext<Arm32RegisterContext>()
        val flags = context.getR0Int()
        val child_stack = context.getR1Pointer()
        val pid = context.getR2Pointer()
        val tls = context.getR3Pointer()
        val ctid = context.getR4Pointer()
        val fn = context.getR5Pointer()
        val arg = context.getR6Pointer()
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
        if (log.isDebugEnabled) {
            log.debug("bionic_clone child_stack={}, thread_id={}, pid={}, tls={}, ctid={}, fn={}, arg={}, flags={}", child_stack, threadId, pid, tls, ctid, fn, arg, list)
        }
        if (threadDispatcherEnabled) {
            if (verbose) {
                System.out.printf("bionic_clone fn=%s, LR=%s%n", fn, context.getLRPointer())
            }
            emulator.getThreadDispatcher().addThread(MarshmallowThread(emulator, fn, arg, ctid, threadId))
        }
        ctid.setInt(0L, threadId)
        return threadId
    }

    private fun flock(backend: Backend): Int {
        val fd = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        val operation = backend.reg_read(ArmConst.UC_ARM_REG_R1).toInt()
        if (log.isDebugEnabled) {
            log.debug("flock fd={}, operation={}", fd, operation)
        }
        return 0
    }

    private fun fchmod(backend: Backend): Int {
        val fd = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        val mode = backend.reg_read(ArmConst.UC_ARM_REG_R1).toInt()
        if (log.isDebugEnabled) {
            log.debug("fchmod fd={}, mode={}", fd, mode)
        }
        return 0
    }

    private fun llseek(backend: Backend, emulator: Emulator<*>): Int {
        val fd = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        val offset_high = backend.reg_read(ArmConst.UC_ARM_REG_R1).toInt().toLong() and 0xffffffffL
        val offset_low = backend.reg_read(ArmConst.UC_ARM_REG_R2).toInt().toLong() and 0xffffffffL
        val offset = (offset_high shl 32) or offset_low
        val result = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R3)
        val whence = backend.reg_read(ArmConst.UC_ARM_REG_R4).toInt()
        if (log.isDebugEnabled) {
            log.debug("llseek fd={}, offset_high={}, offset_low={}, result={}, whence={}", fd, offset_high, offset_low, result, whence)
        }

        val io = fdMap.get(fd)
        if (io == null) {
            emulator.getMemory().setErrno(UnixEmulator.EBADF)
            return -1
        } else {
            return io.llseek(offset, result, whence)
        }
    }

    private fun access(emulator: Emulator<AndroidFileIO>): Int {
        val backend = emulator.getBackend()
        val pathname = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R0)
        val mode = backend.reg_read(ArmConst.UC_ARM_REG_R1).toInt()
        if (pathname == null) {
            emulator.getMemory().setErrno(UnixEmulator.EINVAL)
            return -1
        }

        val path = pathname.getString(0L)
        if (log.isDebugEnabled) {
            log.debug("access pathname={}, mode={}", path, mode)
        }
        val ret = faccessat(emulator, path)
        if (ret == -1) {
            log.info("access pathname={}, mode={}", path, mode)
        }
        return ret
    }

    private fun execve(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val filename = context.getPointerArg(0)
        var argv: Pointer? = context.getPointerArg(1)
        var envp: Pointer? = context.getPointerArg(2)
        checkNotNull(filename)
        val args = ArrayList<String>()
        var pointer: Pointer?
        while ((argv!!.getPointer(0L).also { pointer = it }) != null) {
            args.add(pointer!!.getString(0L))
            argv = argv!!.share(4)
        }
        val env = ArrayList<String>()
        while ((envp!!.getPointer(0L).also { pointer = it }) != null) {
            env.add(pointer!!.getString(0L))
            envp = envp!!.share(4)
        }
        log.info("execve filename={}, args={}, env={}", filename.getString(0L), args, env)
        val log = LoggerFactory.getLogger(AbstractEmulator::class.java)
        if (log.isDebugEnabled) {
            createBreaker(emulator).debug("execve: " + filename.getString(0L))
        }
        emulator.getMemory().setErrno(UnixEmulator.EACCES)
        return -1
    }

    private var persona: Long = 0

    private fun personality(backend: Backend): Int {
        val persona = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt().toLong() and 0xffffffffL
        if (log.isDebugEnabled) {
            log.debug("personality persona=0x{}", java.lang.Long.toHexString(persona))
        }
        val old = this.persona.toInt()
        if (persona != 0xffffffffL) {
            this.persona = persona
        }
        return old
    }

    private fun shutdown(backend: Backend, emulator: Emulator<*>): Int {
        val sockfd = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        val how = backend.reg_read(ArmConst.UC_ARM_REG_R1).toInt()
        if (log.isDebugEnabled) {
            log.debug("shutdown sockfd={}, how={}", sockfd, how)
        }

        val io = fdMap.get(sockfd)
        if (io == null) {
            emulator.getMemory().setErrno(UnixEmulator.EBADF)
            return -1
        }
        return io.shutdown(how)
    }

    private fun dup(emulator: Emulator<*>): Int {
        val backend = emulator.getBackend()
        val oldfd = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()

        val io = fdMap.get(oldfd)
        if (io == null) {
            emulator.getMemory().setErrno(UnixEmulator.EBADF)
            return -1
        }
        if (log.isDebugEnabled) {
            log.debug("dup oldfd={}, io={}", oldfd, io)
        }
        val _new = io.dup2() as AndroidFileIO?
            ?: throw UnsupportedOperationException()
        val newfd = getMinFd()
        fdMap.put(newfd, _new)
        return newfd
    }

    private fun stat64(emulator: Emulator<AndroidFileIO>): Int {
        val pathname = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R0)
        val statbuf = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R1)
        val path = FilenameUtils.normalize(pathname.getString(0L), true)
        if (log.isDebugEnabled) {
            log.debug("stat64 pathname={}, statbuf={}", path, statbuf)
        }
        return stat64(emulator, path, statbuf)
    }

    private fun lstat(emulator: Emulator<AndroidFileIO>): Int {
        val pathname = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R0)
        val statbuf = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R1)
        val path = FilenameUtils.normalize(pathname.getString(0L), true)
        if (log.isDebugEnabled) {
            log.debug("lstat pathname={}, statbuf={}", path, statbuf)
        }
        return stat64(emulator, path, statbuf)
    }

    protected fun stat64(emulator: Emulator<AndroidFileIO>, pathname: String, statbuf: Pointer): Int {
        val result = resolve(emulator, pathname, IOConstants.O_RDONLY)
        if (result != null && result.isSuccess()) {
            return result.io!!.fstat(emulator, Stat32(statbuf))
        }

        log.info("stat64 pathname={}, LR={}", pathname, emulator.getContext<RegisterContext>().getLRPointer())
        emulator.getMemory().setErrno(if (result != null) result.errno else UnixEmulator.ENOENT)
        return -1
    }

    private fun newselect(backend: Backend, emulator: Emulator<*>): Int {
        val nfds = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        val readfds = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R1)
        val writefds = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R2)
        val exceptfds = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R3)
        val timeout = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R4)
        val size = (nfds - 1) / 8 + 1
        if (log.isDebugEnabled) {
            log.debug("newselect nfds={}, readfds={}, writefds={}, exceptfds={}, timeout={}", nfds, readfds, writefds, exceptfds, timeout)
            if (readfds != null) {
                val data = readfds.getByteArray(0L, size)
                Inspector.inspect(data, "readfds")
            }
            if (writefds != null) {
                val data = writefds.getByteArray(0L, size)
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
        throw AbstractMethodError("newselect nfds=$nfds, readfds=null, writefds=$writefds, exceptfds=null, timeout=$timeout")
    }

    protected fun pselect6(emulator: Emulator<*>): Int {
        val context = emulator.getContext<Arm32RegisterContext>()
        val nfds = context.getIntArg(0)
        val readfds = context.getPointerArg(1)
        val writefds = context.getPointerArg(2)
        val exceptfds = context.getPointerArg(3)
        val timeout = context.getR4Pointer()
        val size = (nfds - 1) / 8 + 1
        if (log.isDebugEnabled) {
            log.debug("pselect6 nfds={}, readfds={}, writefds={}, exceptfds={}, timeout={}, LR={}", nfds, readfds, writefds, exceptfds, timeout, context.getLRPointer())
            if (readfds != null) {
                val data = readfds.getByteArray(0L, size)
                Inspector.inspect(data, "readfds")
            }
            if (writefds != null) {
                val data = writefds.getByteArray(0L, size)
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

    private fun getpeername(backend: Backend, emulator: Emulator<*>): Int {
        val sockfd = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        val addr = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R1)
        val addrlen = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R2)
        if (log.isDebugEnabled) {
            log.debug("getpeername sockfd={}, addr={}, addrlen={}", sockfd, addr, addrlen)
        }

        val io = fdMap.get(sockfd)
        if (io == null) {
            emulator.getMemory().setErrno(UnixEmulator.EBADF)
            return -1
        }

        return io.getpeername(addr, addrlen)
    }

    private fun poll(backend: Backend, emulator: Emulator<*>): Int {
        val fds = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R0)
        val nfds = backend.reg_read(ArmConst.UC_ARM_REG_R1).toInt()
        val timeout = backend.reg_read(ArmConst.UC_ARM_REG_R2).toInt()
        var count = 0
        for (i in 0 until nfds) {
            val pollfd = fds.share(i * 8L)
            val fd = pollfd.getInt(0L)
            val events = pollfd.getShort(4) // requested events
            if (log.isDebugEnabled) {
                log.debug("poll fds={}, nfds={}, timeout={}, fd={}, events={}", fds, nfds, timeout, fd, events)
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

    private var mask = 0x12

    private fun umask(emulator: Emulator<*>): Int {
        val backend = emulator.getBackend()
        val mask = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        if (log.isDebugEnabled) {
            log.debug("umask mask=0x{}", java.lang.Long.toHexString(mask.toLong()))
        }
        val old = this.mask
        this.mask = mask
        return old
    }

    private fun setresuid32(backend: Backend): Int {
        val ruid = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        val euid = backend.reg_read(ArmConst.UC_ARM_REG_R1).toInt()
        val suid = backend.reg_read(ArmConst.UC_ARM_REG_R2).toInt()
        if (log.isDebugEnabled) {
            log.debug("setresuid32 ruid={}, euid={}, suid={}", ruid, euid, suid)
        }
        return 0
    }

    private fun setgid32(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val gid = context.getIntArg(0)
        if (log.isDebugEnabled) {
            log.debug("setgid32 gid={}", gid)
        }
        return 0
    }

    private fun setresgid32(backend: Backend): Int {
        val rgid = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        val egid = backend.reg_read(ArmConst.UC_ARM_REG_R1).toInt()
        val sgid = backend.reg_read(ArmConst.UC_ARM_REG_R2).toInt()
        if (log.isDebugEnabled) {
            log.debug("setresgid32 rgid={}, egid={}, sgid={}", rgid, egid, sgid)
        }
        return 0
    }

    private fun mkdir(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val pathname = context.getPointerArg(0)
        val mode = context.getIntArg(1)
        if (log.isDebugEnabled) {
            log.debug("mkdir pathname={}, mode={}", pathname!!.getString(0L), mode)
        }
        emulator.getMemory().setErrno(UnixEmulator.EACCES)
        return -1
    }

    private fun syslog(backend: Backend, emulator: Emulator<*>): Int {
        val type = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        val bufp = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R1)
        val len = backend.reg_read(ArmConst.UC_ARM_REG_R2).toInt()
        if (log.isDebugEnabled) {
            log.debug("syslog type={}, bufp={}, len={}", type, bufp, len)
        }
        throw UnsupportedOperationException()
    }

    private fun sigprocmask(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val how = context.getIntArg(0)
        val set = context.getPointerArg(1)
        val oldset = context.getPointerArg(2)
        return sigprocmask(emulator, how, set!!, oldset!!)
    }

    private fun lgetxattr(backend: Backend, emulator: Emulator<*>): Int {
        val path = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R0)
        val name = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R1)
        val value = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R2)
        val size = backend.reg_read(ArmConst.UC_ARM_REG_R3).toInt()
        if (log.isDebugEnabled) {
            log.debug("lgetxattr path={}, name={}, value={}, size={}", path.getString(0L), name.getString(0L), value, size)
        }
        throw UnsupportedOperationException()
    }

    private fun reboot(backend: Backend, emulator: Emulator<*>): Int {
        val magic = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        val magic2 = backend.reg_read(ArmConst.UC_ARM_REG_R1).toInt()
        val cmd = backend.reg_read(ArmConst.UC_ARM_REG_R2).toInt()
        val arg = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R3)
        if (log.isDebugEnabled) {
            log.debug("reboot magic={}, magic2={}, cmd={}, arg={}", magic, magic2, cmd, arg)
        }
        emulator.getMemory().setErrno(UnixEmulator.EPERM)
        return -1
    }

    private fun setitimer(emulator: Emulator<*>): Int {
        val context = emulator.getContext<Arm32RegisterContext>()
        val which = context.getR0Int()
        val new_value = context.getR1Pointer()
        val old_value = context.getR2Pointer()
        if (log.isDebugEnabled) {
            log.debug("setitimer which={}, new_value={}, old_value={}", which, new_value, old_value)
        }
        return 0
    }

    private fun sigaction(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val signum = context.getIntArg(0)
        val act = context.getPointerArg(1)
        val oldact = context.getPointerArg(2)

        return sigaction(emulator, signum, act!!, oldact!!)
    }

    private fun recvfrom(emulator: Emulator<*>): Int {
        val backend = emulator.getBackend()
        val sockfd = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        val buf = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R1)
        val len = backend.reg_read(ArmConst.UC_ARM_REG_R2).toInt()
        val flags = backend.reg_read(ArmConst.UC_ARM_REG_R3).toInt()
        val src_addr = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R4)
        val addrlen = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R5)

        if (log.isDebugEnabled) {
            log.debug("recvfrom sockfd={}, buf={}, flags={}, src_addr={}, addrlen={}", sockfd, buf, flags, src_addr, addrlen)
        }
        val file = fdMap.get(sockfd)
        if (file == null) {
            emulator.getMemory().setErrno(UnixEmulator.EBADF)
            return -1
        }
        return file.recvfrom(backend, buf, len, flags, src_addr, addrlen)
    }

    private fun sendto(backend: Backend, emulator: Emulator<*>): Int {
        val sockfd = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        val buf = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R1)
        val len = backend.reg_read(ArmConst.UC_ARM_REG_R2).toInt()
        val flags = backend.reg_read(ArmConst.UC_ARM_REG_R3).toInt()
        val dest_addr = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R4)
        val addrlen = backend.reg_read(ArmConst.UC_ARM_REG_R5).toInt()

        return sendto(emulator, sockfd, buf, len, flags, dest_addr, addrlen)
    }

    private fun connect(backend: Backend, emulator: Emulator<*>): Int {
        val sockfd = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        val addr = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R1)
        val addrlen = backend.reg_read(ArmConst.UC_ARM_REG_R2).toInt()
        return connect(emulator, sockfd, addr, addrlen)
    }

    private fun accept(emulator: Emulator<AndroidFileIO>): Int {
        val context = emulator.getContext<RegisterContext>()
        val sockfd = context.getIntArg(0)
        val addr = context.getPointerArg(1)
        val addrlen = context.getPointerArg(2)
        return accept(emulator, sockfd, addr, addrlen, 0)
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

    private fun listen(emulator: Emulator<AndroidFileIO>): Int {
        val context = emulator.getContext<RegisterContext>()
        val sockfd = context.getIntArg(0)
        val backlog = context.getIntArg(1)
        return listen(emulator, sockfd, backlog)
    }

    private fun bind(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val sockfd = context.getIntArg(0)
        val addr = context.getPointerArg(1)
        val addrlen = context.getIntArg(2)
        return bind(emulator, sockfd, addr!!, addrlen)
    }

    private fun getsockname(backend: Backend, emulator: Emulator<*>): Int {
        val sockfd = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        val addr = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R1)
        val addrlen = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R2)
        if (log.isDebugEnabled) {
            log.debug("getsockname sockfd={}, addr={}, addrlen={}", sockfd, addr, addrlen)
        }
        val file = fdMap.get(sockfd)
        if (file == null) {
            emulator.getMemory().setErrno(UnixEmulator.EBADF)
            return -1
        }
        return file.getsockname(addr, addrlen)
    }

    private fun getsockopt(backend: Backend, emulator: Emulator<*>): Int {
        val sockfd = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        val level = backend.reg_read(ArmConst.UC_ARM_REG_R1).toInt()
        val optname = backend.reg_read(ArmConst.UC_ARM_REG_R2).toInt()
        val optval = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R3)
        val optlen = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R4)
        if (log.isDebugEnabled) {
            log.debug("getsockopt sockfd={}, level={}, optname={}, optval={}, optlen={}, from={}", sockfd, level, optname, optval, optlen, VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_LR))
        }

        val file = fdMap.get(sockfd)
        if (file == null) {
            emulator.getMemory().setErrno(UnixEmulator.EBADF)
            return -1
        }
        return file.getsockopt(level, optname, optval, optlen)
    }

    private fun setsockopt(backend: Backend, emulator: Emulator<*>): Int {
        val sockfd = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        val level = backend.reg_read(ArmConst.UC_ARM_REG_R1).toInt()
        val optname = backend.reg_read(ArmConst.UC_ARM_REG_R2).toInt()
        val optval = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R3)
        val optlen = backend.reg_read(ArmConst.UC_ARM_REG_R4).toInt()
        if (log.isDebugEnabled) {
            log.debug("setsockopt sockfd={}, level={}, optname={}, optval={}, optlen={}", sockfd, level, optname, optval, optlen)
        }

        val file = fdMap.get(sockfd)
        if (file == null) {
            emulator.getMemory().setErrno(UnixEmulator.EBADF)
            return -1
        }
        return file.setsockopt(level, optname, optval, optlen)
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

    private fun socket(backend: Backend, emulator: Emulator<*>): Int {
        val domain = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        val type = backend.reg_read(ArmConst.UC_ARM_REG_R1).toInt() and 0x7ffff
        val protocol = backend.reg_read(ArmConst.UC_ARM_REG_R2).toInt()
        if (log.isDebugEnabled) {
            log.debug("socket domain={}, type={}, protocol={}", domain, type, protocol)
        }

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

    private fun getgroups(backend: Backend, emulator: Emulator<*>): Int {
        val size = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        val list = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R1)
        if (log.isDebugEnabled) {
            log.debug("getgroups size={}, list={}", size, list)
        }
        return 0
    }

    protected fun uname(emulator: Emulator<*>): Int {
        val buf = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R0)
        if (log.isDebugEnabled) {
            log.debug("uname buf={}", buf)
        }

        val SYS_NMLN = 65

        val sysname = buf.share(0)
        sysname.setString(0L, "Linux")

        val nodename = sysname.share(SYS_NMLN.toLong())
        nodename.setString(0L, "localhost")

        val release = nodename.share(SYS_NMLN.toLong())
        release.setString(0L, "1.0.0-vortexdbg")

        val version = release.share(SYS_NMLN.toLong())
        version.setString(0L, "#1 SMP PREEMPT Thu Apr 19 14:36:58 CST 2018")

        val machine = version.share(SYS_NMLN.toLong())
        machine.setString(0L, "armv7l")

        val domainname = machine.share(SYS_NMLN.toLong())
        domainname.setString(0L, "localdomain")

        return 0
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
        val start = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt().toLong() and 0xffffffffL
        val length = backend.reg_read(ArmConst.UC_ARM_REG_R1).toInt()
        if (start % emulator.getPageAlign() != 0L) {
            emulator.getMemory().setErrno(UnixEmulator.EINVAL)
            return -1
        }
        emulator.getMemory().munmap(start, length)
        if (log.isDebugEnabled) {
            log.debug("munmap start=0x{}, length={}, offset={}, from={}", java.lang.Long.toHexString(start), length, System.currentTimeMillis() - timeInMillis, VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_LR))
        }
        return 0
    }

    private fun prctl(backend: Backend, emulator: Emulator<*>): Int {
        val option = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        val arg2 = backend.reg_read(ArmConst.UC_ARM_REG_R1).toInt().toLong() and 0xffffffffL
        if (log.isDebugEnabled) {
            log.debug("prctl option=0x{}, arg2=0x{}", Integer.toHexString(option), java.lang.Long.toHexString(arg2))
        }
        when (option) {
            PR_GET_DUMPABLE, PR_SET_DUMPABLE -> return 0
            PR_SET_NAME -> {
                val threadName = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R1)
                val name = threadName.getString(0L)
                if (log.isDebugEnabled) {
                    log.debug("prctl set thread name: {}", name)
                }
                return 0
            }
            PR_GET_NAME -> {
                var name = java.lang.Thread.currentThread().getName()
                if (name.length > 15) {
                    name = name.substring(0, 15)
                }
                if (log.isDebugEnabled) {
                    log.debug("prctl get thread name: {}", name)
                }
                val buffer = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R1)
                buffer.setString(0L, name)
                return 0
            }
            BIONIC_PR_SET_VMA -> {
                val addr = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R2)
                val len = backend.reg_read(ArmConst.UC_ARM_REG_R3).toInt()
                val pointer = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R4)
                if (log.isDebugEnabled) {
                    log.debug("prctl set vma addr={}, len={}, pointer={}, name={}", addr, len, pointer, pointer.getString(0L))
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
        }
        throw UnsupportedOperationException("option=$option")
    }

    private val nanoTime = System.nanoTime()

    protected fun clock_gettime(backend: Backend, emulator: Emulator<*>): Int {
        val clk_id = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        val tp = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R1)
        val offset = if (clk_id == CLOCK_REALTIME) System.currentTimeMillis() * 1000000L else System.nanoTime() - nanoTime
        val tv_sec = offset / 1000000000L
        val tv_nsec = offset % 1000000000L
        if (log.isDebugEnabled) {
            log.debug("clock_gettime clk_id={}, tp={}, offset={}, tv_sec={}, tv_nsec={}", clk_id, tp, offset, tv_sec, tv_nsec)
        }
        when (clk_id) {
            CLOCK_REALTIME, CLOCK_MONOTONIC, CLOCK_MONOTONIC_RAW, CLOCK_MONOTONIC_COARSE, CLOCK_BOOTTIME -> {
                tp.setInt(0L, tv_sec.toInt())
                tp.setInt(4L, tv_nsec.toInt())
                return 0
            }
            CLOCK_THREAD_CPUTIME_ID -> {
                tp.setInt(0L, 0)
                tp.setInt(4L, 1)
                return 0
            }
        }
        throw UnsupportedOperationException("clk_id=$clk_id")
    }

    private fun fcntl(backend: Backend, emulator: Emulator<*>): Int {
        val fd = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        val cmd = backend.reg_read(ArmConst.UC_ARM_REG_R1).toInt()
        val arg = backend.reg_read(ArmConst.UC_ARM_REG_R2).toInt()
        return fcntl(emulator, fd, cmd, arg.toLong())
    }

    private fun writev(backend: Backend, emulator: Emulator<*>): Int {
        val fd = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        val iov = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R1)
        val iovcnt = backend.reg_read(ArmConst.UC_ARM_REG_R2).toInt()
        if (log.isDebugEnabled) {
            for (i in 0 until iovcnt) {
                val iov_base = iov.getPointer(i * 8L)
                val iov_len = iov.getInt(i * 8L + 4)
                val data = iov_base.getByteArray(0L, iov_len)
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
            val iov_base = iov.getPointer(i * 8L)
            val iov_len = iov.getInt(i * 8L + 4)
            val data = iov_base.getByteArray(0L, iov_len)
            count += file.write(data)
        }
        return count
    }

    private fun brk(emulator: Emulator<*>): Int {
        val backend = emulator.getBackend()
        val address = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt().toLong() and 0xffffffffL
        if (log.isDebugEnabled) {
            log.debug("brk address=0x{}", java.lang.Long.toHexString(address))
        }
        return emulator.getMemory().brk(address)
    }

    private fun mprotect(backend: Backend, emulator: Emulator<*>): Int {
        val address = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt().toLong() and 0xffffffffL
        val length = backend.reg_read(ArmConst.UC_ARM_REG_R1).toInt()
        val prot = backend.reg_read(ArmConst.UC_ARM_REG_R2).toInt()
        val alignedAddress = address / ARMEmulator.PAGE_ALIGN * ARMEmulator.PAGE_ALIGN // >> 12 << 12;
        val offset = address - alignedAddress

        val alignedLength = ARM.alignSize(length + offset, emulator.getPageAlign().toLong())
        if (log.isDebugEnabled) {
            log.debug("mprotect address=0x{}, alignedAddress=0x{}, offset={}, length={}, alignedLength={}, prot=0x{}", java.lang.Long.toHexString(address), java.lang.Long.toHexString(alignedAddress), offset, length, alignedLength, Integer.toHexString(prot))
        }
        return emulator.getMemory().mprotect(alignedAddress, alignedLength.toInt(), prot)
    }

    private fun mmap2(backend: Backend, emulator: Emulator<*>): Int {
        val start = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt().toLong() and 0xffffffffL
        val length = backend.reg_read(ArmConst.UC_ARM_REG_R1).toInt()
        val prot = backend.reg_read(ArmConst.UC_ARM_REG_R2).toInt()
        val flags = backend.reg_read(ArmConst.UC_ARM_REG_R3).toInt()
        val fd = backend.reg_read(ArmConst.UC_ARM_REG_R4).toInt()
        val offset = backend.reg_read(ArmConst.UC_ARM_REG_R5).toInt() shl MMAP2_SHIFT

        val warning = length >= 0x10000000
        if (log.isDebugEnabled || warning) {
            val msg = "mmap2 start=0x" + java.lang.Long.toHexString(start) + ", length=" + length + ", prot=0x" + Integer.toHexString(prot) + ", flags=0x" + Integer.toHexString(flags) + ", fd=" + fd + ", offset=" + offset + ", from=" + VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_LR)
            if (warning) {
                log.warn(msg)
                if (log.isDebugEnabled) {
                    emulator.attach().debug("mmap2 warning")
                }
            } else {
                log.debug(msg)
            }
        }
        return emulator.getMemory().mmap2(start, length, prot, flags, fd, offset).toInt()
    }

    private fun gettimeofday(emulator: Emulator<*>): Int {
        val tv = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R0)
        val tz = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R1)
        return gettimeofday(emulator, tv, tz)
    }

    private fun faccessat(backend: Backend, emulator: Emulator<AndroidFileIO>): Int {
        val dirfd = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        val pathname_p = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R1)
        val oflags = backend.reg_read(ArmConst.UC_ARM_REG_R2).toInt()
        val mode = backend.reg_read(ArmConst.UC_ARM_REG_R3).toInt()
        val pathname = pathname_p.getString(0L)
        val msg = "faccessat dirfd=" + dirfd + ", pathname=" + pathname + ", oflags=0x" + Integer.toHexString(oflags) + ", mode=0x" + Integer.toHexString(mode)
        if (log.isDebugEnabled) {
            log.debug(msg)
        }
        val ret = faccessat(emulator, pathname)
        if (ret == -1) {
            log.info(msg)
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

    private fun fstatat64(backend: Backend, emulator: Emulator<AndroidFileIO>): Int {
        val dirfd = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        val pathname = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R1)
        val statbuf = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R2)
        val flags = backend.reg_read(ArmConst.UC_ARM_REG_R3).toInt()
        val path = FilenameUtils.normalize(pathname.getString(0L), true)
        if (log.isDebugEnabled) {
            log.debug("fstatat64 dirfd={}, pathname={}, statbuf={}, flags={}", dirfd, path, statbuf, flags)
        }
        if (dirfd != IO.AT_FDCWD && !path.startsWith("/")) {
            throw BackendException()
        }
        return stat64(emulator, path, statbuf)
    }

    private fun openat(emulator: Emulator<AndroidFileIO>): Int {
        val context = emulator.getContext<RegisterContext>()
        val dirfd = context.getIntArg(0)
        val pathname_p = context.getPointerArg(1)
        val oflags = context.getIntArg(2)
        val mode = context.getIntArg(3)
        var pathname = pathname_p!!.getString(0L)
        log.debug("openat dirfd={}, pathname={}, oflags=0x{}, mode={}", dirfd, pathname, Integer.toHexString(oflags), Integer.toHexString(mode))
        pathname = FilenameUtils.normalize(pathname, true)
        if ("/data/misc/zoneinfo/current/tzdata" == pathname || "/dev/pmsg0" == pathname) {
            emulator.getMemory().setErrno(UnixEmulator.ENOENT)
            return -UnixEmulator.ENOENT
        }
        if (pathname.startsWith("/")) {
            val fd = open(emulator, pathname, oflags)
            if (fd == -1) {
                log.info("openat dirfd={}, pathname={}, oflags=0x{}, mode={}", dirfd, pathname, Integer.toHexString(oflags), Integer.toHexString(mode))
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
                log.info("openat AT_FDCWD dirfd={}, pathname={}, oflags=0x{}, mode={}", dirfd, pathname, Integer.toHexString(oflags), Integer.toHexString(mode))
                return -emulator.getMemory().getLastErrno()
            } else {
                return fd
            }
        }
    }

    private fun open(emulator: Emulator<AndroidFileIO>): Int {
        val context = emulator.getContext<RegisterContext>()
        val pathname_p = context.getPointerArg(0)
        val oflags = context.getIntArg(1)
        val mode = context.getIntArg(2)
        val pathname = pathname_p!!.getString(0L)
        val msg = "open pathname=" + pathname + ", oflags=0x" + Integer.toHexString(oflags) + ", mode=" + Integer.toHexString(mode) + ", from=" + VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_LR)
        if (log.isDebugEnabled) {
            log.debug(msg)
        }
        val fd = open(emulator, pathname, oflags)
        if (fd == -1) {
            log.info(msg)
            return -emulator.getMemory().getLastErrno()
        } else {
            return fd
        }
    }

    private fun ftruncate(backend: Backend): Int {
        val fd = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        val length = backend.reg_read(ArmConst.UC_ARM_REG_R1).toInt()
        if (log.isDebugEnabled) {
            log.debug("ftruncate fd={}, length={}", fd, length)
        }
        val file = fdMap.get(fd) ?: throw UnsupportedOperationException()
        return file.ftruncate(length)
    }

    private fun lseek(emulator: Emulator<*>): Int {
        val backend = emulator.getBackend()
        val fd = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        val offset = backend.reg_read(ArmConst.UC_ARM_REG_R1).toInt()
        val whence = backend.reg_read(ArmConst.UC_ARM_REG_R2).toInt()
        val file = fdMap.get(fd)
        if (file == null) {
            emulator.getMemory().setErrno(UnixEmulator.EBADF)
            return -1
        }
        val pos = file.lseek(offset, whence)
        if (log.isDebugEnabled) {
            log.debug("lseek fd={}, offset={}, whence={}, pos={}, from={}", fd, offset, whence, pos, VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_LR))
        }
        return pos
    }

    private fun close(emulator: Emulator<*>): Int {
        val backend = emulator.getBackend()
        val fd = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
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

    private fun fstat(backend: Backend, emulator: Emulator<*>): Int {
        val fd = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        val stat = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R1)
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
        return file.fstat(emulator, Stat32(stat))
    }

    private fun ioctl(emulator: Emulator<*>): Int {
        val backend = emulator.getBackend()
        val fd = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        val request = backend.reg_read(ArmConst.UC_ARM_REG_R1).toInt().toLong() and 0xffffffffL
        val argp = backend.reg_read(ArmConst.UC_ARM_REG_R2).toInt().toLong() and 0xffffffffL
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
        val backend = emulator.getBackend()
        val fd = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        val buffer = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R1)
        val count = backend.reg_read(ArmConst.UC_ARM_REG_R2).toInt()
        return write(emulator, fd, buffer, count)
    }

    private fun read(emulator: Emulator<*>): Int {
        val backend = emulator.getBackend()
        val fd = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        val buffer = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R1)
        val count = backend.reg_read(ArmConst.UC_ARM_REG_R2).toInt()
        return read(emulator, fd, buffer, count)
    }

    private fun pread64(emulator: Emulator<*>): Int {
        val context = emulator.getContext<RegisterContext>()
        val fd = context.getIntArg(0)
        val buffer = context.getPointerArg(1)
        val count = context.getIntArg(2)
        val offset = context.getIntByReg(ArmConst.UC_ARM_REG_R4).toLong() or (context.getIntByReg(ArmConst.UC_ARM_REG_R5).toLong() shl 32)
        return pread(emulator, fd, buffer!!, count, offset)
    }

    private fun dup2(backend: Backend, emulator: Emulator<*>): Int {
        val oldfd = backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt()
        val newfd = backend.reg_read(ArmConst.UC_ARM_REG_R1).toInt()
        if (log.isDebugEnabled) {
            log.debug("dup2 oldfd={}, newfd={}", oldfd, newfd)
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
        private val log: Logger = LoggerFactory.getLogger(ARM32SyscallHandler::class.java)

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

        private const val PR_GET_DUMPABLE = 3
        private const val PR_SET_DUMPABLE = 4
        private const val PR_SET_NAME = 15
        private const val PR_GET_NAME = 16
        private const val BIONIC_PR_SET_VMA = 0x53564d41
        private const val PR_SET_PTRACER = 0x59616d61

        private const val CLOCK_REALTIME = 0
        private const val CLOCK_MONOTONIC = 1
        private const val CLOCK_THREAD_CPUTIME_ID = 3
        private const val CLOCK_MONOTONIC_RAW = 4
        private const val CLOCK_MONOTONIC_COARSE = 6
        private const val CLOCK_BOOTTIME = 7

        private const val MMAP2_SHIFT = 12
    }
}
