package com.p2pchat.bootstrap.repository;

import com.p2pchat.peer.model.PeerInfo;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Repository cho DB1 - Bootstrap server.
 * Xử lý: đăng ký peer, trạng thái online, danh sách peers, quản lý group.
 */
public class UserRepository {

    private static final Logger log = Logger.getLogger(UserRepository.class.getName());
    private final BootstrapDatabaseConfig db = BootstrapDatabaseConfig.getInstance();

    // ─── users ────────────────────────────────────────────────────────────────

    /**
     * Đăng ký peer lần đầu hoặc cập nhật thông tin mạng (login).
     * Dùng UPSERT để xử lý cả INSERT và UPDATE trong một lệnh.
     */
    public void registerOrUpdate(PeerInfo peer) {
        final String sql = """
            INSERT INTO users (peer_id, username, last_ip, last_port, is_online, last_seen)
            VALUES (?, ?, ?, ?, 1, NOW())
            ON DUPLICATE KEY UPDATE
                username   = VALUES(username),
                last_ip    = VALUES(last_ip),
                last_port  = VALUES(last_port),
                is_online  = 1,
                last_seen  = NOW()
            """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, peer.getPeerId());
            ps.setString(2, peer.getUsername());
            ps.setString(3, peer.getIpAddress());
            ps.setInt   (4, peer.getPort());
            ps.executeUpdate();
            log.info("[DB1] Registered/updated peer: " + peer.getUsername()
                    + " @ " + peer.getIpAddress() + ":" + peer.getPort());
        } catch (SQLException e) {
            log.severe("registerOrUpdate: " + e.getMessage());
        }
    }

    /** Đánh dấu peer offline khi ngắt kết nối */
    public void setOffline(String peerId) {
        final String sql = "UPDATE users SET is_online = 0, last_seen = NOW() WHERE peer_id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, peerId);
            int rows = ps.executeUpdate();
            if (rows > 0) log.info("[DB1] Peer offline: " + peerId);
        } catch (SQLException e) {
            log.warning("setOffline: " + e.getMessage());
        }
    }

    /** Đánh dấu tất cả peers offline (dùng khi bootstrap restart) */
    public void setAllOffline() {
        final String sql = "UPDATE users SET is_online = 0";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int rows = ps.executeUpdate();
            log.info("[DB1] Reset " + rows + " peers to offline.");
        } catch (SQLException e) {
            log.warning("setAllOffline: " + e.getMessage());
        }
    }

    /** Lấy danh sách tất cả peers đang online (trừ chính peer đang hỏi) */
    public List<PeerInfo> getOnlinePeers(String excludePeerId) {
        final String sql = """
            SELECT peer_id, username, last_ip, last_port
            FROM   users
            WHERE  is_online = 1
              AND  peer_id != ?
            ORDER BY last_seen DESC
            """;
        List<PeerInfo> peers = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, excludePeerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PeerInfo p = new PeerInfo(
                            rs.getString("peer_id"),
                            rs.getString("username"),
                            rs.getString("last_ip"),
                            rs.getInt("last_port")
                    );
                    p.setOnline(true);
                    peers.add(p);
                }
            }
        } catch (SQLException e) {
            log.warning("getOnlinePeers: " + e.getMessage());
        }
        return peers;
    }

    /** Lấy thông tin một peer theo peer_id */
    public PeerInfo findByPeerId(String peerId) {
        final String sql = "SELECT * FROM users WHERE peer_id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, peerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    PeerInfo p = new PeerInfo(
                            rs.getString("peer_id"),
                            rs.getString("username"),
                            rs.getString("last_ip"),
                            rs.getInt("last_port")
                    );
                    p.setOnline(rs.getBoolean("is_online"));
                    return p;
                }
            }
        } catch (SQLException e) {
            log.warning("findByPeerId: " + e.getMessage());
        }
        return null;
    }

    /** Đếm số peer đang online */
    public int countOnlinePeers() {
        final String sql = "SELECT COUNT(*) FROM users WHERE is_online = 1";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            log.warning("countOnlinePeers: " + e.getMessage());
        }
        return 0;
    }

}