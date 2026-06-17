package com.vortexdbg.pointer

import com.vortexdbg.arm.backend.*
import com.vortexdbg.debugger.BreakPoint
import com.vortexdbg.debugger.BreakPointCallback

import java.util.Arrays

internal class ByteArrayBackend(private val data: ByteArray) : AbstractBackend(), Backend {

    override fun onInitialize() {
        throw UnsupportedOperationException()
    }

    override fun switchUserMode() {
        throw UnsupportedOperationException()
    }

    override fun enableVFP() {
        throw UnsupportedOperationException()
    }

    @Throws(BackendException::class)
    override fun reg_read(regId: Int): Number {
        throw UnsupportedOperationException()
    }

    @Throws(BackendException::class)
    override fun reg_read_vector(regId: Int): ByteArray {
        throw UnsupportedOperationException()
    }

    @Throws(BackendException::class)
    override fun reg_write_vector(regId: Int, vector: ByteArray) {
        throw UnsupportedOperationException()
    }

    @Throws(BackendException::class)
    override fun reg_write(regId: Int, value: Number) {
        throw UnsupportedOperationException()
    }

    @Throws(BackendException::class)
    override fun mem_read(address: Long, size: Long): ByteArray {
        return Arrays.copyOfRange(data, address.toInt(), (address + size).toInt())
    }

    @Throws(BackendException::class)
    override fun mem_write(address: Long, bytes: ByteArray) {
        System.arraycopy(bytes, 0, data, address.toInt(), bytes.size)
    }

    @Throws(BackendException::class)
    override fun mem_map(address: Long, size: Long, perms: Int) {
        throw UnsupportedOperationException()
    }

    @Throws(BackendException::class)
    override fun mem_protect(address: Long, size: Long, perms: Int) {
        throw UnsupportedOperationException()
    }

    @Throws(BackendException::class)
    override fun mem_unmap(address: Long, size: Long) {
        throw UnsupportedOperationException()
    }

    override fun addBreakPoint(address: Long, callback: BreakPointCallback?, thumb: Boolean): BreakPoint {
        throw UnsupportedOperationException()
    }

    override fun removeBreakPoint(address: Long): Boolean {
        throw UnsupportedOperationException()
    }

    override fun setSingleStep(singleStep: Int) {
        throw UnsupportedOperationException()
    }

    override fun setFastDebug(fastDebug: Boolean) {
        throw UnsupportedOperationException()
    }

    @Throws(BackendException::class)
    override fun removeJitCodeCache(begin: Long, end: Long) {
        throw UnsupportedOperationException()
    }

    @Throws(BackendException::class)
    override fun hook_add_new(callback: CodeHook, begin: Long, end: Long, user_data: Any?) {
        throw UnsupportedOperationException()
    }

    @Throws(BackendException::class)
    override fun debugger_add(callback: DebugHook, begin: Long, end: Long, user_data: Any?) {
        throw UnsupportedOperationException()
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
    override fun hook_add_new(callback: EventMemHook, type: Int, user_data: Any?) {
        throw UnsupportedOperationException()
    }

    @Throws(BackendException::class)
    override fun hook_add_new(callback: InterruptHook, user_data: Any?) {
        throw UnsupportedOperationException()
    }

    @Throws(BackendException::class)
    override fun hook_add_new(callback: BlockHook, begin: Long, end: Long, user_data: Any?) {
        throw UnsupportedOperationException()
    }

    @Throws(BackendException::class)
    override fun emu_start(begin: Long, until: Long, timeout: Long, count: Long) {
        throw UnsupportedOperationException()
    }

    @Throws(BackendException::class)
    override fun emu_stop() {
        throw UnsupportedOperationException()
    }

    @Throws(BackendException::class)
    override fun destroy() {
        throw UnsupportedOperationException()
    }

    override fun context_restore(context: Long) {
        throw UnsupportedOperationException()
    }

    override fun context_save(context: Long) {
        throw UnsupportedOperationException()
    }

    override fun context_alloc(): Long {
        throw UnsupportedOperationException()
    }

    override fun context_free(context: Long) {
        throw UnsupportedOperationException()
    }

    override fun getPageSize(): Int {
        throw UnsupportedOperationException()
    }

    override fun registerEmuCountHook(emu_count: Long) {
        throw UnsupportedOperationException()
    }
}
