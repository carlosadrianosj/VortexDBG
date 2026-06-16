package com.vortexdbg.memory

interface MMapListener {

    fun onMap(address: Long, size: Long, perms: Int)

    fun onProtect(address: Long, size: Long, perms: Int): Int

    fun onUnmap(address: Long, size: Long)

}
