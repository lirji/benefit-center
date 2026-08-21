package com.lrj.benefit.application.port.out;

public interface EventPublisher {
    void publish(String eventType, String partitionKey, String payload);
}
