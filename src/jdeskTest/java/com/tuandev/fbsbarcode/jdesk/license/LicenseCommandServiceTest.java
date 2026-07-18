package com.tuandev.fbsbarcode.jdesk.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tuandev.fbsbarcode.integration.license.LicensePayload;
import com.tuandev.fbsbarcode.integration.license.LicenseState;
import com.tuandev.fbsbarcode.integration.license.LicenseState.LicenseStatus;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.runtime.json.JacksonJsonCodec;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LicenseCommandServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String KEY = "WC-ABCDE-FGHIJ-KLMNO-PQRST";
    private static final String SECRET_FINGERPRINT = "machine-fingerprint-must-not-cross-the-bridge";
    private static final String SECRET_SIGNATURE = "signed-payload-must-not-cross-the-bridge";

    @Test
    void statusReturnsOnlyBoundedLicenseSummaryWithoutKeyOrDeviceMaterial() {
        FakeOperations operations = new FakeOperations(validState());
        LicenseCommandService.StatusResponse response = service(operations).status(null, null)
                .toCompletableFuture().join();

        assertEquals("valid", response.status());
        assertTrue(response.kizAllowed());
        assertTrue(response.hasStoredKey());
        assertEquals("standard", response.plan());
        assertEquals("2026-07-18T00:00:00Z", response.issuedAt());
        assertEquals("2026-08-18T00:00:00Z", response.expiresAt());
        assertEquals("2026-08-01T00:00:00Z", response.offlineGraceEndsAt());
        assertEquals(30, response.daysRemaining());
        String json = new JacksonJsonCodec().encode(response);
        assertFalse(json.contains(KEY));
        assertFalse(json.contains(SECRET_FINGERPRINT));
        assertFalse(json.contains(SECRET_SIGNATURE));
        assertFalse(json.contains("payload"));
    }

    @Test
    void activationNormalizesTheKeyAndNeverEchoesIt() {
        FakeOperations operations = new FakeOperations(LicenseState.of(LicenseStatus.NOT_ACTIVATED));
        operations.activationState = validState();

        LicenseCommandService.ActionResponse response = service(operations).activate(
                        new LicenseCommandService.ActivateRequest("  wc-abcde-fghij-klmno-pqrst  "), null)
                .toCompletableFuture().join();

        assertEquals(KEY, operations.activatedKey.get());
        assertTrue(response.accepted());
        assertEquals("valid", response.license().status());
        assertEquals("", response.errorKind());
        assertFalse(new JacksonJsonCodec().encode(response).contains(KEY));
        JDeskException invalid = assertThrows(JDeskException.class, () -> service(operations).activate(
                new LicenseCommandService.ActivateRequest("not-a-license"), null));
        assertEquals(ErrorCode.INVALID_REQUEST, invalid.code());
    }

    @Test
    void activationMapsNetworkFailureToAnAllowlistedResponse() {
        FakeOperations operations = new FakeOperations(LicenseState.of(LicenseStatus.NOT_ACTIVATED));
        operations.failure = new IOException("raw network " + SECRET_SIGNATURE);

        LicenseCommandService.ActionResponse response = service(operations).activate(
                        new LicenseCommandService.ActivateRequest(KEY), null)
                .toCompletableFuture().join();

        assertFalse(response.accepted());
        assertEquals("network", response.errorKind());
        assertFalse(new JacksonJsonCodec().encode(response).contains(SECRET_SIGNATURE));
    }

    @Test
    void refreshUsesTheExistingOfflineGraceStateAndRedactsPayloadDetails() {
        FakeOperations operations = new FakeOperations(LicenseState.of(LicenseStatus.NOT_ACTIVATED));
        operations.refreshState = new LicenseState(LicenseStatus.OFFLINE_GRACE, payload(), "raw " + SECRET_SIGNATURE);

        LicenseCommandService.StatusResponse response = service(operations).refresh(null, null)
                .toCompletableFuture().join();

        assertEquals("offline_grace", response.status());
        assertTrue(response.kizAllowed());
        assertEquals("offline", response.errorKind());
        assertFalse(new JacksonJsonCodec().encode(response).contains(SECRET_SIGNATURE));
    }

    @Test
    void deactivationRequiresExplicitConfirmationAndClearsTheSafeState() {
        FakeOperations operations = new FakeOperations(validState());
        JDeskException invalid = assertThrows(JDeskException.class, () -> service(operations).deactivate(
                new LicenseCommandService.DeactivateRequest(false), null));
        assertEquals(ErrorCode.INVALID_REQUEST, invalid.code());

        LicenseCommandService.ActionResponse response = service(operations).deactivate(
                        new LicenseCommandService.DeactivateRequest(true), null)
                .toCompletableFuture().join();

        assertTrue(response.accepted());
        assertEquals("not_activated", response.license().status());
        assertFalse(response.license().hasStoredKey());
        assertEquals(1, operations.deactivations);
    }

    @Test
    void everyPublicStatusAndErrorIsAllowlisted() {
        FakeOperations operations = new FakeOperations(LicenseState.of(LicenseStatus.NOT_ACTIVATED));
        LicenseCommandService service = service(operations);
        List<String> expected = List.of(
                "not_activated", "valid", "offline_grace", "expired", "invalid", "device_limit",
                "clock_tampered", "network_error");
        for (int index = 0; index < LicenseStatus.values().length; index++) {
            LicenseStatus status = LicenseStatus.values()[index];
            operations.current = new LicenseState(status, status == LicenseStatus.NOT_ACTIVATED ? null : payload(),
                    "raw " + SECRET_SIGNATURE);
            LicenseCommandService.StatusResponse response = service.status(null, null).toCompletableFuture().join();
            assertEquals(expected.get(index), response.status());
            assertFalse(new JacksonJsonCodec().encode(response).contains(SECRET_SIGNATURE));
        }
    }

    @Test
    void concurrentLicenseMutationsAreSerialized() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        LicenseCommandService.LicenseOperations operations = new LicenseCommandService.LicenseOperations() {
            @Override public LicenseState current() { return validState(); }
            @Override public boolean hasStoredKey() { return true; }
            @Override public LicenseState activate(String key) throws Exception {
                int running = concurrent.incrementAndGet();
                maximum.accumulateAndGet(running, Math::max);
                bothEntered.countDown();
                firstEntered.countDown();
                try {
                    assertTrue(release.await(2, TimeUnit.SECONDS));
                    return validState();
                } finally {
                    concurrent.decrementAndGet();
                }
            }
            @Override public LicenseState refresh() { return validState(); }
            @Override public LicenseState deactivate() { return LicenseState.of(LicenseStatus.NOT_ACTIVATED); }
        };
        LicenseCommandService service = service(operations);

        var first = CompletableFuture.supplyAsync(() -> service.activate(
                new LicenseCommandService.ActivateRequest(KEY), null).toCompletableFuture().join());
        assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
        var second = CompletableFuture.supplyAsync(() -> service.activate(
                new LicenseCommandService.ActivateRequest(KEY), null).toCompletableFuture().join());
        assertFalse(bothEntered.await(100, TimeUnit.MILLISECONDS));
        release.countDown();

        assertTrue(first.join().accepted());
        assertTrue(second.join().accepted());
        assertEquals(1, maximum.get());
    }

    private static LicenseCommandService service(LicenseCommandService.LicenseOperations operations) {
        return new LicenseCommandService(operations, CLOCK);
    }

    private static LicenseState validState() {
        return new LicenseState(LicenseStatus.VALID, payload(), SECRET_SIGNATURE);
    }

    private static LicensePayload payload() {
        return new LicensePayload(
                1, KEY, SECRET_FINGERPRINT, "standard", 5, "valid",
                Instant.parse("2026-07-18T00:00:00Z").toEpochMilli(),
                Instant.parse("2026-08-18T00:00:00Z").toEpochMilli());
    }

    private static final class FakeOperations implements LicenseCommandService.LicenseOperations {
        private LicenseState current;
        private LicenseState activationState;
        private LicenseState refreshState;
        private Exception failure;
        private boolean hasStoredKey;
        private int deactivations;
        private final AtomicReference<String> activatedKey = new AtomicReference<>();

        private FakeOperations(LicenseState current) {
            this.current = current;
            this.activationState = current;
            this.refreshState = current;
            this.hasStoredKey = current.status() != LicenseStatus.NOT_ACTIVATED;
        }

        @Override public LicenseState current() { return current; }
        @Override public boolean hasStoredKey() { return hasStoredKey; }
        @Override public LicenseState activate(String key) throws Exception {
            activatedKey.set(key);
            if (failure != null) throw failure;
            current = activationState;
            hasStoredKey = current.status() != LicenseStatus.NOT_ACTIVATED;
            return current;
        }
        @Override public LicenseState refresh() {
            current = refreshState;
            return current;
        }
        @Override public LicenseState deactivate() {
            deactivations++;
            current = LicenseState.of(LicenseStatus.NOT_ACTIVATED);
            hasStoredKey = false;
            return current;
        }
    }
}
