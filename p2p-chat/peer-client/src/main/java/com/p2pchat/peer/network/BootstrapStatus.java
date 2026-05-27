package com.p2pchat.peer.network;

/**
 * Trạng thái kết nối bootstrap server, dùng để truyền lên UI.
 *
 * @param state            CONNECTED / DISCONNECTED / RECONNECTING
 * @param reconnectAttempt số lần thử hiện tại (1-based), chỉ valid khi RECONNECTING
 * @param reconnectMax     tổng số lần thử tối đa, chỉ valid khi RECONNECTING
 */
public record BootstrapStatus(
        BootstrapState state,
        int reconnectAttempt,
        int reconnectMax
) {
    public static BootstrapStatus connected() {
        return new BootstrapStatus(BootstrapState.CONNECTED, 0, 0);
    }

    public static BootstrapStatus disconnected() {
        return new BootstrapStatus(BootstrapState.DISCONNECTED, 0, 0);
    }

    public static BootstrapStatus reconnecting(int attempt, int max) {
        return new BootstrapStatus(BootstrapState.RECONNECTING, attempt, max);
    }
}
