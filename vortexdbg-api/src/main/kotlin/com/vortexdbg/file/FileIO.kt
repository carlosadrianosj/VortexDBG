package com.vortexdbg.file

import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.Backend
import com.sun.jna.Pointer
import java.io.IOException

interface FileIO {

    fun close()

    fun write(data: ByteArray): Int

    fun read(backend: Backend, buffer: Pointer, count: Int): Int

    fun pread(backend: Backend, buffer: Pointer, count: Int, offset: Long): Int

    fun fcntl(emulator: Emulator<*>, cmd: Int, arg: Long): Int

    fun ioctl(emulator: Emulator<*>, request: Long, argp: Long): Int

    fun dup2(): FileIO

    fun connect(addr: Pointer, addrlen: Int): Int

    fun bind(addr: Pointer, addrlen: Int): Int

    fun listen(backlog: Int): Int

    fun setsockopt(level: Int, optname: Int, optval: Pointer, optlen: Int): Int

    fun sendto(data: ByteArray, flags: Int, dest_addr: Pointer, addrlen: Int): Int

    fun lseek(offset: Int, whence: Int): Int

    fun ftruncate(length: Int): Int

    fun getpeername(addr: Pointer, addrlen: Pointer): Int

    fun shutdown(how: Int): Int

    fun getsockopt(level: Int, optname: Int, optval: Pointer, optlen: Pointer): Int

    fun getsockname(addr: Pointer, addrlen: Pointer): Int

    @Throws(IOException::class)
    fun mmap2(emulator: Emulator<*>, addr: Long, aligned: Int, prot: Int, offset: Int, length: Int): Long

    fun llseek(offset: Long, result: Pointer, whence: Int): Int

    fun recvfrom(backend: Backend, buf: Pointer, len: Int, flags: Int, src_addr: Pointer, addrlen: Pointer): Int

    fun getPath(): String

    fun isStdIO(): Boolean

    companion object {
        const val SEEK_SET = 0
        const val SEEK_CUR = 1
        const val SEEK_END = 2
    }
}
