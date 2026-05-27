package com.p2pchat.peer;

/**
 * Mặc định khi peer kết nối đến Relay Server.
 * HOST sử dụng cùng IP với BootstrapDefaults (chạy trên cùng máy).
 */
public final class RelayDefaults {

    public static final String HOST = "172.23.160.1";
    public static final int PORT = 9100;

    private RelayDefaults() {}
}
