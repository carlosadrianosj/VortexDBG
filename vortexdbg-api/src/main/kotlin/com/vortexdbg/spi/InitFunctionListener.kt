package com.vortexdbg.spi

import com.vortexdbg.Module

interface InitFunctionListener {

    fun onPreCallInitFunction(module: Module, initFunction: Long, index: Int)

    fun onPostCallInitFunction(module: Module, initFunction: Long, index: Int)

}
