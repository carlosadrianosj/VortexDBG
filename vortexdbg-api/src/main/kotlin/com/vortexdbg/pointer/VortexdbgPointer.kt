package com.vortexdbg.pointer

import com.vortexdbg.Emulator
import com.vortexdbg.InvalidMemoryAccessException
import com.vortexdbg.Module
import com.vortexdbg.PointerArg
import com.vortexdbg.Utils
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.memory.Memory
import com.vortexdbg.memory.MemoryMap
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.WString
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import unicorn.UnicornConst

import java.io.ByteArrayOutputStream
import java.io.UnsupportedEncodingException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays

open class VortexdbgPointer private constructor(
    private val emulator: Emulator<*>?,
    private val backend: Backend?,
    @JvmField val peer: Long,
    private val pointerSize: Int,
    private val listener: MemoryWriteListener?
) : Pointer(0), PointerArg {

    fun toUIntPeer(): Long {
        return peer and 0xffffffffL
    }

    fun toIntPeer(): Int {
        return toUIntPeer().toInt()
    }

    constructor(emulator: Emulator<*>?, data: ByteArray?) : this(
        emulator,
        if (data == null) null else ByteArrayBackend(data),
        0L,
        0,
        null
    )

    private constructor(emulator: Emulator<*>, peer: Long, pointerSize: Int) : this(
        emulator,
        emulator.getBackend(),
        peer,
        pointerSize,
        if (emulator is MemoryWriteListener) emulator else null
    )

    private var sizeValue: Long = 0

    fun setSize(size: Long): VortexdbgPointer {
        if (size < 0) {
            throw IllegalArgumentException("size=$size")
        }
        this.sizeValue = size
        return this
    }

    fun getSize(): Long {
        return sizeValue
    }

    override fun indexOf(offset: Long, value: Byte): Long {
        throw AbstractMethodError()
    }

    override fun read(offset: Long, buf: ByteArray, index: Int, length: Int) {
        val data = getByteArray(offset, length)
        System.arraycopy(data, 0, buf, index, length)
    }

    override fun read(offset: Long, buf: ShortArray, index: Int, length: Int) {
        throw AbstractMethodError()
    }

    override fun read(offset: Long, buf: CharArray, index: Int, length: Int) {
        throw AbstractMethodError()
    }

    override fun read(offset: Long, buf: IntArray, index: Int, length: Int) {
        for (i in index until length) {
            buf[i] = getInt((i - index) * 4L + offset)
        }
    }

    override fun read(offset: Long, buf: LongArray, index: Int, length: Int) {
        for (i in index until length) {
            buf[i] = getLong((i - index) * 8L + offset)
        }
    }

    override fun read(offset: Long, buf: FloatArray, index: Int, length: Int) {
        for (i in index until length) {
            buf[i] = getFloat((i - index) * 4L + offset)
        }
    }

    override fun read(offset: Long, buf: DoubleArray, index: Int, length: Int) {
        for (i in index until length) {
            buf[i] = getDouble((i - index) * 8L + offset)
        }
    }

    override fun read(offset: Long, buf: Array<Pointer>, index: Int, length: Int) {
        throw AbstractMethodError()
    }

    fun write(buf: ByteArray) {
        write(0, buf, 0, buf.size)
    }

    override fun write(offset: Long, buf: ByteArray, index: Int, length: Int) {
        if (sizeValue > 0) {
            if (offset < 0) {
                throw IllegalArgumentException()
            }

            if (sizeValue - offset < length) {
                throw InvalidMemoryAccessException()
            }
        }

        val data: ByteArray
        if (index == 0 && buf.size == length) {
            data = buf
        } else {
            data = ByteArray(length)
            System.arraycopy(buf, index, data, 0, length)
        }
        val address = peer + offset
        backend!!.mem_write(address, data)
        if (listener != null) {
            listener.onSystemWrite(address, data)
        }
    }

    override fun write(offset: Long, buf: ShortArray, index: Int, length: Int) {
        for (i in index until length) {
            setShort((i - index) * 2L + offset, buf[i])
        }
    }

    override fun write(offset: Long, buf: CharArray, index: Int, length: Int) {
        throw AbstractMethodError()
    }

    override fun write(offset: Long, buf: IntArray, index: Int, length: Int) {
        for (i in index until length) {
            setInt((i - index) * 4L + offset, buf[i])
        }
    }

    override fun write(offset: Long, buf: LongArray, index: Int, length: Int) {
        for (i in index until length) {
            setLong((i - index) * 8L + offset, buf[i])
        }
    }

    override fun write(offset: Long, buf: FloatArray, index: Int, length: Int) {
        for (i in index until length) {
            setFloat((i - index) * 4L + offset, buf[i])
        }
    }

    override fun write(offset: Long, buf: DoubleArray, index: Int, length: Int) {
        for (i in index until length) {
            setDouble((i - index) * 8L + offset, buf[i])
        }
    }

    override fun write(offset: Long, buf: Array<Pointer>, index: Int, length: Int) {
        throw AbstractMethodError()
    }

    override fun getByte(offset: Long): Byte {
        return getByteArray(offset, 1)[0]
    }

    override fun getChar(offset: Long): Char {
        return getByteBuffer(offset, 2).getChar()
    }

    override fun getShort(offset: Long): Short {
        return getByteBuffer(offset, 2).getShort()
    }

    override fun getInt(offset: Long): Int {
        return getByteBuffer(offset, 4).getInt()
    }

    override fun getLong(offset: Long): Long {
        return getByteBuffer(offset, 8).getLong()
    }

    override fun getNativeLong(offset: Long): NativeLong {
        throw AbstractMethodError()
    }

    override fun getFloat(offset: Long): Float {
        return getByteBuffer(offset, 4).getFloat()
    }

    override fun getDouble(offset: Long): Double {
        return getByteBuffer(offset, 8).getDouble()
    }

    override fun getPointer(offset: Long): VortexdbgPointer {
        return pointer(emulator!!, if (pointerSize == 4) getInt(offset) as Number else getLong(offset) as Number)
    }

    override fun getByteArray(offset: Long, arraySize: Int): ByteArray {
        if (sizeValue > 0 && offset + arraySize > sizeValue) {
            throw InvalidMemoryAccessException()
        }

        if (arraySize < 0 || arraySize >= 0x7ffffff) {
            throw InvalidMemoryAccessException("Invalid array size: $arraySize")
        }
        return backend!!.mem_read(peer + offset, arraySize.toLong())
    }

    override fun getIntArray(offset: Long, arraySize: Int): IntArray {
        if (arraySize < 0 || arraySize >= 0x7ffffff) {
            throw InvalidMemoryAccessException("Invalid array size: $arraySize")
        }

        val array = IntArray(arraySize)
        for (i in 0 until arraySize) {
            array[i] = getInt(offset + i * 4)
        }
        return array
    }

    override fun getByteBuffer(offset: Long, length: Long): ByteBuffer {
        return ByteBuffer.wrap(getByteArray(offset, length.toInt())).order(ByteOrder.LITTLE_ENDIAN)
    }

    override fun getWideString(offset: Long): String {
        throw AbstractMethodError()
    }

    override fun getString(offset: Long): String {
        return getString(offset, "UTF-8")
    }

    override fun getString(offset: Long, encoding: String): String {
        var addr = peer + offset

        val baos = ByteArrayOutputStream(0x40)
        while (true) {
            val data = backend!!.mem_read(addr, 0x10)
            val length = Utils.indexOfNullTerminator(data)
            baos.write(data, 0, length)
            addr += length

            if (length < data.size) { // reach zero
                break
            }

            if (baos.size() > 0x40000) { // 256k
                throw IllegalStateException("buffer overflow")
            }

            if (sizeValue > 0 && offset + baos.size() > sizeValue) {
                throw InvalidMemoryAccessException()
            }
        }

        try {
            val ret = baos.toString(encoding)
            log.debug("getString pointer={}, size={}, encoding={}, ret={}", this, baos.size(), encoding, ret)
            return ret
        } catch (e: UnsupportedEncodingException) {
            throw IllegalStateException(e)
        }
    }

    private fun allocateBuffer(size: Int): ByteBuffer {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    }

    override fun setMemory(offset: Long, length: Long, value: Byte) {
        val data = ByteArray(length.toInt())
        Arrays.fill(data, value)
        write(offset, data, 0, data.size)
    }

    override fun setByte(offset: Long, value: Byte) {
        write(offset, byteArrayOf(value), 0, 1)
    }

    override fun setShort(offset: Long, value: Short) {
        write(offset, allocateBuffer(2).putShort(value).array(), 0, 2)
    }

    override fun setChar(offset: Long, value: Char) {
        write(offset, allocateBuffer(2).putChar(value).array(), 0, 2)
    }

    override fun setInt(offset: Long, value: Int) {
        write(offset, allocateBuffer(4).putInt(value).array(), 0, 4)
    }

    override fun setLong(offset: Long, value: Long) {
        write(offset, allocateBuffer(8).putLong(value).array(), 0, 8)
    }

    override fun setNativeLong(offset: Long, value: NativeLong) {
        throw AbstractMethodError()
    }

    override fun setFloat(offset: Long, value: Float) {
        write(offset, allocateBuffer(4).putFloat(value).array(), 0, 4)
    }

    override fun setDouble(offset: Long, value: Double) {
        write(offset, allocateBuffer(8).putDouble(value).array(), 0, 8)
    }

    override fun setPointer(offset: Long, pointer: Pointer?) {
        val value: Long
        if (pointer == null) {
            value = 0
        } else {
            value = (pointer as VortexdbgPointer).peer
        }

        if (pointerSize == 4) {
            setInt(offset, value.toInt())
        } else {
            setLong(offset, value)
        }
    }

    override fun setWideString(offset: Long, value: String) {
        throw AbstractMethodError()
    }

    override fun setString(offset: Long, value: WString) {
        throw AbstractMethodError()
    }

    override fun setString(offset: Long, value: String) {
        setString(offset, value, "UTF-8")
    }

    override fun setString(offset: Long, value: String, encoding: String) {
        try {
            val data = (value as java.lang.String).getBytes(encoding)
            write(offset, Arrays.copyOf(data, data.size + 1), 0, data.size + 1)
        } catch (e: UnsupportedEncodingException) {
            throw IllegalArgumentException(e)
        }
    }

    override fun share(offset: Long, sz: Long): VortexdbgPointer {
        if (offset == 0L && sz == sizeValue) {
            return this
        }

        val pointer = VortexdbgPointer(emulator!!, peer + offset, pointerSize)
        if (sizeValue > 0) {
            if (offset > sizeValue) {
                throw InvalidMemoryAccessException("offset=" + offset + ", size=" + sizeValue + ", peer=0x" + java.lang.Long.toHexString(peer))
            }

            val newSize = sizeValue - offset
            pointer.setSize(if (sz > 0 && sz < newSize) sz else newSize)
        } else {
            pointer.setSize(sz)
        }
        return pointer
    }

    override fun toString(): String {
        val memory: Memory? = if (emulator == null) null else emulator.getMemory()
        val module: Module? = if (memory == null) null else memory.findModuleByAddress(peer)
        var memoryMap: MemoryMap? = null
        if (memory != null) {
            for (mm in memory.getMemoryMap()) {
                if (peer >= mm.base && peer < mm.base + mm.size) {
                    memoryMap = mm
                    break
                }
            }
        }
        val sb = StringBuilder()
        if (memoryMap == null) {
            sb.append("vortexdbg")
        } else {
            var none = true
            if ((memoryMap.prot and UnicornConst.UC_PROT_READ) != 0) {
                sb.append('R')
                none = false
            }
            if ((memoryMap.prot and UnicornConst.UC_PROT_WRITE) != 0) {
                sb.append('W')
                none = false
            }
            if ((memoryMap.prot and UnicornConst.UC_PROT_EXEC) != 0) {
                sb.append('X')
                none = false
            }
            if (none) {
                sb.append('N')
            }
        }
        sb.append("@0x")
        sb.append(java.lang.Long.toHexString(peer))
        if (module != null) {
            sb.append("[").append(module.name).append("]0x").append(java.lang.Long.toHexString(peer - module.base))
        }
        return sb.toString()
    }

    override fun equals(o: Any?): Boolean {
        if (o === this) {
            return true
        }
        if (o == null) {
            return false
        }
        return (o is VortexdbgPointer) && (o.peer == peer)
    }

    override fun hashCode(): Int {
        return ((peer ushr 32) + (peer and 0xffffffffL)).toInt()
    }

    override fun getPointer(): Pointer {
        return this
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(VortexdbgPointer::class.java)

        @JvmStatic
        fun nativeValueOf(ptr: Pointer?): Long {
            if (ptr == null) {
                return 0L
            }
            val up = ptr as VortexdbgPointer
            return if (up.emulator!!.is64Bit()) up.peer else up.toUIntPeer()
        }

        @JvmStatic
        fun pointer(emulator: Emulator<*>, addr: Long): VortexdbgPointer {
            val peer = if (emulator.is64Bit()) addr else addr and 0xffffffffL
            return VortexdbgPointer(emulator, peer, emulator.getPointerSize())
        }

        @JvmStatic
        fun pointer(emulator: Emulator<*>, number: Number): VortexdbgPointer {
            return pointer(emulator, number.toLong())
        }

        @JvmStatic
        fun register(emulator: Emulator<*>, reg: Int): VortexdbgPointer {
            return pointer(emulator, emulator.getBackend().reg_read(reg))
        }
    }
}
