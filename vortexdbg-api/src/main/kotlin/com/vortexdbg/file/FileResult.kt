package com.vortexdbg.file

class FileResult<T : NewFileIO> private constructor(
    @JvmField val io: T?,
    @JvmField val errno: Int
) {

    fun isSuccess(): Boolean {
        return io != null && errno == 0
    }

    fun isFallback(): Boolean {
        return io != null && errno == FALLBACK_ERRNO
    }

    companion object {
        private const val FALLBACK_ERRNO = -1

        @JvmStatic
        fun <T : NewFileIO> success(io: T?): FileResult<T> {
            if (io == null) {
                throw NullPointerException("io is null")
            }
            return FileResult(io, 0)
        }

        @JvmStatic
        fun <T : NewFileIO> failed(errno: Int): FileResult<T> {
            if (errno == 0) {
                throw IllegalArgumentException("errno=$errno")
            }
            return FileResult(null, errno)
        }

        @JvmStatic
        fun <T : NewFileIO> fallback(io: T?): FileResult<T> {
            if (io == null) {
                throw NullPointerException("io is null")
            }
            return FileResult(io, FALLBACK_ERRNO)
        }
    }
}
