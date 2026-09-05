package com.lrj.benefit.adapters.messaging;

import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** workflow 原始 JSON HMAC 必须可互验，且不得接受短密钥或被改写的 JSON。 */
class WorkflowKafkaSigningTest {
    private static final String KEY = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @Test
    void signsAndVerifiesExactRawJson() {
        byte[] key = WorkflowKafkaSigning.keyFor("benefit-center=" + KEY, "benefit-center");
        String raw = "{\"eventType\":\"workflow.command.start.v1\"}";
        String signature = WorkflowKafkaSigning.sign(key, raw);

        WorkflowKafkaSigning.verify(key, raw, new RecordHeader(
                WorkflowKafkaSigning.SIGNATURE_HEADER, signature.getBytes(StandardCharsets.US_ASCII)));
        assertThatThrownBy(() -> WorkflowKafkaSigning.verify(key, raw + " ", new RecordHeader(
                WorkflowKafkaSigning.SIGNATURE_HEADER, signature.getBytes(StandardCharsets.US_ASCII))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsConfiguredShortKey() {
        String shortKey = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("too-short".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> WorkflowKafkaSigning.keyFor("workflow-server=" + shortKey,
                "workflow-server")).isInstanceOf(IllegalArgumentException.class);
    }
}
