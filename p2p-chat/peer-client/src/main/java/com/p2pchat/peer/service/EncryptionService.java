package com.p2pchat.peer.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.logging.Logger;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Dịch vụ mã hóa tin nhắn sử dụng AES-128.
 */
public class EncryptionService {

    private static final Logger log = Logger.getLogger(EncryptionService.class.getName());
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/ECB/PKCS5Padding";

    // Pre-shared key 16 bytes (AES-128)
    private static final byte[] SHARED_KEY = "P2PChatSecretKey".getBytes(StandardCharsets.UTF_8);

    private final SecretKeySpec keySpec;
    private boolean enabled = true;

    public EncryptionService() {
        this.keySpec = new SecretKeySpec(SHARED_KEY, ALGORITHM);
    }

    public String encrypt(String plainText) {
        if (!enabled || plainText == null)
            return plainText;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            log.warning("Encryption failed, sending plain: " + e.getMessage());
            return plainText;
        }
    }

    public String decrypt(String cipherText) {
        if (!enabled || cipherText == null)
            return cipherText;
        try {
            byte[] decoded = Base64.getDecoder().decode(cipherText);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            byte[] decrypted = cipher.doFinal(decoded);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Nếu decrypt lỗi (có thể peer gửi plain text cũ), trả về nguyên
            return cipherText;
        }
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }
}