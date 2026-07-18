package com.tuandev.fbsbarcode.jdesk;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Converts command failures to an allowlisted public envelope without serializing raw causes. */
public final class SafeCommandExecutor {
    private SafeCommandExecutor() {
    }

    public static <T> CompletionStage<T> execute(Supplier<T> operation) {
        try {
            return CompletableFuture.completedFuture(operation.get());
        } catch (JDeskException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            String reference = UUID.randomUUID().toString();
            throw new JDeskException(ErrorCode.INTERNAL_ERROR, "Operation failed. Reference: " + reference);
        }
    }

    public static JDeskException invalidRequest(String publicMessage) {
        return new JDeskException(ErrorCode.INVALID_REQUEST, publicMessage);
    }
}
