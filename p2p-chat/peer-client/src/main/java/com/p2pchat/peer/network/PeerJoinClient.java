package com.p2pchat.peer.network;

import com.p2pchat.peer.model.PeerInfo;
import com.p2pchat.peer.protocol.Message;
import com.p2pchat.peer.protocol.MessageType;

import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Thay thế BootstrapClient khi người dùng chọn "Join via Known Peer".
 **/

public class PeerJoinClient implements Runnable {

    private static final Logger log = Logger.getLogger(PeerJoinClient.class.getName());

    private final String knownPeerHost;
    private final int knownPeerPort;
    private final String myPeerId;
    private final String myUsername;
    private final int myPort;

    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private volatile boolean running = false;

    private final Consumer<Message> eventHandler;

    public PeerJoinClient(String knownPeerHost, int knownPeerPort,
            String myPeerId, String myUsername, int myPort,
            Consumer<Message> eventHandler) {
        this.knownPeerHost = knownPeerHost;
        this.knownPeerPort = knownPeerPort;
        this.myPeerId = myPeerId;
        this.myUsername = myUsername;
        this.myPort = myPort;
        this.eventHandler = eventHandler;
    }

    /**
     * Kết nối tới peer đã biết, gửi GET_PEERS, nhận PEER_LIST.
     */
    public List<PeerInfo> connectAndGetPeers() throws IOException {
        log.info("[PeerJoin] Connecting to known peer: " + knownPeerHost + ":" + knownPeerPort);

        socket = new Socket(knownPeerHost, knownPeerPort);
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);

        // out trước in → tránh deadlock ObjectInputStream
        out = new ObjectOutputStream(socket.getOutputStream());
        out.flush();
        in = new ObjectInputStream(socket.getInputStream());

        // Gửi GET_PEERS: thông báo mình muốn join qua peer này
        Message req = new Message(MessageType.GET_PEERS, myPeerId, myUsername);
        req.setSenderUsername(myUsername);
        req.putMeta("port", myPort);
        req.putMeta("joinMode", "VIA_PEER"); // peer kia biết đây là join request
        sendMessage(req);
        log.info("[PeerJoin] Sent GET_PEERS to " + knownPeerHost + ":" + knownPeerPort);

        // Đọc PEER_LIST response
        try {
            Message response = (Message) in.readObject();
            if (response != null && response.getType() == MessageType.PEER_LIST) {
                @SuppressWarnings("unchecked")
                List<PeerInfo> peers = (List<PeerInfo>) response.getMeta("peers");
                log.info("[PeerJoin] Got PEER_LIST: " + (peers != null ? peers.size() : 0) + " peers");
                running = true;
                return peers != null ? peers : new ArrayList<>();
            } else {
                log.warning("[PeerJoin] Unexpected response: "
                        + (response != null ? response.getType() : "null"));
            }
        } catch (ClassNotFoundException e) {
            log.warning("[PeerJoin] ClassNotFoundException: " + e.getMessage());
        }

        running = true;
        return new ArrayList<>();
    }

    /**
     * Lắng nghe events từ peer đã biết sau khi join.
     * Peer kia sẽ forward PEER_JOINED/PEER_LEFT events cho mình.
     */
    @Override
    public void run() {
        while (running && socket != null && !socket.isClosed()) {
            try {
                Message msg = (Message) in.readObject();
                if (msg != null && eventHandler != null) {
                    eventHandler.accept(msg);
                }
            } catch (EOFException | java.net.SocketException e) {
                log.warning("[PeerJoin] Connection to known peer lost.");
                running = false;
            } catch (IOException | ClassNotFoundException e) {
                if (running)
                    log.warning("[PeerJoin] Error: " + e.getMessage());
            }
        }
        log.info("[PeerJoin] Listener stopped.");
    }

    public synchronized void sendMessage(Message msg) throws IOException {
        if (out != null && socket != null && !socket.isClosed()) {
            out.writeObject(msg);
            out.flush();
            out.reset();
        }
    }

    public void disconnect() {
        running = false;
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        log.info("[PeerJoin] Disconnected from known peer.");
    }

    public boolean isConnected() {
        return running && socket != null && !socket.isClosed();
    }

    public String getKnownPeerHost() {
        return knownPeerHost;
    }

    public int getKnownPeerPort() {
        return knownPeerPort;
    }
}