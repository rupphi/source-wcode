package com.tuandev.fbsbarcode.shared;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** One explicit print preparation per shop, across FBS/FBO and across asynchronous UI stages. */
public final class PrintRequestGate {
    private static final Set<Integer> ACTIVE = ConcurrentHashMap.newKeySet();
    private PrintRequestGate() { }
    public static Lease tryAcquire(int shopId) {
        return ACTIVE.add(shopId) ? new Lease(shopId) : null;
    }
    public static final class Lease implements AutoCloseable {
        private final int shopId;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Lease(int shopId) { this.shopId = shopId; }
        @Override public void close() { if (closed.compareAndSet(false, true)) ACTIVE.remove(shopId); }
    }
}
