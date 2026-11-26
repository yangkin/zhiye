package com.tcl.csot.zhiye.service.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/** AES-256-GCM 密文与 HMAC 预览签名工具：加密单体应用环境变量/数据库凭证，并签发隔离域名预览令牌。 */
@Component
public class ServiceCrypto {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ServiceCrypto.class);
    private static final String DEFAULT_DEV_SECRET = "zhiye-dev-cloud-secret-change-me";
    private static final SecureRandom RNG = new SecureRandom();
    private final String baseSecret;
    private final SecretKeySpec key;
    private final SecretKeySpec hmacKey;

    public ServiceCrypto(@Value("${zhiye.cloud.secret:" + DEFAULT_DEV_SECRET + "}") String secret) {
        try {
            String base = secret == null || secret.isBlank() ? "dev" : secret;
            this.baseSecret = base;
            if (DEFAULT_DEV_SECRET.equals(base) || "dev".equals(base)) {
                log.warn("ServiceCrypto 正在使用默认开发密钥 zhiye.cloud.secret；生产环境请务必通过环境变量 ZHIYE_CLOUD_SECRET 配置独立强密钥");
            }
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(("zhiye-service-env:" + base).getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(hash, "AES");
            byte[] macHash = MessageDigest.getInstance("SHA-256")
                    .digest(("zhiye-service-preview:" + base).getBytes(StandardCharsets.UTF_8));
            this.hmacKey = new SecretKeySpec(macHash, "HmacSHA256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 基于平台主密钥按业务域派生独立 HMAC 密钥，避免子服务硬编码默认密钥。 */
    public byte[] deriveHmacKey(String domain) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest((domain + ":" + baseSecret).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 签发工作台隔离域名预览令牌（有效期 12 小时）。 */
    public String signPreviewToken(String appId) {
        long exp = Instant.now().getEpochSecond() + 12 * 3600L;
        String payload = appId + "." + exp;
        return payload + "." + hmac(payload);
    }

    /** 校验工作台隔离域名预览令牌。 */
    public boolean verifyPreviewToken(String appId, String token) {
        if (appId == null || token == null || token.isBlank()) return false;
        String[] parts = token.split("\\.");
        if (parts.length != 3 || !appId.equals(parts[0])) return false;
        try {
            long exp = Long.parseLong(parts[1]);
            if (exp < Instant.now().getEpochSecond()) return false;
            String expected = hmac(parts[0] + "." + parts[1]);
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), parts[2].getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    private String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String encrypt(String plain) {
        if (plain == null) return "";
        try {
            byte[] iv = new byte[12];
            RNG.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return "v1:" + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("加密环境变量失败", e);
        }
    }

    public String decrypt(String enc) {
        if (enc == null || enc.isEmpty()) return "";
        if (!enc.startsWith("v1:")) return enc;
        try {
            byte[] raw = Base64.getDecoder().decode(enc.substring(3));
            byte[] iv = new byte[12];
            byte[] ct = new byte[raw.length - 12];
            System.arraycopy(raw, 0, iv, 0, 12);
            System.arraycopy(raw, 12, ct, 0, ct.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("解密环境变量失败", e);
        }
    }
}
