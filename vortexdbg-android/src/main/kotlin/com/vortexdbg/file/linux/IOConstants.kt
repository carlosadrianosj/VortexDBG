package com.vortexdbg.file.linux

interface IOConstants {

    companion object {
        const val O_RDONLY = 0
        const val O_WRONLY = 1
        const val O_RDWR = 2
        const val O_CREAT = 0x40
        const val O_EXCL = 0x80
        const val O_APPEND = 0x400
        const val O_NONBLOCK = 0x800
        const val O_DIRECTORY = 0x10000
        const val O_NOFOLLOW = 0x20000
        const val O_CLOEXEC = 0x80000
    }

}
