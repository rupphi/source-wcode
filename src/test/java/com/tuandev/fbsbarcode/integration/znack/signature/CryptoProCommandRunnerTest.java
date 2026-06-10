package com.tuandev.fbsbarcode.integration.znack.signature;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CryptoProCommandRunnerTest {
    @Test void considersArchitectureIndependentCryptoProInstallDirectories() {
        CryptoProCommandRunner runner = new CryptoProCommandRunner();

        assertTrue(runner.candidates("cryptcp").contains(Path.of("/opt/cprocsp/bin/cryptcp")));
        assertTrue(runner.candidates("cpconfig").contains(Path.of("/opt/cprocsp/sbin/cpconfig")));
    }
}
