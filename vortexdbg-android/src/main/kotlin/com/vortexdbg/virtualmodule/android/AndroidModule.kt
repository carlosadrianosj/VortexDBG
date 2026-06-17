package com.vortexdbg.virtualmodule.android

import com.vortexdbg.Emulator
import com.vortexdbg.arm.Arm64Svc
import com.vortexdbg.arm.ArmSvc
import com.vortexdbg.arm.backend.BackendException
import com.vortexdbg.arm.context.RegisterContext
import com.vortexdbg.linux.android.dvm.DvmObject
import com.vortexdbg.linux.android.dvm.VM
import com.vortexdbg.linux.android.dvm.api.Asset
import com.vortexdbg.memory.SvcMemory
import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.virtualmodule.VirtualModule
import com.sun.jna.Pointer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.HashMap

class AndroidModule(emulator: Emulator<*>, vm: VM) : VirtualModule<VM>(emulator, vm, "libandroid.so") {

    override fun onInitialize(emulator: Emulator<*>, extra: VM?, symbols: MutableMap<String, VortexdbgPointer>) {
        val vm = extra
        val is64Bit = emulator.is64Bit()
        val svcMemory = emulator.getSvcMemory()
        symbols.put("AAssetManager_fromJava", svcMemory.registerSvc(if (is64Bit) object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                return fromJava(emulator, vm!!)
            }
        } else object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                return fromJava(emulator, vm!!)
            }
        }))
        symbols.put("AAssetManager_open", svcMemory.registerSvc(if (is64Bit) object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                return open(emulator, vm!!, assetMap)
            }
        } else object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                return open(emulator, vm!!, assetMap)
            }
        }))
        symbols.put("AAsset_close", svcMemory.registerSvc(if (is64Bit) object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                return close(emulator, vm!!)
            }
        } else object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                return close(emulator, vm!!)
            }
        }))
        symbols.put("AAsset_getBuffer", svcMemory.registerSvc(if (is64Bit) object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                return getBuffer(emulator, vm!!)
            }
        } else object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                return getBuffer(emulator, vm!!)
            }
        }))
        symbols.put("AAsset_getLength", svcMemory.registerSvc(if (is64Bit) object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                return getLength(emulator, vm!!)
            }
        } else object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                return getLength(emulator, vm!!)
            }
        }))
        symbols.put("AAsset_read", svcMemory.registerSvc(if (is64Bit) object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                return read(emulator, vm!!)
            }
        } else object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                return read(emulator, vm!!)
            }
        }))
    }

    private val assetMap: MutableMap<String, ByteArray> = HashMap(1)

    fun addAsset(name: String, bytes: ByteArray) {
        assetMap.put(name, bytes)
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(AndroidModule::class.java)

        private fun fromJava(emulator: Emulator<*>, vm: VM): Long {
            val context = emulator.getContext<RegisterContext>()
            val env = context.getPointerArg(0)
            val assetManager = context.getPointerArg(1)
            val obj = vm.getObject<DvmObject<*>>(assetManager!!.toIntPeer())
            if (log.isDebugEnabled) {
                log.debug("AAssetManager_fromJava env={}, assetManager={}, LR={}", env, obj.getObjectType(), context.getLRPointer())
            }
            return assetManager.peer
        }

        private fun open(emulator: Emulator<*>, vm: VM, assetMap: Map<String, ByteArray>): Long {
            val context = emulator.getContext<RegisterContext>()
            val amgr = context.getPointerArg(0)
            val filename = context.getPointerArg(1)!!.getString(0L)
            val mode = context.getIntArg(2)
            if (log.isDebugEnabled) {
                log.debug("AAssetManager_open amgr={}, filename={}, mode={}, LR={}", amgr, filename, mode, context.getLRPointer())
            }
            val AASSET_MODE_UNKNOWN = 0
            val AASSET_MODE_RANDOM = 1
            val AASSET_MODE_STREAMING = 2
            val AASSET_MODE_BUFFER = 3
            if (mode == AASSET_MODE_STREAMING || AASSET_MODE_BUFFER == mode ||
                    mode == AASSET_MODE_UNKNOWN || mode == AASSET_MODE_RANDOM) {
                val data = if (assetMap.containsKey(filename)) assetMap.get(filename) else vm.openAsset(filename)
                if (data == null) {
                    return 0L
                }
                val asset = Asset(vm, filename)
                asset.open(emulator, data)
                return vm.addLocalObject(asset).toLong()
            }
            throw BackendException("filename=" + filename + ", mode=" + mode + ", LR=" + context.getLRPointer())
        }

        private fun close(emulator: Emulator<*>, vm: VM): Long {
            val context = emulator.getContext<RegisterContext>()
            val pointer = context.getPointerArg(0)
            val asset = vm.getObject<Asset>(pointer!!.toIntPeer())
            asset.close()
            if (log.isDebugEnabled) {
                log.debug("AAsset_close pointer={}, LR={}", pointer, context.getLRPointer())
            }
            return 0
        }

        private fun getBuffer(emulator: Emulator<*>, vm: VM): Long {
            val context = emulator.getContext<RegisterContext>()
            val pointer = context.getPointerArg(0)
            val asset = vm.getObject<Asset>(pointer!!.toIntPeer())
            val buffer = asset.getBuffer()
            if (log.isDebugEnabled) {
                log.debug("AAsset_getBuffer pointer={}, buffer={}, LR={}", pointer, buffer, context.getLRPointer())
            }
            return buffer.peer
        }

        private fun getLength(emulator: Emulator<*>, vm: VM): Long {
            val context = emulator.getContext<RegisterContext>()
            val pointer = context.getPointerArg(0)
            val asset = vm.getObject<Asset>(pointer!!.toIntPeer())
            val length = asset.getLength()
            if (log.isDebugEnabled) {
                log.debug("AAsset_getLength pointer={}, length={}, LR={}", pointer, length, context.getLRPointer())
            }
            return length.toLong()
        }

        private fun read(emulator: Emulator<*>, vm: VM): Long {
            val context = emulator.getContext<RegisterContext>()
            val pointer = context.getPointerArg(0)
            val buf = context.getPointerArg(1)
            val count = context.getIntArg(2)
            val asset = vm.getObject<Asset>(pointer!!.toIntPeer())
            val bytes = asset.read(count)
            if (log.isDebugEnabled) {
                log.debug("AAsset_read pointer={}, buf={}, count={}, LR={}", pointer, buf, count, context.getLRPointer())
            }
            buf!!.write(0L, bytes, 0, bytes.size)
            return bytes.size.toLong()
        }
    }

}
