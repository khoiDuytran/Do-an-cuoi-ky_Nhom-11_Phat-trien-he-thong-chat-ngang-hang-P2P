package com.p2pchat.relay;

import com.p2pchat.peer.protocol.Message;
import com.p2pchat.peer.protocol.MessageType;

import java.io.*;
import java.net.Socket;
import java.util.List;
import java.util.logging.Logger;

/**
 * Xử lý kết nối từ một peer đến relay server.
 * Mỗi peer có một handler riêng để duy trì trạng thái online.
 */
public class RelayClientHandler implements Runnable {

    private static final Logger log = Logger.getLogger(RelayClientHandler.class.getName());

    private final Socket socket;
    private final RelayRepository repo;
    private final RelayClientHandlerRegistry registry;
    private final Runnable onDisconnect;

    private ObjectInputStream in;
    private ObjectOutputStream out;
    private volatile boolean running = false;

    private String peerId;
    private String username;
    private String ipAddress;
    private int port;

    public RelayClientHandler(Socket socket, RelayRepository repo,
                             RelayClientHandlerRegistry registry, Runnable onDisconnect) {
        this.socket = socket;
        this.repo = repo;
        this.registry = registry;
        this.onDisconnect = onDisconnect;
        this.ipAddress = extractIp(socket.getRemoteSocketAddress().toString());
    }

    @Override
    public void run() {
        try {
            out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            in = new ObjectInputStream(socket.getInputStream());
            running = true;

            while (running) {
                try {
                    Message msg = (Message) in.readObject();
                    if (msg != null) {
                        handleMessage(msg);
                    }
                } catch (EOFException | java.net.SocketException e) {
                    if (running) {
                        log.warning("[RelayHandler] Connection lost: " + e.getMessage());
                    }
                    break;
                } catch (ClassNotFoundException e) {
                    log.warning("[RelayHandler] Invalid message: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            if (running) {
                log.warning("[RelayHandler] Handler error: " + e.getMessage());
            }
        } finally {
            cleanup();
        }
    }

    private void handleMessage(Message msg) {
        log.info("[RelayHandler] Received: " + msg.getType() + " from " + msg.getSenderPeerId());

        switch (msg.getType()) {
            case RELAY_REGISTER -> handleRelayRegister(msg);
            case RELAY_UNREGISTER -> handleRelayUnregister(msg);
            case STORE_MESSAGE -> handleStoreMessage(msg);
            case FETCH_MESSAGES -> handleFetchMessages(msg);
            case DELETE_MESSAGE -> handleDeleteMessage(msg);
            case GET_PENDING_COUNT -> handleGetPendingCount(msg);
            case ACK -> handleRelayAck(msg);
            default -> log.fine("[RelayHandler] Unhandled: " + msg.getType());
        }
    }

    private void handleRelayRegister(Message msg) {
        this.peerId = msg.getSenderPeerId();
        this.username = msg.getSenderUsername();
        Object portObj = msg.getMeta("port");
        this.port = portObj instanceof Number n ? n.intValue() : 0;

        // Đăng ký vào database
        repo.registerPeer(peerId, username, ipAddress, port);

        // Đăng ký vào registry để nhận thông báo
        registry.register(peerId, this);

        // Gửi thông báo có tin nhắn đang chờ (nếu có)
        int pendingCount = repo.getPendingCount(peerId);
        if (pendingCount > 0) {
            sendNotifyMessage(pendingCount);
        }

        log.info("[RelayHandler] Peer registered: " + username + " (" + peerId + ")");
    }

    private void handleRelayUnregister(Message msg) {
        if (peerId != null) {
            repo.unregisterPeer(peerId);
            registry.unregister(peerId);
            log.info("[RelayHandler] Peer unregistered: " + peerId);
        }
        running = false;
    }

    private void handleStoreMessage(Message msg) {
        if (peerId == null) {
            log.warning("[RelayHandler] STORE_MESSAGE from unregistered peer");
            return;
        }

        String targetPeerId = msg.getTargetPeerId();
        if (targetPeerId == null || targetPeerId.isEmpty()) {
            log.warning("[RelayHandler] STORE_MESSAGE without target");
            return;
        }

        // Lưu tin nhắn vào database
        repo.storeMessage(msg, targetPeerId);

        // Kiểm tra target có đang online không
        if (registry.isOnline(targetPeerId)) {
            // Gửi thông báo cho peer đích
            RelayClientHandler targetHandler = registry.getHandler(targetPeerId);
            if (targetHandler != null) {
                int pendingCount = repo.getPendingCount(targetPeerId);
                targetHandler.sendNotifyMessage(pendingCount);
            }
        }

        log.info("[RelayHandler] Stored message from " + peerId + " for " + targetPeerId);
    }

    private void handleFetchMessages(Message msg) {
        if (peerId == null) {
            log.warning("[RelayHandler] FETCH_MESSAGES from unregistered peer");
            return;
        }

        // Lấy tất cả tin nhắn đang chờ
        List<RelayRepository.StoredMessage> storedMessages = repo.fetchPendingMessages(peerId);

        // Chuyển đổi sang Message và gửi
        List<Message> messages = storedMessages.stream()
                .map(RelayRepository.StoredMessage::toMessage)
                .toList();

        // Gửi danh sách tin nhắn
        Message response = new Message(MessageType.RELAY_MESSAGE_LIST, "RELAY", "messages");
        response.setTargetPeerId(peerId);
        response.putMeta("messages", (Serializable) messages);
        sendMessage(response);

        // Đánh dấu đã đọc (delivered = 1) nhưng chưa xóa
        for (RelayRepository.StoredMessage sm : storedMessages) {
            repo.markDelivered(sm.messageId);
        }

        log.info("[RelayHandler] Sent " + messages.size() + " pending messages to " + peerId);
    }

    private void handleDeleteMessage(Message msg) {
        if (peerId == null) {
            log.warning("[RelayHandler] DELETE_MESSAGE from unregistered peer");
            return;
        }

        String messageId = (String) msg.getMeta("messageId");
        if (messageId != null) {
            repo.deleteMessage(messageId);
            log.info("[RelayHandler] Deleted message: " + messageId);
        }
    }

    private void handleGetPendingCount(Message msg) {
        if (peerId == null) {
            log.warning("[RelayHandler] GET_PENDING_COUNT from unregistered peer");
            return;
        }

        int count = repo.getPendingCount(peerId);
        Message response = new Message(MessageType.GET_PENDING_COUNT, "RELAY", String.valueOf(count));
        response.setTargetPeerId(peerId);
        sendMessage(response);
    }

    /**
     * Xử lý ACK ngược: receiver đã nhận tin nhắn từ relay, gửi ACK về cho sender.
     * - Nếu sender đang online → forward ACK trực tiếp qua socket.
     * - Nếu sender offline → store ACK vào relay_messages để sender fetch sau.
     */
    private void handleRelayAck(Message ackMsg) {
        String targetSenderId = ackMsg.getTargetPeerId();
        if (targetSenderId == null || targetSenderId.isEmpty()) {
            log.warning("[RelayHandler] ACK missing targetPeerId (original sender)");
            return;
        }

        log.info("[RelayHandler] Relay ACK from " + peerId + " → " + targetSenderId
                + " for msg " + ackMsg.getMeta("originalMessageId"));

        RelayClientHandler senderHandler = registry.getHandler(targetSenderId);
        if (senderHandler != null && senderHandler.isRunning()) {
            // Sender đang online → forward ACK trực tiếp
            senderHandler.sendMessage(ackMsg);
            log.info("[RelayHandler] ACK forwarded directly to " + targetSenderId);
        } else {
            // Sender offline → store ACK vào relay để sender fetch khi online lại
            repo.storeMessage(ackMsg, targetSenderId);
            log.info("[RelayHandler] ACK stored for offline sender: " + targetSenderId);
        }
    }

    /**
     * Gửi thông báo có tin nhắn mới cho peer.
     */
    public void sendNotifyMessage(int count) {
        try {
            Message notify = new Message(MessageType.STORE_NOTIFY, "RELAY", "new_messages");
            notify.setTargetPeerId(peerId);
            notify.putMeta("pendingCount", count);
            sendMessage(notify);
            log.info("[RelayHandler] Sent STORE_NOTIFY to " + peerId + " (count=" + count + ")");
        } catch (Exception e) {
            log.warning("[RelayHandler] Failed to send notify: " + e.getMessage());
        }
    }

    public synchronized void sendMessage(Message msg) {
        try {
            if (out != null) {
                out.writeObject(msg);
                out.flush();
                out.reset();
            }
        } catch (IOException e) {
            log.warning("[RelayHandler] Failed to send message: " + e.getMessage());
        }
    }

    public String getPeerId() { return peerId; }
    public String getUsername() { return username; }
    public boolean isRunning() { return running; }

    private void cleanup() {
        running = false;
        try {
            if (peerId != null) {
                repo.unregisterPeer(peerId);
                registry.unregister(peerId);
            }
        } catch (Exception e) {
            log.warning("[RelayHandler] Cleanup error: " + e.getMessage());
        }
        try {
            socket.close();
        } catch (IOException ignored) {}
        if (onDisconnect != null) {
            onDisconnect.run();
        }
    }

    public void stop() {
        running = false;
        try {
            socket.close();
        } catch (IOException ignored) {}
    }

    private String extractIp(String address) {
        if (address == null) return "";
        String s = address.replaceAll("^/", "").replaceAll("/$", "");
        int colon = s.lastIndexOf(':');
        return colon > 0 ? s.substring(0, colon) : s;
    }
}
