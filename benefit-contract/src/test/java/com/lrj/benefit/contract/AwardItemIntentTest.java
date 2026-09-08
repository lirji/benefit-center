package com.lrj.benefit.contract;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class AwardItemIntentTest {
    @Test void optionalVersionKeepsLegacyConstructorAndRejectsNegative() {
        var legacy = new AwardItemIntent("i", "s", BenefitType.COUPON, null, null, 1, Map.of());
        assertThat(legacy.expectedSkuVersion()).isNull();
        assertThat(new AwardItemIntent("i", "s", BenefitType.COUPON, null, null, 1, Map.of(), 0L)
                .expectedSkuVersion()).isZero();
        assertThatThrownBy(() -> new AwardItemIntent("i", "s", BenefitType.COUPON, null, null, 1, Map.of(), -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonCashMustNotPretendToBeMoney() {
        assertThatThrownBy(() -> new AwardItemIntent(
                "item-1", "coupon-1", BenefitType.COUPON, 0L, "XXX", 1, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not carry monetary fields");
    }
}
