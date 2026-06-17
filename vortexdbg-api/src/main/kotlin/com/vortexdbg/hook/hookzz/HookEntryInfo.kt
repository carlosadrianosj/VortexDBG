package com.vortexdbg.hook.hookzz

interface HookEntryInfo {

    @Suppress("unused")
    fun getHookId(): Long

    fun getAddress(): Long

}
