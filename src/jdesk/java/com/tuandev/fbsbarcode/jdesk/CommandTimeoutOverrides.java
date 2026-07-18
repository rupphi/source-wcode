package com.tuandev.fbsbarcode.jdesk;

import dev.jdesk.api.CommandDefinition;
import dev.jdesk.api.CommandRegistry;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class CommandTimeoutOverrides {
    private CommandTimeoutOverrides() {
    }

    static CommandRegistry withTimeout(CommandRegistry registry, String commandName, Duration timeout) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(commandName, "commandName");
        Objects.requireNonNull(timeout, "timeout");
        List<CommandDefinition> definitions = new ArrayList<>(registry.size());
        boolean found = false;
        for (String name : registry.commandNames()) {
            CommandDefinition definition = registry.find(name).orElseThrow();
            if (definition.name().equals(commandName)) {
                definitions.add(new CommandDefinition(
                        definition.name(),
                        definition.requiredCapability(),
                        definition.requestType(),
                        Optional.of(timeout),
                        definition.handler()));
                found = true;
            } else {
                definitions.add(definition);
            }
        }
        if (!found) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE, "Unknown command timeout override");
        }
        return CommandRegistry.of(definitions.toArray(CommandDefinition[]::new));
    }
}
