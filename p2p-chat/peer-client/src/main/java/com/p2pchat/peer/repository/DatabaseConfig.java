package com.p2pchat.peer.repository;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * Quản lý Connection Pool MySQL cho Peer local (DB2).
 * Mỗi peer kết nối vào database riêng: p2pchat_peer_{peerId_short}
 */
public class DatabaseConfig {

    private static final Logger log = Logger.getLogger(DatabaseConfig.class.getName());

    private static DatabaseConfig instance;
    private HikariDataSource dataSource;

    // Cấu hình mặc định - có thể override qua app.properties
    private String host = "localhost";
    private int port = 3306;
    private String database = "p2pchat_peer";
    private String username = "root";
    private String password = "khoi94";

    private DatabaseConfig() {
    }

    public static synchronized DatabaseConfig getInstance() {
        if (instance == null)
            instance = new DatabaseConfig();
        return instance;
    }

    /**
     * Khởi tạo pool kết nối MySQL cho peer.
     */
    public void initialize(String peerId, String host, int port,
            String username, String password) throws SQLException {
        this.host = host;
        this.port = port;
        // Tên DB an toàn: chỉ lấy 8 ký tự hex đầu của UUID
        this.database = "p2pchat_peer_" + peerId.replace("-", "").substring(0, 8);
        this.username = username;
        this.password = password;

        createDatabaseIfNotExists();
        buildPool();
        createTables();
        log.info("[DB2] Peer database initialized: " + this.database);
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
            log.info("[DB2] Ensured database exists: " + database);
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

        // Pool settings phù hợp cho ứng dụng desktop
        cfg.setMaximumPoolSize(10);
        cfg.setMinimumIdle(2);
        cfg.setConnectionTimeout(10_000);
        cfg.setIdleTimeout(300_000);
        cfg.setMaxLifetime(600_000);
        cfg.setPoolName("PeerDB-Pool");

        // Performance
        cfg.addDataSourceProperty("cachePrepStmts", "true");
        cfg.addDataSourceProperty("prepStmtCacheSize", "250");
        cfg.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        cfg.addDataSourceProperty("useServerPrepStmts", "true");
        cfg.addDataSourceProperty("rewriteBatchedStatements", "true");

        dataSource = new HikariDataSource(cfg);
    }

    /** Tạo schema DB2 */
    private void createTables() throws SQLException {
        try (Connection conn = getConnection();
                var stmt = conn.createStatement()) {

            // Bảng messages: lịch sử chat (đọc nhiều, ghi bình thường)
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS messages (
                        id               BIGINT        NOT NULL AUTO_INCREMENT,
                        message_id       VARCHAR(36)   NOT NULL UNIQUE,
                        sender_peer_id   VARCHAR(36)   NOT NULL,
                        sender_username  VARCHAR(64),
                        target_peer_id   VARCHAR(36),
                        group_id         VARCHAR(36),
                        content          TEXT          NOT NULL,
                        type             ENUM('CHAT','GROUP_CHAT','BROADCAST','FILE', 'GROUP_FILE') NOT NULL,
                        sent_at          DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                        delivered        TINYINT(1)    NOT NULL DEFAULT 0,
                        is_own           TINYINT(1)    NOT NULL DEFAULT 0,
                        PRIMARY KEY (id),
                        INDEX idx_direct  (sender_peer_id, target_peer_id, sent_at),
                        INDEX idx_group   (group_id, sent_at),
                        INDEX idx_msg_id  (message_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);

            // Bảng pending_messages: hàng đợi store-and-forward (ghi nhiều, xóa ngay sau
            // khi gửi)
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS pending_messages (
                        id               BIGINT        NOT NULL AUTO_INCREMENT,
                        message_id       VARCHAR(36)   NOT NULL UNIQUE,
                        target_peer_id   VARCHAR(36)   NOT NULL,
                        content          TEXT          NOT NULL,
                        type             ENUM('CHAT','GROUP_CHAT','BROADCAST','FILE', 'GROUP_FILE') NOT NULL,
                        group_id         VARCHAR(36),
                        created_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                        retry_count      INT           NOT NULL DEFAULT 0,
                        serialized_msg   MEDIUMBLOB    NOT NULL,
                        PRIMARY KEY (id),
                        INDEX idx_pending (target_peer_id, retry_count)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);

            // Bảng peer_groups: danh sách group mà peer này tham gia (lưu local để restore
            // khi restart)
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS peer_groups (
                        id            BIGINT       NOT NULL AUTO_INCREMENT,
                        group_id      VARCHAR(36)  NOT NULL,
                        group_name    VARCHAR(128) NOT NULL,
                        owner_peer_id VARCHAR(36)  NOT NULL,
                        created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY   (id),
                        UNIQUE KEY    uq_group_id (group_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);

            // Bảng peer_group_members: danh sách thành viên của từng group
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS peer_group_members (
                        id         BIGINT      NOT NULL AUTO_INCREMENT,
                        group_id   VARCHAR(36) NOT NULL,
                        peer_id    VARCHAR(36) NOT NULL,
                        joined_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (id),
                        UNIQUE KEY  uq_gm       (group_id, peer_id),
                        INDEX       idx_gm_peer (peer_id),
                        CONSTRAINT  fk_pgm_group
                            FOREIGN KEY (group_id) REFERENCES peer_groups(group_id)
                            ON DELETE CASCADE ON UPDATE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);

            upgradeMessageEnumForFile(conn);

            log.info("[DB2] Tables ready.");
        }
    }

    /** DB cũ tạo bảng trước khi có FILE — mở rộng ENUM (bỏ qua nếu đã đúng). */
    private void upgradeMessageEnumForFile(Connection conn) {
        try (var st = conn.createStatement()) {
            st.execute("""
                    ALTER TABLE messages MODIFY COLUMN type
                    ENUM('CHAT','GROUP_CHAT','BROADCAST','FILE', 'GROUP_FILE') NOT NULL
                    """);
        } catch (SQLException e) {
            log.fine("[DB2] messages.type ENUM: " + e.getMessage());
        }
        try (var st = conn.createStatement()) {
            st.execute("""
                    ALTER TABLE pending_messages MODIFY COLUMN type
                    ENUM('CHAT','GROUP_CHAT','BROADCAST','FILE', 'GROUP_FILE') NOT NULL
                    """);
        } catch (SQLException e) {
            log.fine("[DB2] pending_messages.type ENUM: " + e.getMessage());
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
            log.info("[DB2] Connection pool closed.");
        }
    }

    public String getDatabase() {
        return database;
    }
}