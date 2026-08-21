package com.lrj.benefit.worker;

import com.lrj.benefit.application.port.in.ExecuteFulfillmentUseCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
@ConditionalOnProperty(name = "benefit.worker.enabled", havingValue = "true")
public class FulfillmentScheduler {
    private final ExecuteFulfillmentUseCase fulfillment;
    private final List<String> tenants;
    private final String workerId;
    private final int batchSize;

    public FulfillmentScheduler(ExecuteFulfillmentUseCase fulfillment,
                                @Value("${benefit.worker.tenants:}") String tenants,
                                @Value("${benefit.worker.id:${HOSTNAME:local-worker}}") String workerId,
                                @Value("${benefit.worker.batch-size:100}") int batchSize) {
        this.fulfillment = fulfillment;
        this.tenants = Arrays.stream(tenants.split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList();
        this.workerId = workerId;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${benefit.worker.delay-ms:1000}")
    public void run() {
        for (String tenant : tenants) fulfillment.runBatch(tenant, batchSize, workerId);
    }
}
