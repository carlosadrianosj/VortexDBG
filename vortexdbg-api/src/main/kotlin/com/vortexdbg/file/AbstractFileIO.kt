package com.vortexdbg.file

import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.utils.Inspector
import com.sun.jna.Pointer
import org.slf4j.LoggerFactory
import java.io.IOException

abstract class AbstractFileIO protected constructor(@JvmField protected var oflags: Int) : NewFileIO {

    @JvmField
    protected var op: Int = 0

    protected abstract fun setFlags(arg: Long)

    override fun fcntl(emulator: Emulator<*>, cmd: Int, arg: Long): Int {
        when (cmd) {
            F_GETFD -> return op
            F_SETFD -> {
                if (FD_CLOEXEC.toLong() == arg) {
                    op = op or FD_CLOEXEC
                    return 0
                }
            }
            F_GETFL -> return oflags
            F_SETFL -> {
                setFlags(arg)
                return 0
            }
            F_SETLK, F_SETLKW, F_ADDFILESIGS -> return 0
        }
        throw UnsupportedOperationException(javaClass.name + ", cmd=" + cmd + ", arg=0x" + java.lang.Long.toHexString(arg and 0xffffffffL) + ", this=" + this)
    }

    override fun ioctl(emulator: Emulator<*>, request: Long, argp: Long): Int {
        if (log.isTraceEnabled) {
            emulator.attach().debug("Unsupported ioctl request=0x" + java.lang.Long.toHexString(request) + " on " + javaClass.name)
        }
        throw AbstractMethodError(javaClass.name + ": request=0x" + java.lang.Long.toHexString(request) + ", argp=0x" + java.lang.Long.toHexString(argp))
    }

    override fun bind(addr: Pointer, addrlen: Int): Int {
        throw AbstractMethodError(javaClass.name)
    }

    override fun listen(backlog: Int): Int {
        throw AbstractMethodError(javaClass.name)
    }

    override fun connect(addr: Pointer, addrlen: Int): Int {
        throw AbstractMethodError(javaClass.name)
    }

    override fun setsockopt(level: Int, optname: Int, optval: Pointer, optlen: Int): Int {
        throw AbstractMethodError()
    }

    override fun getsockopt(level: Int, optname: Int, optval: Pointer, optlen: Pointer): Int {
        throw AbstractMethodError(javaClass.name + ": level=" + level + ", optname=" + optname + ", optval=" + optval + ", optlen=" + optlen)
    }

    override fun getsockname(addr: Pointer, addrlen: Pointer): Int {
        throw AbstractMethodError(javaClass.name)
    }

    override fun sendto(data: ByteArray, flags: Int, dest_addr: Pointer, addrlen: Int): Int {
        throw AbstractMethodError(Inspector.inspectString(data, "sendto flags=0x" + Integer.toHexString(flags) + ", dest_addr=" + dest_addr + ", addrlen=" + addrlen))
    }

    override fun recvfrom(backend: Backend, buf: Pointer, len: Int, flags: Int, src_addr: Pointer, addrlen: Pointer): Int {
        throw AbstractMethodError(javaClass.name + ": recvfrom buf=" + buf + ", len=" + len + ", flags=0x" + Integer.toHexString(flags) + ", src_addr=" + src_addr + ", addrlen=" + addrlen)
    }

    override fun lseek(offset: Int, whence: Int): Int {
        throw AbstractMethodError("class=" + javaClass + ", offset=0x" + java.lang.Long.toHexString(offset.toLong()) + ", whence=" + whence + ", path=" + getPath())
    }

    override fun ftruncate(length: Int): Int {
        throw UnsupportedOperationException(javaClass.name)
    }

    override fun getpeername(addr: Pointer, addrlen: Pointer): Int {
        throw AbstractMethodError()
    }

    override fun shutdown(how: Int): Int {
        throw AbstractMethodError()
    }

    @Throws(IOException::class)
    final override fun mmap2(emulator: Emulator<*>, addr: Long, aligned: Int, prot: Int, offset: Int, length: Int): Long {
        val backend = emulator.getBackend()
        val data = getMmapData(addr, offset, length)
        backend.mem_map(addr, aligned.toLong(), prot)
        emulator.getMemory().pointer(addr).write(data)
        return addr
    }

    @Throws(IOException::class)
    protected open fun getMmapData(addr: Long, offset: Int, length: Int): ByteArray {
        throw AbstractMethodError(javaClass.name + ", addr=0x" + java.lang.Long.toHexString(addr) + ", offset=" + offset + ", length=" + length)
    }

    override fun llseek(offset: Long, result: Pointer, whence: Int): Int {
        throw AbstractMethodError()
    }

    override fun close() {
        throw AbstractMethodError(javaClass.name)
    }

    override fun write(data: ByteArray): Int {
        throw UnsupportedOperationException(Inspector.inspectString(data, javaClass.name))
    }

    override fun read(backend: Backend, buffer: Pointer, count: Int): Int {
        throw UnsupportedOperationException(javaClass.name)
    }

    override fun pread(backend: Backend, buffer: Pointer, count: Int, offset: Long): Int {
        throw UnsupportedOperationException(javaClass.name)
    }

    override fun dup2(): FileIO {
        throw AbstractMethodError(javaClass.name)
    }

    override fun getPath(): String {
        throw AbstractMethodError(javaClass.name)
    }

    override fun canRead(): Boolean {
        return true
    }

    @JvmField
    protected var stdio: Boolean = false

    override fun isStdIO(): Boolean {
        return stdio
    }

    companion object {
        private val log = LoggerFactory.getLogger(AbstractFileIO::class.java)

        private const val F_GETFD = 1 /* get file descriptor flags */
        private const val F_SETFD = 2 /* set file descriptor flags */
        private const val F_GETFL = 3 /* get file status flags */
        private const val F_SETFL = 4 /* set file status flags */
        private const val F_SETLK = 6 /* Set record locking info (non-blocking).  */
        private const val F_SETLKW = 7 /* Set record locking info (blocking).	*/
        private const val F_ADDFILESIGS = 61 /* add signature from same file (used by dyld for shared libs) */

        private const val FD_CLOEXEC = 1
    }
}
