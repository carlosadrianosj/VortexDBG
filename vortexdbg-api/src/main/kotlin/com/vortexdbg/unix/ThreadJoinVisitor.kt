package com.vortexdbg.unix

import com.sun.jna.Pointer

abstract class ThreadJoinVisitor(private val saveContext: Boolean) {

    constructor() : this(false)

    fun isSaveContext(): Boolean {
        return saveContext
    }

    abstract fun canJoin(start_routine: Pointer, threadId: Int): Boolean

}
