package com.vortexdbg.worker

/**
 * Worker 的借出凭证，实现 {@link AutoCloseable} 以支持 try-with-resources 自动归还。
 *
 * <p>通过 {@link #get()} 获取被借出的 Worker 实例，
 * 当 try 块结束时 {@link #close()} 自动将 Worker 归还到池中。</p>
 *
 * <pre>{@code
 * try (WorkerLoan<MyWorker> loan = pool.borrow(1, TimeUnit.MINUTES)) {
 *     if (loan != null) {
 *         MyWorker worker = loan.get();
 *         worker.doWork();
 *     }
 * } // 自动归还
 * }</pre>
 *
 * @param <T> Worker 的具体类型
 * @see WorkerPool#borrow
 */
class WorkerLoan<T : Worker> internal constructor(private val worker: T, private val pool: WorkerPool) : AutoCloseable {

    /**
     * 获取被借出的 Worker 实例。
     *
     * @return Worker 实例
     */
    fun get(): T {
        return worker
    }

    /**
     * 将 Worker 归还到池中。由 try-with-resources 自动调用。
     */
    override fun close() {
        pool.release(worker)
    }

}
