package com.vortexdbg.linux.android.dvm

interface Array<T> {

    fun length(): Int

    fun setData(start: Int, data: T)

}
