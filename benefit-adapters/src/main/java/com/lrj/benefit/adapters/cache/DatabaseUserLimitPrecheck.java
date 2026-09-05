package com.lrj.benefit.adapters.cache;

import com.lrj.benefit.application.port.out.UserLimitPrecheck;
import com.lrj.benefit.application.port.out.UserLimitRepository;

/** Redis 关闭时仍执行数据库只读预检；最终结果始终由 reserve 条件更新裁决。 */
public final class DatabaseUserLimitPrecheck implements UserLimitPrecheck {
    private final UserLimitRepository repository;

    public DatabaseUserLimitPrecheck(UserLimitRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean mayReserve(String tenantId, String subjectRef, String skuId,
                              UserLimitRepository.PeriodType periodType, String periodKey,
                              long quantity, long configuredLimit) {
        return repository.currentUsage(tenantId, subjectRef, skuId, periodType, periodKey)
                <= configuredLimit - quantity;
    }

    @Override
    public void invalidate(String tenantId, String subjectRef, String skuId,
                           UserLimitRepository.PeriodType periodType, String periodKey) {
        // 数据库实现不持有缓存，无需失效。
    }
}
