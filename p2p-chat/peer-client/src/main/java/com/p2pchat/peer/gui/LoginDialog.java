package com.p2pchat.peer.gui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.UUID;
import java.util.prefs.Preferences;

public class LoginDialog extends JDialog {
    private static final Preferences PREFS = Preferences.userNodeForPackage(LoginDialog.class);

    private String peerId;
    private String username;
    private int myPort;
    private boolean confirmed = false;

    private final JTextField usernameField = new JTextField(20);
    private final JSpinner myPortSpinner = new JSpinner(new SpinnerNumberModel(9100, 1, 65535, 1));

    private static final Color ACCENT = new Color(37, 99, 235);
    private static final Color BG_FORM = Color.WHITE;
    private static final Color BG_ROOT = new Color(245, 247, 250);
    private static final Color CLR_BORDER = new Color(209, 213, 219);
    private static final Color TXT_LABEL = new Color(55, 65, 81);
    private static final Color TXT_MUTED = new Color(107, 114, 128);

    public LoginDialog(Frame owner) {
        super(owner, "P2P Chat — Sign In", true);
        buildUI();
        loadPreferences();
        pack();
        setMinimumSize(new Dimension(460, 0));
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    private void buildUI() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG_ROOT);
        root.add(buildBanner(), BorderLayout.NORTH);
        root.add(buildForm(), BorderLayout.CENTER);
        root.add(buildButtons(), BorderLayout.SOUTH);

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "continue");
        getRootPane().getActionMap().put("continue", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { onContinue(); }
        });
        setContentPane(root);
    }

    private JPanel buildBanner() {
        JPanel banner = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(ACCENT);
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(new Color(255, 255, 255, 25));
                g2.fillRect(0, getHeight() - 3, getWidth(), 3);
                g2.dispose();
            }
        };
        banner.setPreferredSize(new Dimension(0, 72));
        banner.setBorder(new EmptyBorder(14, 24, 14, 24));

        JLabel title = new JLabel("P2P Chat");
        title.setFont(new Font("SansSerif", Font.BOLD, 24));
        title.setForeground(Color.WHITE);
        JLabel sub = new JLabel("Enter app first, connect network later");
        sub.setFont(new Font("SansSerif", Font.PLAIN, 12));
        sub.setForeground(new Color(191, 219, 254));

        JPanel txt = new JPanel(new GridLayout(2, 1, 0, 2));
        txt.setOpaque(false);
        txt.add(title);
        txt.add(sub);
        banner.add(txt, BorderLayout.WEST);

        String myIp = detectLanIp();
        JPanel ipBadge = new JPanel(new GridLayout(2, 1, 0, 2));
        ipBadge.setOpaque(false);
        JLabel ipLbl = new JLabel("IP của máy này:");
        ipLbl.setFont(new Font("SansSerif", Font.PLAIN, 10));
        ipLbl.setForeground(new Color(191, 219, 254));
        ipLbl.setHorizontalAlignment(SwingConstants.RIGHT);
        JLabel ipVal = new JLabel(myIp);
        ipVal.setFont(new Font("Monospaced", Font.BOLD, 13));
        ipVal.setForeground(Color.WHITE);
        ipVal.setHorizontalAlignment(SwingConstants.RIGHT);
        ipVal.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        ipVal.setToolTipText("Click để copy");
        ipVal.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                copyToClipboard(myIp);
                ipVal.setText("✓ Copied!");
                Timer t = new Timer(1500, ev -> ipVal.setText(myIp));
                t.setRepeats(false);
                t.start();
            }
        });
        ipBadge.add(ipLbl);
        ipBadge.add(ipVal);
        banner.add(ipBadge, BorderLayout.EAST);
        return banner;
    }

    private JPanel buildForm() {
        JPanel form = new JPanel();
        form.setBackground(BG_FORM);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(new EmptyBorder(16, 24, 8, 24));

        form.add(sectionTitle("Identity"));
        form.add(Box.createVerticalStrut(8));
        form.add(row("Username", usernameField, null));
        form.add(row("My Port", myPortSpinner, hint("Port TCP của peer này (có thể tự đổi nếu bận)")));
        form.add(Box.createVerticalStrut(4));
        return form;
    }

    private JPanel buildButtons() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 10));
        p.setBackground(BG_ROOT);
        p.setBorder(new MatteBorder(1, 0, 0, 0, CLR_BORDER));

        JButton cancel = new JButton("Cancel");
        cancel.setFont(new Font("SansSerif", Font.PLAIN, 13));
        cancel.addActionListener(e -> { confirmed = false; dispose(); });

        JButton cont = new JButton("Continue →") {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getModel().isPressed() ? ACCENT.darker() : getModel().isRollover() ? ACCENT.brighter() : ACCENT);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20);
                g2.setColor(Color.WHITE);
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(getText(), (getWidth() - fm.stringWidth(getText())) / 2,
                        (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
                g2.dispose();
            }
        };
        cont.setFont(new Font("SansSerif", Font.BOLD, 14));
        cont.setPreferredSize(new Dimension(130, 38));
        cont.setFocusPainted(false);
        cont.setBorderPainted(false);
        cont.setContentAreaFilled(false);
        cont.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        cont.addActionListener(e -> onContinue());

        p.add(cancel);
        p.add(cont);
        return p;
    }

    private void onContinue() {
        String name = usernameField.getText().trim();
        if (name.length() < 2 || name.length() > 32) {
            JOptionPane.showMessageDialog(this, "Username phải từ 2-32 ký tự.", "Lỗi", JOptionPane.WARNING_MESSAGE);
            return;
        }
        this.username = name;
        this.myPort = (int) myPortSpinner.getValue();

        String savedId = PREFS.get("peerId_" + username, null);
        this.peerId = (savedId != null) ? savedId : UUID.randomUUID().toString();
        savePreferences();
        this.confirmed = true;
        dispose();
    }

    private void loadPreferences() {
        usernameField.setText(PREFS.get("username", ""));
        myPortSpinner.setValue(PREFS.getInt("myPort", 9100));
    }

    private void savePreferences() {
        PREFS.put("username", username);
        PREFS.putInt("myPort", myPort);
        PREFS.put("peerId_" + username, peerId);
    }

    private JLabel sectionTitle(String text) {
        JLabel lbl = new JLabel(text.toUpperCase());
        lbl.setFont(new Font("SansSerif", Font.BOLD, 10));
        lbl.setForeground(TXT_MUTED);
        lbl.setBorder(new MatteBorder(0, 0, 1, 0, CLR_BORDER));
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        lbl.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        return lbl;
    }

    private JPanel row(String labelText, JComponent field, JComponent extra) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setOpaque(false);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, extra != null ? 42 : 30));
        p.setBorder(new EmptyBorder(2, 0, 2, 0));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel lbl = new JLabel(labelText);
        lbl.setFont(new Font("SansSerif", Font.PLAIN, 12));
        lbl.setForeground(TXT_LABEL);
        lbl.setPreferredSize(new Dimension(82, 24));

        JPanel right = new JPanel(new BorderLayout(4, 0));
        right.setOpaque(false);
        right.add(field, BorderLayout.CENTER);
        if (extra != null) right.add(extra, BorderLayout.SOUTH);

        p.add(lbl, BorderLayout.WEST);
        p.add(right, BorderLayout.CENTER);
        return p;
    }

    private JLabel hint(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(new Font("SansSerif", Font.ITALIC, 10));
        lbl.setForeground(TXT_MUTED);
        return lbl;
    }

    public static String detectLanIp() {
        try (java.net.DatagramSocket s = new java.net.DatagramSocket()) {
            s.connect(InetAddress.getByName("8.8.8.8"), 80);
            String ip = s.getLocalAddress().getHostAddress();
            if (ip != null && !ip.startsWith("0.")) return ip;
        } catch (Exception ignored) {}

        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            String fallback = null;
            while (ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!(a instanceof Inet4Address)) continue;
                    String ip = a.getHostAddress();
                    if (ip.startsWith("192.168.")) return ip;
                    if (fallback == null) fallback = ip;
                }
            }
            if (fallback != null) return fallback;
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    private void copyToClipboard(String text) {
        java.awt.datatransfer.StringSelection sel = new java.awt.datatransfer.StringSelection(text);
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
    }

    public boolean isConfirmed() { return confirmed; }
    public String getPeerId() { return peerId; }
    public String getUsername() { return username; }
    public int getMyPort() { return myPort; }
}