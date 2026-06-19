package com.vortexdbg.worker

/**
 * Borrow handle for a [Worker], implementing [AutoCloseable] so the worker is returned to the pool
 * automatically by try-with-resources / Kotlin `use`. Obtain the worker via [get]; [close] releases
 * it back to the pool.
 *
 * ```
 * try (WorkerLoan<MyWorker> loan = pool.borrow(1, TimeUnit.MINUTES)) {
 *     if (loan != null) {
 *         loan.get().doWork();
 *     }
 * } // worker returned here
 * ```
 *
 * @see WorkerPool.borrow
 */
class WorkerLoan<T : Worker> internal constructor(private val worker: T, private val pool: WorkerPool) : AutoCloseable {

    fun get(): T {
        return worker
    }

    override fun close() {
        pool.release(worker)
    }

}
