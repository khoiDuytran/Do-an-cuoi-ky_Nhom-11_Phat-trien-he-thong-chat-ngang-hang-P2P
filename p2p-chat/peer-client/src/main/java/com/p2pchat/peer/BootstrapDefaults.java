package com.p2pchat.peer;

/**
 * Mặc định khi peer chọn "Via Bootstrap" — đổi cho khớp máy chạy bootstrap-server.
 * (Trùng IP máy chạy bootstrap-server — server tự phát hiện IP LAN khi in log.)
 */
public final class BootstrapDefaults {

    public static final String HOST = "192.168.128.1";
    public static final int PORT = 9000;

    private BootstrapDefaults() {}
}
