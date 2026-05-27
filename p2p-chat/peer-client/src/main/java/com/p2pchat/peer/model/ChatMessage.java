package com.p2pchat.peer.model;

import java.time.LocalDateTime;

/**
 * Model tin nhắn lưu trong database local của mỗi peer
 */
public class ChatMessage {

    /** Tin file chờ tải: dòng 1 marker, 2 = tên file, 3 = kích thước (bytes). */
    public static final String FILE_PENDING_MARKER = "__P2P_FILE__";
    /** Đã lưu xuống đĩa: dòng 1 marker, dòng 2 = đường dẫn tuyệt đối. */
    public static final String FILE_SAVED_MARKER = "__P2P_FILE_SAVED__";

    private Long id;
    private String messageId; // UUID từ Message.messageId
    private String senderPeerId;
    private String senderUsername;
    private String targetPeerId; // null nếu là group
    private String groupId; // null nếu là direct
    private String content;
    private MessageType type;
    private LocalDateTime sentAt;
    private boolean delivered;
    private boolean isOwn; // true nếu tin nhắn do peer này gửi

    public enum MessageType {
        CHAT, GROUP_CHAT, BROADCAST, FILE, GROUP_FILE
    }

    public ChatMessage() {
    }

    public static ChatMessage fromDirect(String messageId, String senderPeerId,
            String senderUsername, String targetPeerId,
            String content, boolean isOwn) {
        ChatMessage cm = new ChatMessage();
        cm.messageId = messageId;
        cm.senderPeerId = senderPeerId;
        cm.senderUsername = senderUsername;
        cm.targetPeerId = targetPeerId;
        cm.content = content;
        cm.type = MessageType.CHAT;
        cm.sentAt = LocalDateTime.now();
        cm.delivered = false; // chưa được confirm
        cm.isOwn = isOwn;
        return cm;
    }

    public static ChatMessage fromGroup(String messageId, String senderPeerId,
            String senderUsername, String groupId,
            String content, boolean isOwn) {
        ChatMessage cm = new ChatMessage();
        cm.messageId = messageId;
        cm.senderPeerId = senderPeerId;
        cm.senderUsername = senderUsername;
        cm.groupId = groupId;
        cm.content = content;
        cm.type = MessageType.GROUP_CHAT;
        cm.sentAt = LocalDateTime.now();
        cm.delivered = true;
        cm.isOwn = isOwn;
        return cm;
    }

    /**
     * Tin nhắn dạng file đính kèm (content hiển thị: tên file + đường dẫn local).
     */
    public static ChatMessage fromDirectFile(String messageId, String senderPeerId,
            String senderUsername, String targetPeerId,
            String displayContent, boolean isOwn) {
        ChatMessage cm = new ChatMessage();
        cm.messageId = messageId;
        cm.senderPeerId = senderPeerId;
        cm.senderUsername = senderUsername;
        cm.targetPeerId = targetPeerId;
        cm.content = displayContent;
        cm.type = MessageType.FILE;
        cm.sentAt = LocalDateTime.now();
        cm.delivered = false;
        cm.isOwn = isOwn;
        return cm;
    }

    public static ChatMessage fromGroupFile(String messageId, String senderPeerId,
            String senderUsername, String groupId,
            String displayContent, boolean isOwn) {
        ChatMessage cm = new ChatMessage();
        cm.messageId = messageId;
        cm.senderPeerId = senderPeerId;
        cm.senderUsername = senderUsername;
        cm.groupId = groupId;
        cm.content = displayContent;
        cm.type = MessageType.GROUP_FILE;
        cm.sentAt = LocalDateTime.now();
        cm.delivered = true;
        cm.isOwn = isOwn;
        return cm;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getSenderPeerId() {
        return senderPeerId;
    }

    public void setSenderPeerId(String senderPeerId) {
        this.senderPeerId = senderPeerId;
    }

    public String getSenderUsername() {
        return senderUsername;
    }

    public void setSenderUsername(String senderUsername) {
        this.senderUsername = senderUsername;
    }

    public String getTargetPeerId() {
        return targetPeerId;
    }

    public void setTargetPeerId(String targetPeerId) {
        this.targetPeerId = targetPeerId;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public MessageType getType() {
        return type;
    }

    public void setType(MessageType type) {
        this.type = type;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }

    public void setSentAt(LocalDateTime sentAt) {
        this.sentAt = sentAt;
    }

    public boolean isDelivered() {
        return delivered;
    }

    public void setDelivered(boolean delivered) {
        this.delivered = delivered;
    }

    public boolean isOwn() {
        return isOwn;
    }

    public void setOwn(boolean own) {
        isOwn = own;
    }
}