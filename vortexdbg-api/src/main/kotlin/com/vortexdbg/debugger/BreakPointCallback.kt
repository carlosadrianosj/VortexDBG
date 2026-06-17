package com.vortexdbg.debugger

import com.vortexdbg.Emulator

interface BreakPointCallback {

    /**
     * 当断点被触发时回调
     * @return 返回`false`表示断点成功，返回`true`表示不触发断点，继续进行
     */
    fun onHit(emulator: Emulator<*>, address: Long): Boolean

}
