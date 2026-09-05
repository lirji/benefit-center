package com.lrj.benefit.contract;

import java.time.Instant;
import java.util.List;

/** 模板世代变更事件，供其它实例失效 L1 并供下游维护只读投影。 */
public record SkuTemplateChangedEvent(
        String skuId,
        long version,
        BenefitType benefitType,
        String status,
        Long faceValueMinor,
        String currency,
        String validityType,
        Instant validFrom,
        Instant validTo,
        Integer relativeDays,
        List<Integer> usableWeekdays,
        Long dailyQuota,
        Long userLimitPerDay,
        Long userLimitTotal,
        String equivalentSkuId) {
}
