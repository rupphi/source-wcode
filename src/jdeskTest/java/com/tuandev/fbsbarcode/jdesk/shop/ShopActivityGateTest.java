package com.tuandev.fbsbarcode.jdesk.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ShopActivityGateTest {
    @Test
    void activeLeaseBlocksDeleteUntilClosed() {
        ShopActivityGate gate = new ShopActivityGate();
        ShopActivityGate.Lease lease = gate.begin(7);

        assertThrows(ShopActivityGate.ShopBusyException.class,
                () -> gate.deleteWhenIdle(7, () -> "deleted"));

        lease.close();
        assertEquals("deleted", gate.deleteWhenIdle(7, () -> "deleted"));
        lease.close();
    }

    @Test
    void deleteLeaseBlocksNewActivityButNotAnotherShop() throws Exception {
        ShopActivityGate gate = new ShopActivityGate();
        CountDownLatch deleting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();
        Thread worker = Thread.ofVirtual().start(() -> gate.deleteWhenIdle(7, () -> {
            deleting.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("delete timed out");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            completed.incrementAndGet();
            return null;
        }));
        if (!deleting.await(1, TimeUnit.SECONDS)) throw new AssertionError("delete did not start");

        assertThrows(ShopActivityGate.ShopBusyException.class, () -> gate.begin(7));
        gate.begin(8).close();
        release.countDown();
        worker.join();

        assertEquals(1, completed.get());
        gate.begin(7).close();
    }
}
