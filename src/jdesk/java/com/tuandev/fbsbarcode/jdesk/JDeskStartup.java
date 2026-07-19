package com.tuandev.fbsbarcode.jdesk;

import com.tuandev.fbsbarcode.shared.LocalDataMigrationGate;
import java.io.IOException;
import java.nio.file.Path;

/** Performs fail-closed jDesk data ownership, recovery, snapshot and database initialization. */
public final class JDeskStartup {
    private JDeskStartup() {
    }

    public static Session prepare(Path appDataDir, String appVersion) throws Exception {
        return new Session(LocalDataMigrationGate.prepare(appDataDir, appVersion, "jdesk"));
    }

    public static final class Session implements AutoCloseable {
        private final LocalDataMigrationGate.Session delegate;

        private Session(LocalDataMigrationGate.Session delegate) {
            this.delegate = delegate;
        }

        public synchronized void createSignedUpdateSnapshot() throws Exception {
            delegate.createSignedUpdateSnapshot();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
