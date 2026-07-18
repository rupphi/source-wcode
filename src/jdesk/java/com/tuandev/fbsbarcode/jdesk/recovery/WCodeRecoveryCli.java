package com.tuandev.fbsbarcode.jdesk.recovery;

import com.tuandev.fbsbarcode.BuildConfig;
import com.tuandev.fbsbarcode.shared.AppDataLock;
import com.tuandev.fbsbarcode.shared.AppPaths;
import com.tuandev.fbsbarcode.shared.LocalDataSnapshotService;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Offline recovery entry point that never initializes the normal WCode database bootstrap. */
public final class WCodeRecoveryCli {
    private WCodeRecoveryCli() {
    }

    public static void main(String[] args) {
        System.exit(run(
                AppPaths.appDataDir(),
                BuildConfig.getAppVersion(),
                args,
                System.out,
                System.err));
    }

    static int run(
            Path appDataDir,
            String appVersion,
            String[] args,
            PrintStream out,
            PrintStream err) {
        Objects.requireNonNull(appDataDir, "appDataDir");
        Objects.requireNonNull(appVersion, "appVersion");
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        if (!validArguments(args, err)) {
            return 2;
        }

        try (AppDataLock ownership = AppDataLock.acquire(appDataDir, "recovery-cli")) {
            LocalDataSnapshotService snapshots = new LocalDataSnapshotService();
            LocalDataSnapshotService.RecoveryOutcome recovery = snapshots.recoverInterrupted(ownership);
            if (recovery != LocalDataSnapshotService.RecoveryOutcome.NONE) {
                out.printf("Interrupted restore recovery=%s%n", recovery.name().toLowerCase(java.util.Locale.ROOT));
            }
            return switch (args[0]) {
                case "list" -> list(snapshots, ownership, out);
                case "verify" -> verify(snapshots, ownership, args[1], out);
                case "restore" -> restore(snapshots, ownership, args[1], appVersion, out);
                default -> 2;
            };
        } catch (AppDataLock.AlreadyRunningException exception) {
            err.println("Recovery refused: close every WCode instance and try again.");
            return 4;
        } catch (Exception exception) {
            err.println("Recovery did not complete. Keep WCode closed and rerun the recovery command.");
            return 5;
        }
    }

    private static boolean validArguments(String[] args, PrintStream err) {
        if (args.length == 1 && "list".equals(args[0])) {
            return true;
        }
        if (args.length == 2 && "verify".equals(args[0])) {
            return true;
        }
        if (args.length == 3 && "restore".equals(args[0]) && "--confirm".equals(args[2])) {
            return true;
        }
        if (args.length >= 1 && "restore".equals(args[0])) {
            err.println("Restore requires an exact snapshot id and the explicit --confirm flag.");
        } else {
            err.println("Usage: list | verify <snapshot-id> | restore <snapshot-id> --confirm");
        }
        return false;
    }

    private static int list(
            LocalDataSnapshotService service,
            AppDataLock ownership,
            PrintStream out) throws Exception {
        List<LocalDataSnapshotService.Snapshot> snapshots = service.list(ownership);
        if (snapshots.isEmpty()) {
            out.println("No WCode snapshots found.");
            return 0;
        }
        for (LocalDataSnapshotService.Snapshot snapshot : snapshots) {
            out.printf(
                    "id=%s createdAt=%s appVersion=%s schemaVersion=%d reason=%s verified=%s%n",
                    snapshotId(snapshot),
                    snapshot.createdAt(),
                    snapshot.appVersion(),
                    snapshot.schemaVersion(),
                    snapshot.reason(),
                    service.verify(snapshot));
        }
        return 0;
    }

    private static int verify(
            LocalDataSnapshotService service,
            AppDataLock ownership,
            String snapshotId,
            PrintStream out) throws Exception {
        LocalDataSnapshotService.Snapshot snapshot = service.load(ownership, snapshotId);
        boolean verified = service.verify(snapshot);
        out.printf("id=%s verified=%s%n", snapshotId(snapshot), verified);
        return verified ? 0 : 3;
    }

    private static int restore(
            LocalDataSnapshotService service,
            AppDataLock ownership,
            String snapshotId,
            String appVersion,
            PrintStream out) throws Exception {
        LocalDataSnapshotService.Snapshot snapshot = service.load(ownership, snapshotId);
        if (!service.verify(snapshot)) {
            out.printf("id=%s verified=false%n", snapshotId(snapshot));
            return 3;
        }
        LocalDataSnapshotService.RestoreResult result =
                service.restore(ownership, snapshot, appVersion);
        out.printf(
                "Restore complete. restored=%s recoveryBundle=%s integrity=%s%n",
                snapshotId(result.restoredSnapshot()),
                result.recoveryBundle().id(),
                result.recoveryBundle().integrityStatus().name().toLowerCase(java.util.Locale.ROOT));
        return 0;
    }

    private static String snapshotId(LocalDataSnapshotService.Snapshot snapshot) {
        return snapshot.database().getParent().getFileName().toString();
    }
}
