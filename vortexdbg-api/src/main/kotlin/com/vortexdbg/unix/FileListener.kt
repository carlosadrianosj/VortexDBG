package com.vortexdbg.unix

import com.vortexdbg.Emulator
import com.vortexdbg.file.FileIO

interface FileListener {

    fun onOpenSuccess(emulator: Emulator<*>, pathname: String, io: FileIO)

    fun onRead(emulator: Emulator<*>, pathname: String, bytes: ByteArray)
    fun onWrite(emulator: Emulator<*>, pathname: String, bytes: ByteArray)

    fun onClose(emulator: Emulator<*>, io: FileIO)

}
