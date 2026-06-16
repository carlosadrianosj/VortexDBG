package com.vortexdbg.file

import com.vortexdbg.Emulator

interface IOResolver<T : NewFileIO> {

    fun resolve(emulator: Emulator<T>, pathname: String, oflags: Int): FileResult<T>?

}
