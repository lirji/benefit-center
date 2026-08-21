package com.lrj.benefit.application.service;

import com.lrj.benefit.contract.*;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AwardIntentHasherTest {
    private final AwardIntentHasher hasher = new AwardIntentHasher();

    @Test
    void mapOrderDoesNotChangeHashButDelimiterPlacementDoes() {
        Map<String, String> firstTrace = new LinkedHashMap<>();
        firstTrace.put("z", "last"); firstTrace.put("a", "first");
        Map<String, String> secondTrace = new LinkedHashMap<>();
        secondTrace.put("a", "first"); secondTrace.put("z", "last");

        AwardIntent first = intent("source|request", "business", firstTrace);
        AwardIntent reordered = intent("source|request", "business", secondTrace);
        AwardIntent differentBoundary = intent("source", "request|business", firstTrace);

        assertThat(hasher.hash(first)).isEqualTo(hasher.hash(reordered));
        assertThat(hasher.hash(first)).isNotEqualTo(hasher.hash(differentBoundary));
    }

    private static AwardIntent intent(String requestId, String businessNo, Map<String, String> trace) {
        return new AwardIntent("1.0", "test", requestId, businessNo, "recipient", null,
                PartialPolicy.BEST_EFFORT,
                List.of(new AwardItemIntent("item", "coupon", BenefitType.COUPON,
                        null, null, 1, Map.of("k", "v|x"))), trace);
    }
}
