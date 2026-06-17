package com.vortexdbg.linux.android.dvm

import com.vortexdbg.Emulator
import com.vortexdbg.Module
import com.vortexdbg.Symbol
import org.slf4j.LoggerFactory

open class DalvikModule internal constructor(private val vm: BaseVM, private val module: Module) {

    open fun getModule(): Module {
        return module
    }

    open fun callJNI_OnLoad(emulator: Emulator<*>) {
        val onLoad: Symbol? = module.findSymbolByName("JNI_OnLoad", false)
        if (onLoad != null) {
            try {
                val start = System.currentTimeMillis()
                if (log.isDebugEnabled) {
                    log.debug("Call [{}]JNI_OnLoad: 0x{}", module.name, java.lang.Long.toHexString(onLoad.getAddress()))
                }
                val ret: Number = onLoad.call(emulator, vm.getJavaVM(), null)
                val version = ret.toInt()
                if (log.isDebugEnabled) {
                    log.debug(
                        "Call [{}]JNI_OnLoad finished: version=0x{}, offset={}ms",
                        module.name, Integer.toHexString(version), System.currentTimeMillis() - start
                    )
                }

                vm.checkVersion(version)
            } finally {
                vm.deleteLocalRefs()
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(DalvikModule::class.java)
    }
}
