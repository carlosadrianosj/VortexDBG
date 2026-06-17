package com.vortexdbg.virtualmodule.android

import com.vortexdbg.Emulator
import com.vortexdbg.arm.Arm64Svc
import com.vortexdbg.arm.ArmSvc
import com.vortexdbg.arm.context.RegisterContext
import com.vortexdbg.linux.android.dvm.VM
import com.vortexdbg.memory.MemoryBlock
import com.vortexdbg.memory.SvcMemory
import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.virtualmodule.VirtualModule
import com.sun.jna.Pointer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.Random

@Suppress("unused")
class MediaNdkModule(emulator: Emulator<*>, vm: VM) : VirtualModule<VM>(emulator, vm, "libmediandk.so") {

    override fun onInitialize(emulator: Emulator<*>, extra: VM?, symbols: MutableMap<String, VortexdbgPointer>) {
        val is64Bit = emulator.is64Bit()
        val svcMemory = emulator.getSvcMemory()
        symbols.put("AMediaDrm_createByUUID", svcMemory.registerSvc(if (is64Bit) object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                return createByUUID(emulator)
            }
        } else object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                return createByUUID(emulator)
            }
        }))

        symbols.put("AMediaDrm_getPropertyByteArray", svcMemory.registerSvc(if (is64Bit) object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                return getPropertyByteArray(emulator)
            }
        } else object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                return getPropertyByteArray(emulator)
            }
        }))

        symbols.put("AMediaDrm_getPropertyString", svcMemory.registerSvc(if (is64Bit) object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                return getPropertyString(emulator)
            }
        } else object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                return getPropertyString(emulator)
            }
        }))

        symbols.put("AMediaDrm_release", svcMemory.registerSvc(if (is64Bit) object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                return release()
            }
        } else object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                return release()
            }
        }))
    }

    private fun createByUUID(emulator: Emulator<*>): Long {
        if (log.isDebugEnabled) {
            log.debug("call createByUUID")
        }
        val context = emulator.getContext<RegisterContext>()
        val uuidPtr = context.getPointerArg(0)
        val uuid = uuidPtr!!.getByteArray(0L, 0x10)
        if (uuid.contentEquals(WIDE_VINE_UUID)) {
            return emulator.getMemory().malloc(0x8, true).getPointer().peer
        }
        throw UnsupportedOperationException("createByUUID")
    }


    private fun getPropertyByteArray(emulator: Emulator<*>): Long {
        val context = emulator.getContext<RegisterContext>()
        val propertyNamePtr = context.getPointerArg(1)
        val propertyValuePtr = context.getPointerArg(2)
        val propertyName = propertyNamePtr!!.getString(0L)
        if (propertyName == "deviceUniqueId") {
            val memoryBlock = emulator.getMemory().malloc(0x20, true)
            val b = ByteArray(0x20)
            Random().nextBytes(b)
            memoryBlock.getPointer().write(0L, b, 0, 0x20)
            propertyValuePtr!!.setPointer(0L, memoryBlock.getPointer())
            propertyValuePtr.setLong(emulator.getPointerSize().toLong(), 0x20L)
            return 0
        }
        throw UnsupportedOperationException("getPropertyByteArray")
    }

    private var vendorPropertyBlock: MemoryBlock? = null

    private fun getPropertyString(emulator: Emulator<*>): Long {
        val context = emulator.getContext<RegisterContext>()
        val propertyNamePtr = context.getPointerArg(1)
        val propertyValuePtr = context.getPointerArg(2)
        val propertyName = propertyNamePtr!!.getString(0L)
        if ("vendor" == propertyName) {
            val value = "Google"
            if (vendorPropertyBlock == null) {
                vendorPropertyBlock = emulator.getMemory().malloc(value.length, true)
            }
            vendorPropertyBlock!!.getPointer().setString(0L, value)

            propertyValuePtr!!.setPointer(0L, vendorPropertyBlock!!.getPointer())
            if (emulator.is32Bit()) {
                propertyValuePtr.setInt(4L, value.length)
            } else {
                propertyValuePtr.setLong(8L, value.length.toLong())
            }
            return 0
        }
        throw UnsupportedOperationException("getPropertyString: $propertyName")
    }

    private fun release(): Long {
        if (vendorPropertyBlock != null) {
            vendorPropertyBlock!!.free()
            vendorPropertyBlock = null
        }
        return 0
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(MediaNdkModule::class.java)

        @JvmField
        val WIDE_VINE_UUID = byteArrayOf(
            0xed.toByte(), 0xef.toByte(), 0x8b.toByte(), 0xa9.toByte(), 0x79.toByte(), 0xd6.toByte(), 0x4a.toByte(),
            0xce.toByte(), 0xa3.toByte(), 0xc8.toByte(), 0x27.toByte(), 0xdc.toByte(), 0xd5.toByte(), 0x1d.toByte(), 0x21.toByte(), 0xed.toByte()
        )
    }

}
