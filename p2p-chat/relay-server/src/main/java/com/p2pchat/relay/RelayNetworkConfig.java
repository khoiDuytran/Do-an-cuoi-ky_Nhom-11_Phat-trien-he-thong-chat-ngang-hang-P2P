package com.p2pchat.relay;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;

/**
 * Cấu hình mạng relay: IP LAN dùng để peers trên máy khác biết kết nối tới đâu.
 */
public final class RelayNetworkConfig {

    /** Cổng TCP peer kết nối tới (trùng tham số dòng lệnh khi chạy jar). */
    public static final int DEFAULT_LISTEN_PORT = 9100;

    private RelayNetworkConfig() {
    }

    /**
     * IPv4 LAN của máy đang chạy relay-server (ưu tiên địa chỉ private RFC1918).
     * Nếu không tìm thấy, thử {InetAddress#getLocalHost()} rồi {127.0.0.1}.
     */
    public static String getAdvertisedLanHost() {
        List<Inet4Address> found = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        found.add((Inet4Address) a);
                    }
                }
            }
        } catch (SocketException ignored) {
            // fall through
        }

        if (!found.isEmpty()) {
            found.sort(Comparator.comparingInt(RelayNetworkConfig::ipv4LanScore).reversed());
            return found.get(0).getHostAddress();
        }

        try {
            InetAddress local = InetAddress.getLocalHost();
            if (local instanceof Inet4Address && !local.isLoopbackAddress()) {
                return local.getHostAddress();
            }
        } catch (UnknownHostException ignored) {
            // fall through
        }

        return "127.0.0.1";
    }

    /** Cao hơn = ưu tiên hơn cho địa chỉ quảng bá LAN. */
    private static int ipv4LanScore(Inet4Address a) {
        if (a.isSiteLocalAddress()) {
            return 300;
        }
        if (!a.isLinkLocalAddress()) {
            return 200;
        }
        return 100;
    }
}
