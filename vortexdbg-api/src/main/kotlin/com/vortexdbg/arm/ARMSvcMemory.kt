package com.vortexdbg.arm

import com.vortexdbg.Emulator
import com.vortexdbg.Svc
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.memory.MemRegion
import com.vortexdbg.memory.SvcMemory
import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.spi.SyscallHandler
import org.apache.commons.codec.binary.Hex
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import unicorn.UnicornConst
import java.io.DataOutput
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Arrays
import java.util.HashMap

class ARMSvcMemory(base: Long, size: Int, private val emulator: Emulator<*>) : SvcMemory {

    private var basePointer: VortexdbgPointer

    private val baseAddr: Long
    private val sizeValue: Int

    init {
        this.basePointer = VortexdbgPointer.pointer(emulator, base)
        assert(this.basePointer != null)
        this.basePointer.setSize(size.toLong())

        this.baseAddr = base
        this.sizeValue = size

        val backend: Backend = emulator.getBackend()
        backend.mem_map(base, size.toLong(), UnicornConst.UC_PROT_READ or UnicornConst.UC_PROT_EXEC)
    }

    override fun serialize(out: DataOutput) {
        throw UnsupportedOperationException()
    }

    override fun getBase(): Long {
        return baseAddr
    }

    override fun getSize(): Int {
        return sizeValue
    }

    private val memRegions: MutableList<MemRegion> = ArrayList()

    override fun findRegion(addr: Long): MemRegion? {
        if (addr >= baseAddr && addr < baseAddr + sizeValue) {
            for (region in memRegions) {
                if (addr >= region.begin && addr < region.end) {
                    return region
                }
            }
        }
        return null
    }

    override fun allocate(size: Int, label: String): VortexdbgPointer {
        val alignedSize = ARM.alignSize(size)
        val pointer = basePointer.share(0L, alignedSize.toLong())
        basePointer = basePointer.share(alignedSize.toLong()) as VortexdbgPointer
        if (log.isDebugEnabled) {
            log.debug("allocate size={}, label={}, base={}", alignedSize, label, basePointer)
        }
        memRegions.add(object : MemRegion(pointer.peer, pointer.peer, pointer.peer + alignedSize, UnicornConst.UC_PROT_READ or UnicornConst.UC_PROT_EXEC, null, 0) {
            override fun getName(): String {
                return label
            }
        })
        return pointer
    }

    private val symbolMap: MutableMap<String, VortexdbgPointer> = HashMap()

    override fun allocateSymbolName(name: String): VortexdbgPointer {
        var ptr = symbolMap[name]
        if (ptr == null) {
            val nameBytes = name.toByteArray(java.nio.charset.Charset.defaultCharset())
            val size = nameBytes.size + 1
            ptr = allocate(size, "Symbol.$name")
            ptr.write(0L, Arrays.copyOf(nameBytes, size), 0, size)
            symbolMap[name] = ptr
        }
        return ptr
    }

    private var thumbSvcNumber = 0
    private var armSvcNumber = 0xff

    private val svcMap: MutableMap<Int, Svc> = HashMap()

    override fun getSvc(svcNumber: Int): Svc? {
        return svcMap[svcNumber]
    }

    override fun registerSvc(svc: Svc): VortexdbgPointer {
        val number: Int
        if (svc is ThumbSvc) {
            if (emulator.is64Bit()) {
                throw IllegalStateException("is 64 bit mode")
            }

            if (++thumbSvcNumber == SyscallHandler.DARWIN_SWI_SYSCALL) {
                thumbSvcNumber++
            }
            number = thumbSvcNumber
        } else if (svc is ArmSvc || svc is Arm64Svc) {
            if (svc is ArmSvc && emulator.is64Bit()) {
                throw IllegalStateException("is 64 bit mode")
            }
            if (svc is Arm64Svc && !emulator.is64Bit()) {
                throw IllegalStateException("is 32 bit mode")
            }

            if (++armSvcNumber == SyscallHandler.DARWIN_SWI_SYSCALL) {
                armSvcNumber++
            }
            number = armSvcNumber
        } else {
            throw IllegalStateException("svc=$svc")
        }
        if (svcMap.put(number, svc) != null) {
            throw IllegalStateException()
        }
        return svc.onRegister(this, number)
    }

    override fun writeStackString(str: String): VortexdbgPointer {
        val data = str.toByteArray(StandardCharsets.UTF_8)
        return writeStackBytes(Arrays.copyOf(data, data.size + 1))
    }

    override fun writeStackBytes(data: ByteArray): VortexdbgPointer {
        val pointer = allocate(data.size, "writeStackBytes: " + Hex.encodeHexString(data))
        assert(pointer != null)
        pointer.write(0L, data, 0, data.size)
        return pointer
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(ARMSvcMemory::class.java)
    }

}
