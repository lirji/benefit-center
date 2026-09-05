package com.lrj.benefit.web;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.util.HexFormat;

/** 对 Jackson 规范序列化结果做 SHA-256，用于管理写接口同键异载荷检测。 */
final class RequestPayloadHasher {
    private final ObjectMapper json;

    RequestPayloadHasher(ObjectMapper json) {
        this.json = json;
    }

    String hash(Object payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsBytes(payload)));
        } catch (Exception failure) {
            throw new IllegalStateException("cannot hash request payload", failure);
        }
    }
}
