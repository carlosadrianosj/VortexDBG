package com.vortexdbg.virtualmodule.android

import com.vortexdbg.Emulator
import com.vortexdbg.arm.Arm64Hook
import com.vortexdbg.arm.ArmHook
import com.vortexdbg.arm.HookStatus
import com.vortexdbg.arm.NestedRun
import com.vortexdbg.arm.context.EditableArm32RegisterContext
import com.vortexdbg.arm.context.EditableArm64RegisterContext
import com.vortexdbg.linux.android.SystemPropertyHook
import com.vortexdbg.memory.SvcMemory
import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.virtualmodule.VirtualModule
import com.sun.jna.Pointer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class SystemProperties(emulator: Emulator<*>, extra: Void?) : VirtualModule<Void>(emulator, extra, "libsystemproperties.so") {

    override fun onInitialize(emulator: Emulator<*>, extra: Void?, symbols: MutableMap<String, VortexdbgPointer>) {
        val is64Bit = emulator.is64Bit()
        val svcMemory = emulator.getSvcMemory()
        symbols.put("__system_property_read_callback", svcMemory.registerSvc(if (is64Bit) object : Arm64Hook() {
            override fun hook(emulator: Emulator<*>): HookStatus {
                val context = emulator.getContext<EditableArm64RegisterContext>()
                val pi = context.getPointerArg(0)
                val callback = context.getPointerArg(1)
                val cookie = context.getPointerArg(2)
                log.debug("__system_property_read_callback pi={}, callback={}, cookie={}", pi, callback, cookie)
                val key = pi!!.share((SystemPropertyHook.PROP_VALUE_MAX + 4).toLong())
                val value = pi.share(4L)
                context.setXLong(0, VortexdbgPointer.nativeValueOf(cookie))
                context.setXLong(1, VortexdbgPointer.nativeValueOf(value))
                context.setXLong(2, VortexdbgPointer.nativeValueOf(key))
                context.setXLong(3, pi.getInt(0L).toLong())
                return HookStatus.RET(emulator, VortexdbgPointer.nativeValueOf(callback))
            }
        } else object : ArmHook() {
            @Throws(NestedRun::class)
            override fun hook(emulator: Emulator<*>): HookStatus {
                val context = emulator.getContext<EditableArm32RegisterContext>()
                val pi = context.getPointerArg(0)
                val callback = context.getPointerArg(1)
                val cookie = context.getPointerArg(2)
                log.debug("__system_property_read_callback pi={}, callback={}, cookie={}", pi, callback, cookie)
                val key = pi!!.share((SystemPropertyHook.PROP_VALUE_MAX + 4).toLong())
                val value = pi.share(4L)
                context.setR0(VortexdbgPointer.nativeValueOf(cookie).toInt())
                context.setR1(VortexdbgPointer.nativeValueOf(value).toInt())
                context.setR2(VortexdbgPointer.nativeValueOf(key).toInt())
                context.setR3(pi.getInt(0L))
                return HookStatus.RET(emulator, VortexdbgPointer.nativeValueOf(callback))
            }
        }))
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(SystemProperties::class.java)
    }

}
