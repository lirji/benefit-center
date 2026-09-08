package com.lrj.benefit.adapters.persistence;

import org.apache.ibatis.annotations.Param;
import java.time.Instant;

/** 仅版本受理使用的主库XML映射，不改变旧JDBC仓储。 */
public interface SkuAcceptanceMapper {
    /** 共享锁由原Spring事务持有，管理员更新需等待该次受理完成。 */
    Row lockCurrent(@Param("tenantId") String tenantId, @Param("skuId") String skuId);

    /** 数据行保留原始枚举及星期串，由适配器转换为领域快照。 */
    record Row(String tenantId, String skuId, String benefitType, Long faceValueMinor, String currency,
               String status, String validityType, Instant validFrom, Instant validTo, Integer relativeDays,
               String usableWeekdays, Long dailyQuota, Long userLimitPerDay, Long userLimitTotal,
               String equivalentSkuId, long version) { }
}
