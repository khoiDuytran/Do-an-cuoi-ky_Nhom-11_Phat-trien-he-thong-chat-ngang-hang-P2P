package com.p2pchat.peer.network;

import com.p2pchat.peer.model.PeerInfo;
import com.p2pchat.peer.protocol.Message;
import com.p2pchat.peer.protocol.MessageType;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;

/**
 * Client kết nối tới Bootstrap Server để đăng ký và lấy danh sách peer.
 * Duy trì kết nối persistent để nhận PEER_JOINED / PEER_LEFT notifications.
 * Tự động reconnect với exponential backoff khi mất kết nối.
 */
public class BootstrapClient implements Runnable {

    private static final Logger log = Logger.getLogger(BootstrapClient.class.getName());
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int RECONNECT_BASE_DELAY_MS = 3_000;
    private static final int MAX_RECONNECT_ATTEMPTS = 3;

    private BootstrapStatus lastEmittedStatus = null;

    private final String bootstrapHost;
    private final int bootstrapPort;
    private final String peerId;
    private final String username;
    private final int myPort;

    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private volatile boolean running = false;

    private final java.util.function.Consumer<Message> eventHandler;
    private java.util.function.Consumer<BootstrapStatus> onStatusChange;
    private java.util.function.BiConsumer<Integer, Integer> onReconnectAttempt;

    public BootstrapClient(String bootstrapHost, int bootstrapPort,
            String peerId, String username, int myPort,
            java.util.function.Consumer<Message> eventHandler) {
        this.bootstrapHost = bootstrapHost;
        this.bootstrapPort = bootstrapPort;
        this.peerId = peerId;
        this.username = username;
        this.myPort = myPort;
        this.eventHandler = eventHandler;
    }

    /**
     * Kết nối và đăng ký với bootstrap server.
     * Tạo socket mới, ghi đè socket cũ (socket cũ đã được close trước khi gọi).
     * 
     * return danh sách peers hiện tại, hoặc empty list nếu lỗi
     */
    public List<PeerInfo> connectAndRegister() throws IOException {
        // Ensure old resources are cleaned up
        closeQuietly();

        socket = new Socket();
        socket.connect(new InetSocketAddress(bootstrapHost, bootstrapPort), CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(0);
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);

        out = new ObjectOutputStream(socket.getOutputStream());
        out.flush();
        in = new ObjectInputStream(socket.getInputStream());

        Message registerMsg = Message.createRegister(peerId, username, myPort);
        sendMessage(registerMsg);
        log.info("Sent REGISTER to bootstrap: " + peerId);

        try {
            Message response = (Message) in.readObject();
            if (response != null && response.getType() == MessageType.PEER_LIST) {
                @SuppressWarnings("unchecked")
                List<PeerInfo> peers = (List<PeerInfo>) response.getMeta("peers");
                log.info("Received PEER_LIST with " + (peers != null ? peers.size() : 0) + " peers");
                running = true;
                if (shouldEmitStatus(BootstrapStatus.connected()) && onStatusChange != null) {
                    onStatusChange.accept(BootstrapStatus.connected());
                }
                return peers != null ? peers : new ArrayList<>();
            }
            closeQuietly();
            throw new IOException("Bootstrap did not return PEER_LIST (got "
                    + (response != null ? response.getType() : "null")
                    + "). Check that host:port is the bootstrap server, not a peer port.");
        } catch (ClassNotFoundException e) {
            closeQuietly();
            throw new IOException("Invalid response from bootstrap: " + e.getMessage(), e);
        }
    }

    private void closeQuietly() {
        running = false;
        try {
            if (out != null) {
                out.close();
                out = null;
            }
        } catch (IOException ignored) {
        }
        try {
            if (in != null) {
                in.close();
                in = null;
            }
        } catch (IOException ignored) {
        }
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        socket = null;
    }

    /** Dừng vòng reconnect — gọi trước khi tạo BootstrapClient mới. */
    public void stopReconnectLoop() {
        running = false;
        closeQuietly();
    }

    /**
     * Lắng nghe events từ bootstrap (PEER_JOINED, PEER_LEFT).
     * Chạy trên thread riêng. Sau reconnect thành công, vòng lặp đọc được
     * khôi phục tự động với socket/stream mới.
     */
    @Override
    public void run() {
        while (running) {
            try {
                Message msg = (Message) in.readObject();
                if (msg != null && eventHandler != null) {
                    eventHandler.accept(msg);
                }
            } catch (EOFException | java.net.SocketException e) {
                if (!running)
                    break;
                log.warning("Bootstrap connection lost — reconnecting...");
                reconnect();
                // reconnect() đã khôi phục socket/in/running — vòng while tiếp tục đọc
            } catch (IOException | ClassNotFoundException e) {
                if (running)
                    log.warning("BootstrapClient error: " + e.getMessage());
            }
        }
    }

    void reconnect() {
        // Đóng socket cũ trước khi tạo socket mới để tránh leak.
        // Set running=true để vòng while chạy — cần thiết khi gọi trực tiếp từ test.
        running = true;
        closeQuietly();

        int attempt = 0;
        while (running && attempt < MAX_RECONNECT_ATTEMPTS) {
            try {
                // Thử kết nối trước, chỉ delay SAU khi fail.
                attempt++;
                log.info("Bootstrap reconnect attempt " + attempt + "/" + MAX_RECONNECT_ATTEMPTS);

                if (onReconnectAttempt != null) {
                    final int n = attempt;
                    SwingUtilities.invokeLater(() -> onReconnectAttempt.accept(n, MAX_RECONNECT_ATTEMPTS));
                }

                connectAndRegister();
                log.info("Reconnected to bootstrap successfully.");
                return; // socket/in/running đã được khôi phục bởi connectAndRegister()
                        // run() sẽ tiếp tục vòng while với stream mới

            } catch (Exception e) {
                if (!running)
                    break;
                log.warning("Bootstrap reconnect attempt " + attempt + " failed: " + e.getMessage());

                // Delay sau fail — exponential backoff với jitter ngẫu nhiên.
                long delayMs = (long) (RECONNECT_BASE_DELAY_MS
                        * Math.pow(2, attempt - 1) // 3s, 6s, 12s...
                        * (0.75 + Math.random() * 0.5)); // jitter ±25% → [56%-112%]
                log.info("Waiting " + delayMs + "ms before next attempt...");
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }

        // Hết MAX_RECONNECT_ATTEMPTS hoặc running==false → báo disconnected.
        running = false;
        if (shouldEmitStatus(BootstrapStatus.disconnected()) && onStatusChange != null) {
            SwingUtilities.invokeLater(() -> onStatusChange.accept(BootstrapStatus.disconnected()));
        }
    }

    private boolean shouldEmitStatus(BootstrapStatus next) {
        if (lastEmittedStatus == null || lastEmittedStatus.state() != next.state()) {
            lastEmittedStatus = next;
            return true;
        }
        return false;
    }

    public synchronized void sendMessage(Message msg) throws IOException {
        if (out != null) {
            out.writeObject(msg);
            out.flush();
            out.reset();
        }
    }

    public void disconnect() {
        running = false;
        try {
            if (socket != null && !socket.isClosed()) {
                Message unregMsg = new Message(MessageType.UNREGISTER, peerId, "bye");
                sendMessage(unregMsg);
                socket.close();
            }
        } catch (IOException ignored) {
        }
        lastEmittedStatus = null;
        if (onStatusChange != null)
            onStatusChange.accept(BootstrapStatus.disconnected());
        log.info("Disconnected from bootstrap.");
    }

    public void setOnReconnectAttempt(java.util.function.BiConsumer<Integer, Integer> callback) {
        this.onReconnectAttempt = callback;
    }

    public void setOnStatusChange(java.util.function.Consumer<BootstrapStatus> callback) {
        this.onStatusChange = callback;
    }

    public boolean isConnected() {
        return running && socket != null && socket.isConnected() && !socket.isClosed()
                && out != null && in != null;
    }

    /**
     * Gửi thông báo PEER_JOINED thay mặt một peer mới.
     * Dùng khi peer này nhận GET_PEERS từ peer mới — cần thông báo cho
     * Bootstrap Server biết peer mới đã tham gia (để Bootstrap broadcast
     * PEER_JOINED cho tất cả peers khác, kể cả peers không kết nối trực tiếp).
     */
    public void notifyPeerJoined(PeerInfo newPeer) {
        if (!isConnected()) {
            log.warning("[BootstrapClient] Cannot notify PEER_JOINED — not connected to Bootstrap");
            return;
        }
        try {
            Message msg = new Message(MessageType.PEER_JOINED, newPeer.getPeerId(), newPeer.getUsername());
            msg.setSenderUsername(newPeer.getUsername());
            msg.putMeta("peerInfo", newPeer);
            msg.putMeta("ip", newPeer.getIpAddress());
            msg.putMeta("port", newPeer.getPort());
            sendMessage(msg);
            log.info("[BootstrapClient] Notified PEER_JOINED for: " + newPeer.getUsername());
        } catch (IOException e) {
            log.warning("[BootstrapClient] Failed to notify PEER_JOINED: " + e.getMessage());
        }
    }
}
