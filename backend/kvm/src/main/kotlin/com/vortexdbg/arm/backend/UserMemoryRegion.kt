package com.vortexdbg.arm.backend

class UserMemoryRegion internal constructor(
    @JvmField internal val slot: Int,
    //    int flags;
    @JvmField internal val guest_phys_addr: Long,
    @JvmField internal val memory_size: Long, /* bytes */
    @JvmField internal val userspace_addr: Long /* start of the userspace allocated memory */
) {

    override fun toString(): String {
        return "UserMemoryRegion{" +
                "slot=" + slot +
                ", guest_phys_addr=0x" + java.lang.Long.toHexString(guest_phys_addr) +
                ", memory_size=0x" + java.lang.Long.toHexString(memory_size) +
                ", userspace_addr=0x" + java.lang.Long.toHexString(userspace_addr) +
                '}'
    }

}
