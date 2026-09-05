package com.lrj.benefit.domain.model;

import com.lrj.benefit.contract.BenefitType;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * SKU 模板的不可变领域快照。
 *
 * <p>version 同时承担乐观锁版本与模板世代；订单项持有该值，避免模板后续修改影响已发资产。</p>
 */
public record BenefitSku(
        String tenantId,
        String skuId,
        BenefitType type,
        Long amountMinor,
        String currency,
        SkuTemplateStatus status,
        ValidityType validityType,
        Instant validFrom,
        Instant validTo,
        Integer relativeDays,
        List<Integer> usableWeekdays,
        Long dailyQuota,
        Long userLimitPerDay,
        Long userLimitTotal,
        String equivalentSkuId,
        long version) {

    public BenefitSku {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(skuId, "skuId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(validityType, "validityType");
        usableWeekdays = usableWeekdays == null ? List.of() : List.copyOf(usableWeekdays);
        if (type == BenefitType.CASH && (amountMinor == null || amountMinor <= 0 || currency == null)) {
            throw new IllegalArgumentException("cash sku requires amount and currency");
        }
        if ((amountMinor == null) != (currency == null)) {
            throw new IllegalArgumentException("face value and currency must be provided together");
        }
    }

    /** 兼容旧调用方的派生字段；只有 ACTIVE 模板可接新单。 */
    public boolean enabled() {
        return status == SkuTemplateStatus.ACTIVE;
    }

    /** ACTIVE 只表示生命周期可投放；绝对有效期还必须覆盖当前受理时刻。 */
    public boolean acceptsAt(Instant instant) {
        if (!enabled()) return false;
        if (validityType == ValidityType.RELATIVE) return true;
        return validFrom != null && validTo != null
                && !instant.isBefore(validFrom) && instant.isBefore(validTo);
    }
}
