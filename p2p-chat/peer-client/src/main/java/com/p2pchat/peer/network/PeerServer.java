package com.p2pchat.peer.network;

import com.p2pchat.peer.protocol.Message;

import java.io.IOException;
import java.net.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Server TCP lắng nghe kết nối đến từ các peer khác.
 * messageHandler nhận (Message, ConnectionHandler) để caller có thể reply.
 */
public class PeerServer implements Runnable {

    private static final Logger log = Logger.getLogger(PeerServer.class.getName());

    private final int port;
    // BiConsumer: (message, connectionHandler) — handler để gửi reply trực tiếp
    private final BiConsumer<Message, ConnectionHandler> messageHandler;
    private final Consumer<ConnectionHandler> onNewConnection;
    private ServerSocket serverSocket;
    private final ExecutorService threadPool;
    private volatile boolean running = false;

    public PeerServer(int port,
                      BiConsumer<Message, ConnectionHandler> messageHandler,
                      Consumer<ConnectionHandler> onNewConnection) {
        this.port = port;
        this.messageHandler = messageHandler;
        this.onNewConnection = onNewConnection;
        this.threadPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("peer-conn-" + t.getId());
            return t;
        });
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(port));
            running = true;
            log.info("PeerServer listening on port " + port);

            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    clientSocket.setTcpNoDelay(true);
                    clientSocket.setKeepAlive(true);

                    ConnectionHandler[] ref = new ConnectionHandler[1];
                    ref[0] = new ConnectionHandler(
                            clientSocket,
                            msg -> messageHandler.accept(msg, ref[0]),
                            () -> {}
                    );
                    ConnectionHandler handler = ref[0];

                    if (onNewConnection != null) {
                        onNewConnection.accept(handler);
                    }

                    threadPool.submit(handler);
                    log.info("Accepted connection from: " + clientSocket.getRemoteSocketAddress());

                } catch (SocketException e) {
                    if (running) log.warning("Accept error: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            log.severe("PeerServer failed to start on port " + port + ": " + e.getMessage());
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}
        threadPool.shutdownNow();
        log.info("PeerServer stopped.");
    }

    public int getPort() { return port; }
    public boolean isRunning() { return running; }

    /**
     * Tìm port khả dụng tự động
     */
    public static int findAvailablePort(int startPort) {
        for (int p = startPort; p < startPort + 100; p++) {
            try (ServerSocket s = new ServerSocket(p)) {
                return p;
            } catch (IOException ignored) {}
        }
        return startPort;
    }
}