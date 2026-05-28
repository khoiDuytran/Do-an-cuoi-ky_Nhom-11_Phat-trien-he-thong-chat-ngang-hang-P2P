package com.p2pchat.bootstrap.network;

import com.p2pchat.bootstrap.repository.UserRepository;
import com.p2pchat.peer.model.PeerInfo;
import com.p2pchat.peer.protocol.Message;
import com.p2pchat.peer.protocol.MessageType;

import java.io.*;
import java.net.Socket;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Xử lý một kết nối TCP từ một peer tới Bootstrap Server.
 * Mỗi kết nối chạy trên thread riêng.
 *
 * Luồng:
 * 1. Peer gửi REGISTER → server lưu DB, trả PEER_LIST, broadcast PEER_JOINED
 * cho others
 * 2. Peer gửi UNREGISTER / mất kết nối → server setOffline DB, broadcast
 * PEER_LEFT
 */
public class BootstrapClientHandler implements Runnable {

    private static final Logger log = Logger.getLogger(BootstrapClientHandler.class.getName());

    private final Socket socket;
    private final UserRepository userRepo;
    private final Consumer<BootstrapClientHandler> onDisconnect;
    private final Consumer<BootstrapClientHandler> onRegistered; // ← callback sau REGISTER

    // broadcastCallback(message, excludePeerId)
    private final BiConsumer<Message, String> broadcastCallback;

    private ObjectInputStream in;
    private ObjectOutputStream out;
    private volatile boolean running = true;

    private String peerId;
    private String username;
    private String clientIp;

    public BootstrapClientHandler(Socket socket,
            UserRepository userRepo,
            Consumer<BootstrapClientHandler> onDisconnect,
            BiConsumer<Message, String> broadcastCallback,
            Consumer<BootstrapClientHandler> onRegistered) {
        this.socket = socket;
        this.userRepo = userRepo;
        this.onDisconnect = onDisconnect;
        this.broadcastCallback = broadcastCallback;
        this.onRegistered = onRegistered;
        this.clientIp = socket.getInetAddress().getHostAddress();
    }

    @Override
    public void run() {
        try {
            // out trước in → tránh deadlock ObjectInputStream
            out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            in = new ObjectInputStream(socket.getInputStream());

            while (running && !socket.isClosed()) {
                try {
                    Message msg = (Message) in.readObject();
                    if (msg != null)
                        handleMessage(msg);
                } catch (ClassNotFoundException e) {
                    log.warning("Unknown class from " + clientIp + ": " + e.getMessage());
                }
            }
        } catch (EOFException | java.net.SocketException e) {
            log.info("Peer disconnected: " + (peerId != null ? peerId : clientIp));
        } catch (IOException e) {
            if (running)
                log.warning("Handler IO error [" + clientIp + "]: " + e.getMessage());
        } finally {
            handleDisconnect();
        }
    }

    private void handleMessage(Message msg) {
        switch (msg.getType()) {
            case REGISTER -> handleRegister(msg);
            case UNREGISTER -> handleUnregister(msg);
            case PEER_JOINED -> handlePeerJoined(msg);
            case HEARTBEAT -> sendMessage(Message.createHeartbeat(msg.getTargetPeerId()));
            default -> log.fine("Bootstrap ignores: " + msg.getType());
        }
    }

    private void handlePeerJoined(Message msg) {
        PeerInfo peerInfo = (PeerInfo) msg.getMeta("peerInfo");
        if (peerInfo == null) {
            String ip = (String) msg.getMeta("ip");
            Object portObj = msg.getMeta("port");
            int port = (portObj instanceof Integer i) ? i
                    : (portObj instanceof Number n) ? n.intValue() : 0;
            if (ip == null || port == 0) {
                log.warning("[Bootstrap] PEER_JOINED missing ip/port for: " + msg.getSenderPeerId());
                return;
            }
            peerInfo = new PeerInfo(msg.getSenderPeerId(), msg.getSenderUsername(), ip, port);
        }

        // Cập nhật DB để Bootstrap biết peer này online
        userRepo.registerOrUpdate(peerInfo);

        // Broadcast cho tất cả peers đang kết nối
        broadcastCallback.accept(msg, peerInfo.getPeerId());

        log.info("[Bootstrap] PEER_JOINED relayed for: " + peerInfo.getUsername()
                + " @ " + peerInfo.getIpAddress() + ":" + peerInfo.getPort());
    }

    private void handleRegister(Message msg) {
        this.peerId = msg.getSenderPeerId();
        this.username = msg.getSenderUsername();
        int peerPort = (Integer) msg.getMeta("port");

        PeerInfo peerInfo = new PeerInfo(peerId, username, clientIp, peerPort);

        // 1. Lưu/cập nhật DB
        userRepo.registerOrUpdate(peerInfo);

        // 2. Lấy danh sách peers đang online (trừ peer này)
        List<PeerInfo> onlinePeers = userRepo.getOnlinePeers(peerId);

        // 3. Trả PEER_LIST để đồng bộ khi mở app
        Message response = new Message(MessageType.PEER_LIST, "bootstrap", "peers");
        response.putMeta("peers", (java.io.Serializable) onlinePeers);
        response.putMeta("yourPeerId", peerId);
        sendMessage(response);

        // 4. Đăng ký handler vào connectedHandlers TRƯỚC khi broadcast
        // để các peer sau cũng nhìn thấy peer này khi họ join
        if (onRegistered != null)
            onRegistered.accept(this);

        // 5. Broadcast PEER_JOINED tới tất cả peers đang kết nối (trừ chính peer này)
        Message joinedMsg = new Message(MessageType.PEER_JOINED, peerId, username);
        joinedMsg.setSenderUsername(username);
        joinedMsg.putMeta("peerInfo", peerInfo);
        joinedMsg.putMeta("ip", clientIp);
        joinedMsg.putMeta("port", peerPort);
        broadcastCallback.accept(joinedMsg, peerId);

        log.info("[Bootstrap] REGISTERED: " + username + " (" + peerId + ")"
                + " @ " + clientIp + ":" + peerPort
                + "  | online peers: " + onlinePeers.size());
    }

    private void handleUnregister(Message msg) {
        if (peerId != null) {
            userRepo.setOffline(peerId);
            broadcastPeerLeft();
            log.info("[Bootstrap] UNREGISTERED: " + username);
        }
        running = false;
    }

    private void handleDisconnect() {
        running = false;
        if (peerId != null) {
            userRepo.setOffline(peerId);
            broadcastPeerLeft();
        }
        close();
        if (onDisconnect != null)
            onDisconnect.accept(this);
    }

    private void broadcastPeerLeft() {
        Message leftMsg = new Message(MessageType.PEER_LEFT, peerId, username);
        leftMsg.setSenderUsername(username);
        broadcastCallback.accept(leftMsg, peerId);
    }

    public synchronized void sendMessage(Message msg) {
        try {
            if (out != null && !socket.isClosed()) {
                out.writeObject(msg);
                out.flush();
                out.reset();
            }
        } catch (IOException e) {
            log.warning("sendMessage to [" + username + "] failed: " + e.getMessage());
            running = false;
        }
    }

    public void close() {
        running = false;
        try {
            if (!socket.isClosed())
                socket.close();
        } catch (IOException ignored) {
        }
    }

    public String getPeerId() {
        return peerId;
    }

    public String getUsername() {
        return username;
    }

    public boolean isRunning() {
        return running && !socket.isClosed();
    }
}