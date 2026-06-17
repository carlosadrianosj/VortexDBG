package net.fornwall.jelf

import java.io.IOException

/**
 * A memoized object. Override [computeValue] in subclasses; call [getValue] in using code.
 */
abstract class MemoizedObject<T> {
    private var computed = false
    private var value: T? = null

    /**
     * Should compute the value of this memoized object. This will only be called once, upon the first call to
     * [getValue].
     */
    @Throws(ElfException::class, IOException::class)
    protected abstract fun computeValue(): T

    /** Public accessor for the memoized value. */
    @Throws(ElfException::class, IOException::class)
    fun getValue(): T {
        if (!computed) {
            value = computeValue()
            computed = true
        }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        @JvmStatic
        fun <T> uncheckedArray(size: Int): Array<MemoizedObject<T>> {
            return arrayOfNulls<MemoizedObject<*>>(size) as Array<MemoizedObject<T>>
        }
    }
}
