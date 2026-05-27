package com.p2pchat.peer.network;

import com.p2pchat.peer.protocol.Message;
import com.p2pchat.peer.protocol.MessageType;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;

/**
 * Client kết nối tới Relay Server để gửi/nhận tin nhắn offline.
 * Duy trì kết nối persistent để nhận thông báo tin nhắn mới.
 * Tự động reconnect với exponential backoff khi mất kết nối.
 */
public class RelayClient implements Runnable {

    private static final Logger log = Logger.getLogger(RelayClient.class.getName());
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int RECONNECT_BASE_DELAY_MS = 3_000;
    private static final int MAX_RECONNECT_ATTEMPTS = 3;

    private String relayHost;
    private int relayPort;
    private String peerId;
    private String username;
    private int myPort;

    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private volatile boolean running = false;

    // Event handlers
    private Consumer<Message> onMessageReceived;
    private Consumer<Integer> onPendingCountChanged;
    private Consumer<RelayConnectionStatus> onStatusChanged;

    private final CopyOnWriteArrayList<RelayMessageListener> messageListeners = new CopyOnWriteArrayList<>();

    private RelayConnectionStatus lastEmittedStatus = null;

    public RelayClient(String relayHost, int relayPort, String peerId, String username, int myPort) {
        this.relayHost = relayHost;
        this.relayPort = relayPort;
        this.peerId = peerId;
        this.username = username;
        this.myPort = myPort;
    }

    /**
     * Kết nối và đăng ký với relay server.
     */
    public boolean connectAndRegister() {
        closeQuietly();

        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(relayHost, relayPort), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(0);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);

            out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            in = new ObjectInputStream(socket.getInputStream());

            // Gửi đăng ký
            Message registerMsg = Message.createRelayRegister(peerId, username, myPort);
            sendMessage(registerMsg);
            log.info("[RelayClient] Sent RELAY_REGISTER to " + relayHost + ":" + relayPort);

            running = true;
            emitStatus(RelayConnectionStatus.CONNECTED);

            // Fetch tin nhắn đang chờ ngay lập tức
            fetchPendingMessages();

            return true;
        } catch (IOException e) {
            log.warning("[RelayClient] Failed to connect: " + e.getMessage());
            emitStatus(RelayConnectionStatus.DISCONNECTED);
            return false;
        }
    }

    /**
     * Lắng nghe thông báo từ relay server.
     */
    @Override
    public void run() {
        while (running) {
            try {
                Message msg = (Message) in.readObject();
                if (msg != null) {
                    handleIncomingMessage(msg);
                }
            } catch (EOFException | java.net.SocketException e) {
                if (!running) break;
                log.warning("[RelayClient] Connection lost — reconnecting...");
                emitStatus(RelayConnectionStatus.RECONNECTING);
                reconnect();
            } catch (IOException | ClassNotFoundException e) {
                if (running) {
                    log.warning("[RelayClient] Error: " + e.getMessage());
                }
            }
        }
    }

    private void handleIncomingMessage(Message msg) {
        log.info("[RelayClient] Received: " + msg.getType());

        switch (msg.getType()) {
            case STORE_NOTIFY -> handleStoreNotify(msg);
            case RELAY_MESSAGE_LIST -> handleRelayMessageList(msg);
            default -> {
                // Forward to listeners
                for (RelayMessageListener listener : messageListeners) {
                    listener.onMessageReceived(msg);
                }
            }
        }
    }

    private void handleStoreNotify(Message msg) {
        Object countObj = msg.getMeta("pendingCount");
        int count = countObj instanceof Number n ? n.intValue() : 1;

        log.info("[RelayClient] STORE_NOTIFY: " + count + " messages waiting");

        if (onPendingCountChanged != null) {
            SwingUtilities.invokeLater(() -> onPendingCountChanged.accept(count));
        }

        // Tự động fetch tin nhắn
        fetchPendingMessages();
    }

    private void handleRelayMessageList(Message msg) {
        Object messagesObj = msg.getMeta("messages");
        if (messagesObj instanceof List<?> rawList) {
            for (Object item : rawList) {
                if (item instanceof Message m) {
                    // Đánh dấu đây là tin nhắn offline
                    m.putMeta("isOffline", true);
                    log.info("[RelayClient] Received offline message: " + m.getMessageId());

                    // Forward to listeners
                    for (RelayMessageListener listener : messageListeners) {
                        listener.onMessageReceived(m);
                    }
                }
            }
        }

        // Xóa tin nhắn đã nhận khỏi relay server
        if (messagesObj instanceof List<?> rawList) {
            for (Object item : rawList) {
                if (item instanceof Message m) {
                    deleteMessage(m.getMessageId());
                }
            }
        }
    }

    /**
     * Gửi tin nhắn offline đến relay server.
     */
    public void storeOfflineMessage(Message msg) {
        if (!isConnected()) {
            log.warning("[RelayClient] Cannot store message: not connected");
            return;
        }

        try {
            // Chuyển đổi thành STORE_MESSAGE
            Message storeMsg = Message.createStoreMessage(
                    msg.getSenderPeerId(),
                    msg.getSenderUsername(),
                    msg.getTargetPeerId(),
                    msg.getContent(),
                    msg.getMessageId(),
                    msg.getTimestamp()
            );
            storeMsg.setGroupId(msg.getGroupId());
            storeMsg.setType(MessageType.STORE_MESSAGE);

            sendMessage(storeMsg);
            log.info("[RelayClient] Stored offline message: " + msg.getMessageId());
        } catch (Exception e) {
            log.warning("[RelayClient] Failed to store message: " + e.getMessage());
        }
    }

    /**
     * Yêu cầu lấy tin nhắn đang chờ.
     */
    public void fetchPendingMessages() {
        if (!isConnected()) return;
        try {
            Message fetchMsg = Message.createFetchMessages(peerId);
            sendMessage(fetchMsg);
            log.info("[RelayClient] Fetching pending messages");
        } catch (Exception e) {
            log.warning("[RelayClient] Failed to fetch: " + e.getMessage());
        }
    }

    /**
     * Xóa tin nhắn đã gửi thành công.
     */
    public void deleteMessage(String messageId) {
        if (!isConnected()) return;
        try {
            Message deleteMsg = Message.createDeleteMessage(peerId, messageId);
            sendMessage(deleteMsg);
            log.info("[RelayClient] Deleted message: " + messageId);
        } catch (Exception e) {
            log.warning("[RelayClient] Failed to delete message: " + e.getMessage());
        }
    }

    /**
     * Kết nối lại với exponential backoff.
     */
    private void reconnect() {
        closeQuietly();

        int attempt = 0;
        while (running && attempt < MAX_RECONNECT_ATTEMPTS) {
            try {
                attempt++;
                log.info("[RelayClient] Reconnect attempt " + attempt + "/" + MAX_RECONNECT_ATTEMPTS);

                if (connectAndRegister()) {
                    log.info("[RelayClient] Reconnected successfully");
                    return;
                }
            } catch (Exception e) {
                log.warning("[RelayClient] Reconnect attempt " + attempt + " failed: " + e.getMessage());
            }

            // Exponential backoff với jitter
            try {
                long delayMs = (long) (RECONNECT_BASE_DELAY_MS
                        * Math.pow(2, attempt - 1)
                        * (0.75 + Math.random() * 0.5));
                log.info("[RelayClient] Waiting " + delayMs + "ms before next attempt...");
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                break;
            }
        }

        running = false;
        emitStatus(RelayConnectionStatus.DISCONNECTED);
        log.warning("[RelayClient] Failed to reconnect after " + MAX_RECONNECT_ATTEMPTS + " attempts");
    }

    private void closeQuietly() {
        running = false;
        try {
            if (out != null) { out.close(); out = null; }
        } catch (IOException ignored) {}
        try {
            if (in != null) { in.close(); in = null; }
        } catch (IOException ignored) {}
        try {
            if (socket != null && !socket.isClosed()) { socket.close(); }
        } catch (IOException ignored) {}
        socket = null;
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
                Message unregMsg = Message.createRelayUnregister(peerId);
                sendMessage(unregMsg);
                socket.close();
            }
        } catch (IOException ignored) {}
        emitStatus(RelayConnectionStatus.DISCONNECTED);
        log.info("[RelayClient] Disconnected from relay server");
    }

    public boolean isConnected() {
        return running && socket != null && socket.isConnected() && !socket.isClosed();
    }

    public void addMessageListener(RelayMessageListener listener) {
        messageListeners.add(listener);
    }

    public void removeMessageListener(RelayMessageListener listener) {
        messageListeners.remove(listener);
    }

    public void setOnPendingCountChanged(Consumer<Integer> onPendingCountChanged) {
        this.onPendingCountChanged = onPendingCountChanged;
    }

    public void setOnStatusChanged(Consumer<RelayConnectionStatus> onStatusChanged) {
        this.onStatusChanged = onStatusChanged;
    }

    private void emitStatus(RelayConnectionStatus status) {
        if (lastEmittedStatus == null || lastEmittedStatus != status) {
            lastEmittedStatus = status;
            if (onStatusChanged != null) {
                SwingUtilities.invokeLater(() -> onStatusChanged.accept(status));
            }
        }
    }

    public RelayConnectionStatus getStatus() {
        return lastEmittedStatus != null ? lastEmittedStatus : RelayConnectionStatus.DISCONNECTED;
    }

    /**
     * Listener interface cho tin nhắn từ relay server.
     */
    public interface RelayMessageListener {
        void onMessageReceived(Message message);
    }

    /**
     * Trạng thái kết nối relay.
     */
    public enum RelayConnectionStatus {
        CONNECTED,
        DISCONNECTED,
        RECONNECTING
    }
}
