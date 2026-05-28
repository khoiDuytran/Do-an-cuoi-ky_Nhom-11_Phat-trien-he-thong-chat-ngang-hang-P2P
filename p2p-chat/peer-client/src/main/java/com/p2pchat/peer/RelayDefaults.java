package com.p2pchat.peer;

/**
 * Mặc định khi peer kết nối đến Relay Server.
 * HOST sử dụng cùng IP với BootstrapDefaults (chạy trên cùng máy).
 */
public final class RelayDefaults {

    public static final String HOST = "10.234.131.46";
    public static final int PORT = 9100;

    private RelayDefaults() {
    }
}
