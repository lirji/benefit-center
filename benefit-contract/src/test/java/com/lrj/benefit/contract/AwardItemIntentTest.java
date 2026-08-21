package com.lrj.benefit.contract;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AwardItemIntentTest {

    @Test
    void nonCashMustNotPretendToBeMoney() {
        assertThatThrownBy(() -> new AwardItemIntent(
                "item-1", "coupon-1", BenefitType.COUPON, 0L, "XXX", 1, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not carry monetary fields");
    }
}
