package com.tuandev.fbsbarcode.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

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

            st.execute("INSERT INTO config (id, type) SELECT 1, 1 WHERE NOT EXISTS (SELECT 1 FROM config WHERE id = 1)");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
