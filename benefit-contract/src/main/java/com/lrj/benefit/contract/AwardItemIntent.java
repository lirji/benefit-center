package com.lrj.benefit.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** 原子发奖项；可选版本只约束首次受理，缺失时兼容旧来源。 */
public record AwardItemIntent(
        @NotBlank @Size(max = 128) String clientItemId,
        @NotBlank @Size(max = 128) String benefitSkuId,
        @NotNull BenefitType benefitType,
        Long amountMinor,
        String currency,
        @Positive @Max(1) long quantity,
        @Size(max = 32) Map<@Size(max = 64) String, @Size(max = 256) String> metadata,
        @PositiveOrZero @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonDeserialize(using = ExpectedSkuVersionDeserializer.class) Long expectedSkuVersion) {

    /** 保留原Java构造；无版本请求不改变历史幂等内容。 */
    public AwardItemIntent(String clientItemId, String benefitSkuId, BenefitType benefitType,
            Long amountMinor, String currency, long quantity, Map<String, String> metadata) {
        this(clientItemId, benefitSkuId, benefitType, amountMinor, currency, quantity, metadata, null);
    }

    /** 版本0是历史合法世代，负数不得降级为未指定。 */
    public AwardItemIntent {
        if (expectedSkuVersion != null && expectedSkuVersion < 0) {
            throw new IllegalArgumentException("expectedSkuVersion must not be negative");
        }
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
