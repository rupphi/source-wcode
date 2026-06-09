package com.tuandev.fbsbarcode.config;

import com.tuandev.fbsbarcode.shared.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import com.tuandev.fbsbarcode.integration.wb.WbSchemaSupport;

public class Database {
    private static final Logger LOGGER = LoggerFactory.getLogger(Database.class);
    private static final String DB_NAME = "database.db";

    public static Connection getConnection() {
        try {
            Path dir = AppPaths.appDataDir();

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
            LOGGER.error("Không thể mở database tại thư mục ứng dụng {}", AppPaths.appDataDir(), e);
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

            boolean shopCategoriesExisted = tableExists(conn, "shop_categories");
            st.execute("""
            CREATE TABLE IF NOT EXISTS shop_categories(
                shop_id INTEGER NOT NULL,
                category_id INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                PRIMARY KEY (shop_id, category_id),
                FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE CASCADE,
                FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE CASCADE
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
            CREATE TABLE IF NOT EXISTS fbo_print_templates(
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
                ru_size TEXT,
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
            if (!shopCategoriesExisted) {
                st.execute("""
                        INSERT OR IGNORE INTO shop_categories (shop_id, category_id, created_at)
                        SELECT s.id, c.id, datetime('now')
                        FROM shops s
                        CROSS JOIN categories c
                        """);
            }
            ensureColumnExists(conn, "print_jobs", "shop_name", "TEXT");
            ensureColumnExists(conn, "print_job_items", "ru_size", "TEXT");
            createIndexIfNotExists(conn, "idx_print_jobs_shop_id", "print_jobs", "shop_id");
            createIndexIfNotExists(conn, "idx_print_jobs_shop_supply_status", "print_jobs", "shop_id, supply_id, status");
            createIndexIfNotExists(conn, "idx_print_jobs_shop_printed_at", "print_jobs", "shop_id, printed_at DESC");
            createIndexIfNotExists(conn, "idx_print_job_items_print_job_id", "print_job_items", "print_job_id");
            createIndexIfNotExists(conn, "idx_print_job_items_order_id", "print_job_items", "order_id");
            createIndexIfNotExists(conn, "idx_kizs_shop_category_id", "kizs", "shop_id, category_id");
            createIndexIfNotExists(conn, "idx_shop_categories_category_id", "shop_categories", "category_id");
            createIndexIfNotExists(conn, "idx_image_cache_last_used_at", "image_cache", "last_used_at");
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

    private static boolean tableExists(Connection conn, String tableName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT 1
                FROM sqlite_master
                WHERE type = 'table' AND name = ?
                LIMIT 1
                """)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void createIndexIfNotExists(Connection conn, String indexName, String tableName, String columns) throws SQLException {
        try (Statement statement = conn.createStatement()) {
            statement.execute("CREATE INDEX IF NOT EXISTS " + indexName + " ON " + tableName + "(" + columns + ")");
        }
    }
}
