package com.tuandev.fbsbarcode.jdesk.packing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tuandev.fbsbarcode.features.packing.PackingWorkflow;
import com.tuandev.fbsbarcode.integration.wb.WbSupplySummary;
import com.tuandev.fbsbarcode.jdesk.shop.ShopActivityGate;
import com.tuandev.fbsbarcode.models.Order;
import com.tuandev.fbsbarcode.models.Shop;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PackingMutationCommandServiceTest {
    private static final long PRECISE_ORDER_ID = 9_007_199_254_741_001L;
    private static final String SECRET = "packing-wb-secret";
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-19T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void preparesAndExecutesOneUseCreateWithExactOrderIds() {
        FakeMutations mutations = new FakeMutations();
        PackingMutationCommandService service = service(board(
                List.of(order(PRECISE_ORDER_ID, true), order(102, false)),
                List.of(openSupply())), mutations);

        PackingMutationCommandService.MutationPreview preview = service
                .prepareCreate(new PackingMutationCommandService.PrepareCreateRequest(
                        7, " Shipment 19.07 ", List.of(Long.toString(PRECISE_ORDER_ID), "102")), null)
                .toCompletableFuture()
                .join();

        assertEquals("create", preview.action());
        assertEquals("Shipment 19.07", preview.supplyName());
        assertEquals(2, preview.itemCount());
        assertEquals(1, preview.kizCount());
        assertTrue(preview.ready());
        assertFalse(preview.previewId().isBlank());
        assertFalse(preview.toString().contains(SECRET));
        assertEquals(0, mutations.calls.get());

        PackingMutationCommandService.MutationReceipt receipt = service
                .execute(new PackingMutationCommandService.ExecuteMutationRequest(
                        7, preview.previewId(), true), null)
                .toCompletableFuture()
                .join();

        assertEquals("create", receipt.action());
        assertEquals("SUP-NEW", receipt.supplyId());
        assertEquals(List.of(PRECISE_ORDER_ID, 102L), mutations.orderIds);
        assertEquals(1, mutations.calls.get());
        assertKind("preview_invalid", assertThrows(JDeskException.class, () -> service.execute(
                new PackingMutationCommandService.ExecuteMutationRequest(7, preview.previewId(), true), null)));
        assertEquals(1, mutations.calls.get());
    }

    @Test
    void addPreviewIsBoundToCurrentOpenSupplyAndFreshSelection() {
        FakeMutations mutations = new FakeMutations();
        List<Order> orders = new ArrayList<>(List.of(order(101, false)));
        PackingMutationCommandService service = service(
                shop -> board(orders, List.of(openSupply())), mutations);
        PackingMutationCommandService.MutationPreview preview = service
                .prepareAdd(new PackingMutationCommandService.PrepareAddRequest(
                        7, " SUP-OPEN ", List.of("101")), null)
                .toCompletableFuture()
                .join();

        orders.clear();

        assertKind("state_changed", assertThrows(JDeskException.class, () -> service.execute(
                new PackingMutationCommandService.ExecuteMutationRequest(7, preview.previewId(), true), null)));
        assertEquals(0, mutations.calls.get());
    }

    @Test
    void deliveryPreviewExposesOnlyAllowlistedPrintAndKizBlockers() {
        FakeMutations mutations = new FakeMutations();
        mutations.readiness = new PackingMutationCommandService.DeliveryReadiness(
                false, false, false, List.of("labels_missing", "kiz_missing"));
        PackingMutationCommandService service = service(
                board(List.of(), List.of(openSupply())), mutations);

        PackingMutationCommandService.MutationPreview preview = service
                .prepareDeliver(new PackingMutationCommandService.PrepareDeliverRequest(7, "SUP-OPEN"), null)
                .toCompletableFuture()
                .join();

        assertFalse(preview.ready());
        assertEquals(List.of("labels_missing", "kiz_missing"), preview.blockers());
        assertKind("preflight_blocked", assertThrows(JDeskException.class, () -> service.execute(
                new PackingMutationCommandService.ExecuteMutationRequest(7, preview.previewId(), true), null)));
        assertEquals(0, mutations.calls.get());
    }

    @Test
    void rejectsUnconfirmedCrossShopAndMalformedRequestsBeforeMutation() {
        FakeMutations mutations = new FakeMutations();
        PackingMutationCommandService service = new PackingMutationCommandService(
                () -> List.of(new Shop(7, "Main", SECRET), new Shop(8, "Second", SECRET)),
                shop -> board(List.of(order(101, false)), List.of(openSupply())),
                mutations,
                CLOCK,
                new ShopActivityGate());
        PackingMutationCommandService.MutationPreview preview = service
                .prepareCreate(new PackingMutationCommandService.PrepareCreateRequest(
                        7, "Shipment", List.of("101")), null)
                .toCompletableFuture()
                .join();

        assertKind("confirmation_required", assertThrows(JDeskException.class, () -> service.execute(
                new PackingMutationCommandService.ExecuteMutationRequest(7, preview.previewId(), false), null)));
        assertKind("preview_invalid", assertThrows(JDeskException.class, () -> service.execute(
                new PackingMutationCommandService.ExecuteMutationRequest(8, preview.previewId(), true), null)));
        assertKind("invalid_selection", assertThrows(JDeskException.class, () -> service.prepareCreate(
                new PackingMutationCommandService.PrepareCreateRequest(7, "Shipment", List.of("101", "101")),
                null)));
        assertKind("invalid_selection", assertThrows(JDeskException.class, () -> service.prepareCreate(
                new PackingMutationCommandService.PrepareCreateRequest(7, "Shipment", List.of("9007199254740993x")),
                null)));
        assertEquals(0, mutations.calls.get());
    }

    @Test
    void expiresPreviewWithoutCallingTheMutationRunner() {
        FakeMutations mutations = new FakeMutations();
        MutableClock clock = new MutableClock(Instant.parse("2026-07-19T10:00:00Z"));
        PackingMutationCommandService service = new PackingMutationCommandService(
                () -> List.of(new Shop(7, "Main", SECRET)),
                shop -> board(List.of(order(101, false)), List.of(openSupply())),
                mutations,
                clock,
                new ShopActivityGate());
        PackingMutationCommandService.MutationPreview preview = service
                .prepareCreate(new PackingMutationCommandService.PrepareCreateRequest(
                        7, "Shipment", List.of("101")), null)
                .toCompletableFuture()
                .join();

        clock.advance(Duration.ofMinutes(11));

        assertKind("preview_invalid", assertThrows(JDeskException.class, () -> service.execute(
                new PackingMutationCommandService.ExecuteMutationRequest(7, preview.previewId(), true), null)));
        assertEquals(0, mutations.calls.get());
    }

    private static PackingMutationCommandService service(
            PackingWorkflow.PackingBoard board, FakeMutations mutations) {
        return service(shop -> board, mutations);
    }

    private static PackingMutationCommandService service(
            PackingMutationCommandService.BoardReader boards, FakeMutations mutations) {
        return new PackingMutationCommandService(
                () -> List.of(new Shop(7, "Main", SECRET)),
                boards,
                mutations,
                CLOCK,
                new ShopActivityGate());
    }

    private static PackingWorkflow.PackingBoard board(
            List<Order> orders, List<WbSupplySummary> preparation) {
        return new PackingWorkflow.PackingBoard(orders, preparation, List.of());
    }

    private static WbSupplySummary openSupply() {
        return new WbSupplySummary("SUP-OPEN", "Open supply", false, false, "2026-07-19T09:00:00Z", 1);
    }

    private static Order order(long id, boolean requiresKiz) {
        Order order = new Order();
        order.setId(id);
        order.setRequiresKiz(requiresKiz);
        return order;
    }

    private static void assertKind(String expected, JDeskException error) {
        assertEquals(ErrorCode.INVALID_REQUEST, error.code());
        PackingMutationCommandService.PackingMutationError details =
                (PackingMutationCommandService.PackingMutationError) error.details();
        assertEquals(expected, details.kind());
        assertFalse(error.publicMessage().contains(SECRET));
    }

    private static final class FakeMutations implements PackingMutationCommandService.MutationRunner {
        private final AtomicInteger calls = new AtomicInteger();
        private List<Long> orderIds = List.of();
        private PackingMutationCommandService.DeliveryReadiness readiness =
                new PackingMutationCommandService.DeliveryReadiness(true, true, true, List.of());

        @Override
        public PackingMutationCommandService.DeliveryReadiness inspect(
                Shop shop, WbSupplySummary supply) {
            return readiness;
        }

        @Override
        public String create(Shop shop, String name, List<Long> selectedOrderIds) {
            calls.incrementAndGet();
            orderIds = List.copyOf(selectedOrderIds);
            return "SUP-NEW";
        }

        @Override
        public void add(Shop shop, String supplyId, List<Long> selectedOrderIds) {
            calls.incrementAndGet();
            orderIds = List.copyOf(selectedOrderIds);
        }

        @Override
        public void deliver(Shop shop, WbSupplySummary supply) {
            calls.incrementAndGet();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
