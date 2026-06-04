package com.tuandev.fbsbarcode.features.kiz;

import com.tuandev.fbsbarcode.config.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class KizServiceTest {
    private static final char GS = 0x1D;

    @TempDir
    Path tempDir;

    @AfterEach
    void clearAppDataOverride() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void scannerSafeCodeRemovesOnlyLeadingGs() {
        String raw = GS + "010465039888513821ABC" + GS + "91XYZ" + GS + "92SIGNATURE";

        assertEquals("010465039888513821ABC" + GS + "91XYZ" + GS + "92SIGNATURE",
                KizService.scannerSafeCode(raw));
    }

    @Test
    void scannerSafeCodeKeepsInternalGsAndPlainCodes() {
        String raw = "010465039888513821ABC" + GS + "91XYZ" + GS + "92SIGNATURE";

        assertEquals(raw, KizService.scannerSafeCode(raw));
        assertEquals("", KizService.scannerSafeCode(""));
        assertNull(KizService.scannerSafeCode(null));
    }

    @Test
    void addKizsStoresRawCode() throws Exception {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        Database.initDatabase();
        try (Connection conn = Database.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("INSERT INTO shops(id, name, api_key) VALUES (1, 'Shop', 'token')");
            st.execute("INSERT INTO categories(id, name) VALUES (10, 'Shoes')");
        }

        String scannerSafeKiz = "010465039888513821ABC" + GS + "91XYZ" + GS + "92SIGNATURE";

        KizService.addKizs(1, 10, java.util.List.of(GS + scannerSafeKiz));

        assertEquals(GS + scannerSafeKiz, KizService.getKizs(1, 10, 1).getFirst().getCode());
    }
}
