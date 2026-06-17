package com.vortexdbg.arm.backend

open class BackendException : RuntimeException {

    constructor() : super()

    constructor(message: String?) : super(message)

    constructor(cause: Throwable?) : super(cause)

    constructor(message: String?, cause: Throwable?) : super(message, cause)

}
