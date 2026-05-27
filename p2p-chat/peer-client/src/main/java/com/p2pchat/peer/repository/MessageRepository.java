package com.p2pchat.peer.repository;

import com.p2pchat.peer.model.ChatMessage;
import com.p2pchat.peer.model.PeerInfo;
import com.p2pchat.peer.protocol.Message;

import java.io.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Repository thao tác với DB2 (MySQL local của mỗi peer).
 * Bảng messages: lưu lịch sử tin nhắn gửi/nhận.
 */
public class MessageRepository {

    private static final Logger log = Logger.getLogger(MessageRepository.class.getName());
    private final DatabaseConfig db = DatabaseConfig.getInstance();

    // ─── messages ─────────────────────────────────────────────────────────────

    /** Lưu tin nhắn mới (INSERT IGNORE để idempotent) */
    public void saveMessage(ChatMessage msg) {
        final String sql = """
                INSERT IGNORE INTO messages
                    (message_id, sender_peer_id, sender_username,
                     target_peer_id, group_id, content, type,
                     sent_at, delivered, is_own)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """;
        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, msg.getMessageId());
            ps.setString(2, msg.getSenderPeerId());
            ps.setString(3, msg.getSenderUsername());
            ps.setString(4, msg.getTargetPeerId());
            ps.setString(5, msg.getGroupId());
            ps.setString(6, msg.getContent());
            ps.setString(7, msg.getType().name());
            ps.setTimestamp(8, toTimestamp(msg.getSentAt()));
            ps.setBoolean(9, msg.isDelivered());
            ps.setBoolean(10, msg.isOwn());
            ps.executeUpdate();

        } catch (SQLException e) {
            log.warning("saveMessage: " + e.getMessage());
        }
    }

    /** Cập nhật nội dung (vd. sau khi người dùng tải file về đĩa). */
    public void updateMessageContent(String messageId, String newContent) {
        final String sql = "UPDATE messages SET content = ? WHERE message_id = ?";
        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newContent);
            ps.setString(2, messageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("updateMessageContent: " + e.getMessage());
        }
    }

    /** Đánh dấu tin nhắn đã được nhận bởi peer đích */
    public void markDelivered(String messageId) {
        final String sql = "UPDATE messages SET delivered = 1 WHERE message_id = ?";
        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, messageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("markDelivered: " + e.getMessage());
        }
    }

    /**
     * Đánh dấu tin nhắn chưa được deliver (đang chờ relay server).
     * Gọi khi phát hiện peer offline và tin nhắn được chuyển sang relay store.
     */
    public void markUndelivered(String messageId) {
        final String sql = "UPDATE messages SET delivered = 0 WHERE message_id = ?";
        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, messageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("markUndelivered: " + e.getMessage());
        }
    }

    /**
     * Lịch sử chat trực tiếp giữa hai peer.
     * Dùng index idx_direct → rất nhanh.
     */
    public List<ChatMessage> getDirectHistory(String myPeerId, String otherPeerId, int limit) {
        final String sql = """
                SELECT * FROM messages
                WHERE type IN ('CHAT','FILE')
                    AND sender_username IS NOT NULL
                  AND ((sender_peer_id = ? AND target_peer_id = ?)
                    OR (sender_peer_id = ? AND target_peer_id = ?))
                ORDER BY sent_at DESC
                LIMIT ?
                """;
        List<ChatMessage> result = new ArrayList<>();
        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, myPeerId);
            ps.setString(2, otherPeerId);
            ps.setString(3, otherPeerId);
            ps.setString(4, myPeerId);
            ps.setInt(5, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    result.add(0, mapRow(rs)); // reverse → chronological
            }
        } catch (SQLException e) {
            log.warning("getDirectHistory: " + e.getMessage());
        }
        return result;
    }

    /**
     * Lịch sử chat nhóm.
     * Dùng index idx_group → rất nhanh.
     */
    public List<ChatMessage> getGroupHistory(String groupId, int limit) {
        final String sql = """
                SELECT * FROM messages
                WHERE group_id = ?
                ORDER BY sent_at DESC
                LIMIT ?
                """;
        List<ChatMessage> result = new ArrayList<>();
        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, groupId);
            ps.setInt(2, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    result.add(0, mapRow(rs));
            }
        } catch (SQLException e) {
            log.warning("getGroupHistory: " + e.getMessage());
        }
        return result;
    }

    // ─── pending_messages (store-and-forward) ─────────────────────────────────

    /** Lưu tin nhắn vào hàng đợi khi peer đích offline */
    public void savePendingMessage(Message msg) {
        final String sql = """
                INSERT IGNORE INTO pending_messages
                    (message_id, target_peer_id, content, type,
                     group_id, created_at, retry_count, serialized_msg)
                VALUES (?,?,?,?,?,NOW(3),0,?)
                """;
        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, msg.getMessageId());
            ps.setString(2, msg.getTargetPeerId());
            ps.setString(3, msg.getContent());
            ps.setString(4, msg.getType().name());
            ps.setString(5, msg.getGroupId());
            ps.setBytes(6, serialize(msg));
            ps.executeUpdate();

        } catch (SQLException e) {
            log.warning("savePendingMessage: " + e.getMessage());
        }
    }

    /**
     * Lấy tất cả tin nhắn đang chờ gửi tới một peer (retry_count < 5).
     * Dùng index idx_pending → nhanh.
     */
    public List<Message> getPendingMessages(String targetPeerId) {
        final String sql = """
                SELECT serialized_msg FROM pending_messages
                WHERE target_peer_id = ? AND retry_count < 5
                ORDER BY created_at ASC
                """;
        List<Message> result = new ArrayList<>();
        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, targetPeerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Message m = deserialize(rs.getBytes("serialized_msg"));
                    if (m != null)
                        result.add(m);
                }
            }
        } catch (SQLException e) {
            log.warning("getPendingMessages: " + e.getMessage());
        }
        return result;
    }

    /** Xóa khỏi hàng đợi sau khi gửi thành công */
    public void deletePendingMessage(String messageId) {
        final String sql = "DELETE FROM pending_messages WHERE message_id = ?";
        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, messageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("deletePendingMessage: " + e.getMessage());
        }
    }

    /** Tăng retry_count, tự động bỏ qua sau 5 lần thất bại */
    public void incrementRetryCount(String messageId) {
        final String sql = "UPDATE pending_messages SET retry_count = retry_count + 1 WHERE message_id = ?";
        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, messageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("incrementRetryCount: " + e.getMessage());
        }
    }

    // ─── historical peers ──────────────────────────────────────────────────────

    public List<PeerInfo> getHistoricalPeers(String myPeerId) {
        final String sql = """
                SELECT peer_id,
                       MAX(username)   AS username,
                       MAX(last_seen)  AS last_seen
                FROM (
                    -- Peer gửi cho mình → username đúng
                    SELECT sender_peer_id  AS peer_id,
                           sender_username AS username,
                           sent_at         AS last_seen
                    FROM messages
                    WHERE target_peer_id = ?
                      AND group_id IS NULL
                      AND type IN ('CHAT','FILE')
                      AND sender_username IS NOT NULL

                    UNION ALL

                    -- Mình gửi cho peer → peer_id đúng nhưng KHÔNG dùng sender_username của mình
                    -- Thay vào đó lấy username từ tin peer gửi cho mình (subquery)
                    SELECT target_peer_id AS peer_id,
                           (SELECT sender_username FROM messages m2
                            WHERE m2.sender_peer_id = m1.target_peer_id
                              AND m2.target_peer_id = ?
                              AND m2.sender_username IS NOT NULL
                            ORDER BY m2.sent_at DESC LIMIT 1)  AS username,
                           sent_at AS last_seen
                    FROM messages m1
                    WHERE sender_peer_id = ?
                      AND group_id IS NULL
                      AND type IN ('CHAT','FILE')
                      AND target_peer_id IS NOT NULL
                ) combined
                WHERE peer_id IS NOT NULL
                  AND peer_id <> ?
                  AND username IS NOT NULL
                GROUP BY peer_id
                ORDER BY last_seen DESC
                """;

        List<PeerInfo> result = new ArrayList<>();
        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, myPeerId); // target_peer_id = tôi (peer gửi cho tôi)
            ps.setString(2, myPeerId); // target_peer_id = tôi (subquery lấy username)
            ps.setString(3, myPeerId); // sender_peer_id = tôi (tôi gửi đi)
            ps.setString(4, myPeerId); // peer_id <> tôi

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PeerInfo pi = new PeerInfo();
                    pi.setPeerId(rs.getString("peer_id"));
                    pi.setUsername(rs.getString("username"));
                    pi.setOnline(false);
                    pi.setStatus(PeerInfo.PeerStatus.OFFLINE);
                    Timestamp ts = rs.getTimestamp("last_seen");
                    if (ts != null)
                        pi.setLastSeen(ts.toLocalDateTime());
                    result.add(pi);
                }
            }
        } catch (SQLException e) {
            log.warning("getHistoricalPeers: " + e.getMessage());
        }
        return result;
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private ChatMessage mapRow(ResultSet rs) throws SQLException {
        ChatMessage cm = new ChatMessage();
        cm.setId(rs.getLong("id"));
        cm.setMessageId(rs.getString("message_id"));
        cm.setSenderPeerId(rs.getString("sender_peer_id"));
        cm.setSenderUsername(rs.getString("sender_username"));
        cm.setTargetPeerId(rs.getString("target_peer_id"));
        cm.setGroupId(rs.getString("group_id"));
        cm.setContent(rs.getString("content"));
        cm.setType(ChatMessage.MessageType.valueOf(rs.getString("type")));
        Timestamp ts = rs.getTimestamp("sent_at");
        cm.setSentAt(ts != null ? ts.toLocalDateTime() : LocalDateTime.now());
        cm.setDelivered(rs.getBoolean("delivered"));
        cm.setOwn(rs.getBoolean("is_own"));
        return cm;
    }

    private byte[] serialize(Message msg) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(msg);
            return bos.toByteArray();
        } catch (IOException e) {
            log.severe("serialize failed: " + e.getMessage());
            return new byte[0];
        }
    }

    private Message deserialize(byte[] data) {
        if (data == null || data.length == 0)
            return null;
        try (ByteArrayInputStream bis = new ByteArrayInputStream(data);
                ObjectInputStream ois = new ObjectInputStream(bis)) {
            return (Message) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            log.warning("deserialize failed: " + e.getMessage());
            return null;
        }
    }

    private Timestamp toTimestamp(LocalDateTime ldt) {
        return ldt != null ? Timestamp.valueOf(ldt) : Timestamp.valueOf(LocalDateTime.now());
    }
}