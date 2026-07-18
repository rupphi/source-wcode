package com.tuandev.fbsbarcode.jdesk.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.runtime.json.JacksonJsonCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ShopCommandServiceTest {
    private static final String SECRET = "wb-secret-that-must-never-cross-the-bridge";

    @Test
    void listReturnsOnlyBoundedSummariesAndValidSelection() {
        FakeStore store = new FakeStore(state(7, "Main shop", true));

        ShopCommandService.ShopState response = service(store).list(
                        new ShopCommandService.ShopListRequest(), null)
                .toCompletableFuture().join();

        assertEquals(state(7, "Main shop", true), response);
        String json = new JacksonJsonCodec().encode(response);
        assertFalse(json.contains(SECRET));
        assertFalse(json.toLowerCase().contains("api_key"));
        assertFalse(json.toLowerCase().contains("apikey"));
    }

    @Test
    void createNormalizesNameAndPassesTokenOnlyIntoTheStore() {
        FakeStore store = new FakeStore(state(7, "Main shop", true));

        ShopCommandService.ShopState response = service(store).create(
                        new ShopCommandService.CreateShopRequest("  Main shop  ", "  " + SECRET + "  "),
                        null)
                .toCompletableFuture().join();

        assertEquals("Main shop", store.createdName);
        assertEquals(SECRET, store.createdToken);
        assertFalse(response.toString().contains(SECRET));
    }

    @Test
    void updateUsesBlankTokenAsRetainAndNeverEchoesReplacement() {
        FakeStore store = new FakeStore(state(7, "Renamed", true));
        ShopCommandService service = service(store);

        service.update(new ShopCommandService.UpdateShopRequest(7, " Renamed ", "   "), null)
                .toCompletableFuture().join();
        assertNull(store.updatedToken);

        ShopCommandService.ShopState response = service.update(
                        new ShopCommandService.UpdateShopRequest(7, "Renamed", SECRET), null)
                .toCompletableFuture().join();
        assertEquals(SECRET, store.updatedToken);
        assertFalse(response.toString().contains(SECRET));
    }

    @Test
    void malformedNameTokenAndIdsAreRejectedBeforeStoreAccess() {
        FakeStore store = new FakeStore(state(7, "Main", true));
        ShopCommandService service = service(store);

        assertInvalid(() -> service.create(new ShopCommandService.CreateShopRequest("", SECRET), null));
        assertInvalid(() -> service.create(
                new ShopCommandService.CreateShopRequest("bad\nname", SECRET), null));
        assertInvalid(() -> service.create(
                new ShopCommandService.CreateShopRequest("Main", "x".repeat(16_385)), null));
        assertInvalid(() -> service.update(
                new ShopCommandService.UpdateShopRequest(0, "Main", ""), null));
        assertInvalid(() -> service.select(new ShopCommandService.SelectShopRequest(-1), null));
        assertInvalid(() -> service.delete(new ShopCommandService.DeleteShopRequest(7, false), null));

        assertEquals(0, store.calls);
    }

    @Test
    void deleteRequiresIdleShopAndReturnsAllowlistedBusyError() {
        FakeStore store = new FakeStore(state(8, "Backup", true));
        ShopCommandService service = new ShopCommandService(
                store, new ShopActivityGate(), shopId -> shopId == 7);

        JDeskException error = assertThrows(JDeskException.class, () -> service.delete(
                new ShopCommandService.DeleteShopRequest(7, true), null));

        assertEquals(ErrorCode.INVALID_REQUEST, error.code());
        assertEquals(new ShopCommandService.ShopError("shop_busy"), error.details());
        assertEquals(0, store.calls);
    }

    @Test
    void mutationsAreSerialized() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        FakeStore store = new FakeStore(state(7, "Main", true)) {
            @Override
            public ShopCommandService.ShopState create(String name, String apiKey) {
                enterMutation(firstEntered, bothEntered, release, concurrent, maximum);
                return super.create(name, apiKey);
            }

            @Override
            public ShopCommandService.ShopState select(int shopId) {
                enterMutation(firstEntered, bothEntered, release, concurrent, maximum);
                return super.select(shopId);
            }
        };
        ShopCommandService service = service(store);

        CompletableFuture<?> create = CompletableFuture.supplyAsync(() -> service.create(
                new ShopCommandService.CreateShopRequest("Main", SECRET), null).toCompletableFuture().join());
        assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
        CompletableFuture<?> select = CompletableFuture.supplyAsync(() -> service.select(
                new ShopCommandService.SelectShopRequest(7), null).toCompletableFuture().join());
        assertFalse(bothEntered.await(100, TimeUnit.MILLISECONDS));
        release.countDown();

        create.join();
        select.join();
        assertEquals(1, maximum.get());
    }

    @Test
    void maliciousStoreResponseAndUnexpectedFailureCannotLeakSecret() {
        FakeStore malformed = new FakeStore(new ShopCommandService.ShopState(
                List.of(new ShopCommandService.ManagedShopSummary(7, "bad\n" + SECRET, true)),
                true,
                7));
        assertInternal(() -> service(malformed).list(new ShopCommandService.ShopListRequest(), null));

        FakeStore failing = new FakeStore(state(7, "Main", true)) {
            @Override
            public ShopCommandService.ShopState list() {
                throw new IllegalStateException("database failed with " + SECRET);
            }
        };
        JDeskException error = assertThrows(JDeskException.class, () -> service(failing)
                .list(new ShopCommandService.ShopListRequest(), null));
        assertEquals(ErrorCode.INTERNAL_ERROR, error.code());
        assertFalse(error.publicMessage().contains(SECRET));
        assertNull(error.details());
        assertNull(error.getCause());
    }

    private static void enterMutation(
            CountDownLatch firstEntered,
            CountDownLatch bothEntered,
            CountDownLatch release,
            AtomicInteger concurrent,
            AtomicInteger maximum) {
        int running = concurrent.incrementAndGet();
        maximum.accumulateAndGet(running, Math::max);
        bothEntered.countDown();
        firstEntered.countDown();
        try {
            assertTrue(release.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        } finally {
            concurrent.decrementAndGet();
        }
    }

    private static ShopCommandService service(FakeStore store) {
        return new ShopCommandService(store, new ShopActivityGate(), ignored -> false);
    }

    private static ShopCommandService.ShopState state(int id, String name, boolean tokenConfigured) {
        return new ShopCommandService.ShopState(
                List.of(new ShopCommandService.ManagedShopSummary(id, name, tokenConfigured)), true, id);
    }

    private static void assertInvalid(Runnable action) {
        JDeskException error = assertThrows(JDeskException.class, action::run);
        assertEquals(ErrorCode.INVALID_REQUEST, error.code());
    }

    private static void assertInternal(Runnable action) {
        JDeskException error = assertThrows(JDeskException.class, action::run);
        assertEquals(ErrorCode.INTERNAL_ERROR, error.code());
    }

    private static class FakeStore implements ShopCommandService.ShopStore {
        private final ShopCommandService.ShopState state;
        private int calls;
        private String createdName;
        private String createdToken;
        private String updatedToken;

        private FakeStore(ShopCommandService.ShopState state) {
            this.state = state;
        }

        @Override
        public ShopCommandService.ShopState list() {
            calls++;
            return state;
        }

        @Override
        public ShopCommandService.ShopState create(String name, String apiKey) {
            calls++;
            createdName = name;
            createdToken = apiKey;
            return state;
        }

        @Override
        public ShopCommandService.ShopState update(int shopId, String name, String apiKey) {
            calls++;
            updatedToken = apiKey;
            return state;
        }

        @Override
        public ShopCommandService.ShopState select(int shopId) {
            calls++;
            return state;
        }

        @Override
        public ShopCommandService.ShopState delete(int shopId) {
            calls++;
            return state;
        }
    }
}
