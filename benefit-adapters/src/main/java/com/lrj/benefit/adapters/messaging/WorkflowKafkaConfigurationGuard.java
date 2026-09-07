package com.lrj.benefit.adapters.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * SKU 审批回程开关的启动门禁。ACK consumer 开启后，入站与出站两把 HMAC 都必须完整配置，
 * 避免出现“能消费但无签名回 ACK”或“静默跳过验签”的半边安全状态。
 */
@Component
@ConditionalOnExpression("'${workflow.kafka.enabled:false}' == 'true' && "
        + "'${workflow.kafka.configuration-guard-enabled:true}' == 'true'")
public final class WorkflowKafkaConfigurationGuard implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(WorkflowKafkaConfigurationGuard.class);
    private final String signingKeys;

    public WorkflowKafkaConfigurationGuard(
            @Value("${workflow.kafka.source-signing-keys:}") String signingKeys) {
        this.signingKeys = signingKeys;
    }

    /** 校验两侧逻辑身份的 key，并在成功后明确记录 ACK consumer 已启用。 */
    @Override
    public void afterPropertiesSet() {
        requireKey("benefit-center");
        requireKey("workflow-server");
        log.info("权益 SKU workflow ACK consumer 已启用，双向 HMAC 配置完整");
    }

    private void requireKey(String source) {
        if (WorkflowKafkaSigning.keyFor(signingKeys, source) == null) {
            throw new IllegalStateException(
                    "WORKFLOW_KAFKA_ENABLED=true 时必须配置至少 32 字节的 " + source + " Base64URL HMAC key");
        }
    }
}
