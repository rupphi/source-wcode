package com.tuandev.fbsbarcode.shared;

import javafx.concurrent.Task;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class AppTaskExecutor {
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(1);
    private static final int THREAD_COUNT = Math.max(4, Math.min(Runtime.getRuntime().availableProcessors(), 8));
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(THREAD_COUNT, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "fbsbarcode-bg-" + THREAD_COUNTER.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    });

    private AppTaskExecutor() {
    }

    public static void execute(Task<?> task) {
        EXECUTOR.execute(task);
    }

    public static void shutdown() {
        EXECUTOR.shutdownNow();
    }
}
