package com.novelanalyzer.modules.config.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.config.AuthProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class ConfigSecretService {

    private static final String ENCRYPTED_PREFIX = "enc::v1::";
    private static final String MASKED_VALUE = "已配置";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final AuthProperties authProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    public ConfigSecretService(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    public String encryptIfNecessary(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return "";
        }
        if (isEncrypted(normalized)) {
            return normalized;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, buildKey(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(normalized.getBytes(StandardCharsets.UTF_8));
            byte[] payload = ByteBuffer.allocate(iv.length + encrypted.length)
                .put(iv)
                .put(encrypted)
                .array();
            return ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "failed to encrypt config secret");
        }
    }

    public String decryptIfNecessary(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }
        if (!isEncrypted(normalized)) {
            return normalized;
        }
        try {
            byte[] payload = Base64.getDecoder().decode(normalized.substring(ENCRYPTED_PREFIX.length()));
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] encrypted = new byte[payload.length - GCM_IV_LENGTH];
            System.arraycopy(payload, 0, iv, 0, iv.length);
            System.arraycopy(payload, iv.length, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, buildKey(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "failed to decrypt config secret");
        }
    }

    public boolean hasSecret(String value) {
        return trimToNull(value) != null;
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENCRYPTED_PREFIX);
    }

    public boolean isMaskedValue(String value) {
        return MASKED_VALUE.equals(trimToNull(value));
    }

    public String maskValue(String value) {
        return hasSecret(value) ? MASKED_VALUE : "";
    }

    public String getMaskedValue() {
        return MASKED_VALUE;
    }

    private SecretKeySpec buildKey() throws Exception {
        String masterSecret = resolveMasterSecret();
        if (masterSecret == null || masterSecret.isBlank()) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "config secret master key is not configured");
        }
        byte[] keyBytes = MessageDigest.getInstance("SHA-256")
            .digest(masterSecret.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(keyBytes, "AES");
    }

    private String resolveMasterSecret() {
        String envSecret = System.getenv("CONFIG_SECRET_MASTER_KEY");
        if (envSecret != null && !envSecret.isBlank()) {
            return envSecret;
        }
        String propertySecret = System.getProperty("CONFIG_SECRET_MASTER_KEY");
        if (propertySecret != null && !propertySecret.isBlank()) {
            return propertySecret;
        }
        return authProperties.getJwtSecret();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
