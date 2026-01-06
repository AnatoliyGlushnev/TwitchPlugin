package twitch.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.FileConfiguration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Properties;

public class DatabaseManager {
    private DatabaseManager() {
    }

    public static HikariDataSource createDataSource(FileConfiguration config) {
        String host = config.getString("twitch.database.host", "127.0.0.1");
        int port = config.getInt("twitch.database.port", 5432);
        String dbName = config.getString("twitch.database.name", "twitchstream");
        String user = config.getString("twitch.database.user", "postgres");
        String password = config.getString("twitch.database.password", "");
        boolean ssl = config.getBoolean("twitch.database.ssl", false);
        int poolSize = config.getInt("twitch.database.pool_size", 10);

        boolean autoCreateDatabase = config.getBoolean("twitch.database.auto_create_database", false);
        String adminDatabase = config.getString("twitch.database.admin_database", "postgres");

        if (autoCreateDatabase) {
            ensureDatabaseExists(host, port, adminDatabase, dbName, user, password, ssl);
        }

        String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + dbName + "?ssl=" + ssl;

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setDriverClassName("org.postgresql.Driver");
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(user);
        hikariConfig.setPassword(password);
        hikariConfig.setMaximumPoolSize(poolSize);
        hikariConfig.setPoolName("TwitchStreamPlugin-Pool");
        hikariConfig.setConnectionTimeout(10_000);
        hikariConfig.setValidationTimeout(5_000);
        hikariConfig.setInitializationFailTimeout(10_000);
        hikariConfig.setMinimumIdle(Math.min(2, poolSize));

        return new HikariDataSource(hikariConfig);
    }

    private static void ensureDatabaseExists(String host, int port, String adminDatabase, String dbName, String user, String password, boolean ssl) {
        if (dbName == null || !dbName.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid database name: " + dbName);
        }

        String adminJdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + adminDatabase + "?ssl=" + ssl;

        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);

        try (Connection c = DriverManager.getConnection(adminJdbcUrl, props);
             Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE \"" + dbName + "\"");
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (msg.contains("already exists")) {
                return;
            }
            throw new RuntimeException("Failed to auto-create database '" + dbName + "'", e);
        }
    }
}
