package com.p2pchat.peer.protocol;

public enum MessageType {
    // Bootstrap communication
    REGISTER,           // Peer đăng ký với bootstrap
    UNREGISTER,         // Peer rời mạng
    GET_PEERS,          // Lấy danh sách peers
    PEER_LIST,          // Phản hồi danh sách peers
    PEER_JOINED,        // Broadcast: peer mới vào mạng
    PEER_LEFT,          // Broadcast: peer rời mạng
    HEARTBEAT,          // Kiểm tra peer còn sống
    HEARTBEAT_ACK,      // Phản hồi heartbeat

    // P2P communication
    CHAT,               // Tin nhắn trực tiếp 1-1
    GROUP_CHAT,         // Tin nhắn nhóm
    BROADCAST,          // Broadcast toàn mạng
    ACK,                // Xác nhận nhận tin nhắn

    // Group management
    CREATE_GROUP,
    JOIN_GROUP,
    LEAVE_GROUP,
    GROUP_INFO,

    // File transfer
    FILE_TRANSFER_REQUEST,
    FILE_TRANSFER_ACCEPT,
    FILE_TRANSFER_REJECT,
    FILE_CHUNK,
    FILE_TRANSFER_COMPLETE,

    // Group file transfer
    GROUP_FILE_REQUEST,
    GROUP_FILE_CHUNK,
    GROUP_FILE_COMPLETE,

    // Relay Server communication (offline messaging)
    RELAY_REGISTER,         // Peer đăng ký với relay server
    RELAY_UNREGISTER,       // Peer rời relay server
    STORE_MESSAGE,          // Lưu tin nhắn offline vào relay
    FETCH_MESSAGES,         // Lấy tin nhắn offline đang chờ
    DELETE_MESSAGE,         // Xóa tin nhắn đã gửi
    RELAY_MESSAGE_LIST,     // Phản hồi danh sách tin nhắn offline
    STORE_NOTIFY,           // Thông báo có tin nhắn mới cho peer đích
    GET_PENDING_COUNT,      // Lấy số tin nhắn đang chờ

    // System
    ERROR,
    PING,
    PONG
}