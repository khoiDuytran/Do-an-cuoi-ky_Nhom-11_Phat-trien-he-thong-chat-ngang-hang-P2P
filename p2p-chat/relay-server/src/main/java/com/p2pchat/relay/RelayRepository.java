package com.p2pchat.relay;

import com.p2pchat.peer.protocol.Message;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Repository xử lý các thao tác với DB3 (relay_messages).
 * Quản lý tin nhắn offline giữa các peers.
 */
public class RelayRepository {

    private static final Logger log = Logger.getLogger(RelayRepository.class.getName());
    private final RelayDatabaseConfig db = RelayDatabaseConfig.getInstance();

    // Thời gian hết hạn mặc định: 7 ngày
    public static final int MESSAGE_EXPIRY_DAYS = 7;

    /**
     * Đăng ký peer đang online với relay server.
     */
    public void registerPeer(String peerId, String username, String ipAddress, int port) {
        final String sql = """
                INSERT INTO registered_peers (peer_id, username, ip_address, port, last_seen)
                VALUES (?, ?, ?, ?, NOW())
                ON DUPLICATE KEY UPDATE
                    username = VALUES(username),
                    ip_address = VALUES(ip_address),
                    port = VALUES(port),
                    last_seen = NOW()
                """;
        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, peerId);
            ps.setString(2, username);
            ps.setString(3, ipAddress);
            ps.setInt(4, port);
            ps.executeUpdate();
            log.info("[RelayRepo] Registered peer: " + username + " (" + peerId + ")");
        } catch (SQLException e) {
            log.warning("[RelayRepo] registerPeer failed: " + e.getMessage());
        }
    }

    /**
     * Xóa đăng ký peer.
     */
    public void unregisterPeer(String peerId) {
        final String sql = "DELETE FROM registered_peers WHERE peer_id = ?";
        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, peerId);
            ps.executeUpdate();
            log.info("[RelayRepo] Unregistered peer: " + peerId);
        } catch (SQLException e) {
            log.warning("[RelayRepo] unregisterPeer failed: " + e.getMessage());
        }
    }

    /**
     * Cập nhật last_seen của peer.
     */
    public void updatePeerLastSeen(String peerId) {
        final String sql = "UPDATE registered_peers SET last_seen = NOW() WHERE peer_id = ?";
        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, peerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("[RelayRepo] updatePeerLastSeen failed: " + e.getMessage());
        }
    }

    /**
     * Kiểm tra peer có đang online với relay không.
     */
    public boolean isPeerOnline(String peerId) {
        final String sql = "SELECT 1 FROM registered_peers WHERE peer_id = ? AND last_seen > DATE_SUB(NOW(), INTERVAL 30 SECOND)";
        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, peerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.warning("[RelayRepo] isPeerOnline failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Lấy thông tin peer.
     */
    public PeerInfo getPeerInfo(String peerId) {
        final String sql = "SELECT * FROM registered_peers WHERE peer_id = ?";
        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, peerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new PeerInfo(
                            rs.getString("peer_id"),
                            rs.getString("username"),
                            rs.getString("ip_address"),
                            rs.getInt("port"));
                }
            }
        } catch (SQLException e) {
            log.warning("[RelayRepo] getPeerInfo failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * Lưu tin nhắn offline vào relay server.
     */
    public void storeMessage(Message msg, String targetPeerId) {
        final String sql = """
                INSERT INTO relay_messages
                    (message_id, sender_peer_id, sender_username, target_peer_id,
                     content, type, group_id, timestamp, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE message_id = message_id
                """;
        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, msg.getMessageId());
            ps.setString(2, msg.getSenderPeerId());
            ps.setString(3, msg.getSenderUsername());
            ps.setString(4, targetPeerId);
            ps.setString(5, msg.getContent());
            ps.setString(6, msg.getType().name());
            ps.setString(7, msg.getGroupId());

            Timestamp ts = msg.getTimestamp() != null
                    ? Timestamp.valueOf(msg.getTimestamp())
                    : Timestamp.valueOf(LocalDateTime.now());
            ps.setTimestamp(8, ts);

            // Hết hạn sau 7 ngày
            Timestamp expires = Timestamp.valueOf(LocalDateTime.now().plusDays(MESSAGE_EXPIRY_DAYS));
            ps.setTimestamp(9, expires);

            ps.executeUpdate();
            log.info("[RelayRepo] Stored message for " + targetPeerId + ": " + msg.getMessageId());

        } catch (SQLException e) {
            log.warning("[RelayRepo] storeMessage failed: " + e.getMessage());
        }
    }

    /**
     * Lấy tất cả tin nhắn offline đang chờ cho một peer.
     */
    public List<StoredMessage> fetchPendingMessages(String targetPeerId) {
        final String sql = """
                SELECT * FROM relay_messages
                WHERE target_peer_id = ? AND delivered = 0 AND expires_at > NOW()
                ORDER BY timestamp ASC
                """;
        List<StoredMessage> result = new ArrayList<>();
        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, targetPeerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRowToStoredMessage(rs));
                }
            }
            log.info("[RelayRepo] Fetched " + result.size() + " pending messages for " + targetPeerId);
        } catch (SQLException e) {
            log.warning("[RelayRepo] fetchPendingMessages failed: " + e.getMessage());
        }
        return result;
    }

    /**
     * Đánh dấu tin nhắn đã được gửi thành công.
     */
    public void markDelivered(String messageId) {
        final String sql = "UPDATE relay_messages SET delivered = 1 WHERE message_id = ?";
        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, messageId);
            ps.executeUpdate();
            log.info("[RelayRepo] Marked delivered: " + messageId);
        } catch (SQLException e) {
            log.warning("[RelayRepo] markDelivered failed: " + e.getMessage());
        }
    }

    /**
     * Xóa tin nhắn đã gửi thành công.
     */
    public void deleteMessage(String messageId) {
        final String sql = "DELETE FROM relay_messages WHERE message_id = ?";
        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, messageId);
            ps.executeUpdate();
            log.info("[RelayRepo] Deleted message: " + messageId);
        } catch (SQLException e) {
            log.warning("[RelayRepo] deleteMessage failed: " + e.getMessage());
        }
    }

    /**
     * Lấy số tin nhắn đang chờ cho một peer.
     */
    public int getPendingCount(String targetPeerId) {
        final String sql = """
                SELECT COUNT(*) FROM relay_messages
                WHERE target_peer_id = ? AND delivered = 0 AND expires_at > NOW()
                """;
        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, targetPeerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            log.warning("[RelayRepo] getPendingCount failed: " + e.getMessage());
        }
        return 0;
    }

    /**
     * Xóa tin nhắn đã hết hạn.
     */
    public int cleanupExpiredMessages() {
        final String sql = "DELETE FROM relay_messages WHERE expires_at <= NOW() OR delivered = 1";
        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                log.info("[RelayRepo] Cleaned up " + deleted + " expired/delivered messages");
            }
            return deleted;
        } catch (SQLException e) {
            log.warning("[RelayRepo] cleanupExpiredMessages failed: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Xóa peers không hoạt động.
     */
    public int cleanupInactivePeers() {
        final String sql = "DELETE FROM registered_peers WHERE last_seen < DATE_SUB(NOW(), INTERVAL 60 SECOND)";
        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                log.info("[RelayRepo] Cleaned up " + deleted + " inactive peers");
            }
            return deleted;
        } catch (SQLException e) {
            log.warning("[RelayRepo] cleanupInactivePeers failed: " + e.getMessage());
            return 0;
        }
    }

    private StoredMessage mapRowToStoredMessage(ResultSet rs) throws SQLException {
        StoredMessage sm = new StoredMessage();
        sm.messageId = rs.getString("message_id");
        sm.senderPeerId = rs.getString("sender_peer_id");
        sm.senderUsername = rs.getString("sender_username");
        sm.targetPeerId = rs.getString("target_peer_id");
        sm.content = rs.getString("content");
        sm.type = rs.getString("type");
        sm.groupId = rs.getString("group_id");
        Timestamp ts = rs.getTimestamp("timestamp");
        sm.timestamp = ts != null ? ts.toLocalDateTime() : LocalDateTime.now();
        return sm;
    }

    /**
     * Lớp lưu trữ thông tin tin nhắn relay.
     */
    public static class StoredMessage implements java.io.Serializable {
        private static final long serialVersionUID = 1L;

        public String messageId;
        public String senderPeerId;
        public String senderUsername;
        public String targetPeerId;
        public String content;
        public String type;
        public String groupId;
        public LocalDateTime timestamp;

        public StoredMessage() {
        }

        public Message toMessage() {
            Message msg = new Message();
            msg.setMessageId(messageId);
            msg.setSenderPeerId(senderPeerId);
            msg.setSenderUsername(senderUsername);
            msg.setTargetPeerId(targetPeerId);
            msg.setContent(content);
            msg.setGroupId(groupId);
            msg.setTimestamp(timestamp);
            try {
                msg.setType(com.p2pchat.peer.protocol.MessageType.valueOf(type));
            } catch (Exception e) {
                msg.setType(com.p2pchat.peer.protocol.MessageType.CHAT);
            }
            return msg;
        }
    }

    /**
     * Lớp lưu trữ thông tin peer đơn giản.
     */
    public static class PeerInfo {
        public final String peerId;
        public final String username;
        public final String ipAddress;
        public final int port;

        public PeerInfo(String peerId, String username, String ipAddress, int port) {
            this.peerId = peerId;
            this.username = username;
            this.ipAddress = ipAddress;
            this.port = port;
        }
    }
}
