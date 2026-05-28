package com.p2pchat.peer.network;

public record BootstrapStatus(
        BootstrapState state,
        int reconnectAttempt,
        int reconnectMax) {
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
