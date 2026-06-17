package com.vortexdbg.arm.backend

import com.alibaba.fastjson.util.IOUtils
import com.vortexdbg.Emulator
import com.vortexdbg.arm.ARMEmulator
import com.vortexdbg.arm.backend.kvm.Kvm
import com.vortexdbg.arm.backend.kvm.KvmCallback
import com.vortexdbg.arm.backend.kvm.KvmException
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.TreeMap

abstract class KvmBackend protected constructor(emulator: Emulator<*>, protected val kvm: Kvm) :
    FastBackend(emulator), Backend, KvmCallback {

    private val cachedPageSize: Int = Kvm.getPageSize()

    override fun getPageSize(): Int {
        return cachedPageSize
    }

    private var slotIndex = 0
    private val slots: Array<UserMemoryRegion?>
    protected val memoryRegionMap: MutableMap<Long, UserMemoryRegion> // key is guest_phys_addr

    init {
        val maxSlots = kvm.getMaxSlots()
        if (log.isDebugEnabled) {
            log.debug("init kvm backend kvm={}, maxSlots=0x{}, getPageSize()=0x{}", kvm, Integer.toHexString(maxSlots), Integer.toHexString(getPageSize()))
        }

        this.slots = arrayOfNulls(maxSlots)
        this.memoryRegionMap = TreeMap()
        try {
            this.kvm.setKvmCallback(this)
        } catch (e: KvmException) {
            throw BackendException(e)
        }
    }

    private fun allocateSlot(): Int {
        for (i in slotIndex until slots.size) {
            if (slots[i] == null) {
                return i
            }
        }
        throw BackendException("Allocate slot failed: slotIndex=" + slotIndex + ", maxSlots=" + slots.size)
    }

    @Throws(BackendException::class)
    override fun mem_map(address: Long, size: Long, perms: Int) {
        if ((address and (getPageSize() - 1).toLong()) != 0L) {
            throw IllegalArgumentException("mem_map address=0x" + java.lang.Long.toHexString(address))
        }
        if ((size and (getPageSize() - 1).toLong()) != 0L) {
            throw IllegalArgumentException("mem_map size=0x" + java.lang.Long.toHexString(size))
        }

//        System.out.println("mem_map address=0x" + Long.toHexString(address) + ", size=0x" + Long.toHexString(size));

        var slot = allocateSlot()
        val userspace_addr = kvm.set_user_memory_region(slot, address, size, 0L)
        if (log.isDebugEnabled) {
            log.debug("mem_map slot={}, address=0x{}, size=0x{}, userspace_addr=0x{}", slot, java.lang.Long.toHexString(address), java.lang.Long.toHexString(size), java.lang.Long.toHexString(userspace_addr))
        }
        val region = UserMemoryRegion(slot, address, size, userspace_addr)
        memoryRegionMap[region.guest_phys_addr] = region
        slots[slot++] = region
        slotIndex = slot
    }

    private fun mem_unmap_page(address: Long, region: UserMemoryRegion) {
        if (getPageSize().toLong() == region.memory_size) { // page size region
            if (address != region.guest_phys_addr) {
                throw IllegalStateException("address=0x" + java.lang.Long.toHexString(address) + ", guest_phys_addr=0x" + java.lang.Long.toHexString(region.guest_phys_addr))
            }

            kvm.remove_user_memory_region(region.slot, region.guest_phys_addr, region.memory_size, region.userspace_addr, 0x0L)
            slotIndex = region.slot
            slots[slotIndex] = null
            memoryRegionMap.remove(region.guest_phys_addr)
            return
        }
        if (address == region.guest_phys_addr && getPageSize() < region.memory_size) { // region first page
            kvm.remove_user_memory_region(region.slot, region.guest_phys_addr, getPageSize().toLong(), region.userspace_addr, 0x0L)
            memoryRegionMap.remove(region.guest_phys_addr)

            val userspace_addr = kvm.set_user_memory_region(region.slot, region.guest_phys_addr + getPageSize(), region.memory_size - getPageSize(), region.userspace_addr + getPageSize())
            val newRegion = UserMemoryRegion(region.slot, region.guest_phys_addr + getPageSize(), region.memory_size - getPageSize(), userspace_addr)
            memoryRegionMap[newRegion.guest_phys_addr] = newRegion
            slots[newRegion.slot] = newRegion
            return
        }
        if (address > region.guest_phys_addr && address + getPageSize() == region.guest_phys_addr + region.memory_size) { // region last page
            val off = address - region.guest_phys_addr
            kvm.remove_user_memory_region(region.slot, region.guest_phys_addr, getPageSize().toLong(), region.userspace_addr, off)
            memoryRegionMap.remove(region.guest_phys_addr)

            val userspace_addr = kvm.set_user_memory_region(region.slot, region.guest_phys_addr, region.memory_size - getPageSize(), region.userspace_addr)
            val newRegion = UserMemoryRegion(region.slot, region.guest_phys_addr, region.memory_size - getPageSize(), userspace_addr)
            memoryRegionMap[newRegion.guest_phys_addr] = newRegion
            slots[newRegion.slot] = newRegion
            return
        }

        // region middle page
        if (address > region.guest_phys_addr && address + getPageSize() < region.guest_phys_addr + region.memory_size) { // split region
            kvm.remove_user_memory_region(region.slot, region.guest_phys_addr, 0L, region.userspace_addr, 0L)
            memoryRegionMap.remove(region.guest_phys_addr)

            val first_memory_size = address - region.guest_phys_addr
            val second_memory_size = region.memory_size - first_memory_size
            val first_guest_phys_addr = region.guest_phys_addr

            val first_userspace_addr = kvm.set_user_memory_region(region.slot, first_guest_phys_addr, first_memory_size, region.userspace_addr)

            val first = UserMemoryRegion(region.slot, first_guest_phys_addr, first_memory_size, first_userspace_addr)
            memoryRegionMap[first.guest_phys_addr] = first
            slots[first.slot] = first

            var slot = allocateSlot()
            val second_userspace_addr = kvm.set_user_memory_region(slot, address, second_memory_size, first_userspace_addr + first_memory_size)
            val second = UserMemoryRegion(slot, address, second_memory_size, second_userspace_addr)
            memoryRegionMap[second.guest_phys_addr] = second
            slots[slot++] = second
            slotIndex = slot

            mem_unmap(address, getPageSize().toLong())
            return
        }

        throw UnsupportedOperationException("address=0x" + java.lang.Long.toHexString(address))
    }

    @Throws(BackendException::class)
    override fun mem_unmap(address: Long, size: Long) {
        if ((address and (getPageSize() - 1).toLong()) != 0L) {
            throw IllegalArgumentException("mem_unmap address=0x" + java.lang.Long.toHexString(address))
        }
        if ((size and (getPageSize() - 1).toLong()) != 0L) {
            throw IllegalArgumentException("mem_unmap size=0x" + java.lang.Long.toHexString(size))
        }

//        System.out.println("mem_unmap address=0x" + Long.toHexString(address) + ", size=0x" + Long.toHexString(size));

        var i = address
        while (i < address + size) {
            val userMemoryRegion = findUserMemoryRegion(i)

            mem_unmap_page(i, userMemoryRegion)
            i += getPageSize()
        }
    }

    private fun findUserMemoryRegion(i: Long): UserMemoryRegion {
        var userMemoryRegion: UserMemoryRegion? = null
        for (region in memoryRegionMap.values) {
            val min = Math.max(i, region.guest_phys_addr)
            val max = Math.min(i + getPageSize(), region.guest_phys_addr + region.memory_size)
            if (min < max) {
                userMemoryRegion = region
                break
            }
        }
        if (userMemoryRegion == null) {
            throw IllegalStateException("find userMemoryRegion failed: i=0x" + java.lang.Long.toHexString(i))
        }
        return userMemoryRegion
    }

    @Throws(BackendException::class)
    override fun mem_protect(address: Long, size: Long, perms: Int) {
    }

    @Throws(BackendException::class)
    override fun mem_write(address: Long, bytes: ByteArray) {
        try {
            kvm.mem_write(address, bytes)
        } catch (e: KvmException) {
            throw BackendException(e)
        }
    }

    @Throws(BackendException::class)
    override fun mem_read(address: Long, size: Long): ByteArray {
        try {
            return kvm.mem_read(address, size.toInt())
        } catch (e: KvmException) {
            throw BackendException(e)
        }
    }

    protected fun callSVC(pc: Long, swi: Int) {
        if (log.isDebugEnabled) {
            log.debug("callSVC pc=0x{}, until=0x{}, swi={}", java.lang.Long.toHexString(pc), java.lang.Long.toHexString(until), swi)
        }
        if (pc == until) {
            emu_stop()
            return
        }
        interruptHookNotifier!!.notifyCallSVC(this, ARMEmulator.EXCP_SWI, swi)
    }

    protected var interruptHookNotifier: InterruptHookNotifier? = null

    @Throws(BackendException::class)
    override fun hook_add_new(callback: InterruptHook, user_data: Any?) {
        if (interruptHookNotifier != null) {
            throw IllegalStateException()
        } else {
            interruptHookNotifier = InterruptHookNotifier(callback, user_data)
        }
    }

    protected var until: Long = 0

    @Synchronized
    @Throws(BackendException::class)
    override fun emu_start(begin: Long, until: Long, timeout: Long, count: Long) {
        if (log.isDebugEnabled) {
            log.debug("emu_start begin=0x{}, until=0x{}, timeout={}, count={}", java.lang.Long.toHexString(begin), java.lang.Long.toHexString(until), timeout, count)
        }

        this.until = until + 4
        try {
            kvm.emu_start(begin)
        } catch (e: KvmException) {
            throw BackendException(e)
        }
    }

    @Throws(BackendException::class)
    override fun emu_stop() {
        try {
            kvm.emu_stop()
        } catch (e: KvmException) {
            throw BackendException(e)
        }
    }

    @Throws(BackendException::class)
    override fun destroy() {
        IOUtils.close(kvm)
    }

    @Throws(BackendException::class)
    override fun hook_add_new(callback: EventMemHook, type: Int, user_data: Any?) {
    }

    @Throws(BackendException::class)
    override fun hook_add_new(callback: ReadHook, begin: Long, end: Long, user_data: Any?) {
        throw UnsupportedOperationException()
    }

    @Throws(BackendException::class)
    override fun hook_add_new(callback: WriteHook, begin: Long, end: Long, user_data: Any?) {
        throw UnsupportedOperationException()
    }

    @Throws(BackendException::class)
    override fun hook_add_new(callback: CodeHook, begin: Long, end: Long, user_data: Any?) {
        throw UnsupportedOperationException()
    }

    @Throws(BackendException::class)
    override fun hook_add_new(callback: BlockHook, begin: Long, end: Long, user_data: Any?) {
        throw UnsupportedOperationException()
    }

    override fun context_restore(context: Long) {
        kvm.context_restore(context)
    }

    override fun context_free(context: Long) {
        Kvm.free(context)
    }

    override fun context_save(context: Long) {
        kvm.context_save(context)
    }

    override fun context_alloc(): Long {
        return kvm.context_alloc()
    }

    override fun getMemAllocatedSize(): Long {
        return kvm.getMemAllocatedSize()
    }

    override fun getMemResidentSize(): Long {
        return kvm.getMemResidentSize()
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(KvmBackend::class.java)

        @JvmStatic
        protected val REG_VBAR_EL1 = 0xf0000000L
    }

}
