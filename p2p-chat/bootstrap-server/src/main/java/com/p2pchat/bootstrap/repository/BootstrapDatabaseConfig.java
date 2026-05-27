package com.p2pchat.bootstrap.repository;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * Quản lý Connection Pool MySQL cho Bootstrap Server (DB1).
 *
 * Schema DB1 (bootstrap / global):
 *   - users : đăng ký peer, last_ip, last_port
 */
public class BootstrapDatabaseConfig {

    private static final Logger log = Logger.getLogger(BootstrapDatabaseConfig.class.getName());
    private static BootstrapDatabaseConfig instance;
    private HikariDataSource dataSource;

    private BootstrapDatabaseConfig() {}

    public static synchronized BootstrapDatabaseConfig getInstance() {
        if (instance == null) instance = new BootstrapDatabaseConfig();
        return instance;
    }

    public void initialize(String host, int port, String username, String password) throws SQLException {
        final String dbName = "p2pchat_bootstrap";

        // Bước 1: tạo database nếu chưa có
        String rootUrl = String.format(
                "jdbc:mysql://%s:%d?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Ho_Chi_Minh",
                host, port);
        HikariConfig tmpCfg = new HikariConfig();
        tmpCfg.setJdbcUrl(rootUrl);
        tmpCfg.setUsername(username);
        tmpCfg.setPassword(password);
        tmpCfg.setMaximumPoolSize(2);
        tmpCfg.setConnectionTimeout(8_000);

        try (HikariDataSource tmp = new HikariDataSource(tmpCfg);
             Connection conn = tmp.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE IF NOT EXISTS `" + dbName
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            log.info("[DB1] Database ensured: " + dbName);
        }

        // Bước 2: build pool chính
        String url = String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true"
                        + "&serverTimezone=Asia/Ho_Chi_Minh&characterEncoding=UTF-8",
                host, port, dbName);

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(username);
        cfg.setPassword(password);
        cfg.setDriverClassName("com.mysql.cj.jdbc.Driver");
        cfg.setMaximumPoolSize(20);
        cfg.setMinimumIdle(5);
        cfg.setConnectionTimeout(10_000);
        cfg.setIdleTimeout(300_000);
        cfg.setMaxLifetime(600_000);
        cfg.setPoolName("Bootstrap-DB-Pool");

        cfg.addDataSourceProperty("cachePrepStmts",           "true");
        cfg.addDataSourceProperty("prepStmtCacheSize",        "250");
        cfg.addDataSourceProperty("prepStmtCacheSqlLimit",    "2048");
        cfg.addDataSourceProperty("useServerPrepStmts",       "true");
        cfg.addDataSourceProperty("rewriteBatchedStatements",  "true");

        dataSource = new HikariDataSource(cfg);

        // Bước 3: tạo bảng
        createTables();
        log.info("[DB1] Bootstrap database ready.");
    }

    private void createTables() throws SQLException {
        try (Connection conn = getConnection();
             var stmt = conn.createStatement()) {

            // users: định danh peer, địa chỉ mạng hiện tại
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id           BIGINT       NOT NULL AUTO_INCREMENT,
                    peer_id      VARCHAR(36)  NOT NULL,
                    username     VARCHAR(64)  NOT NULL,
                    last_ip      VARCHAR(64),
                    last_port    INT,
                    is_online    TINYINT(1)   NOT NULL DEFAULT 0,
                    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    last_seen    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                              ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY  (id),
                    UNIQUE KEY   uq_peer_id (peer_id),
                    INDEX        idx_online  (is_online),
                    INDEX        idx_username (username)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);

            log.info("[DB1] All tables created/verified.");
        }
    }

    public Connection getConnection() throws SQLException {
        if (dataSource == null) throw new SQLException("DataSource not initialized. Call initialize() first.");
        return dataSource.getConnection();
    }

    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("[DB1] Connection pool closed.");
        }
    }
}