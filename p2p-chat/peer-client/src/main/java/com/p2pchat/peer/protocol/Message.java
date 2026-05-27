package com.p2pchat.peer.protocol;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Lớp Message đại diện cho tất cả các loại tin nhắn truyền qua mạng TCP.
 * Sử dụng Serializable để có thể truyền qua ObjectOutputStream.
 */
public class Message implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final long GROUP_MAX_FILE_BYTES = 20L * 1024 * 1024; // 20MB

    private String messageId;
    private MessageType type;
    private String senderPeerId;
    private String senderUsername;
    private String targetPeerId;    // null nếu là broadcast/group
    private String groupId;         // null nếu không phải group chat
    private String content;
    private LocalDateTime timestamp;
    private Map<String, Object> metadata; // Dữ liệu bổ sung tùy theo loại message

    public Message() {
        this.messageId = UUID.randomUUID().toString();
        this.timestamp = LocalDateTime.now();
        this.metadata = new HashMap<>();
    }

    public Message(MessageType type, String senderPeerId, String content) {
        this();
        this.type = type;
        this.senderPeerId = senderPeerId;
        this.content = content;
    }

    // Factory methods cho các loại message phổ biến
    public static Message createChat(String senderPeerId, String senderUsername,
                                     String targetPeerId, String content) {
        Message msg = new Message(MessageType.CHAT, senderPeerId, content);
        msg.senderUsername = senderUsername;
        msg.targetPeerId = targetPeerId;
        return msg;
    }

    public static Message createGroupChat(String senderPeerId, String senderUsername,
                                          String groupId, String content) {
        Message msg = new Message(MessageType.GROUP_CHAT, senderPeerId, content);
        msg.senderUsername = senderUsername;
        msg.groupId = groupId;
        return msg;
    }

    public static Message createAck(String senderPeerId, String originalMessageId) {
        Message msg = new Message(MessageType.ACK, senderPeerId, "ACK");
        msg.metadata.put("originalMessageId", originalMessageId);
        return msg;
    }

    public static Message createRegister(String peerId, String username, int port) {
        Message msg = new Message(MessageType.REGISTER, peerId, username);
        msg.senderUsername = username;
        msg.metadata.put("port", port);
        return msg;
    }

    public static Message createHeartbeat(String peerId) {
        return new Message(MessageType.HEARTBEAT, peerId, "ping");
    }

    public static Message createBroadcast(String senderPeerId, String senderUsername, String content) {
        Message msg = new Message(MessageType.BROADCAST, senderPeerId, content);
        msg.senderUsername = senderUsername;
        return msg;
    }

    /** Bắt đầu gửi file 1-1: meta transferId, fileName, fileSize (long), totalChunks (int). */
    public static Message createFileTransferRequest(String senderPeerId, String senderUsername,
                                                    String targetPeerId, String transferId,
                                                    String fileName, long fileSize, int totalChunks) {
        Message msg = new Message(MessageType.FILE_TRANSFER_REQUEST, senderPeerId, "");
        msg.senderUsername = senderUsername;
        msg.targetPeerId = targetPeerId;
        msg.putMeta("transferId", transferId);
        msg.putMeta("fileName", fileName);
        msg.putMeta("fileSize", fileSize);
        msg.putMeta("totalChunks", totalChunks);
        return msg;
    }

    public static Message createFileChunk(String senderPeerId, String senderUsername,
                                          String targetPeerId, String transferId,
                                          int chunkIndex, int totalChunks, byte[] data) {
        Message msg = new Message(MessageType.FILE_CHUNK, senderPeerId, "");
        msg.senderUsername = senderUsername;
        msg.targetPeerId = targetPeerId;
        msg.putMeta("transferId", transferId);
        msg.putMeta("chunkIndex", chunkIndex);
        msg.putMeta("totalChunks", totalChunks);
        msg.putMeta("data", data);
        return msg;
    }

    public static Message createFileTransferComplete(String senderPeerId, String senderUsername,
                                                     String targetPeerId, String transferId) {
        Message msg = new Message(MessageType.FILE_TRANSFER_COMPLETE, senderPeerId, "");
        msg.senderUsername = senderUsername;
        msg.targetPeerId = targetPeerId;
        msg.putMeta("transferId", transferId);
        return msg;
    }

    public static Message createFileTransferReject(String senderPeerId, String targetPeerId,
                                                   String transferId, String reason) {
        Message msg = new Message(MessageType.FILE_TRANSFER_REJECT, senderPeerId, reason != null ? reason : "");
        msg.targetPeerId = targetPeerId;
        msg.putMeta("transferId", transferId);
        return msg;
    }

    public static Message createGroupFileRequest(String senderPeerId, String senderUsername,
                                                String groupId, String transferId,
                                                String fileName, long fileSize, int totalChunks) {
        Message msg = new Message(MessageType.GROUP_FILE_REQUEST, senderPeerId, "");
        msg.senderUsername = senderUsername;
        msg.groupId = groupId;
        msg.putMeta("transferId", transferId);
        msg.putMeta("fileName",   fileName);
        msg.putMeta("fileSize",   fileSize);
        msg.putMeta("totalChunks", totalChunks);
        return msg;
    }

    public static Message createGroupFileChunk(String senderPeerId, String senderUsername,
                                                String groupId, String transferId,
                                                int chunkIndex, int totalChunks, byte[] data) {
        Message msg = new Message(MessageType.GROUP_FILE_CHUNK, senderPeerId, "");
        msg.senderUsername = senderUsername;
        msg.groupId = groupId;
        msg.putMeta("transferId",  transferId);
        msg.putMeta("chunkIndex",  chunkIndex);
        msg.putMeta("totalChunks", totalChunks);
        msg.putMeta("data",        data);
        return msg;
    }

    public static Message createGroupFileComplete(String senderPeerId, String senderUsername,
                                                    String groupId, String transferId) {
        Message msg = new Message(MessageType.GROUP_FILE_COMPLETE, senderPeerId, "");
        msg.senderUsername = senderUsername;
        msg.groupId = groupId;
        msg.putMeta("transferId", transferId);
        return msg;
    }

    // ─── Relay Server Factory Methods ────────────────────────────────────────────

    /** Đăng ký peer với relay server */
    public static Message createRelayRegister(String peerId, String username, int port) {
        Message msg = new Message(MessageType.RELAY_REGISTER, peerId, username);
        msg.senderUsername = username;
        msg.putMeta("port", port);
        return msg;
    }

    /** Rời relay server */
    public static Message createRelayUnregister(String peerId) {
        return new Message(MessageType.RELAY_UNREGISTER, peerId, "bye");
    }

    /** Lưu tin nhắn offline vào relay server */
    public static Message createStoreMessage(String senderPeerId, String senderUsername,
                                            String targetPeerId, String content,
                                            String messageId, LocalDateTime timestamp) {
        Message msg = new Message(MessageType.STORE_MESSAGE, senderPeerId, content);
        msg.senderUsername = senderUsername;
        msg.targetPeerId = targetPeerId;
        msg.messageId = messageId;
        msg.timestamp = timestamp;
        return msg;
    }

    /** Yêu cầu lấy tin nhắn offline đang chờ */
    public static Message createFetchMessages(String peerId) {
        return new Message(MessageType.FETCH_MESSAGES, peerId, "fetch");
    }

    /** Xóa tin nhắn đã gửi thành công */
    public static Message createDeleteMessage(String senderPeerId, String messageId) {
        Message msg = new Message(MessageType.DELETE_MESSAGE, senderPeerId, "");
        msg.putMeta("messageId", messageId);
        return msg;
    }

    /** Lấy số tin nhắn đang chờ */
    public static Message createGetPendingCount(String peerId) {
        return new Message(MessageType.GET_PENDING_COUNT, peerId, "count");
    }

    // Getters & Setters
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public MessageType getType() { return type; }
    public void setType(MessageType type) { this.type = type; }

    public String getSenderPeerId() { return senderPeerId; }
    public void setSenderPeerId(String senderPeerId) { this.senderPeerId = senderPeerId; }

    public String getSenderUsername() { return senderUsername; }
    public void setSenderUsername(String senderUsername) { this.senderUsername = senderUsername; }

    public String getTargetPeerId() { return targetPeerId; }
    public void setTargetPeerId(String targetPeerId) { this.targetPeerId = targetPeerId; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public Object getMeta(String key) { return metadata.get(key); }
    public void putMeta(String key, Object value) { metadata.put(key, value); }

    @Override
    public String toString() {
        return String.format("Message{id='%s', type=%s, from='%s', to='%s', content='%s'}",
                messageId, type, senderPeerId, targetPeerId, content);
    }
}