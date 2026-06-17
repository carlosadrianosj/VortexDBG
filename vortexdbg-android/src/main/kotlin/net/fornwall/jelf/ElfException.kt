package net.fornwall.jelf

/**
 * Generic exception class for all exceptions which occur in this package. Since
 * there is no mechanism built into this library for recovering from errors, the
 * best clients can do is display the error string.
 */
class ElfException : RuntimeException {

    constructor(message: String?) : super(message)

    constructor(cause: Throwable?) : super(cause)

    constructor(message: String?, cause: Throwable?) : super(message, cause)

    companion object {
        private const val serialVersionUID = 1L
    }
}
