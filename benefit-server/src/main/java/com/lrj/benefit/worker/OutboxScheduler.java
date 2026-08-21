package com.lrj.benefit.worker;

import com.lrj.benefit.adapters.messaging.OutboxRelay;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "benefit.outbox.enabled", havingValue = "true")
public class OutboxScheduler {
    private final OutboxRelay relay;
    private final String workerId;
    private final int batchSize;

    public OutboxScheduler(OutboxRelay relay,
                           @Value("${benefit.outbox.worker-id:${HOSTNAME:local-outbox}}") String workerId,
                           @Value("${benefit.outbox.batch-size:100}") int batchSize) {
        this.relay = relay;
        this.workerId = workerId;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${benefit.outbox.delay-ms:1000}")
    public void relay() { relay.relay(workerId, batchSize); }
}
