package net.fornwall.jelf

interface ElfDataIn {

    @Throws(ElfException::class)
    fun readUnsignedByte(): Short

    @Throws(ElfException::class)
    fun readShort(): Short

    @Throws(ElfException::class)
    fun readInt(): Int

    @Throws(ElfException::class)
    fun readLong(): Long

}
