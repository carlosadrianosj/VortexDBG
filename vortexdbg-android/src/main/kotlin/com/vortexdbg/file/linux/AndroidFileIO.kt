package com.vortexdbg.file.linux

import com.vortexdbg.Emulator
import com.vortexdbg.file.NewFileIO
import com.vortexdbg.linux.struct.StatFS
import com.sun.jna.Pointer

interface AndroidFileIO : NewFileIO {

    fun fstat(emulator: Emulator<*>, stat: StatStructure): Int

    fun getdents64(dirp: Pointer, size: Int): Int

    fun accept(addr: Pointer, addrlen: Pointer): AndroidFileIO

    fun statfs(statFS: StatFS): Int

    companion object {
        const val SIOCGIFNAME = 0x8910 /* get iface name		*/
        const val SIOCGIFCONF = 0x8912 /* get iface list		*/
        const val SIOCGIFFLAGS = 0x8913 /* get flags			*/
    }
}
