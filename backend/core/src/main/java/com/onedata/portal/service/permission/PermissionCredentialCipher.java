package com.onedata.portal.service.permission;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;


/** Only the source permission-management credential uses this store. */
@Component
public class PermissionCredentialCipher {
    private final String configuredKey;
    public PermissionCredentialCipher(@Value("${odw.credentials.key:${ODW_CREDENTIAL_KEY:}}") String configuredKey) {
        this.configuredKey = configuredKey;
    }
    private SecretKeySpec key() {
        try {
            byte[] key = Base64.getDecoder().decode(configuredKey);
            if (key.length != 32) throw new IllegalArgumentException();
            return new SecretKeySpec(key, "AES");
        } catch (RuntimeException e) {
            throw new IllegalStateException("请配置ODW_CREDENTIAL_KEY（32字节随机密钥的Base64），不能保存或读取管理凭据");
        }
    }
    public String encrypt(String value) {
        SecretKeySpec key = key();
        try {
            byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (Exception e) { throw new IllegalStateException("管理凭据加密失败"); }
    }
    public String decrypt(String value) {
        SecretKeySpec key = key();
        try {
            if (!value.startsWith("v1:")) throw new IllegalArgumentException();
            ByteBuffer bytes = ByteBuffer.wrap(Base64.getDecoder().decode(value.substring(3)));
            byte[] iv = new byte[12]; bytes.get(iv);
            byte[] encrypted = new byte[bytes.remaining()]; bytes.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("管理凭据解密失败，请核对密钥；不会改用其他账号"); }
    }
}
