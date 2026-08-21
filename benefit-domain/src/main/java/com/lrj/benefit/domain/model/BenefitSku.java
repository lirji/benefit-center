package com.lrj.benefit.domain.model;

import com.lrj.benefit.contract.BenefitType;

import java.util.Objects;

public record BenefitSku(
        String tenantId,
        String skuId,
        BenefitType type,
        Long amountMinor,
        String currency,
        boolean enabled) {

    public BenefitSku {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(skuId, "skuId");
        Objects.requireNonNull(type, "type");
        if (type == BenefitType.CASH && (amountMinor == null || amountMinor <= 0 || currency == null)) {
            throw new IllegalArgumentException("cash sku requires amount and currency");
        }
        if (type != BenefitType.CASH && (amountMinor != null || currency != null)) {
            throw new IllegalArgumentException("non-cash sku must not carry money");
        }
    }
}
