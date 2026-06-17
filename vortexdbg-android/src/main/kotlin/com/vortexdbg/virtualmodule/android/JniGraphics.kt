package com.vortexdbg.virtualmodule.android

import com.vortexdbg.Emulator
import com.vortexdbg.arm.Arm64Svc
import com.vortexdbg.arm.ArmSvc
import com.vortexdbg.arm.context.RegisterContext
import com.vortexdbg.linux.android.dvm.VM
import com.vortexdbg.linux.android.dvm.api.Bitmap
import com.vortexdbg.memory.SvcMemory
import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.utils.Inspector
import com.vortexdbg.virtualmodule.VirtualModule
import com.sun.jna.Pointer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder

class JniGraphics(emulator: Emulator<*>, vm: VM) : VirtualModule<VM>(emulator, vm, "libjnigraphics.so") {

    override fun onInitialize(emulator: Emulator<*>, extra: VM?, symbols: MutableMap<String, VortexdbgPointer>) {
        val vm = extra
        val is64Bit = emulator.is64Bit()
        val svcMemory = emulator.getSvcMemory()
        symbols.put("AndroidBitmap_getInfo", svcMemory.registerSvc(if (is64Bit) object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                return getInfo(emulator, vm!!)
            }
        } else object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                return getInfo(emulator, vm!!)
            }
        }))
        symbols.put("AndroidBitmap_lockPixels", svcMemory.registerSvc(if (is64Bit) object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                return lockPixels(emulator, vm!!)
            }
        } else object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                return lockPixels(emulator, vm!!)
            }
        }))
        symbols.put("AndroidBitmap_unlockPixels", svcMemory.registerSvc(if (is64Bit) object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                return unlockPixels(emulator, vm!!)
            }
        } else object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                return unlockPixels(emulator, vm!!)
            }
        }))
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(JniGraphics::class.java)

        private const val ANDROID_BITMAP_FORMAT_RGBA_8888 = 1
        private const val ANDROID_BITMAP_RESULT_SUCCESS = 0

        private fun getInfo(emulator: Emulator<*>, vm: VM): Long {
            val context = emulator.getContext<RegisterContext>()
            val env = context.getPointerArg(0)
            val jbitmap = context.getPointerArg(1)
            val info = context.getPointerArg(2)
            val bitmap = vm.getObject<Bitmap>(jbitmap!!.toIntPeer())
            val image = bitmap.getValue()
            if (log.isDebugEnabled) {
                log.debug("AndroidBitmap_getInfo env={}, width={}, height={}, stride={}, info={}", env, image.getWidth(), image.getHeight(), image.getWidth() * 4, info)
            }
            info!!.setInt(0L, image.getWidth())
            info.setInt(4L, image.getHeight())
            info.setInt(8L, image.getWidth() * 4) // stride
            info.setInt(12L, ANDROID_BITMAP_FORMAT_RGBA_8888)
            info.setInt(16L, 0) // flags
            return ANDROID_BITMAP_RESULT_SUCCESS.toLong()
        }

        private fun lockPixels(emulator: Emulator<*>, vm: VM): Long {
            val context = emulator.getContext<RegisterContext>()
            val env = context.getPointerArg(0)
            val jbitmap = context.getPointerArg(1)
            val addrPtr = context.getPointerArg(2)
            val bitmap = vm.getObject<Bitmap>(jbitmap!!.toIntPeer())
            val image = bitmap.getValue()
            if (image.getType() != BufferedImage.TYPE_4BYTE_ABGR) {
                throw IllegalStateException("image type=" + image.getType())
            }

            if (addrPtr != null) {
                val buffer = ByteBuffer.allocate(image.getWidth() * image.getHeight() * 4)
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                for (y in 0 until image.getHeight()) {
                    for (x in 0 until image.getWidth()) {
                        val rgb = image.getRGB(x, y)
                        buffer.putInt((((rgb shr 24) and 0xff) shl 24) or ((rgb and 0xff) shl 16) or (((rgb shr 8) and 0xff) shl 8) or ((rgb shr 16) and 0xff)) // convert TYPE_4BYTE_ABGR to ARGB_8888
                    }
                }

                val pointer = bitmap.lockPixels(emulator, image, buffer)
                addrPtr.setPointer(0L, pointer)

                if (log.isDebugEnabled) {
                    log.debug(Inspector.inspectString(buffer.array(), "AndroidBitmap_lockPixels buffer=$buffer"))
                }
            }

            if (log.isDebugEnabled) {
                log.debug("AndroidBitmap_lockPixels env={}, bitmap={}, addrPtr={}", env, bitmap, addrPtr)
            }
            return ANDROID_BITMAP_RESULT_SUCCESS.toLong()
        }

        private fun unlockPixels(emulator: Emulator<*>, vm: VM): Long {
            val context = emulator.getContext<RegisterContext>()
            val env = context.getPointerArg(0)
            val jbitmap = context.getPointerArg(1)
            val bitmap = vm.getObject<Bitmap>(jbitmap!!.toIntPeer())
            bitmap.unlockPixels()
            if (log.isDebugEnabled) {
                log.debug("AndroidBitmap_unlockPixels env={}, bitmap={}", env, bitmap)
            }
            return ANDROID_BITMAP_RESULT_SUCCESS.toLong()
        }
    }

}
