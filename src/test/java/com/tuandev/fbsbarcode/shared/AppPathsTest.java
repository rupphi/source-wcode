package com.tuandev.fbsbarcode.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppPathsTest {
    @TempDir Path tempDir;

    @Test
    void appDataOverrideDisablesLegacyUserDirectoryScanning() {
        String previous = System.getProperty("wcode.appdata.dir");
        Path isolatedAppData = tempDir.resolve("isolated");
        System.setProperty("wcode.appdata.dir", isolatedAppData.toString());
        try {
            assertEquals(isolatedAppData, AppPaths.appDataDir());
            assertTrue(AppPaths.legacyAppDataDirs().isEmpty());
        } finally {
            if (previous == null) {
                System.clearProperty("wcode.appdata.dir");
            } else {
                System.setProperty("wcode.appdata.dir", previous);
            }
        }
    }
}
