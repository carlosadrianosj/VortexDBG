package com.vortexdbg.linux.android

import com.vortexdbg.Emulator
import com.vortexdbg.arm.Arm64Hook
import com.vortexdbg.arm.ArmHook
import com.vortexdbg.arm.HookStatus
import com.vortexdbg.arm.backend.BackendException
import com.vortexdbg.arm.context.EditableArm64RegisterContext
import com.vortexdbg.arm.context.RegisterContext
import com.vortexdbg.hook.HookListener
import com.vortexdbg.memory.SvcMemory
import com.vortexdbg.pointer.VortexdbgPointer
import com.sun.jna.Pointer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.util.Arrays

open class SystemPropertyHook(private val emulator: Emulator<*>) : HookListener {

    override fun hook(svcMemory: SvcMemory, libraryName: String, symbolName: String, old: Long): Long {
        if ("libc.so" == libraryName) {
            if ("__system_property_get" == symbolName) {
                log.debug("Hook {}", symbolName)
                if (emulator.is64Bit()) {
                    return svcMemory.registerSvc(object : Arm64Hook() {
                        override fun hook(emulator: Emulator<*>): HookStatus {
                            val context = emulator.getContext<RegisterContext>()
                            val index = 0
                            val pointer = context.getPointerArg(index)
                            val key = pointer!!.getString(0L)
                            return __system_property_get(old, key, index)
                        }
                    }).peer
                } else {
                    return svcMemory.registerSvc(object : ArmHook() {
                        override fun hook(emulator: Emulator<*>): HookStatus {
                            val context = emulator.getContext<RegisterContext>()
                            val index = 0
                            val pointer = context.getPointerArg(index)
                            val key = pointer!!.getString(0L)
                            return __system_property_get(old, key, index)
                        }
                    }).peer
                }
            }
            if ("__system_property_read" == symbolName) {
                log.debug("Hook {}", symbolName)
                if (emulator.is64Bit()) {
                    return svcMemory.registerSvc(object : Arm64Hook() {
                        override fun hook(emulator: Emulator<*>): HookStatus {
                            val context = emulator.getContext<RegisterContext>()
                            val pi = context.getPointerArg(0)
                            val key = pi!!.share((PROP_VALUE_MAX + 4).toLong()).getString(0L)
                            return __system_property_get(old, key, 1)
                        }
                    }).peer
                } else {
                    return svcMemory.registerSvc(object : ArmHook() {
                        override fun hook(emulator: Emulator<*>): HookStatus {
                            val context = emulator.getContext<RegisterContext>()
                            val pi = context.getPointerArg(0)
                            val key = pi!!.share((PROP_VALUE_MAX + 4).toLong()).getString(0L)
                            return __system_property_get(old, key, 1)
                        }
                    }).peer
                }
            }
            if ("__system_property_find" == symbolName) {
                log.debug("Hook {}", symbolName)
                if (emulator.is64Bit()) {
                    return svcMemory.registerSvc(object : Arm64Hook(true) {
                        private var name: String? = null
                        override fun hook(emulator: Emulator<*>): HookStatus {
                            val context = emulator.getContext<RegisterContext>()
                            val name = context.getPointerArg(0)
                            this.name = name!!.getString(0L)
                            if (log.isDebugEnabled) {
                                log.debug("__system_property_find key={}, LR={}", this.name, context.getLRPointer())
                            }
                            if (log.isTraceEnabled) {
                                emulator.attach().debug("__system_property_find key=" + this.name)
                            }
                            return HookStatus.RET(emulator, old)
                        }
                        override fun handlePostCallback(emulator: Emulator<*>) {
                            super.handlePostCallback(emulator)
                            val context = emulator.getContext<EditableArm64RegisterContext>()
                            val pi = context.getPointerArg(0)
                            if (log.isDebugEnabled) {
                                log.debug("__system_property_find key={}, pi={}, value={}", this.name, pi, if (pi == null) null else pi.share(4L).getString(0L))
                            }
                            if (propertyProvider != null) {
                                val replace = propertyProvider!!.__system_property_find(this.name!!)
                                if (replace != null) {
                                    context.setXLong(0, VortexdbgPointer.nativeValueOf(replace))
                                }
                            }
                        }
                    }).peer
                } else {
                    return svcMemory.registerSvc(object : ArmHook() {
                        override fun hook(emulator: Emulator<*>): HookStatus {
                            val context = emulator.getContext<RegisterContext>()
                            val name = context.getPointerArg(0)
                            if (log.isDebugEnabled) {
                                log.debug("__system_property_find key={}, LR={}", name!!.getString(0L), context.getLRPointer())
                            }
                            if (log.isTraceEnabled) {
                                emulator.attach().debug("__system_property_find key=" + name!!.getString(0L))
                            }
                            return HookStatus.RET(emulator, old)
                        }
                    }).peer
                }
            }
        }
        return 0
    }

    private fun __system_property_get(old: Long, key: String, index: Int): HookStatus {
        val context = emulator.getContext<RegisterContext>()
        if (propertyProvider != null) {
            val value = propertyProvider!!.getProperty(key)
            if (value != null) {
                log.debug("__system_property_get key={}, value={}", key, value)

                val data = value.toByteArray(StandardCharsets.UTF_8)
                if (data.size >= PROP_VALUE_MAX) {
                    throw BackendException("invalid property value length: key=$key, value=$value")
                }

                val newData = Arrays.copyOf(data, data.size + 1)
                val pointer = context.getPointerArg(index + 1)
                pointer!!.write(0L, newData, 0, newData.size)
                return HookStatus.LR(emulator, data.size.toLong())
            }
        }

        log.debug("__system_property_get key={}", key)
        return HookStatus.RET(emulator, old)
    }

    private var propertyProvider: SystemPropertyProvider? = null

    fun setPropertyProvider(propertyProvider: SystemPropertyProvider) {
        this.propertyProvider = propertyProvider
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(SystemPropertyHook::class.java)

        const val PROP_VALUE_MAX = 92
    }

}
