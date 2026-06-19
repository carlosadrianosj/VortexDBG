package com.vortexdbg

import com.alibaba.fastjson.util.IOUtils
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.arm.backend.BackendException
import com.vortexdbg.arm.backend.ReadHook
import com.vortexdbg.arm.backend.UnHook
import com.vortexdbg.arm.backend.WriteHook
import com.vortexdbg.arm.context.RegisterContext
import com.vortexdbg.listener.TraceReadListener
import com.vortexdbg.listener.TraceWriteListener
import com.vortexdbg.pointer.VortexdbgPointer

import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Hook that logs memory accesses while tracing. The [read] flag selects whether
 * read or write accesses are reported.
 */
class TraceMemoryHook(private val read: Boolean) : ReadHook, WriteHook, TraceHook {

    private val dateFormat: DateFormat = SimpleDateFormat("[HH:mm:ss SSS]")

    private var redirect: PrintStream? = null
    internal var traceReadListener: TraceReadListener? = null
    internal var traceWriteListener: TraceWriteListener? = null

    private var unHook: UnHook? = null

    override fun onAttach(unHook: UnHook) {
        if (this.unHook != null) {
            throw IllegalStateException()
        }
        this.unHook = unHook
    }

    override fun detach() {
        if (unHook != null) {
            unHook!!.unhook()
            unHook = null
        }
    }

    override fun stopTrace() {
        detach()
        IOUtils.close(redirect)
        redirect = null
    }

    override fun setRedirect(redirect: PrintStream?) {
        this.redirect = redirect
    }

    override fun hook(backend: Backend, address: Long, size: Int, user: Any?) {
        if (!read) {
            return
        }

        try {
            val data = if (size == 0) ByteArray(0) else backend.mem_read(address, size.toLong())
            val value: String
            when (data.size) {
                1 -> value = String.format("0x%02x", ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).get().toInt() and 0xff)
                2 -> value = String.format("0x%04x", ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff)
                4 -> value = String.format("0x%08x", ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL)
                8 -> value = String.format("0x%016x", ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).long)
                else -> value = "0x" + org.apache.commons.codec.binary.Hex.encodeHexString(data)
            }
            val emulator = user as Emulator<*>
            if (traceReadListener == null || traceReadListener!!.onRead(emulator, address, data, value)) {
                printMsg(dateFormat.format(Date()) + " Memory READ at 0x", emulator, address, size, value)
            }
        } catch (e: BackendException) {
            throw IllegalStateException(e)
        }
    }

    private fun printMsg(type: String, emulator: Emulator<*>, address: Long, size: Int, value: String) {
        val context = emulator.getContext<RegisterContext>()
        val pc = context.getPCPointer()
        val lr = context.getLRPointer()
        var out = System.out
        if (redirect != null) {
            out = redirect!!
        }
        val builder = StringBuilder()
        builder.append(type).append(java.lang.Long.toHexString(address))
        if (size > 0) {
            builder.append(", data size = ").append(size).append(", data value = ").append(value)
        }
        builder.append(", PC=").append(pc).append(", LR=").append(lr)
        out.println(builder)
    }

    override fun hook(backend: Backend, address: Long, size: Int, value: Long, user: Any?) {
        if (read) {
            return
        }

        try {
            val emulator = user as Emulator<*>
            if (traceWriteListener == null || traceWriteListener!!.onWrite(emulator, address, size, value)) {
                val str: String
                when (size) {
                    1 -> str = String.format("0x%02x", value and 0xffL)
                    2 -> str = String.format("0x%04x", value and 0xffffL)
                    4 -> str = String.format("0x%08x", value and 0xffffffffL)
                    8 -> str = String.format("0x%016x", value)
                    else -> str = "0x" + java.lang.Long.toHexString(value)
                }
                printMsg(dateFormat.format(Date()) + " Memory WRITE at 0x", emulator, address, size, str)
            }
        } catch (e: BackendException) {
            throw IllegalStateException(e)
        }
    }

}
