package com.vortexdbg.worker

import java.io.Closeable
import java.util.concurrent.TimeUnit

/**
 * Pool of reusable [Worker] instances, managing their borrow and return. Create instances via
 * [WorkerPoolFactory.create]; closing the pool destroys all managed workers.
 *
 * @see WorkerPoolFactory
 * @see WorkerLoan
 */
interface WorkerPool : Closeable {

    /**
     * Sets the idle timeout, after which an unborrowed worker is eligible for destruction. Minimum 1
     * minute, default 10.
     */
    fun setIdleTimeout(idleTimeoutMinutes: Int)

    /**
     * Sets the floor on live workers that idle cleanup will not drop below. Minimum 1, default 1.
     */
    fun setMinIdle(minIdle: Int)

    /**
     * Sets how many workers to pre-create at startup. Clamped to maxWorkers; default 0 (fully lazy).
     */
    fun setInitialSize(initialSize: Int)

    /**
     * Borrows a worker, blocking up to the given timeout.
     *
     * @return the loan, or `null` if the wait timed out or the pool is closed
     */
    fun <T : Worker> borrow(timeout: Long, unit: TimeUnit): WorkerLoan<T>?

    /**
     * Returns a worker to the pool. Usually not called directly; [WorkerLoan.close] handles it.
     */
    fun release(worker: Worker)

}
