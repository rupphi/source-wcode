package com.tuandev.fbsbarcode.jdesk.diagnostics;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Writes the two-entry redacted bundle through a temporary sibling and atomic publication. */
final class SupportBundleWriter {
    private static final int MAX_NAME = 180;
    private static final int MAX_BUNDLE_BYTES = 64 * 1024;

    void write(Path selected, DiagnosticsCommandService.DiagnosticsSummary summary) throws IOException {
        Path target = requireTarget(selected);
        Path temporary = Files.createTempFile(target.getParent(), ".wcode-support-", ".tmp");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary), StandardCharsets.UTF_8)) {
                entry(zip, "diagnostics.json", new Gson().toJson(summary) + "\n");
                entry(zip, "README.txt", "WCode support bundle\n"
                        + "This file contains aggregate application health only. It intentionally excludes "
                        + "credentials, identities, paths, logs, SQL and stack traces.\n");
            }
            if (Files.size(temporary) > MAX_BUNDLE_BYTES) {
                throw new IOException("Support bundle exceeds its size bound");
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("Atomic support bundle publication is unavailable", exception);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path requireTarget(Path selected) throws IOException {
        if (selected == null) throw new IOException("No support bundle target selected");
        Path target = selected.toAbsolutePath().normalize();
        String name = target.getFileName() == null ? "" : target.getFileName().toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            target = target.resolveSibling(name + ".zip");
            name = target.getFileName().toString();
        }
        if (name.isBlank() || name.length() > MAX_NAME || name.chars().anyMatch(Character::isISOControl)) {
            throw new IOException("Support bundle file name is invalid");
        }
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent) || !Files.isWritable(parent)) {
            throw new IOException("Support bundle directory is unavailable");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target))) {
            throw new IOException("Support bundle target is unsafe");
        }
        return target;
    }

    private static void entry(ZipOutputStream zip, String name, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 32 * 1024) throw new IOException("Support bundle entry is too large");
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }
}
