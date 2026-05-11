package com.tuandev.fbsbarcode.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import com.tuandev.fbsbarcode.integration.wb.WbSchemaSupport;

public class Database {

    private static final String DB_DIR =
            System.getProperty("user.home") + "/fbsbarcode";
    private static final String DB_NAME = "database.db";

    public static Connection getConnection() {
        try {
            Path dir = Paths.get(DB_DIR);

            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }

            Path dbFile = dir.resolve(DB_NAME);

            Connection connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + dbFile.toString()
            );
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("PRAGMA busy_timeout = 5000");
                statement.execute("PRAGMA journal_mode = WAL");
                statement.execute("PRAGMA synchronous = NORMAL");
            }
            return connection;

        } catch (IOException | SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static void initDatabase() {
        try (Connection conn = getConnection();
             Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS shops(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    api_key TEXT NOT NULL
                )
            """);

            st.execute("""
            CREATE TABLE IF NOT EXISTS categories(
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL
            )
            """);

            st.execute("""
            CREATE TABLE IF NOT EXISTS kizs(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                code TEXT NOT NULL,
                shop_id INTEGER NOT NULL,
                category_id INTEGER NOT NULL,
                FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE CASCADE,
                FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE CASCADE
            )
            """);

            st.execute("""
            CREATE TABLE IF NOT EXISTS config(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type INTEGER NOT NULL DEFAULT 1
            )
            """);

            st.execute("""
            CREATE TABLE IF NOT EXISTS app_config(
                key   TEXT PRIMARY KEY,
                value TEXT
            )
            """);

            st.execute("""
            CREATE TABLE IF NOT EXISTS print_templates(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                page_width REAL NOT NULL DEFAULT 164.40944881889766,
                page_height REAL NOT NULL DEFAULT 113.38582677165356,
                is_default INTEGER NOT NULL DEFAULT 0,
                layout_json TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """);

            st.execute("""
            CREATE TABLE IF NOT EXISTS image_cache(
                cache_key TEXT PRIMARY KEY,
                image_url TEXT NOT NULL,
                image_blob BLOB NOT NULL,
                content_type TEXT,
                updated_at TEXT NOT NULL,
                last_used_at TEXT NOT NULL
            )
            """);

            st.execute("""
            CREATE TABLE IF NOT EXISTS print_jobs(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                shop_id INTEGER NOT NULL,
                shop_name TEXT,
                supply_id TEXT,
                supply_name TEXT,
                printed_at TEXT NOT NULL,
                item_count INTEGER NOT NULL,
                template_id INTEGER,
                template_name TEXT,
                template_layout_json TEXT NOT NULL,
                status TEXT NOT NULL,
                error_message TEXT,
                FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE CASCADE
            )
            """);

            st.execute("""
            CREATE TABLE IF NOT EXISTS print_job_items(
                print_job_id INTEGER NOT NULL,
                sort_index INTEGER NOT NULL,
                order_id INTEGER NOT NULL,
                brand TEXT,
                name TEXT,
                subject_name TEXT,
                size TEXT,
                color TEXT,
                article TEXT,
                barcode TEXT,
                sticker TEXT,
                sticker_code TEXT,
                kiz TEXT,
                image_cache_key TEXT,
                PRIMARY KEY (print_job_id, sort_index),
                FOREIGN KEY (print_job_id) REFERENCES print_jobs(id) ON DELETE CASCADE
            )
            """);

            st.execute("INSERT INTO config (id, type) SELECT 1, 1 WHERE NOT EXISTS (SELECT 1 FROM config WHERE id = 1)");
            ensureColumnExists(conn, "print_jobs", "shop_name", "TEXT");
            WbSchemaSupport.initialize(conn);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static void ensureColumnExists(Connection conn, String tableName, String columnName, String columnDefinition) throws SQLException {
        if (hasColumn(conn, tableName, columnName)) {
            return;
        }
        try (Statement statement = conn.createStatement()) {
            statement.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnDefinition);
        }
    }

    private static boolean hasColumn(Connection conn, String tableName, String columnName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("PRAGMA table_info(" + tableName + ")");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (columnName.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }
}
