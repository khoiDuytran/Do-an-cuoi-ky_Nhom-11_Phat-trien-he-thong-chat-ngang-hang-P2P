package com.p2pchat.peer.network;

import com.p2pchat.peer.protocol.Message;
import com.p2pchat.peer.protocol.MessageType;

import java.io.*;
import java.net.Socket;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Xử lý một kết nối TCP đến từ peer khác.
 * Chạy trên thread riêng, đọc Message liên tục và dispatch tới handler.
 */
public class ConnectionHandler implements Runnable {

    private static final Logger log = Logger.getLogger(ConnectionHandler.class.getName());

    private final Socket socket;
    private final Consumer<Message> messageHandler;
    private final Runnable onDisconnect;
    private ObjectInputStream in;
    private ObjectOutputStream out;
    private volatile boolean running = true;
    private String remotePeerId;

    public ConnectionHandler(Socket socket, Consumer<Message> messageHandler, Runnable onDisconnect) {
        this.socket = socket;
        this.messageHandler = messageHandler;
        this.onDisconnect = onDisconnect;
    }

    @Override
    public void run() {
        try {
            // QUAN TRỌNG: tạo out trước in để tránh deadlock
            out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            in  = new ObjectInputStream(socket.getInputStream());

            socket.setSoTimeout(0); // blocking read

            while (running && !socket.isClosed()) {
                try {
                    Message msg = (Message) in.readObject();
                    if (msg != null) {
                        if (msg.getType() == MessageType.HEARTBEAT) {
                            // Trả lời heartbeat ngay lập tức
                            sendMessage(Message.createHeartbeat(msg.getTargetPeerId()));
                        } else {
                            if (remotePeerId == null) remotePeerId = msg.getSenderPeerId();
                            messageHandler.accept(msg);
                        }
                    }
                } catch (ClassNotFoundException e) {
                    log.warning("Unknown message class: " + e.getMessage());
                }
            }
        } catch (EOFException | java.net.SocketException e) {
            log.info("Connection closed from " + (remotePeerId != null ? remotePeerId : socket.getRemoteSocketAddress()));
        } catch (IOException e) {
            if (running) {
                log.warning("ConnectionHandler IO error: " + e.getMessage());
            }
        } finally {
            close();
            if (onDisconnect != null) onDisconnect.run();
        }
    }

    public synchronized void sendMessage(Message msg) {
        try {
            if (out != null && !socket.isClosed()) {
                out.writeObject(msg);
                out.flush();
                out.reset(); // tránh caching object references
            }
        } catch (IOException e) {
            log.warning("sendMessage error: " + e.getMessage());
            running = false;
        }
    }

    public void close() {
        running = false;
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {}
    }

    public boolean isConnected() {
        return running && socket != null && socket.isConnected() && !socket.isClosed();
    }

    public String getRemotePeerId() { return remotePeerId; }
    public void setRemotePeerId(String remotePeerId) { this.remotePeerId = remotePeerId; }
    public String getRemoteAddress() { return socket.getRemoteSocketAddress().toString(); }
}