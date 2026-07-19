package com.tuandev.fbsbarcode.jdesk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jdesk.runtime.boot.NavigationPolicy;
import dev.jdesk.webview.spi.NavigationDecision;
import dev.jdesk.webview.spi.NavigationRequest;
import java.net.URI;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FrontendContentPolicyTest {
    @Test
    void productionUsesOnlyTheBundledApplicationEntry() {
        assertEquals("jdesk://app/index.html", FrontendContentPolicy.BUNDLED_ENTRY);
        assertTrue(FrontendContentPolicy.developmentOrigin(true, true, "http://localhost:5173")
                .isEmpty());
        assertTrue(FrontendContentPolicy.developmentOrigin(
                        false, false, "https://untrusted.invalid")
                .isEmpty());
        assertTrue(FrontendContentPolicy.developmentOrigin(false, false, null).isEmpty());
    }

    @Test
    void packagedProductionRemovesDevelopmentRuntimeAuthority() {
        Properties properties = new Properties();
        properties.setProperty("jdesk.dev", "true");
        properties.setProperty("jdesk.devUrl", "http://localhost:5173");
        properties.setProperty("jdesk.assets.dir", "/untrusted");
        properties.setProperty("jdesk.assets.module", "untrusted.module");
        properties.setProperty("jdesk.assets.classpath", "untrusted");

        FrontendContentPolicy.enforceProductionRuntime(true, properties);

        assertEquals("false", properties.getProperty("jdesk.dev"));
        assertNull(properties.getProperty("jdesk.devUrl"));
        assertNull(properties.getProperty("jdesk.assets.dir"));
        assertNull(properties.getProperty("jdesk.assets.module"));
        assertEquals("web", properties.getProperty("jdesk.assets.classpath"));
    }

    @Test
    void developmentAcceptsOnlyAnExplicitLoopbackHttpOrigin() {
        Properties properties = new Properties();
        properties.setProperty("jdesk.dev", "true");
        properties.setProperty("jdesk.assets.dir", "/development-assets");
        FrontendContentPolicy.enforceProductionRuntime(false, properties);
        assertEquals("true", properties.getProperty("jdesk.dev"));
        assertEquals("/development-assets", properties.getProperty("jdesk.assets.dir"));

        assertEquals(
                Optional.of("http://127.0.0.1:5173"),
                FrontendContentPolicy.developmentOrigin(false, true, "http://127.0.0.1:5173/"));
        assertEquals(
                Optional.of("http://localhost:4173"),
                FrontendContentPolicy.developmentOrigin(false, true, "HTTP://LOCALHOST:4173"));
    }

    @Test
    void developmentFailsClosedForMissingRemoteOrAmbiguousOrigins() {
        for (String candidate : new String[] {
            "",
            "https://localhost:5173",
            "http://localhost",
            "http://localhost:5173/ui",
            "http://localhost:5173/?next=https://untrusted.invalid",
            "http://user@localhost:5173",
            "http://127.0.0.1:5173@untrusted.invalid",
            "http://[::1]:5173",
            "http://untrusted.invalid:5173",
            "not a URI"
        }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> FrontendContentPolicy.developmentOrigin(false, true, candidate),
                    candidate);
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> FrontendContentPolicy.developmentOrigin(false, true, null));
    }

    @Test
    void pinnedJdeskRuntimeBlocksEveryMainFrameOutsideTheBundledOrigin() {
        NavigationPolicy policy = new NavigationPolicy(Set.of("jdesk://app"));

        assertEquals(
                NavigationDecision.ALLOW,
                policy.decide(new NavigationRequest(URI.create("jdesk://app/settings"), true, true)));
        for (String target : new String[] {
            "https://untrusted.invalid/phishing",
            "http://127.0.0.1:5173",
            "file:///tmp/untrusted.html",
            "data:text/html,untrusted",
            "javascript:document.body.textContent='untrusted'"
        }) {
            assertEquals(
                    NavigationDecision.BLOCK,
                    policy.decide(new NavigationRequest(URI.create(target), true, true)),
                    target);
        }
    }
}
