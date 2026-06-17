package com.vortexdbg.linux.file

import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.file.NewFileIO
import com.vortexdbg.file.linux.AndroidFileIO
import com.vortexdbg.file.linux.BaseAndroidFileIO
import com.vortexdbg.pointer.VortexdbgPointer
import com.sun.jna.Pointer
import org.slf4j.LoggerFactory

open class DriverFileIO internal constructor(private val emulator: Emulator<*>, oflags: Int, private val path: String) : BaseAndroidFileIO(oflags), NewFileIO, AndroidFileIO {

    override fun close() {
    }

    override fun write(data: ByteArray): Int {
        throw AbstractMethodError()
    }

    override fun read(backend: Backend, buffer: Pointer, count: Int): Int {
        throw AbstractMethodError()
    }

    private enum class AndroidAlarmType {
        ANDROID_ALARM_RTC_WAKEUP,
        ANDROID_ALARM_RTC,
        ANDROID_ALARM_ELAPSED_REALTIME_WAKEUP,
        ANDROID_ALARM_ELAPSED_REALTIME,
        ANDROID_ALARM_SYSTEMTIME,
        ANDROID_ALARM_TYPE_COUNT;

        companion object {
            fun valueOf(type: Long): AndroidAlarmType {
                for (alarmType in values()) {
                    if (alarmType.ordinal.toLong() == type) {
                        return alarmType
                    }
                }
                throw IllegalArgumentException("type=$type")
            }
        }
    }

    override fun ioctl(emulator: Emulator<*>, request: Long, argp: Long): Int {
        if ("/dev/alarm" == path) {
            var ioc = request
            val nr = ioc and 0xff
            ioc = ioc shr _IOC_NRBITS
            var type = ioc and 0xff
            ioc = ioc shr _IOC_TYPEBITS
            val size = ioc and 0x3fff
            ioc = ioc shr _IOC_SIZEBITS
            val dir = ioc
            if (type == 'a'.code.toLong()) {
                val c = nr and 0xf
                type = nr shr 4
                return androidAlarm(dir, c, AndroidAlarmType.valueOf(type), size, argp)
            }

            log.info("alarm ioctl request=0x{}, argp=0x{}, nr={}, type={}, size={}, dir={}", java.lang.Long.toHexString(request), java.lang.Long.toHexString(argp), nr, type, size, dir)
            return -1
        }

        return super.ioctl(emulator, request, argp)
    }

    private fun androidAlarm(dir: Long, c: Long, type: AndroidAlarmType, size: Long, argp: Long): Int {
        if (dir == _IOC_WRITE.toLong() && c == ANDROID_ALARM_GET_TIME.toLong() && type == AndroidAlarmType.ANDROID_ALARM_ELAPSED_REALTIME) {
            val offset = System.currentTimeMillis()
            val tv_sec = offset / 1000000000L
            val tv_nsec = offset % 1000000000L
            val pointer = VortexdbgPointer.pointer(emulator, argp) ?: throw IllegalArgumentException()
            if (size == 8L) {
                pointer.setInt(0, tv_sec.toInt())
                pointer.setInt(4, tv_nsec.toInt())
                return 0
            } else if (size == 16L) {
                pointer.setLong(0, tv_sec)
                pointer.setLong(8, tv_nsec)
                return 0
            } else {
                throw IllegalArgumentException("size=$size")
            }
        }

        log.info("androidAlarm argp=0x{}, c={}, type={}, size={}, dir={}", java.lang.Long.toHexString(argp), c, type, size, dir)
        return -1
    }

    override fun fstat(emulator: Emulator<*>, stat: com.vortexdbg.file.linux.StatStructure): Int {
        stat.st_blksize = emulator.getPageAlign()
        stat.pack()
        return 0
    }

    override fun getdents64(dirp: Pointer, size: Int): Int {
        throw UnsupportedOperationException(path)
    }

    override fun toString(): String {
        return path
    }

    companion object {
        private val log = LoggerFactory.getLogger(DriverFileIO::class.java)

        @JvmStatic
        fun create(emulator: Emulator<*>, oflags: Int, pathname: String): DriverFileIO? {
            if ("/dev/urandom" == pathname || "/dev/random" == pathname || "/dev/srandom" == pathname) {
                return RandomFileIO(emulator, pathname)
            }
            if ("/dev/alarm" == pathname || "/dev/null" == pathname) {
                return DriverFileIO(emulator, oflags, pathname)
            }
            if ("/dev/ashmem" == pathname) {
                return Ashmem(emulator, oflags, pathname)
            }
            if ("/dev/zero" == pathname) {
                return ZeroFileIO(emulator, oflags, pathname)
            }
            return null
        }

        private const val _IOC_NRBITS = 8
        private const val _IOC_TYPEBITS = 8
        private const val _IOC_SIZEBITS = 14

        private const val _IOC_WRITE = 1
        private const val _IOC_READ = 2

        private const val ANDROID_ALARM_GET_TIME = 4
    }
}
