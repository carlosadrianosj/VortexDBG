package com.vortexdbg.file

import java.io.File
import java.io.FileFilter

class VortexdbgFileFilter : FileFilter {

    override fun accept(pathname: File): Boolean {
        return !pathname.name.startsWith(UNIDBG_PREFIX)
    }

    companion object {
        const val UNIDBG_PREFIX = "__ignore.vortexdbg"
    }
}
