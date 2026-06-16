package com.vortexdbg.memory

class AllocationRecord internal constructor(
    @JvmField val address: Long,
    @JvmField val size: Long,
    @JvmField val perms: Int,
    @JvmField val guestBacktrace: Array<String>,
    @JvmField val hostStackTrace: Array<StackTraceElement>
) {

    @JvmField
    val timestamp: Long = System.nanoTime()

    override fun toString(): String {
        return "AllocationRecord{address=0x" + java.lang.Long.toHexString(address) +
                ", size=0x" + java.lang.Long.toHexString(size) +
                ", perms=" + perms + '}'
    }

}
