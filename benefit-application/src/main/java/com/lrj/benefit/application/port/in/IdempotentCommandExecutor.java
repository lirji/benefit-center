package com.lrj.benefit.application.port.in;

import java.util.function.Supplier;

/** 管理写命令幂等边界；实现必须与业务写入共享本地事务。 */
public interface IdempotentCommandExecutor {
    /**
     * 首次请求执行 command；同键同 operation/payload 直接重放成功，任一不同时抛 409 冲突。
     */
    void execute(String tenantId, String idempotencyKey, String operationName,
                 String payloadHash, Runnable command);

    /**
     * 首次执行并持久化返回值；同键同操作同载荷必须重放首次返回值，而不是重新读取当前业务状态。
     */
    <T> T executeForResult(String tenantId, String idempotencyKey, String operationName,
                           String payloadHash, Supplier<T> command, Class<T> resultType);
}
