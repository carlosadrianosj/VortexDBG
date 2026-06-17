package com.vortexdbg.hook.hookzz

import com.vortexdbg.Emulator
import com.vortexdbg.Svc
import com.vortexdbg.Symbol
import com.vortexdbg.arm.Arm64Svc
import com.vortexdbg.arm.ArmSvc
import com.vortexdbg.arm.context.RegisterContext
import com.vortexdbg.hook.BaseHook
import com.vortexdbg.hook.ReplaceCallback
import com.vortexdbg.pointer.VortexdbgPointer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.Stack

/**
 * 对32位支持比较好
 */
class HookZz private constructor(emulator: Emulator<*>) : BaseHook(emulator, "libhookzz"), IHookZz {

    private val zz_enable_arm_arm64_b_branch: Symbol?
    private val zz_disable_arm_arm64_b_branch: Symbol?

    private val zzReplace: Symbol?
    private val zzWrap: Symbol?
    private val zzDynamicBinaryInstrumentation: Symbol?

    init {
        zz_enable_arm_arm64_b_branch = module.findSymbolByName("zz_enable_arm_arm64_b_branch", false)
        zz_disable_arm_arm64_b_branch = module.findSymbolByName("zz_disable_arm_arm64_b_branch", false)
        zzReplace = module.findSymbolByName("ZzReplace", false)
        zzWrap = module.findSymbolByName("ZzWrap", false)
        zzDynamicBinaryInstrumentation = module.findSymbolByName("ZzDynamicBinaryInstrumentation", false)
        if (log.isDebugEnabled) {
            log.debug("zzReplace={}, zzWrap={}", zzReplace, zzWrap)
        }

        if (zz_enable_arm_arm64_b_branch == null) {
            throw IllegalStateException("zz_enable_arm_arm64_b_branch is null")
        }
        if (zz_disable_arm_arm64_b_branch == null) {
            throw IllegalStateException("zz_disable_arm_arm64_b_branch is null")
        }
        if (zzReplace == null) {
            throw IllegalStateException("zzReplace is null")
        }
        if (zzWrap == null) {
            throw IllegalStateException("zzWrap is null")
        }
        if (zzDynamicBinaryInstrumentation == null) {
            throw IllegalStateException("zzDynamicBinaryInstrumentation is null")
        }
    }

    override fun enable_arm_arm64_b_branch() {
        val ret = zz_enable_arm_arm64_b_branch!!.call(emulator).toInt()
        if (ret != RS_SUCCESS) {
            throw IllegalStateException("ret=$ret")
        }
    }

    override fun disable_arm_arm64_b_branch() {
        val ret = zz_disable_arm_arm64_b_branch!!.call(emulator).toInt()
        if (ret != RS_SUCCESS) {
            throw IllegalStateException("ret=$ret")
        }
    }

    override fun replace(functionAddress: Long, callback: ReplaceCallback) {
        replace(functionAddress, callback, false)
    }

    override fun replace(symbol: Symbol, callback: ReplaceCallback) {
        replace(symbol, callback, false)
    }

    override fun replace(functionAddress: Long, svc: Svc) {
        if (svc == null) {
            throw NullPointerException()
        }
        val originCall = emulator.getMemory().malloc(emulator.getPointerSize(), false).getPointer()
        val callback = emulator.getSvcMemory().registerSvc(svc)
        val ret = zzReplace!!.call(emulator, VortexdbgPointer.pointer(emulator, functionAddress), callback, originCall).toInt()
        if (ret != RS_SUCCESS) {
            throw IllegalStateException("ret=$ret")
        }
    }

    override fun replace(symbol: Symbol, svc: Svc) {
        replace(symbol.getAddress(), svc)
    }

    override fun replace(functionAddress: Long, callback: ReplaceCallback, enablePostCall: Boolean) {
        val originCall = emulator.getMemory().malloc(emulator.getPointerSize(), false).getPointer()
        val replaceCall = createReplacePointer(callback, originCall, enablePostCall)
        val ret = zzReplace!!.call(emulator, VortexdbgPointer.pointer(emulator, functionAddress), replaceCall, originCall).toInt()
        if (ret != RS_SUCCESS) {
            throw IllegalStateException("ret=$ret")
        }
    }

    override fun replace(symbol: Symbol, callback: ReplaceCallback, enablePostCall: Boolean) {
        replace(symbol.getAddress(), callback, enablePostCall)
    }

    override fun <T : RegisterContext> wrap(symbol: Symbol, callback: WrapCallback<T>) {
        wrap(symbol.getAddress(), callback)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : RegisterContext> wrap(functionAddress: Long, callback: WrapCallback<T>) {
        val svcMemory = emulator.getSvcMemory()
        val context = Stack<Any?>()
        val preCall = svcMemory.registerSvc(if (emulator.is32Bit()) object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                callback.preCall(emulator, HookZzArm32RegisterContextImpl(emulator, context) as T, ArmHookEntryInfo(emulator))
                return 0
            }
        } else object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                callback.preCall(emulator, HookZzArm64RegisterContextImpl(emulator, context) as T, Arm64HookEntryInfo(emulator))
                return 0
            }
        })
        val postCall = svcMemory.registerSvc(if (emulator.is32Bit()) object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                callback.postCall(emulator, HookZzArm32RegisterContextImpl(emulator, context) as T, ArmHookEntryInfo(emulator))
                return 0
            }
        } else object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                callback.postCall(emulator, HookZzArm64RegisterContextImpl(emulator, context) as T, Arm64HookEntryInfo(emulator))
                return 0
            }
        })
        val ret = zzWrap!!.call(emulator, VortexdbgPointer.pointer(emulator, functionAddress), preCall, postCall).toInt()
        if (ret != RS_SUCCESS) {
            throw IllegalStateException("ret=$ret")
        }
    }

    override fun <T : RegisterContext> instrument(symbol: Symbol, callback: InstrumentCallback<T>) {
        instrument(symbol.getAddress(), callback)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : RegisterContext> instrument(functionAddress: Long, callback: InstrumentCallback<T>) {
        val svcMemory = emulator.getSvcMemory()
        val dbiCall = svcMemory.registerSvc(if (emulator.is32Bit()) object : ArmSvc() {
            private val context = Stack<Any?>()
            override fun handle(emulator: Emulator<*>): Long {
                callback.dbiCall(emulator, HookZzArm32RegisterContextImpl(emulator, context) as T, ArmHookEntryInfo(emulator))
                return 0
            }
        } else object : Arm64Svc() {
            private val context = Stack<Any?>()
            override fun handle(emulator: Emulator<*>): Long {
                callback.dbiCall(emulator, HookZzArm64RegisterContextImpl(emulator, context) as T, Arm64HookEntryInfo(emulator))
                return 0
            }
        })
        val ret = zzDynamicBinaryInstrumentation!!.call(emulator, VortexdbgPointer.pointer(emulator, functionAddress), dbiCall).toInt()
        if (ret != RS_SUCCESS) {
            throw IllegalStateException("ret=$ret")
        }
    }

    override fun switch_to_file_log(path: String) {
        throw UnsupportedOperationException()
    }

    companion object {

        private val log: Logger = LoggerFactory.getLogger(HookZz::class.java)

        @JvmStatic
        fun getInstance(emulator: Emulator<*>): HookZz {
            var hookZz = emulator.get<HookZz?>(HookZz::class.java.name)
            if (hookZz == null) {
                hookZz = HookZz(emulator)
                emulator.set(HookZz::class.java.name, hookZz)
            }
            return hookZz
        }

        private const val RS_SUCCESS = 1
    }
}
