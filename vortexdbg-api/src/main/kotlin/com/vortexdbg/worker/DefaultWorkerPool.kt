package com.vortexdbg.worker

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * {@link WorkerPool} 的默认实现，使用独立线程管理 Worker 的创建、回收与空闲清理。
 *
 * <ul>
 *   <li>Worker 按需创建：只有当可用池为空且未达到上限时，才创建新的 Worker</li>
 *   <li>总 Worker 数量（借出 + 空闲）不超过 {@code maxWorkers}</li>
 *   <li>空闲超过 {@code idleTimeout} 的 Worker 由管理线程自动销毁</li>
 * </ul>
 *
 * <p>内部维护两个队列：
 * <ul>
 *   <li>{@code workers} — 可供借出的空闲 Worker 队列</li>
 *   <li>{@code releaseQueue} — 归还缓冲队列，由管理线程转入 workers</li>
 * </ul>
 */
internal class DefaultWorkerPool(private val factory: WorkerFactory, private val maxWorkers: Int) : WorkerPool, Runnable {

    /**
     * 包装空闲 Worker，记录入池时间以便判定超时。
     * 使用 {@link System#currentTimeMillis()} 而非 nanoTime，
     * 因为 nanoTime 在 macOS 系统休眠期间不推进，会导致空闲超时失效。
     */
    private class IdleWorker(@JvmField val worker: Worker) {
        @JvmField
        val idleSinceMs: Long = System.currentTimeMillis()
    }

    private val workers: BlockingQueue<IdleWorker> = LinkedBlockingQueue()
    private val releaseQueue: BlockingQueue<Worker> = LinkedBlockingQueue()
    private val totalAlive = AtomicInteger()

    @Volatile
    private var idleTimeoutMs: Long = TimeUnit.MINUTES.toMillis(DEFAULT_IDLE_TIMEOUT_MINUTES.toLong())
    @Volatile
    private var minIdle = 1
    @Volatile
    private var initialSize = 1

    private val thread: Thread

    @Volatile
    private var stopped = false

    init {
        if (maxWorkers <= 0) {
            throw IllegalArgumentException("maxWorkers must be positive: $maxWorkers")
        }

        log.info("Creating worker pool: factory={}, maxWorkers={}, idleTimeout={}min", factory, maxWorkers, DEFAULT_IDLE_TIMEOUT_MINUTES)

        this.thread = Thread(this, "worker pool for $factory")
        thread.isDaemon = true
        thread.start()
    }

    override fun setIdleTimeout(idleTimeoutMinutes: Int) {
        if (idleTimeoutMinutes < MIN_IDLE_TIMEOUT_MINUTES) {
            throw IllegalArgumentException("idleTimeoutMinutes must be at least $MIN_IDLE_TIMEOUT_MINUTES: $idleTimeoutMinutes")
        }
        this.idleTimeoutMs = TimeUnit.MINUTES.toMillis(idleTimeoutMinutes.toLong())
        log.debug("Updated idle timeout: {}min", idleTimeoutMinutes)
    }

    override fun setMinIdle(minIdle: Int) {
        if (minIdle < 1) {
            throw IllegalArgumentException("minIdle must be at least 1: $minIdle")
        }
        this.minIdle = minIdle
        log.debug("Updated minIdle: {}", minIdle)
    }

    override fun setInitialSize(initialSize: Int) {
        if (initialSize < 0) {
            throw IllegalArgumentException("initialSize must be non-negative: $initialSize")
        }
        this.initialSize = Math.min(initialSize, maxWorkers)
        log.debug("Updated initialSize: {}", initialSize)
    }

    /**
     * 管理线程主循环：按需创建 Worker、处理归还、清理空闲超时 Worker。
     */
    override fun run() {
        var lastCleanupMs = System.currentTimeMillis()

        while (!stopped) {
            try {
                val alive = totalAlive.get()
                val shouldCreate = (workers.isEmpty() || alive < initialSize) && alive < maxWorkers

                val release = if (shouldCreate) {
                    releaseQueue.poll()
                } else {
                    releaseQueue.poll(1, TimeUnit.SECONDS)
                }

                if (release != null) {
                    if (!workers.offer(IdleWorker(release))) {
                        throw IllegalStateException("Offer released worker failed.")
                    }
                    continue
                }

                if (shouldCreate) {
                    totalAlive.incrementAndGet()
                    val startMs = System.currentTimeMillis()
                    val worker: Worker
                    try {
                        worker = factory.createWorker()
                    } catch (e: RuntimeException) {
                        totalAlive.decrementAndGet()
                        log.warn("Failed to create worker", e)
                        continue
                    }
                    log.info("Created new worker: {}, totalAlive={}/{}, elapsed={}ms", worker, totalAlive.get(), maxWorkers, System.currentTimeMillis() - startMs)
                    if (!workers.offer(IdleWorker(worker))) {
                        throw IllegalStateException("Offer created worker failed.")
                    }
                    continue
                }

                val now = System.currentTimeMillis()
                if (now - lastCleanupMs >= CLEANUP_INTERVAL_MS) {
                    lastCleanupMs = now
                    val size = workers.size
                    for (i in 0 until size) {
                        val idle = workers.poll() ?: break
                        if (now - idle.idleSinceMs > idleTimeoutMs && totalAlive.get() > minIdle) {
                            idle.worker.destroy()
                            val remaining = totalAlive.decrementAndGet()
                            log.info("Destroyed idle worker: {}, totalAlive={}", idle.worker, remaining)
                        } else if (!workers.offer(idle)) {
                            throw IllegalStateException("Offer idle worker failed.")
                        }
                    }
                }
            } catch (e: InterruptedException) {
                if (!stopped) {
                    log.warn("worker pool thread interrupted unexpectedly", e)
                }
                break
            }
        }

        closeIdleWorkers()
        closeReleasedWorkers()
    }

    private fun closeIdleWorkers() {
        var idle: IdleWorker?
        while ((workers.poll().also { idle = it }) != null) {
            idle!!.worker.destroy()
            log.info("Closed idle worker: {}", idle!!.worker)
        }
    }

    private fun closeReleasedWorkers() {
        var worker: Worker?
        while ((releaseQueue.poll().also { worker = it }) != null) {
            worker!!.destroy()
            log.info("Closed released worker: {}", worker)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Worker> borrow(timeout: Long, unit: TimeUnit): WorkerLoan<T>? {
        if (stopped) {
            return null
        }

        try {
            val idle = workers.poll(timeout, unit)
            return if (idle == null) null else WorkerLoan(idle.worker as T, this)
        } catch (e: InterruptedException) {
            log.warn("borrow interrupted", e)
            return null
        }
    }

    /**
     * 归还 Worker 到 releaseQueue，由管理线程转入可用池。
     * 池已关闭时直接销毁。
     */
    override fun release(worker: Worker) {
        if (stopped) {
            worker.destroy()
            totalAlive.decrementAndGet()
        } else if (!releaseQueue.offer(worker)) {
            throw IllegalStateException("Release worker failed.")
        }
    }

    override fun close() {
        stopped = true
        try {
            thread.join(5000)
        } catch (e: InterruptedException) {
            log.warn("close interrupted while waiting for worker pool thread", e)
        }

        closeIdleWorkers()
        closeReleasedWorkers()
    }

    companion object {

        private val log: Logger = LoggerFactory.getLogger(DefaultWorkerPool::class.java)

        private const val MIN_IDLE_TIMEOUT_MINUTES = 1
        private const val DEFAULT_IDLE_TIMEOUT_MINUTES = 10
        private const val CLEANUP_INTERVAL_MS: Long = 30_000
    }

}
