package com.tuandev.fbsbarcode.jdesk.preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PreferencesCommandServiceTest {
    @Test
    void loadNormalizesLegacyDefaultsWithoutReturningArbitraryConfiguration() {
        FakeStore store = new FakeStore(null, "unexpected-theme");

        PreferencesCommandService.PreferencesResponse response = service(store).load(null, null)
                .toCompletableFuture().join();

        assertEquals("ru", response.language());
        assertEquals("dark", response.theme());
    }

    @Test
    void languageWriteAcceptsOnlyTheFourLegacyLocales() {
        FakeStore store = new FakeStore("ru", "dark");
        PreferencesCommandService service = service(store);

        PreferencesCommandService.PreferencesResponse response = service.setLanguage(
                        new PreferencesCommandService.SetLanguageRequest(" vi "), null)
                .toCompletableFuture().join();

        assertEquals("vi", response.language());
        assertEquals(List.of("vi"), store.languageWrites);
        assertInvalid(() -> service.setLanguage(
                new PreferencesCommandService.SetLanguageRequest("fr"), null));
        assertInvalid(() -> service.setLanguage(null, null));
    }

    @Test
    void themeWritePersistsTheModeIncludingRollbackSafeSystem() {
        FakeStore store = new FakeStore("en", "light");
        PreferencesCommandService service = service(store);

        PreferencesCommandService.PreferencesResponse response = service.setTheme(
                        new PreferencesCommandService.SetThemeRequest(" SYSTEM "), null)
                .toCompletableFuture().join();

        assertEquals("en", response.language());
        assertEquals("system", response.theme());
        assertEquals(List.of("system"), store.themeWrites);
        assertInvalid(() -> service.setTheme(
                new PreferencesCommandService.SetThemeRequest("amoled"), null));
        assertInvalid(() -> service.setTheme(null, null));
    }

    @Test
    void everyPersistedValueIsNormalizedBeforeCrossingTheBridge() {
        FakeStore store = new FakeStore("ZH", "LIGHT");
        PreferencesCommandService.PreferencesResponse response = service(store).load(null, null)
                .toCompletableFuture().join();

        assertEquals("zh", response.language());
        assertEquals("light", response.theme());

        store.language = "x".repeat(2_000);
        store.theme = "secret-config-value";
        response = service(store).load(null, null).toCompletableFuture().join();
        assertEquals("ru", response.language());
        assertEquals("dark", response.theme());
    }

    @Test
    void concurrentPreferenceMutationsAreSerialized() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        FakeStore store = new FakeStore("ru", "dark") {
            @Override public void setLanguage(String language) {
                enterMutation(firstEntered, bothEntered, release, concurrent, maximum);
                super.setLanguage(language);
            }

            @Override public void setTheme(String theme) {
                enterMutation(firstEntered, bothEntered, release, concurrent, maximum);
                super.setTheme(theme);
            }
        };
        PreferencesCommandService service = service(store);

        var language = CompletableFuture.supplyAsync(() -> service.setLanguage(
                new PreferencesCommandService.SetLanguageRequest("en"), null).toCompletableFuture().join());
        assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
        var theme = CompletableFuture.supplyAsync(() -> service.setTheme(
                new PreferencesCommandService.SetThemeRequest("system"), null).toCompletableFuture().join());
        assertFalse(bothEntered.await(100, TimeUnit.MILLISECONDS));
        release.countDown();

        language.join();
        PreferencesCommandService.PreferencesResponse response = theme.join();
        assertEquals("en", response.language());
        assertEquals("system", response.theme());
        assertEquals(1, maximum.get());
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

    private static PreferencesCommandService service(FakeStore store) {
        return new PreferencesCommandService(store);
    }

    private static void assertInvalid(Runnable action) {
        JDeskException error = assertThrows(JDeskException.class, action::run);
        assertEquals(ErrorCode.INVALID_REQUEST, error.code());
    }

    private static class FakeStore implements PreferencesCommandService.PreferencesStore {
        private String language;
        private String theme;
        private final List<String> languageWrites = new ArrayList<>();
        private final List<String> themeWrites = new ArrayList<>();

        private FakeStore(String language, String theme) {
            this.language = language;
            this.theme = theme;
        }

        @Override public String language() { return language; }
        @Override public String theme() { return theme; }
        @Override public void setLanguage(String language) {
            this.language = language;
            languageWrites.add(language);
        }
        @Override public void setTheme(String theme) {
            this.theme = theme;
            themeWrites.add(theme);
        }
    }
}
