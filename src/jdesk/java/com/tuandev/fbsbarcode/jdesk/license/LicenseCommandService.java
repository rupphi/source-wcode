package com.tuandev.fbsbarcode.jdesk.license;

import com.tuandev.fbsbarcode.integration.license.LicenseApiClient.LicenseApiException;
import com.tuandev.fbsbarcode.integration.license.LicensePayload;
import com.tuandev.fbsbarcode.integration.license.LicenseService;
import com.tuandev.fbsbarcode.integration.license.LicenseState;
import com.tuandev.fbsbarcode.integration.license.LicenseState.LicenseStatus;
import com.tuandev.fbsbarcode.jdesk.SafeCommandExecutor;
import com.tuandev.fbsbarcode.shared.ConfigService;
import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.RequiresCapability;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;

/** Secret-safe jDesk adapter over the existing signed-file {@link LicenseService} oracle. */
public final class LicenseCommandService {
    private static final Pattern LICENSE_KEY = Pattern.compile("WC-(?:[A-Z0-9]{5}-){3}[A-Z0-9]{5}");
    private static final Pattern SAFE_PLAN = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Duration OFFLINE_GRACE = Duration.ofDays(14);
    private static final long EARLIEST_TIMESTAMP = Instant.parse("2000-01-01T00:00:00Z").toEpochMilli();
    private static final long LATEST_TIMESTAMP = Instant.parse("2100-01-01T00:00:00Z").toEpochMilli();

    private final LicenseOperations operations;
    private final Clock clock;
    private final Object mutationLock = new Object();

    public LicenseCommandService() {
        this(new LegacyLicenseOperations(), Clock.systemUTC());
    }

    LicenseCommandService(LicenseOperations operations, Clock clock) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @DesktopCommand("license.status")
    @RequiresCapability("license:read")
    public CompletionStage<StatusResponse> status(StatusRequest request, InvocationContext context) {
        return SafeCommandExecutor.execute(() -> toResponse(operations.current()));
    }

    @DesktopCommand("license.refresh")
    @RequiresCapability("license:manage")
    public CompletionStage<StatusResponse> refresh(RefreshRequest request, InvocationContext context) {
        return SafeCommandExecutor.execute(() -> {
            requireNotCancelled(context);
            synchronized (mutationLock) {
                return toResponse(operations.refresh());
            }
        });
    }

    @DesktopCommand("license.activate")
    @RequiresCapability("license:manage")
    public CompletionStage<ActionResponse> activate(ActivateRequest request, InvocationContext context) {
        String key = requireLicenseKey(request == null ? null : request.licenseKey());
        return SafeCommandExecutor.execute(() -> {
            requireNotCancelled(context);
            synchronized (mutationLock) {
                try {
                    StatusResponse response = toResponse(operations.activate(key));
                    boolean accepted = response.kizAllowed();
                    return new ActionResponse(accepted, response, accepted ? "" : response.errorKind());
                } catch (Exception error) {
                    return new ActionResponse(false, toResponse(operations.current()), activationError(error));
                }
            }
        });
    }

    @DesktopCommand("license.deactivate")
    @RequiresCapability("license:manage")
    public CompletionStage<ActionResponse> deactivate(DeactivateRequest request, InvocationContext context) {
        if (request == null || !request.confirmed()) {
            throw SafeCommandExecutor.invalidRequest("Explicit device deactivation confirmation is required.");
        }
        return SafeCommandExecutor.execute(() -> {
            requireNotCancelled(context);
            synchronized (mutationLock) {
                StatusResponse response = toResponse(operations.deactivate());
                return new ActionResponse(true, response, "");
            }
        });
    }

    private StatusResponse toResponse(LicenseState state) {
        LicenseState safeState = state == null ? LicenseState.of(LicenseStatus.INVALID) : state;
        LicensePayload payload = safeState.payload();
        long issuedAt = validTimestamp(payload == null ? 0 : payload.issuedAt());
        long expiresAt = validTimestamp(payload == null ? 0 : payload.expiresAt());
        long graceEndsAt = issuedAt == 0 || expiresAt == 0
                ? 0 : Math.min(expiresAt, issuedAt + OFFLINE_GRACE.toMillis());
        long remainingMillis = expiresAt == 0 ? 0 : Math.max(0, expiresAt - clock.millis());
        int daysRemaining = (int) Math.min(36_500, remainingMillis / Duration.ofDays(1).toMillis());
        String plan = payload == null ? "" : safePlan(payload.plan());
        return new StatusResponse(
                status(safeState.status()), safeState.kizAllowed(), operations.hasStoredKey(), plan,
                timestamp(issuedAt), timestamp(expiresAt), timestamp(graceEndsAt), daysRemaining,
                stateError(safeState.status()));
    }

    private static String requireLicenseKey(String candidate) {
        String normalized = candidate == null ? "" : candidate.strip().toUpperCase(Locale.ROOT);
        if (!LICENSE_KEY.matcher(normalized).matches()) {
            throw SafeCommandExecutor.invalidRequest("The license key format is invalid.");
        }
        return normalized;
    }

    private static String safePlan(String candidate) {
        String normalized = candidate == null ? "" : candidate.strip().toLowerCase(Locale.ROOT);
        return SAFE_PLAN.matcher(normalized).matches() ? normalized : "";
    }

    private static long validTimestamp(long candidate) {
        return candidate >= EARLIEST_TIMESTAMP && candidate <= LATEST_TIMESTAMP ? candidate : 0;
    }

    private static String timestamp(long epochMillis) {
        return epochMillis == 0 ? "" : Instant.ofEpochMilli(epochMillis).toString();
    }

    private static String status(LicenseStatus status) {
        return switch (status) {
            case NOT_ACTIVATED -> "not_activated";
            case VALID -> "valid";
            case OFFLINE_GRACE -> "offline_grace";
            case EXPIRED -> "expired";
            case INVALID -> "invalid";
            case DEVICE_LIMIT -> "device_limit";
            case CLOCK_TAMPERED -> "clock_tampered";
            case NETWORK_ERROR -> "network_error";
        };
    }

    private static String stateError(LicenseStatus status) {
        return switch (status) {
            case OFFLINE_GRACE -> "offline";
            case EXPIRED -> "expired";
            case INVALID -> "invalid_license";
            case DEVICE_LIMIT -> "device_limit";
            case CLOCK_TAMPERED -> "clock_tampered";
            case NETWORK_ERROR -> "network";
            default -> "";
        };
    }

    private static String activationError(Exception error) {
        if (error instanceof LicenseApiException apiError) {
            return switch (apiError.code()) {
                case LicenseApiException.CODE_INVALID_LICENSE -> "invalid_license";
                case LicenseApiException.CODE_DEVICE_LIMIT_REACHED -> "device_limit";
                default -> "unavailable";
            };
        }
        if (error instanceof IOException) return "network";
        return "unavailable";
    }

    private static void requireNotCancelled(InvocationContext context) {
        if (context != null && context.isCancelled()) {
            throw new JDeskException(ErrorCode.CANCELLED, "Operation cancelled.", null, null);
        }
    }

    interface LicenseOperations {
        LicenseState current();
        boolean hasStoredKey();
        LicenseState activate(String key) throws Exception;
        LicenseState refresh();
        LicenseState deactivate();
    }

    public record StatusRequest() {}
    public record RefreshRequest() {}
    public record ActivateRequest(String licenseKey) {}
    public record DeactivateRequest(boolean confirmed) {}
    public record StatusResponse(
            String status, boolean kizAllowed, boolean hasStoredKey, String plan,
            String issuedAt, String expiresAt, String offlineGraceEndsAt,
            int daysRemaining, String errorKind) {}
    public record ActionResponse(boolean accepted, StatusResponse license, String errorKind) {}

    private static final class LegacyLicenseOperations implements LicenseOperations {
        private final LicenseService service = LicenseService.getInstance();

        @Override public LicenseState current() { return service.getState(); }
        @Override public boolean hasStoredKey() {
            String key = ConfigService.getLicenseKey();
            return key != null && !key.isBlank();
        }
        @Override public LicenseState activate(String key) throws Exception { return service.activate(key); }
        @Override public LicenseState refresh() {
            LicenseState state = service.refresh();
            service.startBackgroundRevalidation();
            return state;
        }
        @Override public LicenseState deactivate() { return service.deactivateCurrentDevice(); }
    }
}
