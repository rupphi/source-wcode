package com.tuandev.fbsbarcode.jdesk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jdesk.api.CommandDefinition;
import dev.jdesk.api.CommandHandler;
import dev.jdesk.api.CommandRegistry;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CommandTimeoutOverridesTest {
    @Test
    void overridesOnlyTheLongRunningNativeCommand() {
        CommandHandler exportHandler = (request, context) -> null;
        CommandHandler openHandler = (request, context) -> null;
        CommandRegistry source = CommandRegistry.of(
                new CommandDefinition(
                        "printing.exportSupply",
                        Optional.of("printing:export"),
                        String.class,
                        Optional.empty(),
                        exportHandler),
                new CommandDefinition(
                        "printing.openExport",
                        Optional.of("printing:export"),
                        String.class,
                        Optional.empty(),
                        openHandler));

        CommandRegistry result = CommandTimeoutOverrides.withTimeout(
                source, "printing.exportSupply", Duration.ofMinutes(10));

        CommandDefinition export = result.find("printing.exportSupply").orElseThrow();
        CommandDefinition open = result.find("printing.openExport").orElseThrow();
        assertEquals(Optional.of(Duration.ofMinutes(10)), export.timeout());
        assertSame(exportHandler, export.handler());
        assertTrue(open.timeout().isEmpty());
        assertSame(openHandler, open.handler());
    }
}
