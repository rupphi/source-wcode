package com.tuandev.fbsbarcode.integration.znack.signature;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CryptoProCommandRunnerTest {
    @Test void considersArchitectureIndependentCryptoProInstallDirectories() {
        CryptoProCommandRunner runner = new CryptoProCommandRunner();

        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            assertTrue(runner.candidates("cryptcp").contains(Path.of("C:\\Program Files\\Crypto Pro\\CSP", "cryptcp.exe")));
            assertTrue(runner.candidates("cryptcp").contains(Path.of("C:\\Program Files (x86)\\Crypto Pro\\CSP", "cryptcp.exe")));
        } else {
            assertTrue(runner.candidates("cryptcp").contains(Path.of("/opt/cprocsp/bin/cryptcp")));
            assertTrue(runner.candidates("cpconfig").contains(Path.of("/opt/cprocsp/sbin/cpconfig")));
        }
    }
}
