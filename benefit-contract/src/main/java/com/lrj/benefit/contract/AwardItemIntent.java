package com.lrj.benefit.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record AwardItemIntent(
        @NotBlank @Size(max = 128) String clientItemId,
        @NotBlank @Size(max = 128) String benefitSkuId,
        @NotNull BenefitType benefitType,
        Long amountMinor,
        String currency,
        @Positive @Max(1) long quantity,
        @Size(max = 32) Map<@Size(max = 64) String, @Size(max = 256) String> metadata) {

    public AwardItemIntent {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (quantity != 1) {
            throw new IllegalArgumentException("v1 AwardItemIntent is atomic; split quantity into stable clientItemIds");
        }
        if (benefitType == BenefitType.CASH) {
            if (amountMinor == null || amountMinor <= 0 || currency == null || currency.length() != 3) {
                throw new IllegalArgumentException("cash item requires positive amountMinor and ISO currency");
            }
        } else if (amountMinor != null || currency != null) {
            throw new IllegalArgumentException("non-cash item must not carry monetary fields");
        }
    }
}
