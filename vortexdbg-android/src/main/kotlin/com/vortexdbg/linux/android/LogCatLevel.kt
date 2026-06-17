package com.vortexdbg.linux.android

enum class LogCatLevel(private val value: Int, private val level: Char) {

    /**
     * Priority constant for the println method; use Log.v.
     */
    VERBOSE(2, 'V'),

    /**
     * Priority constant for the println method; use Log.d.
     */
    DEBUG(3, 'D'),

    /**
     * Priority constant for the println method; use Log.i.
     */
    INFO(4, 'I'),

    /**
     * Priority constant for the println method; use Log.w.
     */
    WARN(5, 'W'),

    /**
     * Priority constant for the println method; use Log.e.
     */
    ERROR(6, 'E'),

    /**
     * Priority constant for the println method.
     */
    ASSERT(7, 'A');

    fun getLevel(): Char {
        return level
    }

    override fun toString(): String {
        return level.toString()
    }

    companion object {
        @JvmStatic
        fun valueOf(value: Int): LogCatLevel? {
            for (level in values()) {
                if (level.value == value) {
                    return level
                }
            }
            return null
        }
    }

}
