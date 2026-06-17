package com.vortexdbg.linux.file

import com.vortexdbg.Emulator
import com.vortexdbg.file.linux.AndroidFileIO
import com.vortexdbg.file.linux.LinuxFileSystem
import com.vortexdbg.linux.android.LogCatLevel
import com.vortexdbg.unix.UnixEmulator
import org.slf4j.LoggerFactory

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

open class LocalAndroidUdpSocket(emulator: Emulator<*>) : LocalUdpSocket(emulator), AndroidFileIO {

    override fun connect(path: String): Int {
        if ("/dev/socket/logdw" == path) {
            handler = object : UdpHandler {
                private val LOG_ID_MAIN = 0
                private val LOG_ID_RADIO = 1
                private val LOG_ID_EVENTS = 2
                private val LOG_ID_SYSTEM = 3
                private val LOG_ID_CRASH = 4
                private val LOG_ID_KERNEL = 5
                private val byteArrayOutputStream = ByteArrayOutputStream()

                override fun handle(request: ByteArray) {
                    try {
                        byteArrayOutputStream.write(request)

                        if (byteArrayOutputStream.size() <= 11) {
                            return
                        }

                        var tagIndex = -1
                        var bodyIndex = -1
                        val body = byteArrayOutputStream.toByteArray()
                        val buffer = ByteBuffer.wrap(body)
                        buffer.order(ByteOrder.LITTLE_ENDIAN)
                        val id = buffer.get().toInt() and 0xff
                        val tid = buffer.getShort().toInt() and 0xffff
                        val tv_sec = buffer.getInt()
                        val tv_nsec = buffer.getInt()
                        if (log.isDebugEnabled) {
                            log.debug("handle id={}, tid={}, tv_sec={}, tv_nsec={}", id, tid, tv_sec, tv_nsec)
                        }

                        val type: String
                        when (id) {
                            LOG_ID_MAIN -> type = "main"
                            LOG_ID_RADIO -> type = "radio"
                            LOG_ID_EVENTS -> type = "events"
                            LOG_ID_SYSTEM -> type = "system"
                            LOG_ID_CRASH -> type = "crash"
                            LOG_ID_KERNEL -> type = "kernel"
                            else -> type = Integer.toString(id)
                        }

                        for (i in 12 until body.size) {
                            if (body[i].toInt() != 0) {
                                continue
                            }

                            if (tagIndex == -1) {
                                tagIndex = i
                                continue
                            }

                            bodyIndex = i
                            break
                        }

                        if (tagIndex != -1 && bodyIndex != -1) {
                            byteArrayOutputStream.reset()

                            val value = body[11].toInt() and 0xff
                            val tag = String(body, 12, tagIndex - 12)
                            val text = String(body, tagIndex + 1, bodyIndex - tagIndex - 1)
                            val level = LogCatLevel.valueOf(value)

                            val fileSystem = emulator.getFileSystem() as LinuxFileSystem
                            val handler = fileSystem.getLogCatHandler()
                            if (handler != null) {
                                handler.handleLog(type, level, tag, text)
                            } else {
                                System.err.printf("[%s]%s/%s: %s%n", type, level, tag, text)
                            }
                        }
                    } catch (e: IOException) {
                        throw IllegalStateException(e)
                    }
                }
            }
            return 0
        }

        emulator.getMemory().setErrno(UnixEmulator.EPERM)
        return -1
    }

    companion object {
        private val log = LoggerFactory.getLogger(LocalAndroidUdpSocket::class.java)
    }
}
