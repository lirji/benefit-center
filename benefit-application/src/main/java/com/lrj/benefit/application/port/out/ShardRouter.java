package com.lrj.benefit.application.port.out;

public interface ShardRouter {
    Placement locate(String tenantId, String routingKey);
    record Placement(String databaseKey, int tableSuffix) {}
}
