package com.tuandev.fbsbarcode.features.finance;

import javafx.concurrent.Task;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Dedicated, low-priority executors so analytics cannot occupy order/KIZ workers. */
public final class FinanceExecutor {
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static ScheduledExecutorService scheduler;
    private static ExecutorService syncWorker;
    private static ExecutorService queryWorker;

    private FinanceExecutor() {
    }

    private static synchronized ScheduledExecutorService getScheduler() {
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(threadFactory("wcode-finance-scheduler-"));
        }
        return scheduler;
    }

    private static synchronized ExecutorService getSyncWorker() {
        if (syncWorker == null || syncWorker.isShutdown()) {
            syncWorker = Executors.newSingleThreadExecutor(threadFactory("wcode-finance-sync-"));
        }
        return syncWorker;
    }

    private static synchronized ExecutorService getQueryWorker() {
        if (queryWorker == null || queryWorker.isShutdown()) {
            queryWorker = Executors.newSingleThreadExecutor(threadFactory("wcode-finance-query-"));
        }
        return queryWorker;
    }

    public static void scheduleWithFixedDelay(Runnable task, long initialDelay, long delay, TimeUnit unit) {
        try {
            getScheduler().scheduleWithFixedDelay(task, initialDelay, delay, unit);
        } catch (RejectedExecutionException ignored) {
            // Application is shutting down.
        }
    }

    public static void executeSync(Runnable task) {
        try {
            getSyncWorker().execute(task);
        } catch (RejectedExecutionException ignored) {
            // Application is shutting down.
        }
    }

    public static void executeQuery(Task<?> task) {
        try {
            getQueryWorker().execute(task);
        } catch (RejectedExecutionException ignored) {
            // Application is shutting down.
        }
    }

    public static synchronized void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (syncWorker != null) {
            syncWorker.shutdownNow();
            syncWorker = null;
        }
        if (queryWorker != null) {
            queryWorker.shutdownNow();
            queryWorker = null;
        }
    }

    private static ThreadFactory threadFactory(String prefix) {
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + COUNTER.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        };
    }
}
