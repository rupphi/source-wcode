package com.tuandev.fbsbarcode.integration.update;

import org.junit.jupiter.api.Test;

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
                  "html_url": "https://github.com/tuanworlddev/FBSBarcode/releases/tag/v1.2.3",
                  "assets": [
                    {
                      "name": "FBSBarcode-1.2.3.exe",
                      "browser_download_url": "https://github.com/tuanworlddev/FBSBarcode/releases/download/v1.2.3/FBSBarcode-1.2.3.exe"
                    },
                    {
                      "name": "FBSBarcode-1.2.3.zip",
                      "browser_download_url": "https://github.com/tuanworlddev/FBSBarcode/releases/download/v1.2.3/FBSBarcode-1.2.3.zip"
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
                "https://github.com/tuanworlddev/FBSBarcode/releases/download/v1.2.3/FBSBarcode-1.2.3.exe",
                info.getDownloadUrls().get("exe")
        );
        assertEquals(
                "https://github.com/tuanworlddev/FBSBarcode/releases/download/v1.2.3/FBSBarcode-1.2.3.exe",
                info.getBestDownloadUrl()
        );
    }
}
