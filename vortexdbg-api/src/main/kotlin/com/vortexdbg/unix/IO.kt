package com.vortexdbg.unix

interface IO {

    companion object {
        const val STDIN = "stdin"
        const val FD_STDIN = 0

        const val STDOUT = "stdout"
        const val FD_STDOUT = 1

        const val STDERR = "stderr"
        const val FD_STDERR = 2

        const val S_IFREG = 0x8000 // regular file
        const val S_IFDIR = 0x4000 // directory
        const val S_IFCHR = 0x2000 // character device
        const val S_IFLNK = 0xa000 // symbolic link
        const val S_IFSOCK = 0xc000 // socket

        const val AT_FDCWD = -100
    }
}
