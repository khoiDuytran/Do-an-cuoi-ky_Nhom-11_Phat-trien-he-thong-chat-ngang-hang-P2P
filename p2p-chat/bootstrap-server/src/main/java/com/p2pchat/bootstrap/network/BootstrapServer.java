package com.p2pchat.bootstrap.network;

import com.p2pchat.bootstrap.BootstrapNetworkConfig;
import com.p2pchat.bootstrap.repository.BootstrapDatabaseConfig;
import com.p2pchat.bootstrap.repository.UserRepository;
import com.p2pchat.peer.protocol.Message;

import java.io.IOException;
import java.net.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Bootstrap Server - lắng nghe kết nối TCP từ các peer.
 * Quản lý danh sách peers online và broadcast sự kiện join/leave.
 */
public class BootstrapServer {

    private static final Logger log = Logger.getLogger(BootstrapServer.class.getName());

    private final int port;
    private final UserRepository userRepo;
    private ServerSocket serverSocket;
    private final ExecutorService threadPool;
    private volatile boolean running = false;

    // Danh sách handlers đang kết nối: peerId → handler
    private final ConcurrentHashMap<String, BootstrapClientHandler> connectedHandlers = new ConcurrentHashMap<>();

    public BootstrapServer(int port) {
        this.port = port;
        this.userRepo = new UserRepository();
        this.threadPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("bs-handler-" + t.getId());
            return t;
        });
    }

    public void start() throws IOException {
        // Reset trạng thái online từ lần chạy trước
        userRepo.setAllOffline();

        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        running = true;

        log.info("╔══════════════════════════════════════╗");
        log.info("║   P2P Chat Bootstrap Server          ║");
        log.info("║   Listening on port: " + port + "           ║");
        log.info("╚══════════════════════════════════════╝");
        log.info("[Bootstrap] Peers on LAN: host=" + BootstrapNetworkConfig.getAdvertisedLanHost()
                + "  port=" + port + "  (IP tự phát hiện từ giao diện mạng; kiểm tra nếu có nhiều NIC)");

        // Heartbeat scheduler: dọn dẹp peers mất kết nối
        ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bs-cleaner");
            t.setDaemon(true);
            return t;
        });
        cleaner.scheduleAtFixedRate(this::cleanDeadConnections, 30, 30, TimeUnit.SECONDS);

        // Accept loop
        while (running) {
            try {
                Socket client = serverSocket.accept();
                client.setTcpNoDelay(true);
                client.setKeepAlive(true);

                BootstrapClientHandler handler = new BootstrapClientHandler(
                        client,
                        userRepo,
                        this::onHandlerDisconnected,
                        this::broadcastToAll,
                        this::registerHandler // ← callback khi REGISTER xong
                );

                threadPool.submit(handler);
                log.info("[Bootstrap] New connection from: " + client.getRemoteSocketAddress());

            } catch (SocketException e) {
                if (running)
                    log.warning("Accept error: " + e.getMessage());
            }
        }
    }

    /**
     * Broadcast message tới tất cả peers đang kết nối, trừ excludePeerId
     */
    private void broadcastToAll(Message msg, String excludePeerId) {
        int sent = 0;
        for (var entry : connectedHandlers.entrySet()) {
            if (!entry.getKey().equals(excludePeerId) && entry.getValue().isRunning()) {
                entry.getValue().sendMessage(msg);
                sent++;
            }
        }
        log.fine("[Bootstrap] Broadcast " + msg.getType() + " → " + sent + " peers");
    }

    /** Được gọi bởi handler ngay sau khi REGISTER thành công */
    private void registerHandler(BootstrapClientHandler handler) {
        connectedHandlers.put(handler.getPeerId(), handler);
        log.info("[Bootstrap] Handler registered for: " + handler.getUsername()
                + " | total connected: " + connectedHandlers.size());
    }

    private void onHandlerDisconnected(BootstrapClientHandler handler) {
        if (handler.getPeerId() != null) {
            connectedHandlers.remove(handler.getPeerId());
            log.info("[Bootstrap] Handler removed for: " + handler.getUsername()
                    + " | online: " + connectedHandlers.size());
        }
    }

    private void cleanDeadConnections() {
        int removed = 0;
        for (var it = connectedHandlers.entrySet().iterator(); it.hasNext();) {
            var entry = it.next();
            if (!entry.getValue().isRunning()) {
                it.remove();
                userRepo.setOffline(entry.getKey());
                removed++;
            }
        }
        if (removed > 0)
            log.info("[Bootstrap] Cleaned " + removed + " dead connections.");
        log.info("[Bootstrap] Online peers: " + connectedHandlers.size()
                + " | DB online: " + userRepo.countOnlinePeers());
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null)
                serverSocket.close();
        } catch (IOException ignored) {
        }
        threadPool.shutdownNow();
        BootstrapDatabaseConfig.getInstance().shutdown();
        log.info("[Bootstrap] Server stopped.");
    }

    public int getOnlineCount() {
        return connectedHandlers.size();
    }

    // ─── main entry point ──────────────────────────────────────────────────────

    public static void main(String[] args) {
        // Đọc cấu hình từ args hoặc dùng mặc định
        int bsPort = args.length > 0 ? Integer.parseInt(args[0]) : 9000;
        String dbHost = args.length > 1 ? args[1] : "localhost";
        int dbPort = args.length > 2 ? Integer.parseInt(args[2]) : 3306;
        String dbUser = args.length > 3 ? args[3] : "root";
        String dbPass = args.length > 4 ? args[4] : "khoi94";

        // Setup logging
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "[%1$tF %1$tT] [%4$s] %5$s%6$s%n");

        try {
            // Khởi tạo DB1
            BootstrapDatabaseConfig.getInstance().initialize(dbHost, dbPort, dbUser, dbPass);

            // Khởi động server
            BootstrapServer server = new BootstrapServer(bsPort);

            // Graceful shutdown
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down bootstrap server...");
                server.stop();
            }));

            server.start();

        } catch (Exception e) {
            log.severe("Bootstrap server failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}