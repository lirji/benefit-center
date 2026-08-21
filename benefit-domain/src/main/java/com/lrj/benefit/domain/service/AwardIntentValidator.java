package com.lrj.benefit.domain.service;

import com.lrj.benefit.contract.AwardIntent;
import com.lrj.benefit.contract.BenefitType;
import com.lrj.benefit.contract.PartialPolicy;

import java.util.HashSet;

public final class AwardIntentValidator {
    public void validate(AwardIntent intent) {
        if (!"1.0".equals(intent.schemaVersion())) {
            throw new IllegalArgumentException("unsupported award intent schemaVersion");
        }
        if (intent.partialPolicy() != PartialPolicy.BEST_EFFORT) {
            throw new IllegalArgumentException("v1 supports BEST_EFFORT only");
        }
        requireText("sourceSystem", intent.sourceSystem(), 64);
        requireText("sourceRequestId", intent.sourceRequestId(), 128);
        requireText("recipientRef", intent.recipientRef(), 256);
        if (intent.sourceBusinessNo() != null && intent.sourceBusinessNo().length() > 128) {
            throw new IllegalArgumentException("sourceBusinessNo is too long");
        }
        if (intent.items().isEmpty() || intent.items().size() > 20) {
            throw new IllegalArgumentException("AwardIntent items must contain 1..20 atomic items");
        }
        if (intent.trace().size() > 32) throw new IllegalArgumentException("trace has too many entries");
        var itemIds = new HashSet<String>();
        intent.items().forEach(item -> {
            requireText("clientItemId", item.clientItemId(), 128);
            requireText("benefitSkuId", item.benefitSkuId(), 128);
            if (item.benefitType() == null) throw new IllegalArgumentException("benefitType is required");
            if (item.quantity() != 1) throw new IllegalArgumentException("v1 items must be atomic");
            if (item.benefitType() == BenefitType.CASH
                    && (item.amountMinor() == null || item.amountMinor() <= 0
                    || item.currency() == null || !item.currency().matches("[A-Z]{3}"))) {
                throw new IllegalArgumentException("cash item requires positive amount and ISO currency");
            }
            if (item.benefitType() != BenefitType.CASH
                    && (item.amountMinor() != null || item.currency() != null)) {
                throw new IllegalArgumentException("non-cash item cannot carry money");
            }
            if (item.metadata().size() > 32) throw new IllegalArgumentException("item metadata has too many entries");
            if (!itemIds.add(item.clientItemId())) {
                throw new IllegalArgumentException("duplicate clientItemId: " + item.clientItemId());
            }
        });
    }

    private static void requireText(String name, String value, int maxLength) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        if (value.length() > maxLength) throw new IllegalArgumentException(name + " is too long");
    }
}
