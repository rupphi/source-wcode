package com.tuandev.fbsbarcode.integration.update;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateInstallerServiceTest {

    @Test
    void relaunchesFromTheDataSafeInstallDirectoryAfterUpgrade() {
        String command = new UpdateInstallerService()
                .buildWindowsInstallCommand(Path.of("C:\\Temp\\WCode-update.exe"));

        String currentInstall = "Join-Path $env:LOCALAPPDATA 'WCodeApp\\WCode.exe'";
        String legacyInstall = "Join-Path $env:LOCALAPPDATA 'Programs\\WCode\\WCode.exe'";

        assertTrue(command.contains(currentInstall));
        assertTrue(command.indexOf(currentInstall) < command.indexOf(legacyInstall));
    }
}
