package com.lrj.benefit.application.port.out;

public interface InboxRepository {
    ClaimResult claim(String tenantId, String consumerGroup, String messageId, String payloadHash);
    void markProcessed(String tenantId, String consumerGroup, String messageId);
    void markFailed(String tenantId, String consumerGroup, String messageId);

    enum ClaimResult { CLAIMED, REPLAY, PAYLOAD_CONFLICT }
}
