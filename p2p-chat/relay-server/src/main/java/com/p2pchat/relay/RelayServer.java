package com.p2pchat.relay;

import java.io.IOException;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Relay Server - Server relay lưu trữ tin nhắn offline.
 * Lắng nghe kết nối TCP từ các peers trên port 9100.
 */
public class RelayServer {

    private static final Logger log = Logger.getLogger(RelayServer.class.getName());

    private final int port;
    private final RelayRepository repo;
    private final RelayClientHandlerRegistry registry;
    private ServerSocket serverSocket;
    private final ExecutorService threadPool;
    private final ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    private final AtomicInteger totalConnections = new AtomicInteger(0);
    private final AtomicInteger totalMessagesStored = new AtomicInteger(0);

    public RelayServer(int port) {
        this.port = port;
        this.repo = new RelayRepository();
        this.registry = new RelayClientHandlerRegistry();
        this.threadPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("relay-handler-" + t.getId());
            return t;
        });
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "relay-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() throws IOException {
        // Khởi tạo database
        try {
            RelayDatabaseConfig.getInstance().initialize();
        } catch (Exception e) {
            throw new IOException("Failed to initialize database: " + e.getMessage(), e);
        }

        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        running = true;

        // Lên lịch cleanup định kỳ
        scheduler.scheduleAtFixedRate(this::cleanupExpiredData, 5, 5, TimeUnit.MINUTES);

        // Lấy IP LAN để quảng bá
        String lanHost = RelayNetworkConfig.getAdvertisedLanHost();

        log.info("╔══════════════════════════════════════════╗");
        log.info("║   P2P Chat Relay Server              ║");
        log.info("║   Listening on port: " + port + "                  ║");
        log.info("║   LAN Host (for peers): " + lanHost + "       ║");
        log.info("║   Message expiry: 7 days              ║");
        log.info("╚══════════════════════════════════════════╝");
        log.info("[Relay] Peers on LAN: host=" + lanHost + "  port=" + port);

        acceptLoop();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setTcpNoDelay(true);
                clientSocket.setKeepAlive(true);

                int connId = totalConnections.incrementAndGet();
                log.info("[Relay] New connection #" + connId + " from: " + clientSocket.getRemoteSocketAddress());

                RelayClientHandler handler = new RelayClientHandler(
                        clientSocket,
                        repo,
                        registry,
                        () -> onClientDisconnected());

                threadPool.submit(handler);

            } catch (SocketException e) {
                if (running) {
                    log.warning("[Relay] Accept error: " + e.getMessage());
                }
            } catch (IOException e) {
                if (running) {
                    log.warning("[Relay] Accept error: " + e.getMessage());
                }
            }
        }
    }

    private void onClientDisconnected() {
        int online = registry.getOnlineCount();
        log.info("[Relay] Client disconnected. Online peers: " + online);
    }

    private void cleanupExpiredData() {
        try {
            int expiredMessages = repo.cleanupExpiredMessages();
            int inactivePeers = repo.cleanupInactivePeers();

            if (expiredMessages > 0 || inactivePeers > 0) {
                log.info("[Relay] Cleanup complete: " + expiredMessages + " messages, " + inactivePeers
                        + " peers removed");
            }
        } catch (Exception e) {
            log.warning("[Relay] Cleanup error: " + e.getMessage());
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }

        threadPool.shutdownNow();
        scheduler.shutdownNow();

        RelayDatabaseConfig.getInstance().shutdown();

        log.info("[Relay] Server stopped. Total connections: " + totalConnections.get());
    }

    public int getOnlinePeerCount() {
        return registry.getOnlineCount();
    }

    public static void main(String[] args) {
        // Đọc cấu hình từ args hoặc dùng mặc định
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9100;
        String dbHost = args.length > 1 ? args[1] : "localhost";
        int dbPort = args.length > 2 ? Integer.parseInt(args[2]) : 3306;
        String dbUser = args.length > 3 ? args[3] : "root";
        String dbPass = args.length > 4 ? args[4] : "khoi94";

        // Setup logging
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "[%1$tF %1$tT] [%4$s] %5$s%6$s%n");

        try {
            // Khởi tạo DB3
            RelayDatabaseConfig.getInstance().initialize(dbHost, dbPort, dbUser, dbPass);

            // Khởi động server
            RelayServer server = new RelayServer(port);

            // Graceful shutdown
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down relay server...");
                server.stop();
            }));

            server.start();

        } catch (Exception e) {
            log.severe("Relay server failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
