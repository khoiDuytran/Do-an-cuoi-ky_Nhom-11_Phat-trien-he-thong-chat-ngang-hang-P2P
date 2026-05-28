package com.p2pchat.peer.gui;

import com.p2pchat.peer.model.ChatGroup;
import com.p2pchat.peer.model.ChatMessage;
import com.p2pchat.peer.network.P2PNode;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Panel hiển thị cuộc trò chuyện với một peer hoặc nhóm.
 */
public class ChatPanel extends JPanel {

    private final P2PNode node;
    private final String conversationId;
    private final String conversationName;
    private final boolean isGroup;

    private final JPanel messagesPanel;
    private final JScrollPane scrollPane;
    private JTextArea inputArea;
    private final JLabel statusLabel;

    private JButton sendBtn;
    private JButton attachBtn;

    // Map messageId → bubble để update tick khi ACK về
    private final Map<String, ChatBubblePanel> bubbleMap = new ConcurrentHashMap<>();

    public ChatPanel(P2PNode node, String conversationId, String conversationName, boolean isGroup) {
        this.node = node;
        this.conversationId = conversationId;
        this.conversationName = conversationName;
        this.isGroup = isGroup;

        setLayout(new BorderLayout());
        setBackground(Color.WHITE);

        JPanel header = new JPanel(new BorderLayout(10, 0));
        header.setBackground(new Color(248, 248, 248));
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(210, 210, 215)),
                BorderFactory.createEmptyBorder(10, 16, 10, 16)));

        JPanel titlePanel = new JPanel(new GridLayout(2, 1, 0, 2));
        titlePanel.setOpaque(false);
        JLabel nameLabel = new JLabel((isGroup ? "[Group] " : "") + conversationName);
        nameLabel.setFont(new Font("SansSerif", Font.BOLD, 16));
        nameLabel.setForeground(new Color(28, 28, 30));
        statusLabel = new JLabel(isGroup ? "Group Chat" : "Direct Message");
        statusLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        statusLabel.setForeground(new Color(142, 142, 147));
        titlePanel.add(nameLabel);
        titlePanel.add(statusLabel);
        header.add(titlePanel, BorderLayout.WEST);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        if (isGroup) {
            JButton inviteBtn = makeIconButton("+ Invite", new Color(0, 122, 255));
            inviteBtn.addActionListener(e -> showInviteDialog());
            actions.add(inviteBtn);
            JButton leaveBtn = makeIconButton("Leave", new Color(142, 142, 147));
            leaveBtn.setToolTipText("Leave this group");
            leaveBtn.addActionListener(e -> confirmLeaveGroup());
            actions.add(leaveBtn);
        }
        JButton broadcastBtn = makeIconButton("Broadcast", new Color(255, 149, 0));
        broadcastBtn.addActionListener(e -> {
            String text = inputArea.getText().trim();
            if (!text.isEmpty()) {
                node.broadcastMessage(text);
                inputArea.setText("");
                addSystemMessage("[Broadcast] You: " + text);
            }
        });
        actions.add(broadcastBtn);
        header.add(actions, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        messagesPanel = new JPanel();
        messagesPanel.setLayout(new BoxLayout(messagesPanel, BoxLayout.Y_AXIS));
        messagesPanel.setBackground(new Color(242, 242, 247));
        messagesPanel.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));

        scrollPane = new JScrollPane(messagesPanel);
        scrollPane.setBorder(null);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.getViewport().setBackground(new Color(242, 242, 247));
        add(scrollPane, BorderLayout.CENTER);

        JPanel inputPanel = new JPanel(new BorderLayout(8, 0));
        inputPanel.setBackground(Color.WHITE);
        inputPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(210, 210, 215)),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));

        inputArea = new JTextArea(3, 40);
        inputArea.setFont(new Font("SansSerif", Font.PLAIN, 14));
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(210, 210, 215), 1, true),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        inputArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && !e.isShiftDown()) {
                    e.consume();
                    sendMessage();
                }
            }
        });

        sendBtn = createSendButton();
        JScrollPane inputScroll = new JScrollPane(inputArea);
        inputScroll.setBorder(null);
        inputScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);

        JPanel bottomRow = new JPanel(new BorderLayout(8, 0));
        bottomRow.setOpaque(false);
        // if (!isGroup) {
        attachBtn = createSendFileButton();
        attachBtn.addActionListener(e -> chooseAndSendFile());
        bottomRow.add(attachBtn, BorderLayout.WEST);
        // }
        bottomRow.add(inputScroll, BorderLayout.CENTER);
        bottomRow.add(sendBtn, BorderLayout.EAST);

        JLabel hintLabel = new JLabel("Enter to send  |  Shift+Enter for new line");
        hintLabel.setFont(new Font("SansSerif", Font.ITALIC, 10));
        hintLabel.setForeground(new Color(170, 170, 175));
        inputPanel.add(hintLabel, BorderLayout.NORTH);
        inputPanel.add(bottomRow, BorderLayout.CENTER);
        add(inputPanel, BorderLayout.SOUTH);

        loadHistory();
    }

    private JButton createSendButton() {
        JButton btn = new JButton("Send") {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color base = new Color(0, 122, 255);
                g2.setColor(getModel().isPressed() ? base.darker() : getModel().isRollover() ? base.brighter() : base);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20);
                g2.setColor(Color.WHITE);
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(getText(), (getWidth() - fm.stringWidth(getText())) / 2,
                        (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
                g2.dispose();
            }
        };
        btn.setPreferredSize(new Dimension(80, 36));
        btn.setFont(new Font("SansSerif", Font.BOLD, 14));
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addActionListener(e -> sendMessage());
        return btn;
    }

    private JButton createSendFileButton() {
        JButton btn = makeIconButton("Send file", new Color(88, 86, 214));
        btn.setToolTipText("Send a file to this peer (max 50 MB, online only)");
        btn.setPreferredSize(new Dimension(110, 36));
        return btn;
    }

    private void chooseAndSendFile() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Choose file to send");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
            return;
        File f = fc.getSelectedFile();
        if (f == null || !f.isFile())
            return;

        // Kiểm tra giới hạn phía UI cho group
        long maxBytes = isGroup ? 20L * 1024 * 1024 : 50L * 1024 * 1024;
        if (f.length() > maxBytes) {
            JOptionPane.showMessageDialog(this,
                    "File vượt giới hạn " + (isGroup ? "20MB" : "50MB") + " cho "
                            + (isGroup ? "group chat." : "direct chat."),
                    "File quá lớn", JOptionPane.WARNING_MESSAGE);
            return;
        }

        setStatus("Sending file…");
        new SwingWorker<Boolean, Void>() {
            private String err;

            @Override
            protected Boolean doInBackground() {
                try {
                    return isGroup
                            ? node.sendGroupFile(conversationId, f)
                            : node.sendDirectFile(conversationId, f);
                } catch (Exception e) {
                    err = e.getMessage() != null ? e.getMessage() : e.toString();
                    return false;
                }
            }

            @Override
            protected void done() {
                try {
                    if (Boolean.TRUE.equals(get())) {
                        addSystemMessage("✓ Sent file: " + f.getName());
                        setStatus(isGroup ? "Group Chat" : "● Online");
                    } else {
                        if (err != null) {
                            addSystemMessage("File send failed: " + err);
                        }
                        setStatus(isGroup ? "Group Chat"
                                : (node.isDirectPeerConnected(conversationId) ? "● Online" : "○ Offline"));
                    }
                } catch (Exception ignored) {
                    // setStatus(node.isDirectPeerConnected(conversationId) ? "● Online" : "○
                    // Offline");
                }
            }
        }.execute();
    }

    private void sendMessage() {
        String text = inputArea.getText().trim();
        if (text.isEmpty())
            return;
        inputArea.setText("");

        if (isGroup) {
            String mid = node.sendGroupMessage(conversationId, text);
            if (mid == null)
                return;
            ChatMessage cm = ChatMessage.fromGroup(mid,
                    node.getPeerId(), node.getUsername(), conversationId, text, true);
            addMessageBubble(cm);
        } else {
            boolean connected = node.isDirectPeerConnected(conversationId);
            String mid = node.sendDirectMessage(conversationId, text);
            ChatMessage cm = ChatMessage.fromDirect(mid,
                    node.getPeerId(), node.getUsername(), conversationId, text, true);
            addMessageBubble(cm);
            if (!connected)
                setStatus("Direct chat");
        }
    }

    private void confirmLeaveGroup() {
        int r = JOptionPane.showConfirmDialog(this,
                "Leave \"" + conversationName + "\"? You will stop receiving group messages.",
                "Leave Group", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r == JOptionPane.YES_OPTION) {
            node.leaveGroup(conversationId);
        }
    }

    public void addIncomingMessage(ChatMessage msg) {
        SwingUtilities.invokeLater(() -> addMessageBubble(msg));
    }

    public void addMessageBubble(ChatMessage msg) {
        ChatBubblePanel bubble = new ChatBubblePanel(msg, node.getPeerId(), node);
        bubble.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Đăng ký vào map để update tick sau khi ACK về
        if (msg.getMessageId() != null) {
            bubbleMap.put(msg.getMessageId(), bubble);
        }
        messagesPanel.add(bubble);
        messagesPanel.revalidate();
        messagesPanel.repaint();
        scrollToBottom();
    }

    public void addSystemMessage(String text) {
        SwingUtilities.invokeLater(() -> {
            ChatBubblePanel sysMsg = new ChatBubblePanel(text);
            sysMsg.setAlignmentX(Component.LEFT_ALIGNMENT);
            messagesPanel.add(sysMsg);
            messagesPanel.revalidate();
            messagesPanel.repaint();
            scrollToBottom();
        });
    }

    private void loadHistory() {
        List<ChatMessage> history;
        if (isGroup) {
            history = node.getMessageRepository().getGroupHistory(conversationId, 50);
        } else {
            history = node.getMessageRepository().getDirectHistory(node.getPeerId(), conversationId, 50);
        }
        for (ChatMessage msg : history) {
            ChatBubblePanel bubble = new ChatBubblePanel(msg, node.getPeerId(), node);
            bubble.setAlignmentX(Component.LEFT_ALIGNMENT);
            messagesPanel.add(bubble);
        }
        messagesPanel.revalidate();
        SwingUtilities.invokeLater(this::scrollToBottom);
    }

    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> {
            JScrollBar vsb = scrollPane.getVerticalScrollBar();
            vsb.setValue(vsb.getMaximum());
        });
    }

    private void showInviteDialog() {
        List<String> options = new ArrayList<>();
        List<String> peerIds = new ArrayList<>();
        node.getKnownPeers().values().forEach(p -> {
            ChatGroup grp = node.getGroups().get(conversationId);
            if (grp == null || !grp.hasMember(p.getPeerId())) {
                options.add(p.getUsername());
                peerIds.add(p.getPeerId());
            }
        });
        if (options.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No peers available to invite.");
            return;
        }
        String selected = (String) JOptionPane.showInputDialog(this, "Select peer:",
                "Invite to Group", JOptionPane.QUESTION_MESSAGE, null,
                options.toArray(), options.get(0));
        if (selected != null) {
            int idx = options.indexOf(selected);
            if (idx >= 0) {
                node.invitePeerToGroup(conversationId, peerIds.get(idx));
                addSystemMessage("Invited " + selected + " to the group.");
            }
        }
    }

    public void setStatus(String status) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(status));
    }

    private JButton makeIconButton(String label, Color color) {
        JButton btn = new JButton(label) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getModel().isRollover() ? color.brighter() : color);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
                g2.setColor(Color.WHITE);
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(getText(), (getWidth() - fm.stringWidth(getText())) / 2,
                        (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
                g2.dispose();
            }
        };
        btn.setFont(new Font("SansSerif", Font.BOLD, 12));
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.setPreferredSize(new Dimension(100, 30));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    public String getConversationId() {
        return conversationId;
    }

    public boolean isGroupChat() {
        return isGroup;
    }

    /**
     * Cập nhật tick ✓ → ✓✓ cho bubble có messageId tương ứng.
     * Gọi từ P2PNode khi nhận ACK (handleAck).
     * Thread-safe: dispatch sang EDT nếu cần.
     */
    public void updateDeliveredStatus(String messageId) {
        ChatBubblePanel bubble = bubbleMap.get(messageId);
        if (bubble == null)
            return;
        SwingUtilities.invokeLater(() -> {
            bubble.markDelivered();
            bubbleMap.remove(messageId); // không cần theo dõi nữa sau khi đã delivered
        });
    }

    // public void setNetworkConnected(boolean connected) {
    // SwingUtilities.invokeLater(() -> {
    // sendBtn.setVisible(connected);
    // attachBtn.setVisible(connected);
    // });
    // }
}