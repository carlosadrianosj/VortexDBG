package com.vortexdbg.worker

/**
 * Factory for new [Worker] instances. Typically passed as a method reference to
 * [WorkerPoolFactory.create], e.g. `WorkerPoolFactory.create(MyWorker::new, 4)`.
 *
 * @see WorkerPoolFactory
 */
interface WorkerFactory {

    fun createWorker(): Worker

}
