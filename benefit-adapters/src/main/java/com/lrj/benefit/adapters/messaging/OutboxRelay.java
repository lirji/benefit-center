package com.lrj.benefit.adapters.messaging;

import com.lrj.benefit.application.port.out.EventPublisher;
import com.lrj.benefit.application.port.out.OutboxRepository;

import java.time.Clock;
import java.time.Duration;

public final class OutboxRelay {
    private final OutboxRepository outbox;
    private final EventPublisher publisher;
    private final Clock clock;
    private final int maxAttempts;

    public OutboxRelay(OutboxRepository outbox, EventPublisher publisher, Clock clock, int maxAttempts) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
    }

    public RelayResult relay(String workerId, int limit) {
        int published = 0, failed = 0;
        for (var message : outbox.claimDue(workerId, clock.instant(), limit)) {
            try {
                publisher.publish(message.eventType(), message.aggregateId(), message.payload());
                outbox.markPublished(message.tenantId(), message.eventId(), workerId, clock.instant());
                published++;
            } catch (RuntimeException deliveryFailure) {
                long backoff = Math.min(300, 1L << Math.min(message.attemptCount(), 8));
                outbox.markFailed(message.tenantId(), message.eventId(), workerId,
                        clock.instant().plus(Duration.ofSeconds(backoff)), maxAttempts);
                failed++;
            }
        }
        return new RelayResult(published, failed);
    }

    public record RelayResult(int published, int failed) {}
}
