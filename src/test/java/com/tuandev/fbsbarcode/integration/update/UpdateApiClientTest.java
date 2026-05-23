package com.tuandev.fbsbarcode.integration.update;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class UpdateApiClientTest {

    @Test
    void parseGitHubReleaseMapsVersionChangelogAndAssets() {
        String body = """
                {
                  "tag_name": "v1.2.3",
                  "published_at": "2026-05-11T00:00:00Z",
                  "body": "Bug fixes and improvements",
                  "html_url": "https://github.com/tuanworlddev/WCode/releases/tag/v1.2.3",
                  "assets": [
                    {
                      "name": "WCode.exe",
                      "browser_download_url": "https://github.com/tuanworlddev/WCode/releases/download/v1.2.3/WCode.exe"
                    },
                    {
                      "name": "WCode-portable.zip",
                      "browser_download_url": "https://github.com/tuanworlddev/WCode/releases/download/v1.2.3/WCode-portable.zip"
                    }
                  ]
                }
                """;

        UpdateInfo info = UpdateApiClient.parseGitHubRelease(body);

        assertNotNull(info);
        assertEquals("1.2.3", info.getVersion());
        assertEquals("2026-05-11T00:00:00Z", info.getReleaseDate());
        assertEquals("Bug fixes and improvements", info.getChangelog());
        assertEquals(
                "https://github.com/tuanworlddev/WCode/releases/download/v1.2.3/WCode.exe",
                info.getDownloadUrls().get("exe")
        );
        assertWithOs(
                "Windows 11",
                "https://github.com/tuanworlddev/WCode/releases/download/v1.2.3/WCode.exe",
                info
        );
    }

    @Test
    void bestDownloadUrlPrefersInstallerForCurrentOperatingSystem() {
        UpdateInfo info = new UpdateInfo();
        info.setDownloadUrls(Map.of(
                "exe", "https://example.com/WCode.exe",
                "msi", "https://example.com/WCode.msi",
                "zip", "https://example.com/WCode-portable.zip",
                "dmg", "https://example.com/WCode-mac-arm64.dmg",
                "deb", "https://example.com/WCode-linux-amd64.deb",
                "release", "https://example.com/releases/latest"
        ));

        assertWithOs("Windows 11", "https://example.com/WCode.exe", info);
        assertWithOs("Mac OS X", "https://example.com/WCode-mac-arm64.dmg", info);
        assertWithOs("Linux", "https://example.com/WCode-linux-amd64.deb", info);
    }

    @Test
    void bestDownloadUrlFallsBackWhenPlatformAssetIsMissing() {
        UpdateInfo info = new UpdateInfo();
        info.setDownloadUrls(Map.of(
                "release", "https://example.com/releases/latest",
                "zip", "https://example.com/WCode-portable.zip"
        ));

        assertWithOs("Mac OS X", "https://example.com/releases/latest", info);
    }

    private static void assertWithOs(String osName, String expectedUrl, UpdateInfo info) {
        String originalOs = System.getProperty("os.name");
        try {
            System.setProperty("os.name", osName);
            assertEquals(expectedUrl, info.getBestDownloadUrl());
        } finally {
            if (originalOs == null) {
                System.clearProperty("os.name");
            } else {
                System.setProperty("os.name", originalOs);
            }
        }
    }
}
