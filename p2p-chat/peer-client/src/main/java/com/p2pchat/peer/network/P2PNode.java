package com.p2pchat.peer.network;

import com.p2pchat.peer.model.ChatGroup;
import com.p2pchat.peer.model.ChatMessage;
import com.p2pchat.peer.model.PeerInfo;
import com.p2pchat.peer.protocol.Message;
import com.p2pchat.peer.protocol.MessageType;
import com.p2pchat.peer.repository.GroupRepository;
import com.p2pchat.peer.repository.MessageRepository;
import com.p2pchat.peer.service.EncryptionService;
import com.p2pchat.peer.RelayDefaults;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Lõi của hệ thống P2P.
 * Quản lý: kết nối đến bootstrap, kết nối đến các peers,
 * routing tin nhắn, heartbeat, store-and-forward.
 */
public class P2PNode {

    private static final Logger log = Logger.getLogger(P2PNode.class.getName());
    private static final int HEARTBEAT_INTERVAL_MS = 15_000;
    private static final int CONNECTION_TIMEOUT_MS  = 5_000;
    private static final int FILE_CHUNK_BYTES = 64 * 1024;
    private static final long MAX_FILE_TRANSFER_BYTES = 50L * 1024 * 1024;
    private static final long GROUP_MAX_FILE_BYTES = 20L * 1024 * 1024; // 20MB

    // Identity
    private final String peerId;
    private final String username;
    private int listeningPort;

    // Bootstrap info (saved so join-via-peer mode can still notify Bootstrap)
    private String bootstrapHost;
    private int bootstrapPort;

    // Network
    private PeerServer     peerServer;
    private BootstrapClient bootstrapClient;
    private PeerJoinClient  peerJoinClient;   // ← dùng khi join via peer
    private RelayClient    relayClient;       // ← kết nối relay server cho offline messaging
    private Thread serverThread;
    private Thread bootstrapThread;

    // Connected peers: peerId -> ConnectionHandler
    private final ConcurrentHashMap<String, ConnectionHandler> connections = new ConcurrentHashMap<>();

    // Known peers (from bootstrap): peerId -> PeerInfo
    private final ConcurrentHashMap<String, PeerInfo> knownPeers = new ConcurrentHashMap<>();

    // Groups: groupId -> ChatGroup
    private final ConcurrentHashMap<String, ChatGroup> groups = new ConcurrentHashMap<>();

    // Repository
    private final MessageRepository messageRepo;
    private final GroupRepository   groupRepo;

    // Encryption
    private final EncryptionService encryptionService;

    // UI callbacks
    private Consumer<Message> onMessageReceived;
    private Consumer<String> onAckReceived; // messageId → notify GUI update tick
    private BiConsumer<PeerInfo, Boolean> onPeerStatusChanged; // PeerInfo, isOnline
    private Consumer<String> onSystemEvent;
    /** (group, systemLine) — line shown as system message in that group's chat if the panel is open */
    private BiConsumer<ChatGroup, String> onGroupSync;
    /** groupId — this peer left the group; remove from sidebar / close panel */
    private Consumer<String> onLocalGroupLeft;
    /** Tin FILE / artifact đã lưu DB — UI thêm bubble (không đi qua luồng Message CHAT). */
    private Consumer<ChatMessage> onChatArtifact;
    /** Bootstrap server connection state — re-emitted to UI. */
    private java.util.function.Consumer<BootstrapStatus> onBootstrapStatus;
    /** Relay server connection state — re-emitted to UI. */
    private java.util.function.Consumer<RelayClient.RelayConnectionStatus> onRelayStatus;
    /** Số tin nhắn offline đang chờ */
    private int pendingOfflineCount = 0;
    private java.util.function.Consumer<Integer> onPendingCountChanged;

    private final ConcurrentHashMap<String, IncomingFileAssembly> incomingFileTransfers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> fileSendAborted = new ConcurrentHashMap<>();
    /** File đã nhận xong, chờ user bấm tải — key = messageId trong ChatMessage. */
    private final ConcurrentHashMap<String, byte[]> receivedFilePayloads = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "p2p-scheduler");
        t.setDaemon(true);
        return t;
    });

    // Chống xử lý trùng cùng messageId khi message đi qua nhiều đường.
    private static final long DEDUP_WINDOW_MS = 2 * 60 * 1000;
    private final ConcurrentHashMap<String, Long> recentlyProcessedMessageIds = new ConcurrentHashMap<>();

    public P2PNode(String peerId, String username) {
        this.peerId = peerId;
        this.username = username;
        this.messageRepo = new MessageRepository();
        this.groupRepo   = new GroupRepository();
        this.encryptionService = new EncryptionService();
    }

    // ─── Lifecycle ─────────────────────────────────────────────────────────────

    /** Backward-compat: join qua Bootstrap Server */
    public void start(String bootstrapHost, int bootstrapPort, int preferredPort) throws Exception {
        startViaBootstrap(bootstrapHost, bootstrapPort, preferredPort);
    }

    /**
     * Mode 1: Join qua Bootstrap Server.
     * BS trả toàn bộ PEER_LIST và broadcast PEER_JOINED cho mọi người.
     */
    public void startViaBootstrap(String bootstrapHost, int bootstrapPort, int preferredPort) throws Exception {
        this.listeningPort = PeerServer.findAvailablePort(preferredPort);
        this.bootstrapHost = bootstrapHost;
        this.bootstrapPort = bootstrapPort;
        log.info("[P2PNode] Starting via Bootstrap: " + bootstrapHost + ":" + bootstrapPort
                + "  myPort=" + listeningPort);

        // 1. Start TCP server
        startPeerServer();

        // 2. Kết nối Bootstrap
        if (bootstrapClient != null) {
            bootstrapClient.stopReconnectLoop(); // stop old reconnect loop before replacing
        }
        bootstrapClient = new BootstrapClient(
                bootstrapHost, bootstrapPort,
                peerId, username, listeningPort,
                this::handleBootstrapEvent
        );
        bootstrapClient.setOnStatusChange(this::forwardBootstrapStatus);
        List<PeerInfo> existingPeers = bootstrapClient.connectAndRegister();

        // 3. Lắng nghe events từ Bootstrap
        bootstrapThread = new Thread(bootstrapClient, "bootstrap-listener");
        bootstrapThread.setDaemon(true);
        bootstrapThread.start();

        // 4. Store peers & connect
        storePeersAndConnect(existingPeers);

        // 5. Restore groups + heartbeat
        restoreGroupsAndHeartbeat();

        // 6. Kết nối relay server cho offline messaging
        startRelayClient();

        notifySystem("✓ Joined via Bootstrap. " + existingPeers.size() + " peers online.");
    }

    /**
     * Mode 2: Join qua một peer đã biết (không cần Bootstrap Server).
     * Peer kia đóng vai mini-bootstrap: trả PEER_LIST + forward events.
     */
    public void startViaPeer(String knownPeerHost, int knownPeerPort, int preferredPort) throws Exception {
        this.listeningPort = PeerServer.findAvailablePort(preferredPort);
        log.info("[P2PNode] Starting via known peer: " + knownPeerHost + ":" + knownPeerPort
                + "  myPort=" + listeningPort);

        // 1. Start TCP server
        startPeerServer();

        // 2. Kết nối peer đã biết và lấy PEER_LIST
        peerJoinClient = new PeerJoinClient(
                knownPeerHost, knownPeerPort,
                peerId, username, listeningPort,
                this::handleBootstrapEvent   // reuse same handler
        );
        List<PeerInfo> existingPeers = peerJoinClient.connectAndGetPeers();

        // 3. Lắng nghe events tiếp theo từ peer kia
        bootstrapThread = new Thread(peerJoinClient, "peerrelay-listener");
        bootstrapThread.setDaemon(true);
        bootstrapThread.start();

        // 4. Store peers & connect
        storePeersAndConnect(existingPeers);

        // 5. Restore groups + heartbeat
        restoreGroupsAndHeartbeat();

        // 6. Kết nối relay server cho offline messaging
        startRelayClient();

        notifySystem("✓ Joined via peer relay. " + existingPeers.size() + " peers known.");
    }

    // ── Shared startup helpers ────────────────────────────────────────────────

    private void startPeerServer() {
        peerServer = new PeerServer(listeningPort,
                this::handleIncomingMessage,      // BiConsumer<Message, ConnectionHandler>
                this::registerIncomingConnection);
        serverThread = new Thread(peerServer, "peer-server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private void storePeersAndConnect(List<PeerInfo> peers) {
        for (PeerInfo peer : peers) {
            if (!peer.getPeerId().equals(peerId)) {
                knownPeers.put(peer.getPeerId(), peer);
            }
        }
        connectToKnownPeers();
    }

    private void restoreGroupsAndHeartbeat() {
        // Restore groups từ DB2
        List<ChatGroup> savedGroups = groupRepo.loadAllGroups();
        for (ChatGroup g : savedGroups) {
            groups.put(g.getGroupId(), g);
            log.info("[P2PNode] Restored group: " + g.getGroupName());
        }
        // Heartbeat
        scheduler.scheduleAtFixedRate(this::sendHeartbeats,
                HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::cleanupProcessedMessageIds,
                DEDUP_WINDOW_MS, DEDUP_WINDOW_MS, TimeUnit.MILLISECONDS);
        log.info("[P2PNode] Started. Connections: " + connections.size());
    }

    public void stop() {
        log.info("Stopping P2PNode...");
        scheduler.shutdownNow();

        // Thông báo các peers
        Message byeMsg = new Message(MessageType.PEER_LEFT, peerId, username);
        broadcastToAllPeers(byeMsg);

        // Ngắt kết nối
        connections.values().forEach(ConnectionHandler::close);
        connections.clear();

        if (bootstrapClient != null) bootstrapClient.disconnect();
        if (peerJoinClient != null) peerJoinClient.disconnect();
        if (relayClient != null) relayClient.disconnect();
        if (peerServer != null) peerServer.stop();
        receivedFilePayloads.clear();
    }

    // ─── Message Sending ───────────────────────────────────────────────────────

    /**
     * Gửi tin nhắn trực tiếp tới một peer.
     * @return messageId dùng cho UI / ACK (cùng id với bản ghi DB)
     */
    public String sendDirectMessage(String targetPeerId, String content) {
        Message msg = Message.createChat(peerId, username, targetPeerId, encryptionService.encrypt(content));

        // Lưu vào lịch sử local
        ChatMessage cm = ChatMessage.fromDirect(msg.getMessageId(), peerId, username, targetPeerId, content, true);
        messageRepo.saveMessage(cm);

        ConnectionHandler conn = connections.get(targetPeerId);
        if (conn != null && conn.isConnected()) {
            // Peer online - gửi trực tiếp P2P
            conn.sendMessage(msg);
            log.info("Direct message sent to " + targetPeerId);
        } else {
            // Peer offline - gửi qua relay server
            sendViaRelay(msg);
            // Đảm bảo DB phản ánh đúng: tin đang chờ relay, chưa delivered
            // (saveMessage() ở trên đã lưu với delivered=false nhờ fix ChatMessage.fromDirect,
            //  nhưng gọi thêm để chắc chắn trong mọi trường hợp race condition)
            messageRepo.markUndelivered(msg.getMessageId());
            log.info("Peer offline. Message sent via relay for: " + targetPeerId);
            notifySystem("⚠ " + getPeerUsername(targetPeerId) + " is offline. Message stored on relay server.");
        }
        return msg.getMessageId();
    }

    /**
     * Gửi tin nhắn nhóm tới tất cả members.
     * Dùng memberPeerIds làm primary list, connections map làm fallback.
     * @return messageId dùng cho UI / DB
     */
    public String sendGroupMessage(String groupId, String content) {
        ChatGroup group = groups.get(groupId);
        if (group == null) {
            log.warning("sendGroupMessage: group not found: " + groupId);
            return null;
        }

        Message msg = Message.createGroupChat(peerId, username, groupId, encryptionService.encrypt(content));

        // Lưu local
        ChatMessage cm = ChatMessage.fromGroup(msg.getMessageId(), peerId, username, groupId, content, true);
        messageRepo.saveMessage(cm);

        // Tập hợp targets: tất cả members trong group + tất cả connections đang có
        // (để đảm bảo không bỏ sót ai dù member list chưa sync đầy đủ)
        Set<String> targets = new java.util.LinkedHashSet<>(group.getMemberPeerIds());

        int sent = 0;
        for (String memberId : targets) {
            if (memberId.equals(peerId)) continue; // không gửi cho mình
            ConnectionHandler conn = connections.get(memberId);
            if (conn != null && conn.isConnected()) {
                conn.sendMessage(msg);
                sent++;
                log.fine("[Group] Sent to member: " + memberId);
            } else {
                // Không kiểm tra offline - tin nhắn vẫn được xử lý
                log.fine("[Group] Member not directly connected: " + memberId);
            }
        }
        log.info("[Group] sendGroupMessage '" + content + "' → " + sent + "/" + targets.size() + " members online");
        return msg.getMessageId();
    }

    /**
     * Broadcast tới toàn bộ peers đang kết nối
     */
    public void broadcastMessage(String content) {
        Message msg = Message.createBroadcast(peerId, username, encryptionService.encrypt(content));

        // Lưu local
        ChatMessage cm = ChatMessage.fromDirect(msg.getMessageId(), peerId, username, null, content, true);
        cm.setType(ChatMessage.MessageType.BROADCAST);
        messageRepo.saveMessage(cm);

        broadcastToAllPeers(msg);
        log.info("Broadcast sent to " + connections.size() + " peers.");
    }

    private void broadcastToAllPeers(Message msg) {
        connections.values().forEach(conn -> {
            if (conn.isConnected()) conn.sendMessage(msg);
        });
    }

    // ─── Incoming Message Handling ─────────────────────────────────────────────

    /** BiConsumer - PeerServer truyền cả message lẫn connection handler */
    private void handleIncomingMessage(Message msg, ConnectionHandler sourceHandler) {
        // Gán peerId vào handler ngay khi nhận message đầu tiên
        if (sourceHandler.getRemotePeerId() == null && msg.getSenderPeerId() != null) {
            sourceHandler.setRemotePeerId(msg.getSenderPeerId());
            // Thêm vào connections map nếu chưa có
            connections.putIfAbsent(msg.getSenderPeerId(), sourceHandler);
        }

        log.fine("Received [" + msg.getType() + "] from " + msg.getSenderPeerId());

        if (isDuplicateIncomingMessage(msg)) {
            log.fine("Ignored duplicate message: " + msg.getMessageId()
                    + " type=" + msg.getType());
            return;
        }

        switch (msg.getType()) {
            case CHAT        -> handleDirectChat(msg);
            case GROUP_CHAT  -> handleGroupChat(msg);
            case BROADCAST   -> handleBroadcast(msg);
            case ACK         -> handleAck(msg);
            case PEER_JOINED -> handlePeerJoined(msg);
            case PEER_LEFT   -> handlePeerLeft(msg);
            case CREATE_GROUP -> handleCreateGroup(msg);
            case JOIN_GROUP  -> handleJoinGroup(msg);
            case LEAVE_GROUP -> handleLeaveGroup(msg);
            case GROUP_INFO  -> handleGroupInfo(msg);
            case PING        -> handlePing(msg);
            case GET_PEERS   -> handleGetPeers(msg, sourceHandler); // truyền handler trực tiếp
            case FILE_TRANSFER_REQUEST  -> handleFileTransferRequest(msg);
            case FILE_CHUNK             -> handleFileChunk(msg);
            case FILE_TRANSFER_COMPLETE -> handleFileTransferComplete(msg);
            case FILE_TRANSFER_REJECT   -> handleFileTransferReject(msg);
            case GROUP_FILE_REQUEST  -> handleGroupFileRequest(msg);
            case GROUP_FILE_CHUNK    -> handleGroupFileChunk(msg);
            case GROUP_FILE_COMPLETE -> handleGroupFileComplete(msg);
            default          -> log.fine("Unhandled: " + msg.getType());
        }
    }

    private void handleDirectChat(Message msg) {
        // Decrypt
        String decrypted = encryptionService.decrypt(msg.getContent());
        msg.setContent(decrypted);

        // Lưu vào DB
        ChatMessage cm = ChatMessage.fromDirect(
                msg.getMessageId(), msg.getSenderPeerId(),
                msg.getSenderUsername(), peerId, decrypted, false
        );
        messageRepo.saveMessage(cm);

        // Gửi ACK
        ConnectionHandler conn = connections.get(msg.getSenderPeerId());
        if (conn != null) {
            conn.sendMessage(Message.createAck(peerId, msg.getMessageId()));
        }

        // Notify UI
        if (onMessageReceived != null) onMessageReceived.accept(msg);
    }

    private void handleGroupChat(Message msg) {
        String decrypted = encryptionService.decrypt(msg.getContent());
        msg.setContent(decrypted);

        // Lưu DB
        ChatMessage cm = ChatMessage.fromGroup(
                msg.getMessageId(), msg.getSenderPeerId(),
                msg.getSenderUsername(), msg.getGroupId(), decrypted, false
        );
        messageRepo.saveMessage(cm);

        // Relay tới các members khác trong group mà mình đang kết nối
        // (cần thiết khi topology không phải full-mesh — VD: ngocduc chỉ kết nối qua khoi,
        //  khoi cần relay message của ngocduc tới dang và ngược lại)
        ChatGroup group = groups.get(msg.getGroupId());
        if (group != null) {
            // Dùng messageId để chống relay vòng lặp: lưu đã relay rồi
            String relayKey = "relayed_" + msg.getMessageId();
            if (msg.getMeta(relayKey) == null) {
                msg.putMeta(relayKey, true); // đánh dấu đã relay
                for (String memberId : group.getMemberPeerIds()) {
                    // Không gửi lại cho sender và không gửi cho chính mình
                    if (!memberId.equals(msg.getSenderPeerId()) && !memberId.equals(peerId)) {
                        ConnectionHandler conn = connections.get(memberId);
                        if (conn != null && conn.isConnected()) {
                            conn.sendMessage(msg);
                            log.fine("[Relay] GROUP_CHAT " + msg.getMessageId()
                                    + " → " + memberId);
                        }
                    }
                }
            }
        }

        // Notify UI
        if (onMessageReceived != null) onMessageReceived.accept(msg);
    }

    private void handleBroadcast(Message msg) {
        String decrypted = encryptionService.decrypt(msg.getContent());
        msg.setContent(decrypted);

        ChatMessage cm = ChatMessage.fromDirect(
                msg.getMessageId(), msg.getSenderPeerId(),
                msg.getSenderUsername(), null, decrypted, false
        );
        cm.setType(ChatMessage.MessageType.BROADCAST);
        messageRepo.saveMessage(cm);

        if (onMessageReceived != null) onMessageReceived.accept(msg);
    }

    private void handleAck(Message msg) {
        String originalId = (String) msg.getMeta("originalMessageId");
        if (originalId == null) return;

        // Cập nhật DB
        messageRepo.markDelivered(originalId);
        messageRepo.deletePendingMessage(originalId);

        // Cập nhật UI: tìm ChatPanel tương ứng và update tick ✓ → ✓✓
        if (onAckReceived != null) {
            onAckReceived.accept(originalId);
        }
    }

    private void handlePeerJoined(Message msg) {
        PeerInfo peerInfo = (PeerInfo) msg.getMeta("peerInfo");
        if (peerInfo == null) {
            String ip   = (String)  msg.getMeta("ip");
            Object portObj = msg.getMeta("port");
            int port = (portObj instanceof Integer i) ? i
                    : (portObj instanceof Number  n) ? n.intValue() : 0;
            if (ip == null || port == 0) {
                log.warning("PEER_JOINED missing ip/port for: " + msg.getSenderPeerId());
                return;
            }
            peerInfo = new PeerInfo(msg.getSenderPeerId(), msg.getSenderUsername(), ip, port);
        }
        final PeerInfo pi = peerInfo;

        // Bỏ qua chính mình
        if (pi.getPeerId().equals(peerId)) return;

        // Kiểm tra peer đã online chưa TRƯỚC KHI cập nhật
        // để quyết định có forward hay không
        PeerInfo existing = knownPeers.get(pi.getPeerId());
        boolean wasAlreadyOnline = (existing != null && existing.isOnline());

        // Cập nhật trạng thái
        knownPeers.put(pi.getPeerId(), pi);
        pi.setOnline(true);

        log.info("[P2PNode] PEER_JOINED: " + pi.getUsername()
                + " @ " + pi.getIpAddress() + ":" + pi.getPort());

        // Kết nối tới peer mới nếu chưa có
        if (!connections.containsKey(pi.getPeerId())) {
            scheduler.submit(() -> connectToPeer(pi));
        }

        // Gửi pending messages
        scheduler.submit(() -> deliverPendingMessages(pi.getPeerId()));

        // QUAN TRỌNG: Forward PEER_JOINED tới tất cả connected peers
        // Điều này đảm bảo peers join qua peer (không có BootstrapClient) vẫn nhận
        // được thông báo khi có peer mới join qua Bootstrap.
        // Ví dụ: B join qua A, C join qua Bootstrap. Khi Bootstrap gửi PEER_JOINED(C) cho A,
        // A sẽ forward cho B để B biết C đã online.
        //
        // Chỉ forward nếu peer chưa từng online trước đó.
        // Kiểm tra này ngăn vòng lặp vô hạn khi A forward cho B, B forward lại cho A...
        if (!wasAlreadyOnline) {
            connections.forEach((pid, conn) -> {
                if (!pid.equals(pi.getPeerId()) && conn.isConnected()) {
                    conn.sendMessage(msg);
                }
            });
        }

        if (onPeerStatusChanged != null) onPeerStatusChanged.accept(pi, true);
        notifySystem("🟢 " + pi.getUsername() + " joined the network.");
    }

    private void handlePeerLeft(Message msg) {
        String leftPeerId = msg.getSenderPeerId();
        PeerInfo pi = knownPeers.get(leftPeerId);
        if (pi != null) {
            // Nếu đã offline trước đó thì bỏ qua để tránh duplicate notify.
            if (!pi.isOnline()) return;
            pi.setOnline(false);
            ConnectionHandler conn = connections.remove(leftPeerId);
            if (conn != null) conn.close();
            if (onPeerStatusChanged != null) onPeerStatusChanged.accept(pi, false);
            notifySystem("🔴 " + pi.getUsername() + " left the network.");
        }
    }

    private void handleCreateGroup(Message msg) {
        ChatGroup group = (ChatGroup) msg.getMeta("group");
        if (group != null) {
            // Lưu vào DB2 local
            groupRepo.saveGroup(group);
            log.info("[P2PNode] Received & saved group from peer: " + group.getGroupName());

            groups.put(group.getGroupId(), group);
            if (onGroupSync != null) {
                String by = msg.getSenderUsername() != null ? msg.getSenderUsername() : msg.getSenderPeerId();
                onGroupSync.accept(group, "📢 New group \"" + group.getGroupName() + "\" by " + by + ".");
            }
        }
    }

    private void handleJoinGroup(Message msg) {
        String groupId   = msg.getGroupId();
        String newMember = msg.getSenderPeerId();
        ChatGroup group  = groups.get(groupId);
        if (group != null) {
            group.addMember(newMember);
            groupRepo.addMember(groupId, newMember); // lưu DB2
            log.info("[P2PNode] Member joined group DB2: " + newMember);
            if (onGroupSync != null) {
                String who = getPeerUsername(newMember);
                onGroupSync.accept(group, "👋 " + who + " joined the group.");
            }
        }
    }

    private void handleLeaveGroup(Message msg) {
        String groupId = msg.getGroupId();
        ChatGroup group = groups.get(groupId);
        if (group != null) {
            String who = getPeerUsername(msg.getSenderPeerId());
            group.removeMember(msg.getSenderPeerId());
            groupRepo.removeMember(groupId, msg.getSenderPeerId()); // cập nhật DB2
            if (onGroupSync != null) {
                String line = msg.getSenderPeerId().equals(peerId)
                        ? "You left the group."
                        : ("👋 " + who + " left the group.");
                onGroupSync.accept(group, line);
            }
        }
    }

    private void handleGroupInfo(Message msg) {
        ChatGroup updatedGroup = (ChatGroup) msg.getMeta("group");
        if (updatedGroup != null) {
            ChatGroup previous = groups.get(updatedGroup.getGroupId());
            boolean iJoined = updatedGroup.hasMember(peerId)
                    && (previous == null || !previous.hasMember(peerId));
            // Replace toàn bộ group để đảm bảo member list luôn mới nhất
            groups.put(updatedGroup.getGroupId(), updatedGroup);
            groupRepo.saveGroup(updatedGroup); // upsert vào DB2
            log.info("[P2PNode] Group updated: " + updatedGroup.getGroupName()
                    + " members=" + updatedGroup.getMemberPeerIds().size());
            if (onGroupSync != null) {
                String line = iJoined
                        ? ("👋 You were added to \"" + updatedGroup.getGroupName() + "\".")
                        : ("👥 Group \"" + updatedGroup.getGroupName() + "\" updated — "
                        + updatedGroup.getMemberPeerIds().size() + " members.");
                onGroupSync.accept(updatedGroup, line);
            }
        }
    }

    private void handlePing(Message msg) {
        ConnectionHandler conn = connections.get(msg.getSenderPeerId());
        if (conn != null) {
            Message pong = new Message(MessageType.PONG, peerId, "pong");
            pong.setTargetPeerId(msg.getSenderPeerId());
            conn.sendMessage(pong);
        }
    }

    /**
     * Xử lý khi peer mới gửi GET_PEERS để join qua mình.
     * sourceHandler được truyền thẳng từ PeerServer — luôn non-null.
     */
    private void handleGetPeers(Message msg, ConnectionHandler sourceHandler) {
        String newPeerId   = msg.getSenderPeerId();
        String newUsername = msg.getSenderUsername();
        Object portObj     = msg.getMeta("port");
        int    newPort     = (portObj instanceof Integer i) ? i
                : (portObj instanceof Number  n) ? n.intValue() : 0;
        String newIp       = extractIp(sourceHandler.getRemoteAddress());

        log.info("[P2PNode] GET_PEERS from: " + newUsername + " @ " + newIp + ":" + newPort);

        // 1. Đăng ký handler vào connections ngay
        connections.putIfAbsent(newPeerId, sourceHandler);
        sourceHandler.setRemotePeerId(newPeerId);

        // 2. Tạo PeerInfo cho peer mới
        PeerInfo newPeer = null;
        if (newPort > 0 && !newIp.isEmpty()) {
            newPeer = new PeerInfo(newPeerId, newUsername, newIp, newPort);
            newPeer.setOnline(true);
        }

        // 3. Thêm peer mới vào knownPeers NGAY — để PEER_LIST bao gồm B
        //    (thay vì đợi đến bước notify UI)
        if (newPeer != null) {
            knownPeers.put(newPeerId, newPeer);
            log.info("[P2PNode] Added new peer to knownPeers before sending PEER_LIST: " + newUsername);
        }

        // 4. Build PEER_LIST = bản thân + tất cả knownPeers đang online
        //    (giờ B đã nằm trong knownPeers nên A sẽ thấy B trong PEER_LIST)
        List<PeerInfo> peerList = new ArrayList<>();
        PeerInfo myself = new PeerInfo(peerId, username, detectMyIp(), listeningPort);
        myself.setOnline(true);
        peerList.add(myself);
        for (PeerInfo p : knownPeers.values()) {
            if (p.isOnline() && !p.getPeerId().equals(newPeerId)) {
                peerList.add(p);
            }
        }

        // 5. Gửi PEER_LIST trực tiếp qua sourceHandler
        Message response = new Message(MessageType.PEER_LIST, peerId, "peers");
        response.setTargetPeerId(newPeerId);
        response.putMeta("peers", (java.io.Serializable) peerList);
        sourceHandler.sendMessage(response);
        log.info("[P2PNode] Sent PEER_LIST (" + peerList.size() + " peers) → " + newUsername);

        // 6. Thông báo cho Bootstrap Server biết peer mới đã join
        //    → Bootstrap broadcast PEER_JOINED cho TẤT CẢ peers (kể cả những peer
        //      không kết nối trực tiếp với A, như C join qua Bootstrap)
        if (newPeer != null && bootstrapClient != null && bootstrapClient.isConnected()) {
            bootstrapClient.notifyPeerJoined(newPeer);
        } else if (newPeer != null) {
            log.warning("[P2PNode] Cannot notify Bootstrap of new peer (not connected). "
                    + "Peer " + newUsername + " may not be visible to all network members.");
        }

        // 7. Broadcast PEER_JOINED tới các peers đang kết nối trực tiếp với mình
        if (newPeer != null) {
            Message joined = new Message(MessageType.PEER_JOINED, newPeerId, newUsername);
            joined.setSenderUsername(newUsername);
            joined.putMeta("peerInfo", newPeer);
            joined.putMeta("ip",   newIp);
            joined.putMeta("port", newPort);
            connections.forEach((pid, conn) -> {
                if (!pid.equals(newPeerId) && conn.isConnected()) conn.sendMessage(joined);
            });

            if (onPeerStatusChanged != null) onPeerStatusChanged.accept(newPeer, true);
            notifySystem("🟢 " + newUsername + " joined via peer relay.");
        }
    }

    private String detectMyIp() {
        try (java.net.DatagramSocket s = new java.net.DatagramSocket()) {
            s.connect(java.net.InetAddress.getByName("8.8.8.8"), 80);
            return s.getLocalAddress().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    private String extractIp(String remoteAddress) {
        // remoteAddress có dạng "/192.168.1.5:49201" hoặc "192.168.1.5:49201"
        if (remoteAddress == null) return "";
        String s = remoteAddress.replaceAll("^/", "");
        int colon = s.lastIndexOf(':');
        return colon > 0 ? s.substring(0, colon) : s;
    }

    // ─── Bootstrap Events ──────────────────────────────────────────────────────

    /**
     * Xử lý events từ Bootstrap hoặc PeerJoinClient.
     * Các message PEER_JOINED/LEFT đến từ bootstrap connection (không qua PeerServer).
     * Các message khác (GROUP_CHAT, ACK...) có thể đến khi peer relay qua connection này.
     */
    private void handleBootstrapEvent(Message msg) {
        log.info("[Bootstrap→] " + msg.getType() + " from=" + msg.getSenderPeerId()
                + " user=" + msg.getSenderUsername());
        if (isDuplicateIncomingMessage(msg)) {
            log.fine("[Bootstrap→] Ignored duplicate message: " + msg.getMessageId()
                    + " type=" + msg.getType());
            return;
        }
        switch (msg.getType()) {
            case PEER_JOINED  -> handlePeerJoined(msg);
            case PEER_LEFT    -> handlePeerLeft(msg);
            // Các message P2P có thể đến qua kênh này khi join via peer
            case CHAT         -> handleDirectChat(msg);
            case GROUP_CHAT   -> handleGroupChat(msg);
            case BROADCAST    -> handleBroadcast(msg);
            case ACK          -> handleAck(msg);
            case CREATE_GROUP -> handleCreateGroup(msg);
            case GROUP_INFO   -> handleGroupInfo(msg);
            case JOIN_GROUP   -> handleJoinGroup(msg);
            case LEAVE_GROUP  -> handleLeaveGroup(msg);
            // Relay notification - peer mới online, gửi pending messages
            case STORE_NOTIFY -> handleStoreNotify(msg);
            default           -> log.fine("[Bootstrap→] Ignored: " + msg.getType());
        }
    }

    /** Re-emits BootstrapClient status directly to the UI. */
    private void forwardBootstrapStatus(BootstrapStatus status) {
        if (onBootstrapStatus != null) {
            onBootstrapStatus.accept(status);
        }
    }

    /**
     * Xử lý thông báo có tin nhắn offline mới từ relay server.
     * Khi peer online và có tin nhắn offline đang chờ, relay server sẽ thông báo.
     */
    private void handleStoreNotify(Message msg) {
        Object countObj = msg.getMeta("pendingCount");
        int count = countObj instanceof Number n ? n.intValue() : 1;

        log.info("[P2PNode] STORE_NOTIFY: " + count + " messages waiting on relay");

        // Fetch tin nhắn từ relay server
        if (relayClient != null && relayClient.isConnected()) {
            relayClient.fetchPendingMessages();
        }

        notifySystem("📬 " + count + " offline message(s) received!");
    }

    // ─── Connection Management ─────────────────────────────────────────────────

    private void registerIncomingConnection(ConnectionHandler handler) {
        // Kết nối đến từ peer khác - chờ message đầu tiên để lấy peerId
        // peerId sẽ được set khi nhận message đầu tiên trong handleIncomingMessage
        log.info("Incoming connection registered from " + handler.getRemoteAddress());
    }

    private void connectToKnownPeers() {
        for (PeerInfo peer : knownPeers.values()) {
            if (!connections.containsKey(peer.getPeerId())) {
                try {
                    connectToPeer(peer);
                } catch (Exception e) {
                    log.warning("Could not connect to " + peer.getUsername() + ": " + e.getMessage());
                }
            }
        }
    }

    public boolean connectToPeer(PeerInfo peer) {
        if (peer.getPeerId().equals(peerId)) return false;
        if (connections.containsKey(peer.getPeerId())) return true;

        try {
            Socket socket = new Socket();
            socket.connect(
                    new java.net.InetSocketAddress(peer.getIpAddress(), peer.getPort()),
                    CONNECTION_TIMEOUT_MS
            );
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);

            ConnectionHandler[] ref = new ConnectionHandler[1];
            ref[0] = new ConnectionHandler(
                    socket,
                    msg -> handleIncomingMessage(msg, ref[0]),
                    () -> handlePeerDisconnected(peer.getPeerId())
            );
            ConnectionHandler handler = ref[0];
            handler.setRemotePeerId(peer.getPeerId());
            connections.put(peer.getPeerId(), handler);

            Thread t = new Thread(handler, "conn-" + peer.getUsername());
            t.setDaemon(true);
            t.start();

            // Gửi PING để xác nhận kết nối
            Message ping = new Message(MessageType.PING, peerId, username);
            ping.putMeta("port", listeningPort);
            handler.sendMessage(ping);

            log.info("Connected to peer: " + peer.getUsername() + " @ " + peer.getAddress());
            peer.setOnline(true);

            // Gửi pending messages
            deliverPendingMessages(peer.getPeerId());

            return true;
        } catch (IOException e) {
            log.warning("Cannot connect to " + peer.getUsername() + " @ " + peer.getAddress() + ": " + e.getMessage());
            peer.setOnline(false);
            return false;
        }
    }

    private void handlePeerDisconnected(String peerId) {
        connections.remove(peerId);
        PeerInfo pi = knownPeers.get(peerId);
        if (pi != null) {
            // Có thể vừa nhận PEER_LEFT rồi socket mới đóng callback.
            if (!pi.isOnline()) return;
            pi.setOnline(false);
            if (onPeerStatusChanged != null) onPeerStatusChanged.accept(pi, false);
            notifySystem("🔴 " + pi.getUsername() + " disconnected.");
        }
    }

    // ─── Store-and-Forward Delivery ────────────────────────────────────────────

    private void deliverPendingMessages(String targetPeerId) {
        List<Message> pending = messageRepo.getPendingMessages(targetPeerId);
        if (pending.isEmpty()) return;

        log.info("Delivering " + pending.size() + " pending messages to " + targetPeerId);
        ConnectionHandler conn = connections.get(targetPeerId);
        if (conn == null || !conn.isConnected()) return;

        for (Message msg : pending) {
            try {
                conn.sendMessage(msg);
                messageRepo.deletePendingMessage(msg.getMessageId());
                Thread.sleep(50); // rate-limit
            } catch (Exception e) {
                messageRepo.incrementRetryCount(msg.getMessageId());
            }
        }
    }

    // ─── Heartbeat ─────────────────────────────────────────────────────────────

    private void sendHeartbeats() {
        Message heartbeat = Message.createHeartbeat(peerId);
        List<String> toRemove = new ArrayList<>();

        connections.forEach((id, conn) -> {
            if (!conn.isConnected()) {
                toRemove.add(id);
            }
        });

        toRemove.forEach(id -> {
            connections.remove(id);
            handlePeerDisconnected(id);
        });
    }

    private boolean isDuplicateIncomingMessage(Message msg) {
        if (msg == null || msg.getMessageId() == null) return false;
        MessageType type = msg.getType();
        if (type != MessageType.CHAT
                && type != MessageType.GROUP_CHAT
                && type != MessageType.BROADCAST
                && type != MessageType.PEER_JOINED
                && type != MessageType.PEER_LEFT) {
            return false;
        }

        long now = System.currentTimeMillis();
        Long previous = recentlyProcessedMessageIds.putIfAbsent(msg.getMessageId(), now);
        return previous != null;
    }

    private void cleanupProcessedMessageIds() {
        long cutoff = System.currentTimeMillis() - DEDUP_WINDOW_MS;
        recentlyProcessedMessageIds.entrySet().removeIf(e -> e.getValue() < cutoff);
    }

    // ─── Group Management ──────────────────────────────────────────────────────

    public ChatGroup createGroup(String groupName) {
        String groupId = UUID.randomUUID().toString();
        ChatGroup group = new ChatGroup(groupId, groupName, peerId);
        group.addMember(peerId); // owner tự động là member

        // 1. Lưu vào DB2 (peer local)
        groupRepo.saveGroup(group);
        log.info("[P2PNode] Group saved to DB2: " + groupName);

        // 2. Lưu vào memory
        groups.put(groupId, group);

        // 3. Gửi lên Bootstrap để lưu DB1 (các peer khác có thể discover)
        Message dbMsg = new Message(MessageType.CREATE_GROUP, peerId, groupName);
        dbMsg.setGroupId(groupId);
        dbMsg.putMeta("group", group);
        try {
            if (bootstrapClient != null) bootstrapClient.sendMessage(dbMsg);
        } catch (Exception e) {
            log.warning("Failed to notify bootstrap of new group: " + e.getMessage());
        }

        // 4. Broadcast tới tất cả peers đang kết nối
//        broadcastToAllPeers(dbMsg);

        return group;
    }

    public void invitePeerToGroup(String groupId, String targetPeerId) {
        ChatGroup group = groups.get(groupId);
        if (group == null) return;

        // Thêm member mới vào group object
        group.addMember(targetPeerId);

        // Cập nhật groups map để UI nhìn thấy member list mới nhất
        groups.put(groupId, group);

        // Cập nhật DB2 local
        groupRepo.addMember(groupId, targetPeerId);

        // Gửi GROUP_INFO (group đầy đủ) tới:
        //   1. targetPeer: để họ biết họ đã join group
        //   2. TẤT CẢ members hiện tại: để họ cập nhật member list mới nhất
        // Quan trọng: nếu không broadcast, các member cũ không biết member mới
        // → sendGroupMessage sẽ thiếu member trong loop → không gửi tới đủ người
        Message groupInfoMsg = new Message(MessageType.GROUP_INFO, peerId, "invite");
        groupInfoMsg.setGroupId(groupId);
        groupInfoMsg.putMeta("group", group); // gửi cả group object với member list mới nhất

        // Gửi tới tất cả members (bao gồm cả targetPeer)
        for (String memberId : group.getMemberPeerIds()) {
            if (!memberId.equals(peerId)) { // không gửi cho chính mình
                ConnectionHandler conn = connections.get(memberId);
                if (conn != null && conn.isConnected()) {
                    conn.sendMessage(groupInfoMsg);
                    log.info("[Group] Sent updated GROUP_INFO to: " + memberId);
                }
            }
        }

        if (onGroupSync != null) {
            String invited = getPeerUsername(targetPeerId);
            onGroupSync.accept(group, "👥 Invited " + invited + " — " + group.getMemberPeerIds().size() + " members.");
        }
    }

    public void leaveGroup(String groupId) {
        ChatGroup group = groups.get(groupId);
        if (group == null) return;

        group.removeMember(peerId);
        Message msg = new Message(MessageType.LEAVE_GROUP, peerId, "leave");
        msg.setGroupId(groupId);
        msg.setSenderUsername(username);

        // Một kênh duy nhất để tránh LEAVE_GROUP trùng (P2P + bootstrap).
        if (bootstrapClient != null && bootstrapClient.isConnected()) {
            try {
                bootstrapClient.sendMessage(msg);
            } catch (Exception e) {
                log.warning("Failed to notify bootstrap of leave: " + e.getMessage());
            }
        } else {
            for (String memberId : group.getMemberPeerIds()) {
                ConnectionHandler conn = connections.get(memberId);
                if (conn != null && conn.isConnected()) conn.sendMessage(msg);
            }
        }

        groups.remove(groupId);
        groupRepo.deleteGroup(groupId);

        if (onLocalGroupLeft != null) onLocalGroupLeft.accept(groupId);
    }

    // ─── File transfer (direct P2P only) ───────────────────────────────────────

    private static final class IncomingFileAssembly {
        final String senderPeerId;
        final String senderUsername;
        final String fileName;
        final long fileSize;
        final ByteArrayOutputStream accumulator = new ByteArrayOutputStream();

        IncomingFileAssembly(String senderPeerId, String senderUsername,
                             String fileName, long fileSize) {
            this.senderPeerId = senderPeerId;
            this.senderUsername = senderUsername;
            this.fileName = fileName;
            this.fileSize = fileSize;
        }
    }

    private static int totalChunksForFileSize(long fileSize) {
        if (fileSize <= 0) return 0;
        return (int) ((fileSize + FILE_CHUNK_BYTES - 1) / FILE_CHUNK_BYTES);
    }

    private static String safeIncomingFileName(String name) {
        if (name == null || name.isBlank()) return "received.bin";
        String n = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return n.isEmpty() ? "received.bin" : n;
    }

    /**
     * Gửi file tới một peer qua kết nối TCP trực tiếp (không hàng đợi offline).
     * @return true nếu gửi xong (tối đa ~50 MB)
     */
    public boolean sendDirectFile(String targetPeerId, File file) throws IOException {
        if (file == null || !file.isFile()) return false;
        long len = file.length();
        if (len > MAX_FILE_TRANSFER_BYTES) {
            notifySystem("File too large (max 50 MB).");
            return false;
        }
        ConnectionHandler conn = connections.get(targetPeerId);
        if (conn == null || !conn.isConnected()) {
            notifySystem("Peer offline — cannot send file.");
            return false;
        }

        String transferId = UUID.randomUUID().toString();
        AtomicBoolean abort = new AtomicBoolean(false);
        fileSendAborted.put(transferId, abort);

        int totalChunks = totalChunksForFileSize(len);
        String fileName = file.getName();
        Message req = Message.createFileTransferRequest(peerId, username, targetPeerId,
                transferId, fileName, len, totalChunks);
        conn.sendMessage(req);

        try {
            if (len > 0) {
                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buf = new byte[FILE_CHUNK_BYTES];
                    int idx = 0;
                    int read;
                    while ((read = fis.read(buf)) != -1) {
                        if (abort.get()) {
                            fileSendAborted.remove(transferId);
                            return false;
                        }
                        byte[] chunk = read == buf.length ? buf : Arrays.copyOf(buf, read);
                        conn.sendMessage(Message.createFileChunk(peerId, username, targetPeerId,
                                transferId, idx++, totalChunks, chunk));
                    }
                }
            }
            if (abort.get()) {
                fileSendAborted.remove(transferId);
                return false;
            }
            conn.sendMessage(Message.createFileTransferComplete(peerId, username, targetPeerId, transferId));
        } finally {
            fileSendAborted.remove(transferId);
        }

        String display = "📎 Sent: " + fileName;
        ChatMessage cm = ChatMessage.fromDirectFile(UUID.randomUUID().toString(),
                peerId, username, targetPeerId, display, true);
        messageRepo.saveMessage(cm);
        if (onChatArtifact != null) onChatArtifact.accept(cm);
        return true;
    }

    public boolean sendGroupFile(String groupId, File file) {
        ChatGroup group = groups.get(groupId);
        if (group == null) {
            notifySystem(groupId + "Group is not exsist.");
            return false;
        }
        if (file.length() > GROUP_MAX_FILE_BYTES) {
            notifySystem("File must be less than 20MB for group file transfer.");
            return false;
        }

        String transferId = UUID.randomUUID().toString();
        long fileSize = file.length();
        int totalChunks = (int) Math.ceil((double) fileSize / FILE_CHUNK_BYTES);
        AtomicBoolean aborted = new AtomicBoolean(false);
        fileSendAborted.put(transferId, aborted);

        // Gửi REQUEST tới tất cả member đang connect
        Message req = Message.createGroupFileRequest(
            peerId, username, groupId, transferId,
            file.getName(), fileSize, totalChunks
        );
        sendToGroupConnections(group, req);

        scheduler.submit(() -> {
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buf = new byte[FILE_CHUNK_BYTES];
                int chunkIndex = 0;
                int read;
                while ((read = fis.read(buf)) != -1 && !aborted.get()) {
                    byte[] data = Arrays.copyOf(buf, read);
                    Message chunk = Message.createGroupFileChunk(
                        peerId, username, groupId, transferId,
                    chunkIndex, totalChunks, data);
                    sendToGroupConnections(group, chunk);
                    chunkIndex++;
                }
                if (!aborted.get()) {
                    Message done = Message.createGroupFileComplete(
                        peerId, username, groupId, transferId);
                    sendToGroupConnections(group, done);
                    
                    String display = "📎 Sent: " + file.getName();
                    ChatMessage cm = ChatMessage.fromGroupFile(UUID.randomUUID().toString(),
                    peerId, username, groupId, display, true);
                    messageRepo.saveMessage(cm);
                    if (onChatArtifact != null) onChatArtifact.accept(cm);
                }
            } catch (IOException e) {
                log.severe("sendGroupFile error: " + e.getMessage());
            } finally {
                fileSendAborted.remove(transferId);
            }
        });
        return true;
    }

    /** Gửi message tới tất cả member trong group mà mình đang có kết nối TCP */
    private void sendToGroupConnections(ChatGroup group, Message msg) {
        for (String memberId : group.getMemberPeerIds()) {
            if (memberId.equals(peerId)) continue;
            ConnectionHandler conn = connections.get(memberId);
            if (conn != null && conn.isConnected()) {
                conn.sendMessage(msg);
            } else {
                log.fine("[Group] Member not directly connected: " + memberId);
            }
        }
    }

    private void sendFileReject(String toPeerId, String transferId, String reason) {
        ConnectionHandler c = connections.get(toPeerId);
        if (c != null && c.isConnected()) {
            c.sendMessage(Message.createFileTransferReject(peerId, toPeerId, transferId, reason));
        }
    }

    private void handleFileTransferRequest(Message msg) {
        if (!peerId.equals(msg.getTargetPeerId())) return;
        String transferId = (String) msg.getMeta("transferId");
        String rawName = (String) msg.getMeta("fileName");
        Object fsObj = msg.getMeta("fileSize");
        Object tcObj = msg.getMeta("totalChunks");
        if (transferId == null || fsObj == null || tcObj == null) return;

        long fileSize = fsObj instanceof Number n ? n.longValue() : Long.parseLong(fsObj.toString());
        int totalChunks = tcObj instanceof Number n ? n.intValue() : Integer.parseInt(tcObj.toString());
        String fileName = safeIncomingFileName(rawName);

        if (fileSize < 0 || fileSize > MAX_FILE_TRANSFER_BYTES) {
            sendFileReject(msg.getSenderPeerId(), transferId, "file too large");
            return;
        }
        int expected = totalChunksForFileSize(fileSize);
        if (totalChunks != expected && !(fileSize == 0 && totalChunks == 0)) {
            sendFileReject(msg.getSenderPeerId(), transferId, "invalid chunk count");
            return;
        }
        incomingFileTransfers.putIfAbsent(transferId,
                new IncomingFileAssembly(msg.getSenderPeerId(), msg.getSenderUsername(), fileName, fileSize));
    }

    private void handleFileChunk(Message msg) {
        if (!peerId.equals(msg.getTargetPeerId())) return;
        String transferId = (String) msg.getMeta("transferId");
        Object dataObj = msg.getMeta("data");
        if (transferId == null || !(dataObj instanceof byte[] data)) return;

        IncomingFileAssembly asm = incomingFileTransfers.get(transferId);
        if (asm == null) return;
        synchronized (asm) {
            if (asm.accumulator.size() + data.length > asm.fileSize + FILE_CHUNK_BYTES) {
                incomingFileTransfers.remove(transferId);
                sendFileReject(msg.getSenderPeerId(), transferId, "unexpected data");
                return;
            }
            try {
                asm.accumulator.write(data);
            } catch (IOException e) {
                incomingFileTransfers.remove(transferId);
                sendFileReject(msg.getSenderPeerId(), transferId, "buffer error");
            }
        }
    }

    private void handleFileTransferComplete(Message msg) {
        if (!peerId.equals(msg.getTargetPeerId())) return;
        String transferId = (String) msg.getMeta("transferId");
        if (transferId == null) return;

        IncomingFileAssembly asm = incomingFileTransfers.remove(transferId);
        if (asm == null) return;

        byte[] payload;
        synchronized (asm) {
            if (asm.accumulator.size() != asm.fileSize) {
                log.warning("[File] Size mismatch transfer=" + transferId
                        + " expected=" + asm.fileSize + " got=" + asm.accumulator.size());
                return;
            }
            payload = asm.accumulator.toByteArray();
        }

        String messageId = UUID.randomUUID().toString();
        receivedFilePayloads.put(messageId, payload);
        String content = ChatMessage.FILE_PENDING_MARKER + "\n" + asm.fileName + "\n" + asm.fileSize;
        ChatMessage cm = ChatMessage.fromDirectFile(messageId,
                asm.senderPeerId, asm.senderUsername, peerId, content, false);
        messageRepo.saveMessage(cm);
        if (onChatArtifact != null) onChatArtifact.accept(cm);
        notifySystem("📥 " + asm.senderUsername + " sent a file — open chat to download.");
    }

    private void handleGroupFileRequest(Message msg) {
        String groupId = msg.getGroupId();
        String transferId = (String) msg.getMeta("transferId");
        if (groupId == null || transferId == null) return;
        if (isDuplicateIncomingMessage(msg)) return;
        
        // Tạo assembly
        String fileName = (String) msg.getMeta("fileName");
        long   fileSize = ((Number) msg.getMeta("fileSize")).longValue();
        IncomingFileAssembly asm = new IncomingFileAssembly(
            msg.getSenderPeerId(), msg.getSenderUsername(),
            fileName, fileSize);
        incomingFileTransfers.put(transferId, asm);

        //relay tới các member khác
        relayGroupFileMsg(msg, groupId);
    }

    private void handleGroupFileChunk(Message msg) {
        String transferId = (String) msg.getMeta("transferId");
        if (transferId == null) return;

        IncomingFileAssembly asm = incomingFileTransfers.get(transferId);
        if (asm != null) {
            byte[] data = (byte[]) msg.getMeta("data");
            if (data != null) {
                asm.accumulator.write(data, 0, data.length);
            }
        }

        //relay tiếp (dùng relayKey chống loop)
        relayGroupFileMsg(msg, msg.getGroupId());
    }

    private void handleGroupFileComplete(Message msg) {
        String transferId = (String) msg.getMeta("transferId");
        if (transferId == null) return;
    
        IncomingFileAssembly asm = incomingFileTransfers.remove(transferId);
        if (asm != null) {
            byte[] payload = asm.accumulator.toByteArray();
            // Tạo ChatMessage loại FILE, lưu DB, đưa lên UI
            String mid = UUID.randomUUID().toString();
            receivedFilePayloads.put(mid, payload);

            String content = ChatMessage.FILE_PENDING_MARKER
                + "\n" + asm.fileName
                + "\n" + asm.fileSize;
    
            ChatMessage cm = ChatMessage.fromGroupFile(
                    mid, msg.getSenderPeerId(), msg.getSenderUsername(),
                    msg.getGroupId(), content, false);
            messageRepo.saveMessage(cm);
    
            if (onChatArtifact != null) onChatArtifact.accept(cm);
            notifySystem("📥 " + asm.senderUsername
                    + " gửi file vào nhóm — mở group chat để tải về.");
        }
    
        relayGroupFileMsg(msg, msg.getGroupId());
    }

    /** Relay group file message tới các member khác, chống loop bằng relayKey */
    private void relayGroupFileMsg(Message msg, String groupId) {
        if (groupId == null) return;
        ChatGroup group = groups.get(groupId);
        if (group == null) return;

        String relayKey = "relayed_" + msg.getMessageId();
        if (msg.getMeta(relayKey) != null) return; // đã relay rồi
        msg.putMeta(relayKey, true);

        for (String memberId : group.getMemberPeerIds()) {
            if (memberId.equals(msg.getSenderPeerId())) continue;
            if (memberId.equals(peerId)) continue;
            ConnectionHandler conn = connections.get(memberId);
            if (conn != null && conn.isConnected()) {
                conn.sendMessage(msg);
            }
        }
    }

    /** Payload còn trong RAM (chưa tải xuống đĩa). */
    public boolean hasReceivedFilePayload(String messageId) {
        return messageId != null && receivedFilePayloads.containsKey(messageId);
    }

    /** Dữ liệu file nhận (không xóa — gọi {@link #consumeReceivedFilePayload} sau khi ghi file). */
    public byte[] peekReceivedFilePayload(String messageId) {
        if (messageId == null) return null;
        return receivedFilePayloads.get(messageId);
    }

    public void consumeReceivedFilePayload(String messageId) {
        if (messageId != null) receivedFilePayloads.remove(messageId);
    }

    /** Sau khi user lưu file: cập nhật DB để lần mở sau hiện đường dẫn, không còn nút tải. */
    public void markReceivedFileSavedInHistory(String messageId, java.nio.file.Path savedPath) {
        if (messageId == null || savedPath == null) return;
        String content = ChatMessage.FILE_SAVED_MARKER + "\n" + savedPath.toAbsolutePath();
        messageRepo.updateMessageContent(messageId, content);
    }

    private void handleFileTransferReject(Message msg) {
        if (!peerId.equals(msg.getTargetPeerId())) return;
        String transferId = (String) msg.getMeta("transferId");
        if (transferId != null) {
            AtomicBoolean a = fileSendAborted.get(transferId);
            if (a != null) a.set(true);
        }
        String reason = msg.getContent() != null && !msg.getContent().isEmpty()
                ? msg.getContent() : "rejected";
        notifySystem("File transfer: " + reason);
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private String getPeerUsername(String peerId) {
        PeerInfo pi = knownPeers.get(peerId);
        return pi != null ? pi.getUsername() : peerId;
    }

    private void notifySystem(String event) {
        log.info("[SYSTEM] " + event);
        if (onSystemEvent != null) onSystemEvent.accept(event);
    }

    // ─── Getters / Setters ─────────────────────────────────────────────────────

    public String getPeerId() { return peerId; }
    public String getUsername() { return username; }
    public int getListeningPort() { return listeningPort; }
    public Map<String, PeerInfo> getKnownPeers() { return Collections.unmodifiableMap(knownPeers); }
    public Map<String, ConnectionHandler> getConnections() { return Collections.unmodifiableMap(connections); }

    /** Có kết nối TCP trực tiếp tới peer (dùng cho gợi ý offline / queue). */
    public boolean isDirectPeerConnected(String peerId) {
        ConnectionHandler c = connections.get(peerId);
        return c != null && c.isConnected();
    }
    public Map<String, ChatGroup> getGroups() { return Collections.unmodifiableMap(groups); }
    public MessageRepository getMessageRepository() { return messageRepo; }

    public void setOnMessageReceived(Consumer<Message> handler) { this.onMessageReceived = handler; }
    public void setOnAckReceived(Consumer<String> handler) { this.onAckReceived = handler; }
    public void setOnPeerStatusChanged(BiConsumer<PeerInfo, Boolean> handler) { this.onPeerStatusChanged = handler; }
    public void setOnSystemEvent(Consumer<String> handler) { this.onSystemEvent = handler; }
    public void setOnGroupSync(BiConsumer<ChatGroup, String> handler) { this.onGroupSync = handler; }
    public void setOnLocalGroupLeft(Consumer<String> handler) { this.onLocalGroupLeft = handler; }
    public void setOnChatArtifact(Consumer<ChatMessage> handler) { this.onChatArtifact = handler; }
    public void setOnBootstrapStatusChanged(java.util.function.Consumer<BootstrapStatus> handler) {
        this.onBootstrapStatus = handler;
    }
    public void setOnRelayStatusChanged(java.util.function.Consumer<RelayClient.RelayConnectionStatus> handler) {
        this.onRelayStatus = handler;
    }
    public void setOnPendingCountChanged(java.util.function.Consumer<Integer> handler) {
        this.onPendingCountChanged = handler;
    }
    public int getPendingOfflineCount() { return pendingOfflineCount; }

    // ─── Relay Server Integration ───────────────────────────────────────────────

    /**
     * Kết nối đến Relay Server để gửi/nhận tin nhắn offline.
     * Gọi sau khi đã start() thành công.
     */
    public void startRelayClient() {
        startRelayClient(RelayDefaults.HOST, RelayDefaults.PORT);
    }

    /**
     * Kết nối đến Relay Server với cấu hình tùy chỉnh.
     */
    public void startRelayClient(String relayHost, int relayPort) {
        if (relayClient != null) {
            relayClient.disconnect();
        }

        relayClient = new RelayClient(relayHost, relayPort, peerId, username, listeningPort);
        relayClient.addMessageListener(this::handleRelayMessage);
        relayClient.setOnStatusChanged(this::forwardRelayStatus);
        relayClient.setOnPendingCountChanged(count -> {
            this.pendingOfflineCount = count;
            if (onPendingCountChanged != null) {
                onPendingCountChanged.accept(count);
            }
        });

        // Kết nối và đăng ký TRƯỚC, sau đó mới start listener
        // Điều này đảm bảo rằng khi listener bắt đầu, streams đã được khởi tạo
        Thread relayThread = new Thread(() -> {
            if (relayClient.connectAndRegister()) {
                log.info("[P2PNode] Connected to relay server at " + relayHost + ":" + relayPort);

                // Chỉ start listener SAU KHI connect thành công
                Thread relayListener = new Thread(relayClient, "relay-listener");
                relayListener.setDaemon(true);
                relayListener.start();
            } else {
                log.warning("[P2PNode] Failed to connect to relay server");
            }
        }, "relay-client");
        relayThread.setDaemon(true);
        relayThread.start();
    }

    /**
     * Dừng relay client.
     */
    public void stopRelayClient() {
        if (relayClient != null) {
            relayClient.disconnect();
            relayClient = null;
        }
    }

    /**
     * Gửi tin nhắn qua relay server (dùng khi peer đích offline).
     */
    public void sendViaRelay(Message msg) {
        if (relayClient != null && relayClient.isConnected()) {
            relayClient.storeOfflineMessage(msg);
            log.info("[P2PNode] Message sent via relay: " + msg.getMessageId());
        } else {
            log.warning("[P2PNode] Relay not connected, falling back to local storage");
            // Fallback: lưu local
            if (msg.getType() == MessageType.CHAT) {
                messageRepo.savePendingMessage(msg);
            }
        }
    }

    /**
     * Xử lý tin nhắn nhận được từ relay server.
     */
    private void handleRelayMessage(Message msg) {
        log.info("[P2PNode] Relay message received: " + msg.getType());

        // ACK ngược từ receiver (đã nhận tin offline) → đánh dấu delivered trong DB local
        if (msg.getType() == MessageType.ACK) {
            handleAck(msg);
            return;
        }

        // Decrypt nội dung
        String decrypted = encryptionService.decrypt(msg.getContent());
        msg.setContent(decrypted);

        // Lưu vào DB
        ChatMessage cm = ChatMessage.fromDirect(
                msg.getMessageId(), msg.getSenderPeerId(),
                msg.getSenderUsername(), peerId, decrypted, false
        );
        messageRepo.saveMessage(cm);

        // Xóa khỏi relay server (đã nhận thành công)
        if (relayClient != null) {
            relayClient.deleteMessage(msg.getMessageId());
        }

        // Gửi ACK về cho sender để họ markDelivered
        // Ưu tiên kết nối TCP trực tiếp, fallback sang relay nếu không có
        ConnectionHandler conn = connections.get(msg.getSenderPeerId());
        if (conn != null && conn.isConnected()) {
            conn.sendMessage(Message.createAck(peerId, msg.getMessageId()));
        } else if (relayClient != null && relayClient.isConnected()) {
            // Sender không online trực tiếp → gửi ACK ngược qua relay
            Message ackMsg = Message.createAck(peerId, msg.getMessageId());
            ackMsg.setTargetPeerId(msg.getSenderPeerId()); // đảm bảo relay biết route đúng
            relayClient.storeOfflineMessage(ackMsg);
            log.info("[P2PNode] ACK sent via relay for message: " + msg.getMessageId());
        }

        // Notify UI
        if (onMessageReceived != null) {
            onMessageReceived.accept(msg);
        }
    }

    private void forwardRelayStatus(RelayClient.RelayConnectionStatus status) {
        if (onRelayStatus != null) {
            onRelayStatus.accept(status);
        }
    }
}