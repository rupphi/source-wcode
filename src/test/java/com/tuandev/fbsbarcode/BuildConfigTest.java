package com.tuandev.fbsbarcode;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuildConfigTest {

    @Test
    void getAppVersionReturnsNonNull() {
        String version = BuildConfig.getAppVersion();
        assertNotNull(version);
        assertFalse(version.isEmpty());
    }

    @Test
    void getUpdateUrlReturnsNonNull() {
        String url = BuildConfig.getUpdateUrl();
        assertNotNull(url);
        assertFalse(url.isEmpty());
    }

    @Test
    void getUpdateUrlReturnsValidUrl() {
        String url = BuildConfig.getUpdateUrl();
        assertTrue(url.startsWith("http"));
    }
}
