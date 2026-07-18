package com.tuandev.fbsbarcode.jdesk.preferences;

import com.tuandev.fbsbarcode.jdesk.SafeCommandExecutor;
import com.tuandev.fbsbarcode.shared.AppLanguage;
import com.tuandev.fbsbarcode.shared.ConfigService;
import com.tuandev.fbsbarcode.shared.I18nService;
import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.RequiresCapability;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/** Bounded jDesk adapter over the two legacy application preference rows. */
public final class PreferencesCommandService {
    private static final Set<String> LANGUAGES = Set.of("ru", "en", "zh", "vi");
    private static final Set<String> THEMES = Set.of("dark", "light", "system");

    private final PreferencesStore store;
    private final Object mutationLock = new Object();

    public PreferencesCommandService() {
        this(new LegacyPreferencesStore());
    }

    PreferencesCommandService(PreferencesStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @DesktopCommand("preferences.load")
    @RequiresCapability("preferences:read")
    public CompletionStage<PreferencesResponse> load(LoadRequest request, InvocationContext context) {
        return SafeCommandExecutor.execute(this::current);
    }

    @DesktopCommand("preferences.setLanguage")
    @RequiresCapability("preferences:write")
    public CompletionStage<PreferencesResponse> setLanguage(
            SetLanguageRequest request, InvocationContext context) {
        String language = requireValue(request == null ? null : request.language(), LANGUAGES, "language");
        return SafeCommandExecutor.execute(() -> {
            requireNotCancelled(context);
            synchronized (mutationLock) {
                store.setLanguage(language);
                return current();
            }
        });
    }

    @DesktopCommand("preferences.setTheme")
    @RequiresCapability("preferences:write")
    public CompletionStage<PreferencesResponse> setTheme(
            SetThemeRequest request, InvocationContext context) {
        String theme = requireValue(request == null ? null : request.theme(), THEMES, "theme");
        return SafeCommandExecutor.execute(() -> {
            requireNotCancelled(context);
            synchronized (mutationLock) {
                store.setTheme(theme);
                return current();
            }
        });
    }

    private PreferencesResponse current() {
        return new PreferencesResponse(
                normalize(store.language(), LANGUAGES, "ru"),
                normalize(store.theme(), THEMES, "dark"));
    }

    private static String requireValue(String candidate, Set<String> allowed, String field) {
        if (candidate == null || candidate.length() > 32) {
            throw SafeCommandExecutor.invalidRequest("A supported " + field + " is required.");
        }
        String normalized = candidate.strip().toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw SafeCommandExecutor.invalidRequest("A supported " + field + " is required.");
        }
        return normalized;
    }

    private static String normalize(String candidate, Set<String> allowed, String fallback) {
        if (candidate == null || candidate.length() > 32) return fallback;
        String normalized = candidate.strip().toLowerCase(Locale.ROOT);
        return allowed.contains(normalized) ? normalized : fallback;
    }

    private static void requireNotCancelled(InvocationContext context) {
        if (context != null && context.isCancelled()) {
            throw new JDeskException(ErrorCode.CANCELLED, "Operation cancelled.", null, null);
        }
    }

    interface PreferencesStore {
        String language();
        String theme();
        void setLanguage(String language);
        void setTheme(String theme);
    }

    public record LoadRequest() {}
    public record SetLanguageRequest(String language) {}
    public record SetThemeRequest(String theme) {}
    public record PreferencesResponse(String language, String theme) {}

    private static final class LegacyPreferencesStore implements PreferencesStore {
        @Override public String language() { return ConfigService.getAppLanguage(); }
        @Override public String theme() { return ConfigService.getConfigValue("app_theme"); }
        @Override public void setLanguage(String language) {
            ConfigService.setAppLanguage(language);
            I18nService.getInstance().setLanguage(AppLanguage.fromCode(language));
        }
        @Override public void setTheme(String theme) { ConfigService.setConfigValue("app_theme", theme); }
    }
}
