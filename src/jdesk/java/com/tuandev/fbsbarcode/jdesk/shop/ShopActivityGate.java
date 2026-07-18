package com.tuandev.fbsbarcode.jdesk.shop;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Supplier;

/** Coordinates long shop work with destructive deletion without thread-affine locks. */
public final class ShopActivityGate {
    private final ConcurrentMap<Integer, StampedLock> locks = new ConcurrentHashMap<>();

    public Lease begin(int shopId) {
        StampedLock lock = lock(shopId);
        long stamp = lock.tryReadLock();
        if (stamp == 0L) {
            throw new ShopBusyException();
        }
        return new Lease(lock, stamp);
    }

    public <T> T deleteWhenIdle(int shopId, Supplier<T> deletion) {
        Objects.requireNonNull(deletion, "deletion");
        StampedLock lock = lock(shopId);
        long stamp = lock.tryWriteLock();
        if (stamp == 0L) {
            throw new ShopBusyException();
        }
        try {
            return deletion.get();
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    private StampedLock lock(int shopId) {
        if (shopId <= 0) throw new IllegalArgumentException("Shop id must be positive");
        return locks.computeIfAbsent(shopId, ignored -> new StampedLock());
    }

    public static final class Lease implements AutoCloseable {
        private final StampedLock lock;
        private final long stamp;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(StampedLock lock, long stamp) {
            this.lock = lock;
            this.stamp = stamp;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                lock.unlockRead(stamp);
            }
        }
    }

    public static final class ShopBusyException extends RuntimeException {
        private ShopBusyException() {
            super("shop_busy", null, false, false);
        }
    }
}
