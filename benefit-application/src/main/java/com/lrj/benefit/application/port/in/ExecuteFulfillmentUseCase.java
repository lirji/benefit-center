package com.lrj.benefit.application.port.in;

public interface ExecuteFulfillmentUseCase {
    BatchResult runBatch(String tenantId, int limit, String workerId);

    record BatchResult(int claimed, int succeeded, int failed, int unknown) {}
}
