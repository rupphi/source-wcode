package com.tuandev.fbsbarcode.jdesk.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tuandev.fbsbarcode.features.dashboard.DashboardKpis;
import com.tuandev.fbsbarcode.models.Shop;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class WorkspaceCommandServiceTest {
    private static final String SECRET = "wb-secret-that-must-never-cross-the-bridge";

    @Test
    void bootstrapReturnsSanitizedShopsAndSelectedShop() {
        WorkspaceCommandService service = service(
                List.of(new Shop(7, "Main shop", SECRET)),
                new DashboardKpis(12, 3, 2),
                7);

        WorkspaceCommandService.BootstrapResponse response = service
                .bootstrap(new WorkspaceCommandService.BootstrapRequest(), null)
                .toCompletableFuture()
                .join();

        assertEquals("WCode", response.app().name());
        assertEquals("1.1.7", response.app().version());
        assertEquals(Optional.of(7), response.selectedShopId());
        assertEquals(List.of(new WorkspaceCommandService.ShopSummary(7, "Main shop", true)), response.shops());
        assertFalse(response.toString().contains(SECRET));
    }

    @Test
    void dashboardValidatesOwnershipAndReturnsNonSecretKpis() {
        WorkspaceCommandService service = service(
                List.of(new Shop(7, "Main shop", SECRET)),
                new DashboardKpis(12, 3, 2),
                7);

        WorkspaceCommandService.DashboardResponse response = service
                .loadDashboard(new WorkspaceCommandService.DashboardRequest(7), null)
                .toCompletableFuture()
                .join();

        assertEquals(new WorkspaceCommandService.DashboardResponse(7, 12, 3, 2), response);
        assertFalse(response.toString().contains(SECRET));
    }

    @Test
    void dashboardRejectsInvalidOrUnownedShopBeforeRepositoryAccess() {
        AtomicBoolean repositoryCalled = new AtomicBoolean();
        WorkspaceCommandService service = new WorkspaceCommandService(
                () -> List.of(new Shop(7, "Main shop", SECRET)),
                ignored -> {
                    repositoryCalled.set(true);
                    return new DashboardKpis(0, 0, 0);
                },
                () -> 7,
                () -> "1.1.7");

        JDeskException zero = assertThrows(
                JDeskException.class,
                () -> service.loadDashboard(new WorkspaceCommandService.DashboardRequest(0), null));
        JDeskException unknown = assertThrows(
                JDeskException.class,
                () -> service.loadDashboard(new WorkspaceCommandService.DashboardRequest(9), null));

        assertEquals(ErrorCode.INVALID_REQUEST, zero.code());
        assertEquals(ErrorCode.INVALID_REQUEST, unknown.code());
        assertFalse(repositoryCalled.get());
    }

    @Test
    void unexpectedFailureBecomesSafeErrorWithoutCauseOrRawMessage() {
        WorkspaceCommandService service = new WorkspaceCommandService(
                () -> {
                    throw new IllegalStateException("database failed with " + SECRET);
                },
                ignored -> new DashboardKpis(0, 0, 0),
                () -> null,
                () -> "1.1.7");

        JDeskException error = assertThrows(
                JDeskException.class,
                () -> service.bootstrap(new WorkspaceCommandService.BootstrapRequest(), null));

        assertEquals(ErrorCode.INTERNAL_ERROR, error.code());
        assertTrue(error.publicMessage().startsWith("Operation failed. Reference: "));
        assertFalse(error.publicMessage().contains(SECRET));
        assertNull(error.details());
        assertNull(error.getCause());
    }

    private static WorkspaceCommandService service(
            List<Shop> shops, DashboardKpis kpis, Integer selectedShopId) {
        return new WorkspaceCommandService(
                () -> shops,
                ignored -> kpis,
                () -> selectedShopId,
                () -> "1.1.7");
    }
}
