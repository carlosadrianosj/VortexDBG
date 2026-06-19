package com.vortexdbg.worker

import org.scijava.nativelib.NativeLibraryUtil

/**
 * Static factory for [WorkerPool]. Pools are lazily initialized: workers are created on demand and
 * destroyed after the idle timeout (default 10 minutes, see [WorkerPool.setIdleTimeout]).
 *
 * ```
 * // Max workers defaults to the CPU core count
 * WorkerPool pool = WorkerPoolFactory.create(MyWorker::new);
 *
 * // Explicit max worker count
 * WorkerPool pool = WorkerPoolFactory.create(MyWorker::new, 4);
 *
 * // hypervisorBackend=true clamps to a single worker on Apple Silicon
 * WorkerPool pool = WorkerPoolFactory.create(MyWorker::new, 4, true);
 *
 * // Pre-create workers at startup
 * WorkerPool pool = WorkerPoolFactory.create(MyWorker::new, 8);
 * pool.setInitialSize(4);
 * ```
 */
class WorkerPoolFactory {

    companion object {

        /** Creates a pool whose max worker count is the current CPU core count. */
        @JvmStatic
        fun create(factory: WorkerFactory): WorkerPool {
            return create(factory, Runtime.getRuntime().availableProcessors())
        }

        /** Creates a pool with at most [workerCount] workers (must be > 0). */
        @JvmStatic
        fun create(factory: WorkerFactory, workerCount: Int): WorkerPool {
            return create(factory, workerCount, NativeLibraryUtil.getArchitecture() == NativeLibraryUtil.Architecture.OSX_ARM64)
        }

        /**
         * Creates a pool with at most [workerCount] workers.
         *
         * @param hypervisorBackend when true, clamps [workerCount] to 1 on Apple Silicon, where the
         *   hypervisor backend cannot run more than one VM per process
         */
        @JvmStatic
        fun create(factory: WorkerFactory, workerCount: Int, hypervisorBackend: Boolean): WorkerPool {
            var workerCount = workerCount
            if (hypervisorBackend &&
                NativeLibraryUtil.getArchitecture() == NativeLibraryUtil.Architecture.OSX_ARM64 &&
                workerCount > 1) {
                workerCount = 1
            }
            return DefaultWorkerPool(factory, workerCount)
        }

    }

}
