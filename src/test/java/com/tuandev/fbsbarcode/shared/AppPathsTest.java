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

    @Test
    void windowsSeparatesCurrentDataFromTheLegacyInstallerDirectory() {
        String previousOs = System.getProperty("os.name");
        String previousOverride = System.getProperty("wcode.appdata.dir");
        System.setProperty("os.name", "Windows 11");
        System.clearProperty("wcode.appdata.dir");
        try {
            assertEquals("WCodeData", AppPaths.appDataDir().getFileName().toString());
            assertTrue(AppPaths.legacyAppDataDirs().stream()
                    .anyMatch(path -> path.getFileName().toString().equals("WCode")));
        } finally {
            if (previousOs == null) {
                System.clearProperty("os.name");
            } else {
                System.setProperty("os.name", previousOs);
            }
            if (previousOverride == null) {
                System.clearProperty("wcode.appdata.dir");
            } else {
                System.setProperty("wcode.appdata.dir", previousOverride);
            }
        }
    }
}
