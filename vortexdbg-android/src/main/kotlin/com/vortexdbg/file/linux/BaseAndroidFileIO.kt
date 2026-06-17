package com.vortexdbg.file.linux

import com.vortexdbg.Emulator
import com.vortexdbg.file.BaseFileIO
import com.vortexdbg.linux.struct.StatFS
import com.sun.jna.Pointer

abstract class BaseAndroidFileIO(oflags: Int) : BaseFileIO(oflags), AndroidFileIO {

    override fun fstat(emulator: Emulator<*>, stat: StatStructure): Int {
        throw UnsupportedOperationException(javaClass.name)
    }

    override fun getdents64(dirp: Pointer, size: Int): Int {
        throw UnsupportedOperationException(javaClass.name)
    }

    override fun accept(addr: Pointer, addrlen: Pointer): AndroidFileIO {
        throw UnsupportedOperationException(javaClass.name)
    }

    override fun statfs(statFS: StatFS): Int {
        throw UnsupportedOperationException(javaClass.name)
    }

    override fun setFlags(arg: Long) {
        if ((IOConstants.O_APPEND.toLong() and arg) != 0L) {
            oflags = oflags or IOConstants.O_APPEND
        }
        if ((IOConstants.O_RDWR.toLong() and arg) != 0L) {
            oflags = oflags or IOConstants.O_RDWR
        }
        if ((IOConstants.O_NONBLOCK.toLong() and arg) != 0L) {
            oflags = oflags or IOConstants.O_NONBLOCK
        }
    }
}
