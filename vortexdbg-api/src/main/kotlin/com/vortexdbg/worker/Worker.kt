package com.vortexdbg.worker

/**
 * A reusable pooled resource, such as an emulator instance. Implementations release their underlying
 * resources in [destroy]; borrowing and returning are handled by [WorkerPool] and [WorkerLoan], so a
 * worker need not be aware of the pool.
 *
 * @see WorkerPool
 * @see WorkerLoan
 */
interface Worker {

    /**
     * Releases the underlying resources held by this worker. Called by the pool when the worker is no
     * longer managed (e.g. on pool shutdown or idle eviction).
     */
    fun destroy()

}
