package com.tuandev.fbsbarcode.integration.znack.registration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class ZnackCardRegistrationSchema {
    private ZnackCardRegistrationSchema() {
    }

    public static void initialize(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS wb_print_kiz_demands(
                        shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
                        gtin TEXT NOT NULL, demand_key TEXT NOT NULL, quantity INTEGER NOT NULL,
                        request_key TEXT NOT NULL, created_at TEXT NOT NULL,
                        PRIMARY KEY(shop_id,gtin,demand_key))
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS znack_registration_publication(
                        shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
                        chrt_id INTEGER NOT NULL, next_check_at TEXT NOT NULL DEFAULT '',
                        wb_attempt_at TEXT, ready_for_kiz INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(shop_id,chrt_id))
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS znack_registration_queue(
                        shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
                        chrt_id INTEGER NOT NULL, sku_json TEXT NOT NULL, draft_json TEXT NOT NULL,
                        credential_fingerprint TEXT NOT NULL, phase TEXT NOT NULL DEFAULT 'QUEUED',
                        created_at TEXT NOT NULL, PRIMARY KEY(shop_id,chrt_id))
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS znack_registration_documents(
                        shop_id INTEGER PRIMARY KEY REFERENCES shops(id) ON DELETE CASCADE,
                        declaration_number TEXT, declaration_date TEXT,
                        certificate_number TEXT, certificate_date TEXT)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS znack_card_registrations(
                        shop_id INTEGER NOT NULL,
                        chrt_id INTEGER NOT NULL,
                        nm_id INTEGER NOT NULL,
                        vendor_code TEXT,
                        source_barcode TEXT,
                        gtin TEXT,
                        good_id INTEGER,
                        feed_id TEXT,
                        tnved TEXT,
                        category_id INTEGER,
                        good_name TEXT,
                        payload_json TEXT,
                        status TEXT NOT NULL DEFAULT 'NOT_CREATED',
                        error_message TEXT,
                        wb_updated INTEGER NOT NULL DEFAULT 0,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY(shop_id, chrt_id),
                        FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_znack_card_registrations_shop_status
                    ON znack_card_registrations(shop_id, status, updated_at)
                    """);
            statement.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_znack_card_registrations_shop_gtin
                    ON znack_card_registrations(shop_id, gtin)
                    WHERE gtin IS NOT NULL AND TRIM(gtin) <> ''
                    """);
        }
    }
}
