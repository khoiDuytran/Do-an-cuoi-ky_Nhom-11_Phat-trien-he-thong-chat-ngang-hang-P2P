package com.p2pchat.peer.model;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Thông tin của một peer trong mạng P2P
 */
public class PeerInfo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String peerId;
    private String username;
    private String ipAddress;
    private int port;
    private boolean online;
    private LocalDateTime lastSeen;
    private PeerStatus status;

    public enum PeerStatus {
        ONLINE, AWAY, BUSY, OFFLINE
    }

    public PeerInfo() {}

    public PeerInfo(String peerId, String username, String ipAddress, int port) {
        this.peerId = peerId;
        this.username = username;
        this.ipAddress = ipAddress;
        this.port = port;
        this.online = true;
        this.lastSeen = LocalDateTime.now();
        this.status = PeerStatus.ONLINE;
    }

    public String getAddress() {
        return ipAddress + ":" + port;
    }

    // Getters & Setters
    public String getPeerId() { return peerId; }
    public void setPeerId(String peerId) { this.peerId = peerId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public boolean isOnline() { return online; }
    public void setOnline(boolean online) { this.online = online; }

    public LocalDateTime getLastSeen() { return lastSeen; }
    public void setLastSeen(LocalDateTime lastSeen) { this.lastSeen = lastSeen; }

    public PeerStatus getStatus() { return status; }
    public void setStatus(PeerStatus status) { this.status = status; }

    @Override
    public String toString() {
        return String.format("PeerInfo{id='%s', username='%s', address='%s:%d', online=%b}",
                peerId, username, ipAddress, port, online);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PeerInfo p)) return false;
        return peerId != null && peerId.equals(p.peerId);
    }

    @Override
    public int hashCode() {
        return peerId != null ? peerId.hashCode() : 0;
    }
}