package com.lrj.benefit.adapters.messaging;

import org.apache.kafka.common.header.Header;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/** workflow Kafka 原始 JSON HMAC 工具；空配置仅供本地关闭信任校验的环境使用。 */
final class WorkflowKafkaSigning {
    static final String SIGNATURE_HEADER = "workflow-signature-v1";

    private WorkflowKafkaSigning() {}

    static byte[] keyFor(String mappings, String source) {
        if (mappings == null || mappings.isBlank()) return null;
        for (String pair : mappings.split(",")) {
            String[] parts = pair.trim().split("=", 2);
            if (parts.length == 2 && source.equals(parts[0].trim())) {
                byte[] key;
                try {
                    key = Base64.getUrlDecoder().decode(parts[1].trim());
                } catch (IllegalArgumentException invalidBase64) {
                    throw new IllegalArgumentException("workflow signing key must be Base64URL", invalidBase64);
                }
                if (key.length < 32) {
                    throw new IllegalArgumentException("workflow signing key must decode to at least 32 bytes");
                }
                return key;
            }
        }
        return null;
    }

    static String sign(byte[] key, String raw) {
        if (key == null) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("cannot sign workflow Kafka payload", failure);
        }
    }

    static void verify(byte[] key, String raw, Header signatureHeader) {
        if (key == null) return;
        if (signatureHeader == null || signatureHeader.value() == null) {
            throw new IllegalArgumentException("missing workflow-signature-v1 header");
        }
        byte[] expected = sign(key, raw).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, signatureHeader.value())) {
            throw new IllegalArgumentException("invalid workflow-signature-v1 header");
        }
    }
}
