package com.p2pchat.peer.gui;

import com.p2pchat.peer.model.ChatMessage;
import com.p2pchat.peer.network.P2PNode;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

/**
 * Panel hiển thị một tin nhắn dạng chat bubble
 */
public class ChatBubblePanel extends JPanel {

    private static final Color OWN_BG = new Color(0, 122, 255); // iOS blue
    private static final Color OWN_TEXT = Color.WHITE;
    private static final Color OTHER_BG = new Color(229, 229, 234); // iOS gray
    private static final Color OTHER_TEXT = new Color(28, 28, 30);
    private static final Color TIME_COLOR = new Color(142, 142, 147);
    private static final Color SYS_BG = new Color(255, 249, 230);
    private static final Color SYS_TEXT = new Color(120, 80, 0);
    private static final Color ACCENT_BTN = new Color(0, 122, 255);

    // File type icons (using Unicode symbols)
    private static final String ICON_FILE = "📄";
    private static final String ICON_IMAGE = "🖼️";
    private static final String ICON_VIDEO = "🎬";
    private static final String ICON_AUDIO = "🎵";
    private static final String ICON_ARCHIVE = "📦";
    private static final String ICON_PDF = "📕";
    private static final String ICON_WORD = "📘";
    private static final String ICON_EXCEL = "📗";
    private static final String ICON_CODE = "💻";
    private static final String ICON_ATTACH = "📎";

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");
    private static final int ARC = 18;
    private static final int MAX_BUBBLE_WIDTH = 380;
    private final boolean isGroupMessage;

    public enum BubbleType {
        OWN, OTHER, SYSTEM
    }

    private final String text;
    private final String sender;
    private final String time;
    private final BubbleType type;
    private boolean isDelivered;

    // Lưu ref để update tick sau khi ACK về
    private String messageId;
    private JLabel statusLabel;

    public ChatBubblePanel(ChatMessage msg, String myPeerId, P2PNode node) {
        boolean own = msg.isOwn() || msg.getSenderPeerId().equals(myPeerId);
        this.type = determineType(msg, own);
        this.text = msg.getContent();
        this.sender = own ? "You" : msg.getSenderUsername();
        this.time = msg.getSentAt() != null ? msg.getSentAt().format(TIME_FMT) : "";
        this.isDelivered = own ? msg.isDelivered() : true;
        this.messageId = msg.getMessageId();
        this.isGroupMessage = msg.getType() == ChatMessage.MessageType.GROUP_CHAT
                || msg.getType() == ChatMessage.MessageType.GROUP_FILE;

        setOpaque(false);
        setLayout(new BorderLayout());

        if (msg.getType() == ChatMessage.MessageType.FILE || msg.getType() == ChatMessage.MessageType.GROUP_FILE) {
            buildFileAttachmentBubble(msg, node, own);
        } else if (type == BubbleType.SYSTEM) {
            buildSystemBubble();
        } else {
            buildChatBubble();
        }
    }

    public ChatBubblePanel(String systemText) {
        this.type = BubbleType.SYSTEM;
        this.text = systemText;
        this.sender = "System";
        this.time = "";
        this.isDelivered = false;
        this.isGroupMessage = false;
        setOpaque(false);
        setLayout(new BorderLayout());
        buildSystemBubble();
    }

    private BubbleType determineType(ChatMessage msg, boolean own) {
        if (msg.getType() == ChatMessage.MessageType.BROADCAST)
            return BubbleType.SYSTEM;
        return own ? BubbleType.OWN : BubbleType.OTHER;
    }

    private void buildFileAttachmentBubble(ChatMessage msg, P2PNode node, boolean own) {
        boolean isOwn = own;
        JPanel bubble = new JPanel(new BorderLayout(0, 6)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(isOwn ? OWN_BG : OTHER_BG);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), ARC, ARC);
                g2.dispose();
            }
        };
        bubble.setOpaque(false);
        bubble.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

        if (!isOwn) {
            JLabel senderLabel = new JLabel(sender);
            senderLabel.setFont(new Font("SansSerif", Font.BOLD, 11));
            senderLabel.setForeground(new Color(0, 122, 255));
            bubble.add(senderLabel, BorderLayout.NORTH);
        }

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        String c = msg.getContent() != null ? msg.getContent() : "";
        String[] lines = c.split("\n", 4);

        if (isOwn) {
            JLabel line = new JLabel("<html>" + escapeHtml(c.replace("\n", "<br>")) + "</html>");
            line.setFont(new Font("SansSerif", Font.PLAIN, 14));
            line.setForeground(isOwn ? OWN_TEXT : OTHER_TEXT);
            body.add(line);
        } else if (lines.length >= 2 && ChatMessage.FILE_SAVED_MARKER.equals(lines[0])) {
            JLabel title = new JLabel("Đã tải về");
            title.setFont(new Font("SansSerif", Font.BOLD, 13));
            title.setForeground(OTHER_TEXT);
            body.add(title);
            JLabel path = new JLabel(
                    "<html>" + escapeHtml(String.join("\n", Arrays.copyOfRange(lines, 1, lines.length))) + "</html>");
            path.setFont(new Font("SansSerif", Font.PLAIN, 12));
            path.setForeground(TIME_COLOR);
            body.add(path);
        } else if (lines.length >= 3 && ChatMessage.FILE_PENDING_MARKER.equals(lines[0])) {
            String fileName = lines[1];
            long sizeBytes = parseLongSafe(lines[2]);
            String fileIcon = getFileIcon(fileName);
            JPanel row = new JPanel(new BorderLayout(10, 0));
            row.setOpaque(false);
            JLabel icon = new JLabel(fileIcon);
            icon.setFont(new Font("SansSerif", Font.PLAIN, 22));
            row.add(icon, BorderLayout.WEST);

            JPanel meta = new JPanel(new GridLayout(2, 1, 0, 2));
            meta.setOpaque(false);
            JLabel nameLbl = new JLabel(fileName);
            nameLbl.setFont(new Font("SansSerif", Font.BOLD, 14));
            nameLbl.setForeground(OTHER_TEXT);
            JLabel sizeLbl = new JLabel(formatFileSize(sizeBytes));
            sizeLbl.setFont(new Font("SansSerif", Font.PLAIN, 11));
            sizeLbl.setForeground(TIME_COLOR);
            meta.add(nameLbl);
            meta.add(sizeLbl);
            row.add(meta, BorderLayout.CENTER);

            JButton downloadBtn = new JButton("Tải về");
            downloadBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
            downloadBtn.setForeground(Color.WHITE);
            downloadBtn.setBackground(ACCENT_BTN);
            downloadBtn.setOpaque(true);
            downloadBtn.setBorderPainted(false);
            downloadBtn.setFocusPainted(false);
            downloadBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            boolean inMemory = node != null && msg.getMessageId() != null
                    && node.hasReceivedFilePayload(msg.getMessageId());
            downloadBtn.setEnabled(inMemory);
            if (!inMemory) {
                downloadBtn.setToolTipText("File không còn trong bộ nhớ (đóng app hoặc đã tải).");
                downloadBtn.setBackground(new Color(180, 180, 185));
            }

            downloadBtn.addActionListener(e -> runDownloadFile(msg, fileName, node, downloadBtn));
            row.add(downloadBtn, BorderLayout.EAST);
            body.add(row);
        } else {
            JLabel legacy = new JLabel("<html>" + escapeHtml(c.replace("\n", "<br>")) + "</html>");
            legacy.setFont(new Font("SansSerif", Font.PLAIN, 13));
            legacy.setForeground(OTHER_TEXT);
            body.add(legacy);
            if (lines.length >= 2 && lines[0].trim().startsWith("📎")) {
                JLabel hint = new JLabel("(Tin cũ — file đã lưu tự động trước đó)");
                hint.setFont(new Font("SansSerif", Font.ITALIC, 10));
                hint.setForeground(TIME_COLOR);
                body.add(Box.createVerticalStrut(4));
                body.add(hint);
            }
        }

        bubble.add(body, BorderLayout.CENTER);

        // Time + delivery status (chỉ hiện cho tin nhắn của mình)
        JPanel bottomRow = new JPanel();
        bottomRow.setLayout(new BoxLayout(bottomRow, BoxLayout.Y_AXIS));
        bottomRow.setOpaque(false);
        bottomRow.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));

        JLabel timeLabel = new JLabel(time);
        timeLabel.setFont(new Font("SansSerif", Font.PLAIN, 10));
        timeLabel.setForeground(isOwn ? new Color(200, 220, 255) : TIME_COLOR);
        timeLabel.setAlignmentX(isOwn ? Component.RIGHT_ALIGNMENT : Component.LEFT_ALIGNMENT);
        bottomRow.add(timeLabel);

        if (isOwn) {
            JLabel statusLabel = new JLabel(isGroupMessage ? "✓" : (isDelivered ? "✓✓" : "✓"));
            statusLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
            statusLabel.setForeground(isDelivered
                    ? new Color(180, 255, 180) // xanh lá sáng — đã nhận ACK
                    : new Color(180, 210, 255)); // xanh nhạt — đã gửi, chờ ACK
            statusLabel.setAlignmentX(Component.RIGHT_ALIGNMENT);
            bottomRow.add(statusLabel);
        }

        bubble.add(bottomRow, BorderLayout.SOUTH);

        bubble.setMaximumSize(new Dimension(MAX_BUBBLE_WIDTH, Integer.MAX_VALUE));

        JPanel wrap = new JPanel(new FlowLayout(isOwn ? FlowLayout.RIGHT : FlowLayout.LEFT, 8, 2));
        wrap.setOpaque(false);
        wrap.add(bubble);
        setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        add(wrap, BorderLayout.CENTER);
    }

    private void runDownloadFile(ChatMessage msg, String suggestedName, P2PNode node, JButton downloadBtn) {
        if (node == null)
            return;
        byte[] data = node.peekReceivedFilePayload(msg.getMessageId());
        if (data == null) {
            JOptionPane.showMessageDialog(this, "File không còn trong bộ nhớ.", "Tải về", JOptionPane.WARNING_MESSAGE);
            downloadBtn.setEnabled(false);
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Lưu file");
        fc.setSelectedFile(new File(sanitizeFileName(suggestedName)));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
            return;

        Path dest = fc.getSelectedFile().toPath();
        downloadBtn.setEnabled(false);
        new SwingWorker<Void, Void>() {
            Throwable err;

            @Override
            protected Void doInBackground() throws Exception {
                Path parent = dest.getParent();
                if (parent != null)
                    Files.createDirectories(parent);
                Files.write(dest, data);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                } catch (Exception e) {
                    err = e;
                }
                if (err != null) {
                    JOptionPane.showMessageDialog(ChatBubblePanel.this,
                            "Không ghi được file:\n" + err.getMessage(),
                            "Lỗi", JOptionPane.ERROR_MESSAGE);
                    downloadBtn.setEnabled(true);
                    return;
                }
                node.consumeReceivedFilePayload(msg.getMessageId());
                node.markReceivedFileSavedInHistory(msg.getMessageId(), dest);
                downloadBtn.setText("Đã tải");
                downloadBtn.setBackground(new Color(52, 199, 89));
                JOptionPane.showMessageDialog(ChatBubblePanel.this,
                        "Đã lưu:\n" + dest.toAbsolutePath(),
                        "Tải về", JOptionPane.INFORMATION_MESSAGE);
            }
        }.execute();
    }

    private static String sanitizeFileName(String name) {
        if (name == null || name.isBlank())
            return "download.bin";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private static long parseLongSafe(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1024)
            return bytes + " B";
        if (bytes < 1024 * 1024)
            return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String escapeHtml(String s) {
        if (s == null)
            return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Xác định icon dựa trên file extension.
     */
    private static String getFileIcon(String fileName) {
        if (fileName == null || fileName.isEmpty())
            return ICON_FILE;
        String lower = fileName.toLowerCase();
        if (lower.matches(".*\\.(jpg|jpeg|png|gif|bmp|webp|svg|ico|tiff?)$"))
            return ICON_IMAGE;
        if (lower.matches(".*\\.(mp4|avi|mkv|mov|wmv|flv|webm)$"))
            return ICON_VIDEO;
        if (lower.matches(".*\\.(mp3|wav|flac|aac|ogg|wma|m4a)$"))
            return ICON_AUDIO;
        if (lower.matches(".*\\.(zip|rar|7z|tar|gz|bz2)$"))
            return ICON_ARCHIVE;
        if (lower.matches(".*\\.(pdf)$"))
            return ICON_PDF;
        if (lower.matches(".*\\.(doc|docx|odt|rtf)$"))
            return ICON_WORD;
        if (lower.matches(".*\\.(xls|xlsx|ods|csv)$"))
            return ICON_EXCEL;
        if (lower.matches(".*\\.(java|c|cpp|cs|py|js|ts|html|css|xml|json|sql|sh|bat|ps1)$"))
            return ICON_CODE;
        return ICON_FILE;
    }

    private void buildChatBubble() {
        boolean isOwn = type == BubbleType.OWN;

        JPanel bubble = new JPanel(new BorderLayout(0, 2)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(isOwn ? OWN_BG : OTHER_BG);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), ARC, ARC);
                g2.dispose();
            }
        };
        bubble.setOpaque(false);
        bubble.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

        // Sender name (chỉ hiện cho "other")
        if (!isOwn) {
            JLabel senderLabel = new JLabel(sender);
            senderLabel.setFont(new Font("SansSerif", Font.BOLD, 11));
            senderLabel.setForeground(new Color(0, 122, 255));
            bubble.add(senderLabel, BorderLayout.NORTH);
        }

        final int padding = 40; // border + insets của bubble
        FontMetrics fm = getFontMetrics(new Font("SansSerif", Font.PLAIN, 14));
        int naturalWidth = 0;
        for (String line : text.split("\n")) {
            naturalWidth = Math.max(naturalWidth, fm.stringWidth(line));
        }
        String htmlText;
        if (naturalWidth > MAX_BUBBLE_WIDTH - padding) {
            // Text dài: ép wrap tại MAX_BUBBLE_WIDTH
            htmlText = "<html><body style='width:" + (MAX_BUBBLE_WIDTH - padding) + "px'>"
                    + escapeHtml(text).replace("\n", "<br>") + "</body></html>";
        } else {
            // Text ngắn: không ép width, bubble co vừa nội dung
            htmlText = "<html>" + escapeHtml(text).replace("\n", "<br>") + "</html>";
        }
        JLabel textLabel = new JLabel(htmlText);
        textLabel.setFont(new Font("SansSerif", Font.PLAIN, 14));
        textLabel.setForeground(isOwn ? OWN_TEXT : OTHER_TEXT);
        bubble.add(textLabel, BorderLayout.CENTER);

        // Time + delivery status (chỉ hiện cho tin nhắn của mình)
        JPanel bottomRow = new JPanel();
        bottomRow.setLayout(new BoxLayout(bottomRow, BoxLayout.Y_AXIS));
        bottomRow.setOpaque(false);
        bottomRow.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));

        JLabel timeLabel = new JLabel(time);
        timeLabel.setFont(new Font("SansSerif", Font.PLAIN, 10));
        timeLabel.setForeground(isOwn ? new Color(200, 220, 255) : TIME_COLOR);
        JPanel timeRow = new JPanel(new FlowLayout(isOwn ? FlowLayout.RIGHT : FlowLayout.LEFT, 0, 0));
        timeRow.setOpaque(false);
        timeRow.add(timeLabel);
        bottomRow.add(timeRow);

        if (isOwn) {
            statusLabel = new JLabel(isGroupMessage ? "✓" : (isDelivered ? "✓✓" : "✓"));
            statusLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
            statusLabel.setForeground(isDelivered
                    ? new Color(180, 255, 180) // xanh lá sáng — đã nhận ACK
                    : new Color(180, 210, 255)); // xanh nhạt — đã gửi, chờ ACK
            JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            statusRow.setOpaque(false);
            statusRow.add(statusLabel);
            bottomRow.add(statusRow);
        }

        bubble.add(bottomRow, BorderLayout.SOUTH);

        bubble.setMaximumSize(new Dimension(MAX_BUBBLE_WIDTH, Integer.MAX_VALUE));

        JPanel row = new JPanel(new FlowLayout(isOwn ? FlowLayout.RIGHT : FlowLayout.LEFT, 8, 2));
        row.setOpaque(false);
        row.add(bubble);

        setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        add(row, BorderLayout.CENTER);
    }

    private void buildSystemBubble() {
        JLabel label = new JLabel("<html><center>" + text + "</center></html>") {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(SYS_BG);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        label.setFont(new Font("SansSerif", Font.ITALIC, 12));
        label.setForeground(SYS_TEXT);
        label.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        label.setOpaque(false);

        JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER));
        row.setOpaque(false);
        row.add(label);
        add(row, BorderLayout.CENTER);
    }

    /**
     * Cập nhật tick status lên ✓✓ (delivered) khi nhận được ACK.
     * Phải gọi trên EDT.
     */
    public void markDelivered() {
        if (statusLabel == null || isDelivered)
            return;
        isDelivered = true;
        statusLabel.setText("✓✓");
        statusLabel.setForeground(new Color(180, 255, 180));
        statusLabel.repaint();
    }

    public String getMessageId() {
        return messageId;
    }

    private int calculateWidth(String text, String timeStr) {
        FontMetrics fmText = getFontMetrics(new Font("SansSerif", Font.PLAIN, 14));
        FontMetrics fmTime = getFontMetrics(new Font("SansSerif", Font.PLAIN, 10));

        int maxLine = 0;
        for (String line : text.split("\n")) {
            maxLine = Math.max(maxLine, fmText.stringWidth(line));
        }

        int timeWidth = fmTime.stringWidth(timeStr) + 20; // +20 cho tick + gap

        return Math.max(maxLine, timeWidth) + 50; // padding
    }
}