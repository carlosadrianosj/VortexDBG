package com.vortexdbg.memory

import com.vortexdbg.serialize.Serializable

import java.io.DataOutput
import java.io.IOException

class MemoryMap(
    @JvmField val base: Long,
    @JvmField val size: Long,
    @JvmField var prot: Int
) : Serializable {

    @Throws(IOException::class)
    override fun serialize(out: DataOutput) {
        out.writeLong(base)
        out.writeLong(size)
        out.writeInt(prot)
    }

    override fun toString(): String {
        return "MemoryMap{" +
                "base=0x" + java.lang.Long.toHexString(base) +
                ", size=0x" + java.lang.Long.toHexString(size) +
                ", prot=" + prot +
                '}'
    }
}
