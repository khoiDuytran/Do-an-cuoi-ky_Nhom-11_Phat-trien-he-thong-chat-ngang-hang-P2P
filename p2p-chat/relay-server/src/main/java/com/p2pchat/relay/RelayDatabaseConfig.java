package com.p2pchat.relay;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * Quản lý Connection Pool MySQL cho Relay Server (DB3).
 * Database: p2pchat_relay - lưu trữ tin nhắn offline giữa các peers.
 */
public class RelayDatabaseConfig {

    private static final Logger log = Logger.getLogger(RelayDatabaseConfig.class.getName());

    private static RelayDatabaseConfig instance;
    private HikariDataSource dataSource;

    // Cấu hình mặc định
    private String host = "localhost";
    private int port = 3306;
    private String database = "p2pchat_relay";
    private String username = "root";
    private String password = "khoi94";

    private RelayDatabaseConfig() {
    }

    public static synchronized RelayDatabaseConfig getInstance() {
        if (instance == null)
            instance = new RelayDatabaseConfig();
        return instance;
    }

    /**
     * Khởi tạo pool kết nối MySQL cho relay server.
     */
    public void initialize(String host, int port, String username, String password) throws SQLException {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;

        createDatabaseIfNotExists();
        buildPool();
        createTables();
        log.info("[DB3] Relay database initialized: " + this.database);
    }

    public void initialize() throws SQLException {
        initialize(host, port, username, password);
    }

    /** Tạo database nếu chưa tồn tại */
    private void createDatabaseIfNotExists() throws SQLException {
        String url = String.format("jdbc:mysql://%s:%d?useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=Asia/Ho_Chi_Minh", host, port);
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(username);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(2);
        cfg.setConnectionTimeout(8_000);

        try (HikariDataSource tmp = new HikariDataSource(cfg);
                Connection conn = tmp.getConnection();
                var stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE IF NOT EXISTS `" + database
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            log.info("[DB3] Ensured database exists: " + database);
        }
    }

    private void buildPool() {
        String url = String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true"
                        + "&serverTimezone=Asia/Ho_Chi_Minh&characterEncoding=UTF-8",
                host, port, database);

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(username);
        cfg.setPassword(password);
        cfg.setDriverClassName("com.mysql.cj.jdbc.Driver");

        // Pool settings
        cfg.setMaximumPoolSize(20);
        cfg.setMinimumIdle(5);
        cfg.setConnectionTimeout(10_000);
        cfg.setIdleTimeout(300_000);
        cfg.setMaxLifetime(600_000);
        cfg.setPoolName("RelayDB-Pool");

        // Performance
        cfg.addDataSourceProperty("cachePrepStmts", "true");
        cfg.addDataSourceProperty("prepStmtCacheSize", "250");
        cfg.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        cfg.addDataSourceProperty("useServerPrepStmts", "true");
        cfg.addDataSourceProperty("rewriteBatchedStatements", "true");

        dataSource = new HikariDataSource(cfg);
    }

    /** Tạo schema DB3 */
    private void createTables() throws SQLException {
        try (Connection conn = getConnection();
                var stmt = conn.createStatement()) {

            // Bảng relay_messages: lưu tin nhắn offline
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS relay_messages (
                        id                BIGINT         NOT NULL AUTO_INCREMENT,
                        message_id        VARCHAR(36)    NOT NULL UNIQUE,
                        sender_peer_id    VARCHAR(36)    NOT NULL,
                        sender_username   VARCHAR(64),
                        target_peer_id    VARCHAR(36)    NOT NULL,
                        content           TEXT,
                        type              VARCHAR(20)    NOT NULL,
                        group_id          VARCHAR(36),
                        timestamp         DATETIME(3)    NOT NULL,
                        expires_at        DATETIME(3)    NOT NULL,
                        delivered         TINYINT(1)     NOT NULL DEFAULT 0,
                        created_at        DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                        PRIMARY KEY (id),
                        INDEX idx_target  (target_peer_id, delivered, expires_at),
                        INDEX idx_sender  (sender_peer_id),
                        INDEX idx_expires (expires_at),
                        INDEX idx_msg_id  (message_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);

            // Bảng registered_peers: theo dõi peers đang online với relay
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS registered_peers (
                        id           BIGINT      NOT NULL AUTO_INCREMENT,
                        peer_id      VARCHAR(36) NOT NULL UNIQUE,
                        username     VARCHAR(64),
                        ip_address   VARCHAR(45),
                        port         INT,
                        last_seen    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (id),
                        INDEX idx_peer_id (peer_id),
                        INDEX idx_last_seen (last_seen)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);

            log.info("[DB3] Tables ready.");
        }
    }

    public Connection getConnection() throws SQLException {
        if (dataSource == null)
            throw new SQLException("DataSource not initialized");
        return dataSource.getConnection();
    }

    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("[DB3] Connection pool closed.");
        }
    }

    public String getDatabase() {
        return database;
    }
}
