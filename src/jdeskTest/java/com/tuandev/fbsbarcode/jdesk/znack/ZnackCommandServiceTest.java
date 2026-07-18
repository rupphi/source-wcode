package com.tuandev.fbsbarcode.jdesk.znack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tuandev.fbsbarcode.integration.znack.ZnackModels.Settings;
import com.tuandev.fbsbarcode.models.Shop;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.runtime.json.JacksonJsonCodec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ZnackCommandServiceTest {
    private static final String GTIN = "04601234567890";
    private static final String SECOND_GTIN = "04601234567891";
    private static final String SECRET = "private-path-and-selector-must-not-cross-the-bridge";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void returnsOnlyEditableSettingsAndSanitizedCertificateSummary() {
        FakeSource source = new FakeSource();
        source.settings = settings("OMS-7", "CONNECTION-7", "DOC-1", "18.07.2026", true);

        ZnackCommandService.SettingsResponse response = service(source)
                .settings(new ZnackCommandService.SettingsRequest(7), null)
                .toCompletableFuture()
                .join();

        assertEquals("OMS-7", response.omsId());
        assertEquals("CONNECTION-7", response.omsConnection());
        assertEquals("DOC-1", response.documentNumber());
        assertEquals("18.07.2026", response.documentDate());
        assertTrue(response.autoIntroduction());
        assertEquals("VERIFIED", response.signatureStatus());
        assertEquals("ООО Маркировка", response.certificateLabel());
        assertEquals("2027-07-18", response.certificateValidTo());
        assertEquals(64, response.version().length());
        assertFalse(response.toString().contains(SECRET));

        String json = new JacksonJsonCodec().encode(response);
        assertFalse(json.contains("trueApiBaseUrl"));
        assertFalse(json.contains("signerCertificate"));
        assertFalse(json.contains("thumbprint"));
        assertFalse(json.contains(SECRET));
    }

    @Test
    void savesEditableSettingsByMergingTheFreshPrivateSnapshot() {
        FakeSource source = new FakeSource();
        source.settings = settings("OMS-7", "CONNECTION-7", "", "", false);
        ZnackCommandService service = service(source);
        String version = service.settings(new ZnackCommandService.SettingsRequest(7), null)
                .toCompletableFuture().join().version();

        ZnackCommandService.SettingsResponse response = service.saveSettings(
                        new ZnackCommandService.SaveSettingsRequest(
                                7, "OMS-8", " CONNECTION-8 ", " DOC-8 ", "18.07.2026", true, version),
                        null)
                .toCompletableFuture()
                .join();

        Settings saved = source.saved.get();
        assertEquals("OMS-8", saved.omsId());
        assertEquals("CONNECTION-8", saved.omsConnection());
        assertEquals("DOC-8", saved.documentNumber());
        assertEquals("18.07.2026", saved.documentDate());
        assertTrue(saved.autoIntroduction());
        assertEquals(SECRET + "/cryptcp", saved.cryptcpPath());
        assertEquals(SECRET + "-selector", saved.signerCertificate());
        assertEquals("OMS-8", response.omsId());
        assertFalse(version.equals(response.version()));

        JDeskException stale = assertThrows(JDeskException.class, () -> service.saveSettings(
                new ZnackCommandService.SaveSettingsRequest(
                        7, "OMS-9", "CONNECTION-9", "", "", false, version), null));
        assertEquals(ErrorCode.INVALID_REQUEST, stale.code());
        assertEquals(1, source.saves.get());
    }

    @Test
    void rejectsIncompleteOrInvalidSettingsBeforeMutation() {
        FakeSource source = new FakeSource();
        ZnackCommandService service = service(source);
        String version = service.settings(new ZnackCommandService.SettingsRequest(7), null)
                .toCompletableFuture().join().version();
        List<ZnackCommandService.SaveSettingsRequest> invalid = List.of(
                new ZnackCommandService.SaveSettingsRequest(7, "", "connection", "", "", false, version),
                new ZnackCommandService.SaveSettingsRequest(7, "oms", "", "", "", false, version),
                new ZnackCommandService.SaveSettingsRequest(7, "oms", "connection", "DOC", "", false, version),
                new ZnackCommandService.SaveSettingsRequest(7, "oms", "connection", "DOC", "31.02.2026", false, version),
                new ZnackCommandService.SaveSettingsRequest(7, "oms\n", "connection", "", "", false, version));

        for (ZnackCommandService.SaveSettingsRequest request : invalid) {
            JDeskException error = assertThrows(JDeskException.class, () -> service.saveSettings(request, null));
            assertEquals(ErrorCode.INVALID_REQUEST, error.code());
        }
        assertEquals(0, source.saves.get());
    }

    @Test
    void returnsABoundedProductPageForTheRequestedVisibility() {
        FakeSource source = new FakeSource();
        source.categories = List.of(" Shoes ", "Clothes", "shoes");
        for (int index = 0; index < 11; index++) {
            source.products.add(new ZnackCommandService.ProductRow(
                    index == 0 ? GTIN : String.format("046012345678%02d", index),
                    index == 0 ? " Product \n One " : "Product " + index,
                    index == 0 ? " Shoes " : "Clothes",
                    " 6403 ",
                    index == 0 ? " UNIT " : "",
                    index == 0 ? Boolean.TRUE : null,
                    index == 0 ? Boolean.FALSE : null,
                    Instant.parse("2026-07-18T00:00:00Z"),
                    true));
        }

        ZnackCommandService.ProductsResponse response = service(source).products(
                        new ZnackCommandService.ProductsRequest(
                                7, " product ", List.of(" Shoes ", "shoes"), true, 2, 10),
                        null)
                .toCompletableFuture()
                .join();

        assertEquals(new ZnackCommandService.ProductQuery(
                7, "product", List.of("Shoes"), true, 11, 10), source.query.get());
        assertEquals(List.of("Shoes", "Clothes"), response.availableCategories());
        assertEquals(10, response.items().size());
        assertTrue(response.hasMore());
        ZnackCommandService.ProductItem first = response.items().getFirst();
        assertEquals("Product One", first.productName());
        assertEquals("Shoes", first.category());
        assertEquals("6403", first.tnVed());
        assertEquals("UNIT", first.cisType());
        assertEquals("READY", first.goodMarkStatus());
        assertEquals("NOT_READY", first.goodTurnStatus());
        assertTrue(first.deleted());
    }

    @Test
    void validatesAndAtomicallyChangesProductVisibility() {
        FakeSource source = new FakeSource();
        ZnackCommandService.VisibilityResponse response = service(source).setProductVisibility(
                        new ZnackCommandService.SetProductVisibilityRequest(
                                7, List.of(" 04601234567890 ", SECOND_GTIN), true),
                        null)
                .toCompletableFuture()
                .join();

        assertEquals(List.of(GTIN, SECOND_GTIN), source.visibilityGtins.get());
        assertTrue(source.visibilityDeleted.get());
        assertEquals(2, response.changed());

        source.visibilityFailure = new ZnackCommandService.VisibilityConflictException();
        JDeskException conflict = assertThrows(JDeskException.class, () -> service(source).setProductVisibility(
                new ZnackCommandService.SetProductVisibilityRequest(7, List.of(GTIN), false), null));
        assertEquals(ErrorCode.INVALID_REQUEST, conflict.code());
        assertFalse(conflict.publicMessage().contains(GTIN));

        List<List<String>> invalid = List.of(
                List.of(),
                List.of(GTIN, GTIN),
                List.of("02900699308808"),
                List.of("bad"));
        for (List<String> gtins : invalid) {
            JDeskException error = assertThrows(JDeskException.class, () -> service(source).setProductVisibility(
                    new ZnackCommandService.SetProductVisibilityRequest(7, gtins, true), null));
            assertEquals(ErrorCode.INVALID_REQUEST, error.code());
        }
    }

    @Test
    void rejectsMalformedAndUnownedReadsAndRedactsSourceFailures() {
        FakeSource source = new FakeSource();
        ZnackCommandService service = service(source);
        List<ZnackCommandService.ProductsRequest> invalid = List.of(
                new ZnackCommandService.ProductsRequest(0, "", List.of(), false, 1, 10),
                new ZnackCommandService.ProductsRequest(9, "", List.of(), false, 1, 10),
                new ZnackCommandService.ProductsRequest(7, "bad\nquery", List.of(), false, 1, 10),
                new ZnackCommandService.ProductsRequest(7, "", null, false, 1, 10),
                new ZnackCommandService.ProductsRequest(7, "", List.of(), false, 0, 10),
                new ZnackCommandService.ProductsRequest(7, "", List.of(), false, 1, 101));
        for (ZnackCommandService.ProductsRequest request : invalid) {
            JDeskException error = assertThrows(JDeskException.class, () -> service.products(request, null));
            assertEquals(ErrorCode.INVALID_REQUEST, error.code());
        }
        assertEquals(0, source.productReads.get());

        source.productsFailure = new IllegalStateException("sqlite " + SECRET);
        JDeskException opaque = assertThrows(JDeskException.class, () -> service(source).products(
                new ZnackCommandService.ProductsRequest(7, "", List.of(), false, 1, 10), null));
        assertEquals(ErrorCode.INTERNAL_ERROR, opaque.code());
        assertFalse(opaque.publicMessage().contains(SECRET));
        assertNull(opaque.details());
        assertNull(opaque.getCause());
    }

    private static ZnackCommandService service(FakeSource source) {
        return new ZnackCommandService(
                () -> List.of(new Shop(7, "Main", SECRET)), source, CLOCK);
    }

    private static Settings settings(
            String omsId,
            String omsConnection,
            String documentNumber,
            String documentDate,
            boolean autoIntroduction) {
        return new Settings(
                "https://private.example/" + SECRET,
                "https://private-suz.example/" + SECRET,
                omsId,
                omsConnection,
                "7700000000",
                "7700000000",
                "7700000000",
                SECRET + "/signer",
                SECRET + "-selector",
                "[\"" + SECRET + "\"]",
                documentNumber,
                documentDate,
                SECRET + "/pdf",
                autoIntroduction,
                SECRET + "/cert-list",
                "[\"" + SECRET + "\"]",
                "{\"selector\":\"" + SECRET + "-selector\",\"thumbprint\":\"" + SECRET
                        + "\",\"subject\":\"ООО Маркировка\",\"validTo\":\"2027-07-18T00:00:00Z\"}",
                Instant.parse("2026-07-17T00:00:00Z"),
                SECRET + "/certmgr",
                SECRET + "/cryptcp",
                SECRET + "/csptest",
                60,
                "",
                Settings.DEFAULT_DOCUMENT_TYPE);
    }

    private static final class FakeSource implements ZnackCommandService.ZnackDataSource {
        private Settings settings = Settings.empty();
        private final AtomicReference<Settings> saved = new AtomicReference<>();
        private final AtomicInteger saves = new AtomicInteger();
        private List<String> categories = List.of();
        private final List<ZnackCommandService.ProductRow> products = new ArrayList<>();
        private final AtomicReference<ZnackCommandService.ProductQuery> query = new AtomicReference<>();
        private final AtomicInteger productReads = new AtomicInteger();
        private RuntimeException productsFailure;
        private final AtomicReference<List<String>> visibilityGtins = new AtomicReference<>();
        private final AtomicReference<Boolean> visibilityDeleted = new AtomicReference<>();
        private RuntimeException visibilityFailure;

        @Override
        public Settings settings(int shopId) {
            return settings;
        }

        @Override
        public void saveSettings(int shopId, Settings value) {
            saves.incrementAndGet();
            saved.set(value);
            settings = value;
        }

        @Override
        public List<String> categories(int shopId, boolean deleted) {
            return categories;
        }

        @Override
        public List<ZnackCommandService.ProductRow> products(ZnackCommandService.ProductQuery request) {
            productReads.incrementAndGet();
            query.set(request);
            if (productsFailure != null) throw productsFailure;
            return products;
        }

        @Override
        public void setProductVisibility(int shopId, String shopName, List<String> gtins, boolean deleted) {
            if (visibilityFailure != null) throw visibilityFailure;
            visibilityGtins.set(gtins);
            visibilityDeleted.set(deleted);
        }
    }
}
