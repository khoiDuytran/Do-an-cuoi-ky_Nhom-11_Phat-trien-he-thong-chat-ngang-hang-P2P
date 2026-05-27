package com.p2pchat.peer;

import com.p2pchat.peer.gui.LoginDialog;
import com.p2pchat.peer.gui.MainWindow;
import com.p2pchat.peer.network.P2PNode;
import com.p2pchat.peer.repository.DatabaseConfig;

import javax.swing.*;
import java.awt.*;
import java.util.logging.*;

/**
 * Entry point của ứng dụng Peer.
 * Luồng khởi động:
 * 1. Hiện LoginDialog (username + myPort)
 * 2. Khởi tạo MySQL local bằng cấu hình cố định trong code
 * 3. Tạo P2PNode và vào MainWindow ngay
 * 4. User bấm Connect trong MainWindow để join mạng
 */
public class PeerApp {
    private static final String DB_HOST = "localhost";
    private static final int DB_PORT = 3306;
    private static final String DB_USER = "root";
    private static final String DB_PASSWORD = "khoi94";

    public static void main(String[] args) {
        // Setup logging
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "[%1$tF %1$tT] [%4$-7s] %3$s : %5$s%6$s%n");

        // Look and Feel
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        // Chạy trên EDT
        SwingUtilities.invokeLater(() -> {
            // 1. Login Dialog
            LoginDialog login = new LoginDialog(null);
            login.setVisible(true);

            if (!login.isConfirmed()) {
                System.exit(0);
                return;
            }

            // 2. Loading splash
            JDialog loading = createLoadingDialog(login.getUsername());
            loading.setVisible(true);

            // 3. Khởi tạo trong background thread
            new SwingWorker<Void, String>() {
                private P2PNode node;
                private MainWindow window;
                private String errorMsg;

                @Override
                protected Void doInBackground() {
                    try {
                        publish("Initializing MySQL database...");
                        DatabaseConfig.getInstance().initialize(
                                login.getPeerId(), DB_HOST, DB_PORT, DB_USER, DB_PASSWORD);

                        publish("Loading UI...");
                        node = new P2PNode(login.getPeerId(), login.getUsername());
                        window = new MainWindow(node, login.getMyPort());

                    } catch (Exception e) {
                        Logger.getLogger("PeerApp").severe("Startup failed: " + e.getMessage());
                        errorMsg = e.getMessage();
                    }
                    return null;
                }

                @Override
                protected void process(java.util.List<String> chunks) {
                    if (!chunks.isEmpty()) {
                        // Update loading label nếu cần
                        System.out.println("[Startup] " + chunks.get(chunks.size() - 1));
                    }
                }

                @Override
                protected void done() {
                    loading.dispose();

                    if (errorMsg != null) {
                        JOptionPane.showMessageDialog(null,
                                "<html><b>Startup failed:</b><br>" + errorMsg
                                        + "<br><br>Please check MySQL service and DB credentials in PeerApp.</html>",
                                "Startup Error", JOptionPane.ERROR_MESSAGE);
                        System.exit(1);
                        return;
                    }

                    // Graceful shutdown hook
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        if (node != null) {
                            node.stop();
                            DatabaseConfig.getInstance().shutdown();
                        }
                    }));

                    window.setVisible(true);
                }
            }.execute();
        });
    }

    private static JDialog createLoadingDialog(String username) {
        JDialog d = new JDialog((JFrame) null, "Connecting...", false);
        d.setUndecorated(true);
        d.setSize(320, 120);
        d.setLocationRelativeTo(null);

        JPanel panel = new JPanel(new BorderLayout(0, 12)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(28, 28, 35));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
                g2.dispose();
            }
        };
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(24, 32, 24, 32));

        JLabel label = new JLabel("Connecting as " + username + "...");
        label.setFont(new Font("SansSerif", Font.BOLD, 14));
        label.setForeground(Color.WHITE);
        label.setHorizontalAlignment(SwingConstants.CENTER);

        JProgressBar pb = new JProgressBar();
        pb.setIndeterminate(true);
        pb.setForeground(new Color(0, 122, 255));
        pb.setBackground(new Color(50, 50, 60));
        pb.setBorderPainted(false);
        pb.setPreferredSize(new Dimension(0, 4));

        panel.add(label, BorderLayout.CENTER);
        panel.add(pb, BorderLayout.SOUTH);
        d.add(panel);
        return d;
    }
}