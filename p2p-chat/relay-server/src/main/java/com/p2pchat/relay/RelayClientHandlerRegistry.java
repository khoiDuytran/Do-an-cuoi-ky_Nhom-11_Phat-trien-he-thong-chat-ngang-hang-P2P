package com.p2pchat.relay;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry quản lý các kết nối peer đang online với relay server.
 */
public class RelayClientHandlerRegistry {

    private final ConcurrentHashMap<String, RelayClientHandler> handlers = new ConcurrentHashMap<>();

    /**
     * Đăng ký một handler cho peer.
     */
    public void register(String peerId, RelayClientHandler handler) {
        // Đóng handler cũ nếu có
        RelayClientHandler old = handlers.get(peerId);
        if (old != null && old != handler) {
            old.stop();
        }
        handlers.put(peerId, handler);
    }

    /**
     * Xóa đăng ký peer.
     */
    public void unregister(String peerId) {
        handlers.remove(peerId);
    }

    /**
     * Lấy handler của một peer.
     */
    public RelayClientHandler getHandler(String peerId) {
        return handlers.get(peerId);
    }

    /**
     * Kiểm tra peer có đang online không.
     */
    public boolean isOnline(String peerId) {
        RelayClientHandler handler = handlers.get(peerId);
        return handler != null && handler.isRunning();
    }

    /**
     * Lấy số peers đang online.
     */
    public int getOnlineCount() {
        return handlers.size();
    }

    /**
     * Lấy danh sách tất cả peer IDs đang online.
     */
    public java.util.List<String> getOnlinePeerIds() {
        return new java.util.ArrayList<>(handlers.keySet());
    }
}
