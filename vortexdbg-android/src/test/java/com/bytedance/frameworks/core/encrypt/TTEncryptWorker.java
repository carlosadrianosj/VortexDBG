package com.bytedance.frameworks.core.encrypt;

import com.alibaba.fastjson.util.IOUtils;
import com.vortexdbg.arm.backend.HypervisorFactory;
import com.vortexdbg.utils.Inspector;
import com.vortexdbg.worker.Worker;
import com.vortexdbg.worker.WorkerLoan;
import com.vortexdbg.worker.WorkerPool;
import com.vortexdbg.worker.WorkerPoolFactory;
import org.scijava.nativelib.NativeLibraryUtil;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class TTEncryptWorker implements Worker {

    public static void main(String[] args) throws InterruptedException {
        final WorkerPool pool = WorkerPoolFactory.create(TTEncryptWorker::new,
                NativeLibraryUtil.getArchitecture() == NativeLibraryUtil.Architecture.OSX_ARM64 ? HypervisorFactory.getMaxVcpuCount() : Runtime.getRuntime().availableProcessors());

        int testThreads = 500;
        ExecutorService executorService = Executors.newFixedThreadPool(testThreads);
        for (int i = 0; i < testThreads; i++) {
            final String name = "T" + i;
            executorService.submit(() -> {
                long start = System.currentTimeMillis();
                try (WorkerLoan<TTEncryptWorker> loan = pool.borrow(1, TimeUnit.MINUTES)) {
                    if (loan != null) {
                        TTEncryptWorker worker = loan.get();
                        long currentTimeMillis = System.currentTimeMillis();
                        byte[] data = worker.doWork();
                        Inspector.inspect(data, name + ": " + (System.currentTimeMillis() - start) + "ms" + ", " + (System.currentTimeMillis() - currentTimeMillis) + "ms");
                    } else {
                        System.err.println("Borrow failed");
                    }
                }
            });
        }
        executorService.shutdown();
        if (!executorService.awaitTermination(10, TimeUnit.MINUTES)) {
            throw new IllegalStateException();
        }
        IOUtils.close(pool);
    }

    private final TTEncrypt ttEncrypt;

    private TTEncryptWorker() {
        ttEncrypt = new TTEncrypt(false);
        System.err.println("Create: " + ttEncrypt);
    }

    @Override
    public void destroy() {
        ttEncrypt.destroy();
        System.err.println("Destroy: " + ttEncrypt);
    }

    private byte[] doWork() {
        return ttEncrypt.ttEncrypt();
    }

}
