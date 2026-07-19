package com.tuandev.fbsbarcode.jdesk;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/** Keeps production on bundled assets and narrows development to one explicit loopback origin. */
final class FrontendContentPolicy {
    static final String BUNDLED_ENTRY = "jdesk://app/index.html";

    private FrontendContentPolicy() {
    }

    static void enforceProductionRuntime(boolean packagedProduction, Properties properties) {
        Objects.requireNonNull(properties, "properties");
        if (!packagedProduction) {
            return;
        }
        properties.setProperty("jdesk.dev", "false");
        properties.remove("jdesk.devUrl");
        properties.remove("jdesk.assets.dir");
        properties.remove("jdesk.assets.module");
        properties.setProperty("jdesk.assets.classpath", "web");
    }

    static Optional<String> developmentOrigin(
            boolean packagedProduction, boolean enabled, String configuredUrl) {
        if (packagedProduction || !enabled) {
            return Optional.empty();
        }

        URI uri;
        try {
            uri = new URI(configuredUrl);
        } catch (NullPointerException | URISyntaxException exception) {
            throw invalidDevelopmentOrigin(exception);
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        String path = uri.getRawPath();
        String normalizedScheme = scheme == null ? null : scheme.toLowerCase(Locale.ROOT);
        String normalizedHost = host == null ? null : host.toLowerCase(Locale.ROOT);
        boolean loopback = "localhost".equals(normalizedHost) || "127.0.0.1".equals(normalizedHost);
        if (uri.isOpaque()
                || !"http".equals(normalizedScheme)
                || !loopback
                || uri.getPort() < 1
                || uri.getPort() > 65535
                || uri.getUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !(path == null || path.isEmpty() || "/".equals(path))) {
            throw invalidDevelopmentOrigin(null);
        }
        return Optional.of("http://" + normalizedHost + ":" + uri.getPort());
    }

    private static IllegalArgumentException invalidDevelopmentOrigin(Exception cause) {
        return new IllegalArgumentException(
                "jdesk.devUrl must be an explicit http://127.0.0.1:<port> or http://localhost:<port> origin",
                cause);
    }
}
