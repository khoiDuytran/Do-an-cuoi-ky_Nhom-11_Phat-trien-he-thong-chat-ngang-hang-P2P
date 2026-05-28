package com.p2pchat.peer.repository;

import com.p2pchat.peer.model.ChatGroup;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Repository lưu trữ group data trong DB2 (MySQL local của mỗi peer).
 */
public class GroupRepository {

    private static final Logger log = Logger.getLogger(GroupRepository.class.getName());
    private final DatabaseConfig db = DatabaseConfig.getInstance();

    // ─── Schema (tự tạo khi khởi tạo DatabaseConfig) ─────────────────────────
    // Bảng peer_groups và peer_group_members được thêm vào createTables()

    // ─── CRUD groups

    /** Lưu group mới hoặc update nếu đã tồn tại */
    public void saveGroup(ChatGroup group) {
        final String sql = """
                INSERT INTO peer_groups (group_id, group_name, owner_peer_id, created_at)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    group_name    = VALUES(group_name),
                    owner_peer_id = VALUES(owner_peer_id)
                """;
        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, group.getGroupId());
            ps.setString(2, group.getGroupName());
            ps.setString(3, group.getOwnerPeerId());
            ps.setTimestamp(4, group.getCreatedAt() != null
                    ? Timestamp.valueOf(group.getCreatedAt())
                    : Timestamp.valueOf(LocalDateTime.now()));
            ps.executeUpdate();

            // Lưu members
            saveMembers(group);
            log.info("[GroupRepo] Saved group: " + group.getGroupName()
                    + " (" + group.getGroupId() + ")");

        } catch (SQLException e) {
            log.severe("saveGroup: " + e.getMessage());
        }
    }

    /** Thêm một member vào group */
    public void addMember(String groupId, String peerId) {
        final String sql = """
                INSERT IGNORE INTO peer_group_members (group_id, peer_id, joined_at)
                VALUES (?, ?, NOW())
                """;
        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, groupId);
            ps.setString(2, peerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("addMember: " + e.getMessage());
        }
    }

    /** Xóa một member khỏi group */
    public void removeMember(String groupId, String peerId) {
        final String sql = "DELETE FROM peer_group_members WHERE group_id = ? AND peer_id = ?";
        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, groupId);
            ps.setString(2, peerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("removeMember: " + e.getMessage());
        }
    }

    /** Xóa hoàn toàn group */
    public void deleteGroup(String groupId) {
        // ON DELETE CASCADE xóa members theo
        final String sql = "DELETE FROM peer_groups WHERE group_id = ?";
        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, groupId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("deleteGroup: " + e.getMessage());
        }
    }

    /** Load tất cả groups mà peer này là thành viên */
    public List<ChatGroup> loadAllGroups() {
        final String sql = """
                SELECT g.group_id, g.group_name, g.owner_peer_id, g.created_at
                FROM   peer_groups g
                ORDER BY g.created_at ASC
                """;
        List<ChatGroup> result = new ArrayList<>();
        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                ChatGroup g = new ChatGroup(
                        rs.getString("group_id"),
                        rs.getString("group_name"),
                        rs.getString("owner_peer_id"));
                Timestamp ts = rs.getTimestamp("created_at");
                if (ts != null)
                    g.setCreatedAt(ts.toLocalDateTime());

                // Load members
                g.setMemberPeerIds(loadMembers(rs.getString("group_id")));
                result.add(g);
            }

        } catch (SQLException e) {
            log.warning("loadAllGroups: " + e.getMessage());
        }
        return result;
    }

    /** Load members của một group */
    public List<String> loadMembers(String groupId) {
        final String sql = "SELECT peer_id FROM peer_group_members WHERE group_id = ?";
        List<String> members = new ArrayList<>();
        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    members.add(rs.getString("peer_id"));
            }
        } catch (SQLException e) {
            log.warning("loadMembers: " + e.getMessage());
        }
        return members;
    }

    private void saveMembers(ChatGroup group) throws SQLException {
        final String sql = """
                INSERT IGNORE INTO peer_group_members (group_id, peer_id, joined_at)
                VALUES (?, ?, NOW())
                """;
        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String memberId : group.getMemberPeerIds()) {
                ps.setString(1, group.getGroupId());
                ps.setString(2, memberId);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
