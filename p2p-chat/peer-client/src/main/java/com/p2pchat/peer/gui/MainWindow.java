package com.p2pchat.peer.gui;

import com.p2pchat.peer.BootstrapDefaults;
import com.p2pchat.peer.model.ChatGroup;
import com.p2pchat.peer.model.ChatMessage;
import com.p2pchat.peer.model.PeerInfo;
import com.p2pchat.peer.network.BootstrapStatus;
import com.p2pchat.peer.network.P2PNode;
import com.p2pchat.peer.protocol.Message;
import com.p2pchat.peer.protocol.MessageType;
import com.p2pchat.peer.repository.MessageRepository;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MainWindow extends JFrame {

    // ── Bảng màu Light theme ─────────────────────────────────────────────────
    // Sidebar
    private static final Color SB_BG = new Color(248, 249, 252); // nền sidebar
    private static final Color SB_HEADER_BG = new Color(255, 255, 255); // header trắng
    private static final Color SB_BORDER = new Color(218, 220, 230); // viền ngăn cách
    private static final Color SB_ITEM_BG = Color.WHITE; // item nền
    private static final Color SB_ITEM_ALT = new Color(248, 249, 252); // item xen kẽ
    private static final Color SB_ITEM_SEL = new Color(224, 236, 255); // item đang chọn
    private static final Color SB_ITEM_HOV = new Color(237, 241, 250); // hover
    // Tab bar
    private static final Color TAB_BG = new Color(240, 242, 248);
    private static final Color TAB_ACTIVE_BG = new Color(255, 255, 255);
    private static final Color TAB_ACTIVE_BORDER = new Color(59, 130, 246); // xanh đậm
    // Text
    private static final Color TXT_PRIMARY = new Color(17, 24, 39); // gần đen
    private static final Color TXT_SECONDARY = new Color(75, 85, 99); // xám đậm
    private static final Color TXT_MUTED = new Color(156, 163, 175); // xám nhạt
    private static final Color TXT_ACCENT = new Color(37, 99, 235); // xanh
    // Status dots
    private static final Color CLR_ONLINE = new Color(22, 163, 74); // xanh lá đậm
    private static final Color CLR_OFFLINE = new Color(209, 213, 219); // xám
    // Status bar
    private static final Color STATUS_BG = new Color(243, 244, 246);
    // Misc
    private static final Color ACCENT = new Color(37, 99, 235);
    private static final Color ACCENT_HOVER = new Color(29, 78, 216);

    // ── State ─────────────────────────────────────────────────────────────────
    private final P2PNode node;
    private final int preferredPort;
    private final JPanel chatArea;
    private final CardLayout cardLayout;
    private final Map<String, ChatPanel> chatPanels = new ConcurrentHashMap<>();
    private final MessageRepository messageRepo = new MessageRepository();

    // Models
    private final DefaultListModel<PeerInfo> peerModel = new DefaultListModel<>();
    private final DefaultListModel<ChatGroup> groupModel = new DefaultListModel<>();
    private JList<PeerInfo> peerList;
    private JList<ChatGroup> groupList;

    // Status bar
    private final JLabel statusLabel = new JLabel("  Not connected — click Connect to join network");
    private final JLabel onlineCountLabel = new JLabel("0 online");

    // Custom tab
    private JButton tabPeersBtn;
    private JButton tabGroupsBtn;
    private final CardLayout sideCardLayout = new CardLayout();
    private final JPanel sideCards = new JPanel(sideCardLayout);

    // Nút refresh thủ công cho từng tab (tránh ghi đè listener/reference)
    private JButton peerRefreshBtn;
    private JButton groupRefreshBtn;
    private JButton connectBtn;
    private volatile boolean networkStarted = false;
    private volatile boolean bootstrapConnected = false;

    // ── Constructor ───────────────────────────────────────────────────────────
    public MainWindow(P2PNode node, int preferredPort) {
        super("P2P Chat  —  " + node.getUsername());
        this.node = node;
        this.preferredPort = preferredPort;
        cardLayout = new CardLayout();
        chatArea = new JPanel(cardLayout);
        chatArea.add(buildWelcomePanel(), "WELCOME");
        buildUI();
        registerCallbacks();
        loadHistoricalPeers();
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                confirmExit();
            }
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    // BUILD UI
    // ═════════════════════════════════════════════════════════════════════════

    private void buildUI() {
        setSize(1100, 740);
        setMinimumSize(new Dimension(860, 580));
        setLocationRelativeTo(null);
        setBackground(Color.WHITE);

        JPanel sidebar = buildSidebar();
        sidebar.setPreferredSize(new Dimension(260, 0));

        // Đường kẻ dọc giữa sidebar và chat
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, chatArea);
        split.setDividerLocation(260);
        split.setDividerSize(1);
        split.setResizeWeight(0.0);
        split.setBorder(null);
        split.setBackground(SB_BORDER);

        // Status bar
        JPanel statusBar = buildStatusBar();

        setLayout(new BorderLayout());
        add(split, BorderLayout.CENTER);
        add(statusBar, BorderLayout.SOUTH);
    }

    private JPanel buildSidebar() {
        JPanel sb = new JPanel(new BorderLayout());
        sb.setBackground(SB_BG);
        sb.setBorder(new MatteBorder(0, 0, 0, 1, SB_BORDER));

        // 1 ── Tôi (header)
        sb.add(buildMeHeader(), BorderLayout.NORTH);

        // 2 ── Tab + list
        JPanel center = new JPanel(new BorderLayout());
        center.setBackground(SB_BG);
        center.add(buildTabBar(), BorderLayout.NORTH);
        sideCards.setBackground(SB_BG);
        sideCards.add(buildPeersPanel(), "PEERS");
        sideCards.add(buildGroupsPanel(), "GROUPS");
        sideCardLayout.show(sideCards, "PEERS");
        center.add(sideCards, BorderLayout.CENTER);

        sb.add(center, BorderLayout.CENTER);
        return sb;
    }

    // ── Me header ─────────────────────────────────────────────────────────────
    private JPanel buildMeHeader() {
        JPanel panel = new JPanel(new BorderLayout(12, 0));
        panel.setBackground(SB_HEADER_BG);
        panel.setBorder(BorderFactory.createCompoundBorder(
                new MatteBorder(0, 0, 1, 0, SB_BORDER),
                new EmptyBorder(14, 16, 14, 16)));

        // App title bên trái
        JLabel appTitle = new JLabel("P2P Chat");
        appTitle.setFont(new Font("SansSerif", Font.BOLD, 18));
        appTitle.setForeground(ACCENT);

        // Me chip bên phải
        JPanel meChip = new JPanel(new BorderLayout(8, 0));
        meChip.setOpaque(false);

        // Avatar
        JPanel avatar = buildAvatarPanel(node.getUsername(), 34, ACCENT);

        JPanel info = new JPanel(new GridLayout(2, 1, 0, 1));
        info.setOpaque(false);
        JLabel nameLbl = new JLabel(node.getUsername());
        nameLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        nameLbl.setForeground(TXT_PRIMARY);
        JLabel portLbl = new JLabel("Port " + preferredPort);
        portLbl.setFont(new Font("SansSerif", Font.PLAIN, 11));
        portLbl.setForeground(TXT_MUTED);
        info.add(nameLbl);
        info.add(portLbl);

        meChip.add(avatar, BorderLayout.WEST);
        meChip.add(info, BorderLayout.CENTER);

        panel.add(appTitle, BorderLayout.WEST);
        panel.add(meChip, BorderLayout.EAST);
        return panel;
    }

    // ── Tab bar ───────────────────────────────────────────────────────────────
    private JPanel buildTabBar() {
        JPanel bar = new JPanel(new GridLayout(1, 2, 0, 0));
        bar.setBackground(TAB_BG);
        bar.setPreferredSize(new Dimension(0, 38));
        bar.setBorder(new MatteBorder(0, 0, 1, 0, SB_BORDER));

        tabPeersBtn = makeTabButton("👥  Peers", true);
        tabGroupsBtn = makeTabButton("💬  Groups", false);
        tabPeersBtn.addActionListener(e -> switchTab("PEERS"));
        tabGroupsBtn.addActionListener(e -> switchTab("GROUPS"));
        bar.add(tabPeersBtn);
        bar.add(tabGroupsBtn);
        return bar;
    }

    private JButton makeTabButton(String text, boolean active) {
        JButton btn = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean isActive = Boolean.TRUE.equals(getClientProperty("active"));
                // nền
                g2.setColor(isActive ? TAB_ACTIVE_BG : TAB_BG);
                g2.fillRect(0, 0, getWidth(), getHeight());
                // gạch dưới khi active
                if (isActive) {
                    g2.setColor(TAB_ACTIVE_BORDER);
                    g2.fillRect(0, getHeight() - 3, getWidth(), 3);
                }
                // chữ
                g2.setColor(isActive ? TXT_ACCENT : TXT_SECONDARY);
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(getText(),
                        (getWidth() - fm.stringWidth(getText())) / 2,
                        (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
                g2.dispose();
            }
        };
        btn.putClientProperty("active", active);
        btn.setFont(new Font("SansSerif", Font.BOLD, 12));
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    private void switchTab(String card) {
        sideCardLayout.show(sideCards, card);
        tabPeersBtn.putClientProperty("active", "PEERS".equals(card));
        tabGroupsBtn.putClientProperty("active", "GROUPS".equals(card));
        tabPeersBtn.repaint();
        tabGroupsBtn.repaint();
    }

    // ── Peers panel ───────────────────────────────────────────────────────────
    private JPanel buildPeersPanel() {
        connectBtn = makeRoundButton("Connect", ACCENT, ACCENT_HOVER);
        connectBtn.setPreferredSize(new Dimension(110, 26));
        connectBtn.addActionListener(e -> showConnectDialog());

        // Nút refresh thủ công
        peerRefreshBtn = makeRoundButton("⟳ Refresh", ACCENT, ACCENT_HOVER);
        peerRefreshBtn.setPreferredSize(new Dimension(110, 26));
        peerRefreshBtn.setEnabled(false);
        peerRefreshBtn.addActionListener(e -> doRefresh());

        JPanel header = buildSectionHeader(peerRefreshBtn, connectBtn);

        peerList = new JList<>(peerModel);
        peerList.setCellRenderer(new PeerCellRenderer());
        peerList.setBackground(SB_ITEM_BG);
        peerList.setSelectionBackground(SB_ITEM_SEL);
        peerList.setSelectionForeground(TXT_PRIMARY);
        peerList.setFixedCellHeight(62);
        peerList.setBorder(null);
        peerList.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        peerList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int i = peerList.locationToIndex(e.getPoint());
                if (i >= 0 && peerList.getCellBounds(i, i).contains(e.getPoint()))
                    openDirectChat(peerModel.get(i));
            }
        });

        JScrollPane scroll = styledScroll(peerList);

        // Empty state
        JPanel emptyPane = buildEmptyState("No peers online yet",
                "Waiting for others to join...", "👤");

        // Dùng CardLayout để toggle giữa list và empty state
        JPanel listArea = new JPanel(new CardLayout());
        listArea.add(scroll, "LIST");
        listArea.add(emptyPane, "EMPTY");
        ((CardLayout) listArea.getLayout()).show(listArea,
                peerModel.isEmpty() ? "EMPTY" : "LIST");

        // Observer: toggle khi model thay đổi
        peerModel.addListDataListener(new javax.swing.event.ListDataListener() {
            private void refresh() {
                ((CardLayout) listArea.getLayout()).show(listArea,
                        peerModel.isEmpty() ? "EMPTY" : "LIST");
            }

            public void intervalAdded(javax.swing.event.ListDataEvent e) {
                refresh();
            }

            public void intervalRemoved(javax.swing.event.ListDataEvent e) {
                refresh();
            }

            public void contentsChanged(javax.swing.event.ListDataEvent e) {
            }
        });

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(SB_BG);
        panel.add(header, BorderLayout.NORTH);
        panel.add(listArea, BorderLayout.CENTER);
        return panel;
    }

    // ── Groups panel ──────────────────────────────────────────────────────────
    private JPanel buildGroupsPanel() {
        // Section header + nút "New Group"
        JButton addBtn = makeRoundButton("+ New Group", ACCENT, ACCENT_HOVER);
        addBtn.setPreferredSize(new Dimension(110, 26));
        addBtn.addActionListener(e -> createNewGroup());

        // Nút refresh thủ công
        groupRefreshBtn = makeRoundButton("⟳ Refresh", ACCENT, ACCENT_HOVER);
        groupRefreshBtn.setPreferredSize(new Dimension(110, 26));
        groupRefreshBtn.setEnabled(false);
        groupRefreshBtn.addActionListener(e -> doRefresh());

        JPanel header = buildSectionHeader(groupRefreshBtn, addBtn);

        groupList = new JList<>(groupModel);
        groupList.setCellRenderer(new GroupCellRenderer());
        groupList.setBackground(SB_ITEM_BG);
        groupList.setSelectionBackground(SB_ITEM_SEL);
        groupList.setSelectionForeground(TXT_PRIMARY);
        groupList.setFixedCellHeight(58);
        groupList.setBorder(null);
        groupList.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        groupList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int i = groupList.locationToIndex(e.getPoint());
                if (i >= 0 && groupList.getCellBounds(i, i).contains(e.getPoint()))
                    openGroupChat(groupModel.get(i));
            }
        });

        JScrollPane scroll = styledScroll(groupList);

        JPanel emptyPane = buildEmptyState("No groups yet",
                "Click \"+ New Group\" to create one", "💬");

        JPanel listArea = new JPanel(new CardLayout());
        listArea.add(scroll, "LIST");
        listArea.add(emptyPane, "EMPTY");
        ((CardLayout) listArea.getLayout()).show(listArea,
                groupModel.isEmpty() ? "EMPTY" : "LIST");

        groupModel.addListDataListener(new javax.swing.event.ListDataListener() {
            private void refresh() {
                ((CardLayout) listArea.getLayout()).show(listArea,
                        groupModel.isEmpty() ? "EMPTY" : "LIST");
            }

            public void intervalAdded(javax.swing.event.ListDataEvent e) {
                refresh();
            }

            public void intervalRemoved(javax.swing.event.ListDataEvent e) {
                refresh();
            }

            public void contentsChanged(javax.swing.event.ListDataEvent e) {
            }
        });

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(SB_BG);
        panel.add(header, BorderLayout.NORTH);
        panel.add(listArea, BorderLayout.CENTER);
        return panel;
    }

    // ── Status bar ────────────────────────────────────────────────────────────
    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(STATUS_BG);
        bar.setPreferredSize(new Dimension(0, 26));
        bar.setBorder(new MatteBorder(1, 0, 0, 0, SB_BORDER));

        statusLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        statusLabel.setForeground(TXT_SECONDARY);

        // Online count pill
        JPanel pill = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 3)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(220, 252, 231));
                g2.fillRoundRect(0, 1, getWidth() - 2, getHeight() - 2, 10, 10);
                g2.dispose();
            }
        };
        pill.setOpaque(false);
        // Dot
        JLabel dot = new JLabel("●");
        dot.setFont(new Font("SansSerif", Font.PLAIN, 8));
        dot.setForeground(CLR_ONLINE);
        onlineCountLabel.setFont(new Font("SansSerif", Font.BOLD, 11));
        onlineCountLabel.setForeground(new Color(22, 101, 52));
        pill.add(dot);
        pill.add(onlineCountLabel);

        bar.add(statusLabel, BorderLayout.WEST);
        bar.add(pill, BorderLayout.EAST);
        return bar;
    }

    // ── Welcome panel ─────────────────────────────────────────────────────────
    private JPanel buildWelcomePanel() {
        JPanel outer = new JPanel(new GridBagLayout());
        outer.setBackground(new Color(249, 250, 251));

        JPanel card = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Color.WHITE);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20);
                g2.setColor(SB_BORDER);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 20, 20);
                g2.dispose();
            }
        };
        card.setOpaque(false);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(44, 60, 44, 60));
        card.setPreferredSize(new Dimension(380, 260));

        // Icon circle
        JPanel iconCircle = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // outer glow
                g2.setColor(new Color(219, 234, 254));
                g2.fillOval(4, 4, getWidth() - 8, getHeight() - 8);
                // inner
                g2.setColor(ACCENT);
                g2.fillOval(14, 14, getWidth() - 28, getHeight() - 28);
                // chat icon
                g2.setColor(Color.WHITE);
                int cx = getWidth() / 2, cy = getHeight() / 2;
                g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawRoundRect(cx - 18, cy - 13, 36, 26, 8, 8);
                g2.fillOval(cx - 6, cy + 11, 7, 7);
                g2.dispose();
            }
        };
        iconCircle.setOpaque(false);
        iconCircle.setPreferredSize(new Dimension(88, 88));
        iconCircle.setMaximumSize(new Dimension(Integer.MAX_VALUE, 88));

        JLabel title = new JLabel("Select a peer to chat");
        title.setFont(new Font("SansSerif", Font.BOLD, 20));
        title.setForeground(TXT_PRIMARY);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel sub = new JLabel(
                "<html><center style='color:#6B7280;font-size:13px'>"
                        + "Peers appear in the sidebar automatically<br>"
                        + "when they join the network</center></html>");
        sub.setAlignmentX(Component.CENTER_ALIGNMENT);

        card.add(iconCircle);
        card.add(Box.createVerticalStrut(20));
        card.add(title);
        card.add(Box.createVerticalStrut(10));
        card.add(sub);

        outer.add(card);
        return outer;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HISTORICAL PEERS (load từ DB local)
    // ═════════════════════════════════════════════════════════════════════════

    private void loadHistoricalPeers() {
        SwingWorker<java.util.List<PeerInfo>, Void> worker = new SwingWorker<java.util.List<PeerInfo>, Void>() {
            @Override
            protected java.util.List<PeerInfo> doInBackground() {
                return messageRepo.getHistoricalPeers(node.getPeerId());
            }

            @Override
            protected void done() {
                try {
                    java.util.List<PeerInfo> historical = get();
                    for (PeerInfo p : historical) {
                        // Chỉ thêm nếu chưa có trong model (tránh đè peer online vừa join)
                        boolean exists = false;
                        for (int i = 0; i < peerModel.size(); i++) {
                            if (peerModel.get(i).getPeerId().equals(p.getPeerId())) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) {
                            peerModel.addElement(p);
                        }
                    }
                    if (!historical.isEmpty()) {
                        onlineCountLabel.setText(countOnline() + " online");
                    }
                } catch (Exception e) {
                    // không critical, bỏ qua
                }
            }
        };
        worker.execute();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CALLBACKS & AUTO-REFRESH
    // ═════════════════════════════════════════════════════════════════════════

    private void registerCallbacks() {
        node.setOnMessageReceived(this::onMessageReceived);
        node.setOnPeerStatusChanged(this::onPeerStatusChanged);
        node.setOnGroupSync((g, line) -> SwingUtilities.invokeLater(() -> {
            upsertGroup(g);
            ChatPanel cp = chatPanels.get(g.getGroupId());
            if (cp != null)
                cp.addSystemMessage(line);
            if (groupList != null)
                groupList.repaint();
        }));
        node.setOnLocalGroupLeft(gid -> SwingUtilities.invokeLater(() -> removeLocalGroupUi(gid)));
        node.setOnChatArtifact(cm -> SwingUtilities.invokeLater(() -> {
            String convId = (cm.getGroupId() != null)
                    ? cm.getGroupId()
                    : (cm.isOwn() ? cm.getTargetPeerId() : cm.getSenderPeerId());
            if (convId == null)
                return;
            ChatPanel cp = chatPanels.get(convId);
            if (cp != null)
                cp.addIncomingMessage(cm);
        }));
        node.setOnSystemEvent(msg -> SwingUtilities.invokeLater(() -> {
            statusLabel.setText("  " + msg);
            String cur = getCurrentPanelId();
            if (cur != null && chatPanels.containsKey(cur))
                chatPanels.get(cur).addSystemMessage(msg);
        }));
        node.setOnBootstrapStatusChanged(this::onBootstrapStatusChanged);

        // Khi nhận ACK → tìm đúng ChatPanel của cuộc trò chuyện và update tick ✓ → ✓✓
        node.setOnAckReceived(messageId -> {
            // Tìm ChatPanel chứa bubble có messageId này
            // chatPanels key = peerId (direct) hoặc groupId (group)
            for (ChatPanel cp : chatPanels.values()) {
                // updateDeliveredStatus tự check bubbleMap, nếu không có messageId đó thì bỏ
                // qua
                cp.updateDeliveredStatus(messageId);
            }
        });
    }

    private void onBootstrapStatusChanged(BootstrapStatus status) {
        SwingUtilities.invokeLater(() -> {
            if (status == null)
                return;
            switch (status.state()) {
                case RECONNECTING -> {
                    bootstrapConnected = false;
                    connectBtn.setText("Reconnecting...");
                    connectBtn.setEnabled(false);
                    int a = status.reconnectAttempt();
                    int m = status.reconnectMax();
                    statusLabel.setText(
                            "  ⏳ Reconnecting to bootstrap server... (" + a + "/" + m + ")");
                }
                case CONNECTED -> {
                    bootstrapConnected = true;
                    connectBtn.setText("Connected");
                    connectBtn.setEnabled(false);
                    statusLabel.setText("  ✓ Connected to bootstrap server");
                }
                case DISCONNECTED -> {
                    networkStarted = false;
                    bootstrapConnected = false;
                    connectBtn.setText("Connect");
                    connectBtn.setEnabled(true);
                    // Remove all old listeners to prevent duplicate showConnectDialog() calls
                    for (ActionListener al : connectBtn.getActionListeners()) {
                        connectBtn.removeActionListener(al);
                    }
                    connectBtn.addActionListener(e -> showConnectDialog());
                    statusLabel.setText("  Bootstrap server unavailable — click Connect to retry");
                }
            }
        });
    }

    /** Sau khi peer này rời nhóm (DB local đã xóa). */
    private void removeLocalGroupUi(String groupId) {
        int idx = indexOfGroup(groupId);
        if (idx >= 0)
            groupModel.remove(idx);
        ChatPanel cp = chatPanels.remove(groupId);
        if (cp != null) {
            boolean wasVisible = cp.isVisible();
            chatArea.remove(cp);
            if (wasVisible)
                cardLayout.show(chatArea, "WELCOME");
        }
        if (groupList != null)
            groupList.repaint();
        statusLabel.setText("  You left the group.");
    }

    /**
     * Auto-refresh danh sách peer mỗi 1.5s từ node.getKnownPeers().
     * Đảm bảo UI luôn phản ánh đúng trạng thái mạng dù callback bị miss.
     */
    /**
     * Refresh thủ công: đọc thẳng từ node.getKnownPeers() và node.getGroups()
     * rồi cập nhật model trên EDT.
     */
    private void doRefresh() {
        // Hiệu ứng spin trên nút
        setRefreshButtonsEnabled(false);
        setRefreshButtonsText("...");

        SwingUtilities.invokeLater(() -> {
            // ── Sync peers ───────────────────────────────────────────────
            for (PeerInfo peer : node.getKnownPeers().values()) {
                if (!peer.getPeerId().equals(node.getPeerId())) {
                    upsertPeer(peer);
                }
            }
            onlineCountLabel.setText(countOnline() + " online");
            peerList.repaint();

            // ── Sync groups ──────────────────────────────────────────────
            Set<String> nodeGroupIds = new HashSet<>();
            for (ChatGroup g : node.getGroups().values()) {
                nodeGroupIds.add(g.getGroupId());
                int idx = indexOfGroup(g.getGroupId());
                if (idx >= 0)
                    groupModel.set(idx, g);
                else
                    groupModel.addElement(g);
            }
            for (int i = groupModel.size() - 1; i >= 0; i--) {
                if (!nodeGroupIds.contains(groupModel.get(i).getGroupId()))
                    groupModel.remove(i);
            }
            groupList.repaint();

            // Trả nút về trạng thái bình thường sau 400ms
            javax.swing.Timer reset = new javax.swing.Timer(400, ev -> {
                setRefreshButtonsText("⟳ Refresh");
                setRefreshButtonsEnabled(true);
            });
            reset.setRepeats(false);
            reset.start();

            statusLabel.setText("  ✓ Refreshed — " + peerModel.size() + " peers known");
        });
    }

    private void showConnectDialog() {
        if (networkStarted) {
            doRefresh();
            return;
        }

        String[] options = { "Bootstrap", "Known Peer", "Cancel" };
        int choice = JOptionPane.showOptionDialog(
                this,
                "Choose how to join network:",
                "Connect to Network",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]);
        if (choice == 0) {
            connectViaBootstrap();
        } else if (choice == 1) {
            connectViaKnownPeer();
        }
    }

    private void connectViaBootstrap() {
        JTextField hostField = new JTextField(BootstrapDefaults.HOST);
        JSpinner portSpinner = new JSpinner(new SpinnerNumberModel(BootstrapDefaults.PORT, 1, 65535, 1));
        JPanel p = new JPanel(new GridLayout(2, 2, 8, 8));
        p.add(new JLabel("Bootstrap IP/Host:"));
        p.add(hostField);
        p.add(new JLabel("Bootstrap Port:"));
        p.add(portSpinner);

        int r = JOptionPane.showConfirmDialog(
                this, p, "Connect via Bootstrap", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION)
            return;

        startNetworkAsync("Connecting to bootstrap...",
                () -> node.startViaBootstrap(hostField.getText().trim(), (int) portSpinner.getValue(), preferredPort));
    }

    private void connectViaKnownPeer() {
        JTextField hostField = new JTextField();
        JSpinner portSpinner = new JSpinner(new SpinnerNumberModel(9101, 1, 65535, 1));
        JPanel p = new JPanel(new GridLayout(2, 2, 8, 8));
        p.add(new JLabel("Known Peer IP/Host:"));
        p.add(hostField);
        p.add(new JLabel("Known Peer Port:"));
        p.add(portSpinner);

        int r = JOptionPane.showConfirmDialog(
                this, p, "Connect via Known Peer", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION)
            return;
        if (hostField.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter known peer IP/host.",
                    "Missing input", JOptionPane.WARNING_MESSAGE);
            return;
        }

        startNetworkAsync("Connecting via known peer...",
                () -> node.startViaPeer(hostField.getText().trim(), (int) portSpinner.getValue(), preferredPort));
    }

    private interface ConnectAction {
        void run() throws Exception;
    }

    private void startNetworkAsync(String status, ConnectAction action) {
        connectBtn.setEnabled(false);
        connectBtn.setText("...");
        statusLabel.setText("  " + status);

        new SwingWorker<Void, Void>() {
            private String error;

            @Override
            protected Void doInBackground() {
                try {
                    action.run();
                } catch (Exception e) {
                    error = formatNetworkError(e);
                }
                return null;
            }

            @Override
            protected void done() {
                if (error != null) {
                    connectBtn.setEnabled(true);
                    connectBtn.setText("Connect");
                    statusLabel.setText("  Connect failed");
                    JOptionPane.showMessageDialog(MainWindow.this,
                            "<html><b>Connect failed:</b><br>" + error + "</html>",
                            "Connection Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }

                networkStarted = true;
                bootstrapConnected = true;
                connectBtn.setText("Connected");
                setRefreshButtonsEnabled(true);
                addKnownPeers(node.getKnownPeers().values());
                node.getGroups().values().forEach(MainWindow.this::upsertGroup);
                onlineCountLabel.setText(countOnline() + " online");
                statusLabel.setText("  ✓ Connected to network");
            }
        }.execute();
    }

    private void setRefreshButtonsEnabled(boolean enabled) {
        if (peerRefreshBtn != null)
            peerRefreshBtn.setEnabled(enabled);
        if (groupRefreshBtn != null)
            groupRefreshBtn.setEnabled(enabled);
    }

    private void setRefreshButtonsText(String text) {
        if (peerRefreshBtn != null)
            peerRefreshBtn.setText(text);
        if (groupRefreshBtn != null)
            groupRefreshBtn.setText(text);
    }

    private static String formatNetworkError(Throwable e) {
        String m = e.getMessage() != null ? e.getMessage() : e.toString();
        Throwable c = e.getCause();
        if (c != null && c.getMessage() != null && !m.contains(c.getMessage())) {
            m = m + " — " + c.getMessage();
        }
        String lower = m.toLowerCase();
        if (lower.contains("refused") || lower.contains("timed out") || lower.contains("timeout")) {
            m += "<br><br><small>Kiểm tra: máy bootstrap đang chạy jar; nhập <b>IP LAN</b> của máy đó (không dùng localhost từ máy khác);"
                    + " cổng là cổng <b>TCP bootstrap</b> (vd 9000), không phải cổng MySQL hay cổng peer.</small>";
        }
        return m;
    }

    private void onMessageReceived(Message msg) {
        SwingUtilities.invokeLater(() -> {
            // Đồng bộ nhóm qua onGroupSync — tránh hiển thị như tin chat 1-1
            if (msg.getType() == MessageType.CREATE_GROUP
                    || msg.getType() == MessageType.GROUP_INFO
                    || msg.getType() == MessageType.JOIN_GROUP
                    || msg.getType() == MessageType.LEAVE_GROUP) {
                return;
            }

            // Bỏ qua chunk/request — chúng không tạo bubble trực tiếp
            if (msg.getType() == MessageType.GROUP_FILE_REQUEST
                    || msg.getType() == MessageType.GROUP_FILE_CHUNK) {
                return;
            }

            if (msg.getType() == MessageType.BROADCAST) {
                // Broadcast từ người khác mới hiển thị, không hiện của mình
                if (!msg.getSenderPeerId().equals(node.getPeerId())) {
                    chatPanels.values().forEach(cp -> cp.addSystemMessage("[Broadcast] " + msg.getSenderUsername()
                            + ": " + msg.getContent()));
                }
                return;
            }

            // Bỏ qua message do chính mình gửi —
            // ChatPanel.sendMessage() đã thêm optimistic bubble rồi
            if (msg.getSenderPeerId().equals(node.getPeerId()))
                return;

            String convId;
            boolean isGroup = msg.getType() == MessageType.GROUP_CHAT
                    || msg.getType() == MessageType.GROUP_FILE_REQUEST
                    || msg.getType() == MessageType.GROUP_FILE_CHUNK
                    || msg.getType() == MessageType.GROUP_FILE_COMPLETE;
            convId = isGroup ? msg.getGroupId() : msg.getSenderPeerId();

            boolean createdNewPanel = false;
            if (!chatPanels.containsKey(convId)) {
                String name = isGroup
                        ? node.getGroups().getOrDefault(convId,
                                new ChatGroup(convId, "Group", "")).getGroupName()
                        : node.getKnownPeers().getOrDefault(msg.getSenderPeerId(),
                                new PeerInfo(msg.getSenderPeerId(), msg.getSenderUsername(), "", 0)).getUsername();
                openChatPanel(convId, name, isGroup);
                createdNewPanel = true;
            }

            ChatPanel cp = chatPanels.get(convId);
            if (cp != null) {
                ChatMessage cm = isGroup
                        ? ChatMessage.fromGroup(msg.getMessageId(), msg.getSenderPeerId(),
                                msg.getSenderUsername(), convId, msg.getContent(), false)
                        : ChatMessage.fromDirect(msg.getMessageId(), msg.getSenderPeerId(),
                                msg.getSenderUsername(), node.getPeerId(), msg.getContent(), false);
                // ChatPanel vừa tạo sẽ loadHistory(), đã chứa message mới nhất từ DB.
                if (!createdNewPanel)
                    cp.addIncomingMessage(cm);
                if (!convId.equals(getCurrentPanelId()))
                    statusLabel.setText("  💬 New message from " + msg.getSenderUsername());
            }
        });
    }

    private void onPeerStatusChanged(PeerInfo peer, boolean isOnline) {
        SwingUtilities.invokeLater(() -> {
            peer.setOnline(isOnline);
            upsertPeer(peer);
            if (peerList != null)
                peerList.repaint();
            onlineCountLabel.setText(countOnline() + " online");
            ChatPanel cp = chatPanels.get(peer.getPeerId());
            if (cp != null)
                cp.setStatus(isOnline ? "● Online" : "○ Offline");
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    // NAVIGATION
    // ═════════════════════════════════════════════════════════════════════════

    public void openDirectChat(PeerInfo peer) {
        openChatPanel(peer.getPeerId(), peer.getUsername(), false);
    }

    public void openGroupChat(ChatGroup group) {
        openChatPanel(group.getGroupId(), group.getGroupName(), true);
    }

    private void openChatPanel(String id, String name, boolean isGroup) {
        if (!chatPanels.containsKey(id)) {
            ChatPanel panel = new ChatPanel(node, id, name, isGroup);
            chatPanels.put(id, panel);
            chatArea.add(panel, id);
        }
        cardLayout.show(chatArea, id);
    }

    private String getCurrentPanelId() {
        for (var e : chatPanels.entrySet())
            if (e.getValue().isVisible())
                return e.getKey();
        return null;
    }

    private void createNewGroup() {
        String name = JOptionPane.showInputDialog(this,
                "Enter group name:", "New Group", JOptionPane.QUESTION_MESSAGE);
        if (name != null && !name.isBlank()) {
            ChatGroup g = node.createGroup(name.trim());
            upsertGroup(g);
            switchTab("GROUPS");
            openGroupChat(g);
            ChatPanel cp = chatPanels.get(g.getGroupId());
            if (cp != null)
                cp.addSystemMessage("You created this group.");
        }
    }

    private void confirmExit() {
        int r = JOptionPane.showConfirmDialog(this,
                "Disconnect and exit?", "Exit P2P Chat",
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (r == JOptionPane.YES_OPTION) {
            node.stop();
            System.exit(0);
        }
    }

    public void addKnownPeers(java.util.Collection<PeerInfo> peers) {
        SwingUtilities.invokeLater(() -> {
            for (PeerInfo p : peers) {
                if (!p.getPeerId().equals(node.getPeerId())) {
                    // upsertPeer sẽ update nếu peer lịch sử đã có trong model,
                    // hoặc thêm mới nếu chưa có — đảm bảo online=true từ network
                    upsertPeer(p);
                }
            }
            onlineCountLabel.setText(countOnline() + " online");
        });
    }

    public void addGroup(ChatGroup g) {
        SwingUtilities.invokeLater(() -> upsertGroup(g));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CELL RENDERERS
    // ═════════════════════════════════════════════════════════════════════════

    private class PeerCellRenderer implements ListCellRenderer<PeerInfo> {
        @Override
        public Component getListCellRendererComponent(JList<? extends PeerInfo> list,
                PeerInfo peer, int idx, boolean selected, boolean focused) {

            JPanel cell = new JPanel(new BorderLayout(10, 0)) {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    if (selected)
                        g2.setColor(SB_ITEM_SEL);
                    else if (idx % 2 == 0)
                        g2.setColor(SB_ITEM_BG);
                    else
                        g2.setColor(SB_ITEM_ALT);
                    g2.fillRect(0, 0, getWidth(), getHeight());
                    // bottom separator
                    g2.setColor(new Color(232, 234, 242));
                    g2.fillRect(16, getHeight() - 1, getWidth() - 16, 1);
                    g2.dispose();
                }
            };
            cell.setOpaque(false);
            cell.setBorder(new EmptyBorder(10, 16, 10, 16));

            // Avatar
            Color avColor = peer.isOnline() ? ACCENT : new Color(156, 163, 175);
            JPanel av = buildAvatarPanel(peer.getUsername(), 38, avColor);

            // Text
            JLabel nameLbl = new JLabel(peer.getUsername());
            nameLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
            nameLbl.setForeground(TXT_PRIMARY);

            // Status badge
            JPanel statusBadge = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            statusBadge.setOpaque(false);
            JLabel dotLbl = new JLabel("●");
            dotLbl.setFont(new Font("SansSerif", Font.PLAIN, 9));
            dotLbl.setForeground(peer.isOnline() ? CLR_ONLINE : CLR_OFFLINE);
            JLabel statusTxt = new JLabel(peer.isOnline() ? "Online" : "Offline");
            statusTxt.setFont(new Font("SansSerif", Font.PLAIN, 11));
            statusTxt.setForeground(peer.isOnline() ? new Color(21, 128, 61) : TXT_MUTED);
            statusBadge.add(dotLbl);
            statusBadge.add(statusTxt);

            JPanel txt = new JPanel(new GridLayout(2, 1, 0, 2));
            txt.setOpaque(false);
            txt.add(nameLbl);
            txt.add(statusBadge);

            cell.add(av, BorderLayout.WEST);
            cell.add(txt, BorderLayout.CENTER);

            // Arrow indicator khi hover/selected
            if (selected) {
                JLabel arrow = new JLabel("›");
                arrow.setFont(new Font("SansSerif", Font.BOLD, 18));
                arrow.setForeground(ACCENT);
                cell.add(arrow, BorderLayout.EAST);
            }

            return cell;
        }
    }

    private class GroupCellRenderer implements ListCellRenderer<ChatGroup> {
        @Override
        public Component getListCellRendererComponent(JList<? extends ChatGroup> list,
                ChatGroup group, int idx, boolean selected, boolean focused) {

            JPanel cell = new JPanel(new BorderLayout(10, 0)) {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setColor(selected ? SB_ITEM_SEL : (idx % 2 == 0 ? SB_ITEM_BG : SB_ITEM_ALT));
                    g2.fillRect(0, 0, getWidth(), getHeight());
                    g2.setColor(new Color(232, 234, 242));
                    g2.fillRect(16, getHeight() - 1, getWidth() - 16, 1);
                    g2.dispose();
                }
            };
            cell.setOpaque(false);
            cell.setBorder(new EmptyBorder(10, 16, 10, 16));

            // Group icon
            Color groupColor = new Color(124, 58, 237); // violet
            JPanel icon = buildGroupIconPanel(group.getGroupName(), 38, groupColor);

            JLabel nameLbl = new JLabel(group.getGroupName());
            nameLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
            nameLbl.setForeground(TXT_PRIMARY);

            JLabel memLbl = new JLabel(group.getMemberPeerIds().size() + " members");
            memLbl.setFont(new Font("SansSerif", Font.PLAIN, 11));
            memLbl.setForeground(TXT_MUTED);

            JPanel txt = new JPanel(new GridLayout(2, 1, 0, 2));
            txt.setOpaque(false);
            txt.add(nameLbl);
            txt.add(memLbl);

            cell.add(icon, BorderLayout.WEST);
            cell.add(txt, BorderLayout.CENTER);
            if (selected) {
                JLabel arrow = new JLabel("›");
                arrow.setFont(new Font("SansSerif", Font.BOLD, 18));
                arrow.setForeground(groupColor);
                cell.add(arrow, BorderLayout.EAST);
            }
            return cell;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HELPERS: custom components
    // ═════════════════════════════════════════════════════════════════════════

    /** Avatar tròn vẽ tay với màu nền và chữ viết tắt */
    private JPanel buildAvatarPanel(String name, int size, Color bgColor) {
        String initials = getInitials(name);
        JPanel av = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // Shadow nhẹ
                g2.setColor(new Color(0, 0, 0, 15));
                g2.fillOval(1, 2, getWidth() - 1, getHeight() - 1);
                // Avatar circle
                g2.setColor(bgColor);
                g2.fillOval(0, 0, getWidth() - 1, getHeight() - 1);
                // Initials
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("SansSerif", Font.BOLD, (int) (getWidth() * 0.36)));
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(initials,
                        (getWidth() - fm.stringWidth(initials)) / 2,
                        (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
                g2.dispose();
            }
        };
        av.setOpaque(false);
        av.setPreferredSize(new Dimension(size, size));
        return av;
    }

    /** Group icon hình vuông bo góc */
    private JPanel buildGroupIconPanel(String name, int size, Color bgColor) {
        String initial = name.trim().isEmpty() ? "#" : String.valueOf(name.trim().charAt(0)).toUpperCase();
        JPanel icon = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(bgColor.getRed(), bgColor.getGreen(), bgColor.getBlue(), 30));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.setColor(bgColor);
                g2.fillRoundRect(3, 3, getWidth() - 6, getHeight() - 6, 8, 8);
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("SansSerif", Font.BOLD, (int) (getWidth() * 0.40)));
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(initial,
                        (getWidth() - fm.stringWidth(initial)) / 2,
                        (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
                g2.dispose();
            }
        };
        icon.setOpaque(false);
        icon.setPreferredSize(new Dimension(size, size));
        return icon;
    }

    /** Section header "Title" + optional action button */
    private JPanel buildSectionHeader(JComponent... actions) {
        JPanel h = new JPanel(new BorderLayout(8, 0));
        h.setBackground(TAB_BG);
        h.setBorder(BorderFactory.createCompoundBorder(
                new MatteBorder(0, 0, 1, 0, SB_BORDER),
                new EmptyBorder(8, 16, 8, 12)));
        if (actions.length > 0) {
            JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            btnPanel.setOpaque(false);
            for (JComponent c : actions)
                btnPanel.add(c);
            h.add(btnPanel, BorderLayout.EAST);
        }
        return h;
    }

    /** Empty state placeholder */
    private JPanel buildEmptyState(String title, String sub, String emoji) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(SB_BG);
        p.setBorder(new EmptyBorder(48, 20, 20, 20));

        JLabel emojiLbl = new JLabel(emoji);
        emojiLbl.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 36));
        emojiLbl.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel titleLbl = new JLabel(title);
        titleLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        titleLbl.setForeground(TXT_SECONDARY);
        titleLbl.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel subLbl = new JLabel(
                "<html><center style='color:#9CA3AF;'>" + sub + "</center></html>");
        subLbl.setFont(new Font("SansSerif", Font.PLAIN, 11));
        subLbl.setAlignmentX(Component.CENTER_ALIGNMENT);

        p.add(emojiLbl);
        p.add(Box.createVerticalStrut(12));
        p.add(titleLbl);
        p.add(Box.createVerticalStrut(6));
        p.add(subLbl);
        return p;
    }

    /** Styled scrollpane: ẩn border, custom scrollbar */
    private JScrollPane styledScroll(JComponent content) {
        JScrollPane sp = new JScrollPane(content);
        sp.setBorder(null);
        sp.getViewport().setBackground(SB_ITEM_BG);
        sp.setBackground(SB_ITEM_BG);
        sp.getVerticalScrollBar().setUI(new javax.swing.plaf.basic.BasicScrollBarUI() {
            @Override
            protected void configureScrollBarColors() {
                thumbColor = new Color(203, 213, 225);
                trackColor = SB_BG;
            }

            @Override
            protected JButton createDecreaseButton(int o) {
                return zeroBtn();
            }

            @Override
            protected JButton createIncreaseButton(int o) {
                return zeroBtn();
            }

            private JButton zeroBtn() {
                JButton b = new JButton();
                b.setPreferredSize(new Dimension(0, 0));
                b.setBorder(null);
                return b;
            }
        });
        sp.getVerticalScrollBar().setPreferredSize(new Dimension(6, 0));
        return sp;
    }

    /** Nút bo góc pill */
    private JButton makeRoundButton(String text, Color bg, Color hover) {
        JButton btn = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getModel().isRollover() ? hover : bg);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
                g2.setColor(Color.WHITE);
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(getText(),
                        (getWidth() - fm.stringWidth(getText())) / 2,
                        (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
                g2.dispose();
            }
        };
        btn.setFont(new Font("SansSerif", Font.BOLD, 11));
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // MODEL HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private void upsertPeer(PeerInfo peer) {
        for (int i = 0; i < peerModel.size(); i++) {
            if (peerModel.get(i).getPeerId().equals(peer.getPeerId())) {
                peerModel.set(i, peer);
                return;
            }
        }
        peerModel.addElement(peer);
    }

    private void upsertGroup(ChatGroup group) {
        int idx = indexOfGroup(group.getGroupId());
        if (idx >= 0)
            groupModel.set(idx, group);
        else
            groupModel.addElement(group);
    }

    private int indexOfGroup(String groupId) {
        for (int i = 0; i < groupModel.size(); i++)
            if (groupModel.get(i).getGroupId().equals(groupId))
                return i;
        return -1;
    }

    private int countOnline() {
        int n = 0;
        for (int i = 0; i < peerModel.size(); i++)
            if (peerModel.get(i).isOnline())
                n++;
        return n;
    }

    private String getInitials(String name) {
        String[] p = name.trim().split("\\s+");
        if (p.length >= 2)
            return ("" + p[0].charAt(0) + p[1].charAt(0)).toUpperCase();
        return name.substring(0, Math.min(2, name.length())).toUpperCase();
    }
}