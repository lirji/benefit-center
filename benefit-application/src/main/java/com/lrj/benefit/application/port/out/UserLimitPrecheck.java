package com.lrj.benefit.application.port.out;

/** Redis/L2 只做快速拒绝；返回可受理后仍必须执行数据库 CAS。 */
public interface UserLimitPrecheck {
    boolean mayReserve(String tenantId, String subjectRef, String skuId,
                       UserLimitRepository.PeriodType periodType, String periodKey,
                       long quantity, long configuredLimit);

    /** 真账变化后删缓存，下一次从数据库回源。 */
    void invalidate(String tenantId, String subjectRef, String skuId,
                    UserLimitRepository.PeriodType periodType, String periodKey);
}
