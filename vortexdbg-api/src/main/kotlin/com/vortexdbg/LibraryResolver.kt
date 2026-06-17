package com.vortexdbg

import com.vortexdbg.spi.LibraryFile

interface LibraryResolver {

    fun resolveLibrary(emulator: Emulator<*>, libraryName: String): LibraryFile?

    fun onSetToLoader(emulator: Emulator<*>)
}
