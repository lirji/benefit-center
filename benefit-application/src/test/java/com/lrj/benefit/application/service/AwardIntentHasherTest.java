package com.lrj.benefit.application.service;

import com.lrj.benefit.contract.*;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AwardIntentHasherTest {
    private final AwardIntentHasher hasher = new AwardIntentHasher();

    @Test void optionalVersionChangesHashAndNullRetainsLegacyBytes() {
        AwardIntent legacy = intent("source", "business", Map.of());
        // 由git HEAD原Hasher实际编译执行得出，不能用本次实现反算期望值。
        assertThat(hasher.hash(legacy)).isEqualTo("44d2ae3f732e8af4237f5d0cc31a5f575313d730b6ec3432cdf936aa1d558b71");
        var item = legacy.items().getFirst();
        var explicitNull = withItem(legacy, new AwardItemIntent(item.clientItemId(), item.benefitSkuId(),
                item.benefitType(), item.amountMinor(), item.currency(), item.quantity(), item.metadata(), null));
        var zero = withItem(legacy, new AwardItemIntent(item.clientItemId(), item.benefitSkuId(),
                item.benefitType(), item.amountMinor(), item.currency(), item.quantity(), item.metadata(), 0L));
        var one = withItem(legacy, new AwardItemIntent(item.clientItemId(), item.benefitSkuId(),
                item.benefitType(), item.amountMinor(), item.currency(), item.quantity(), item.metadata(), 1L));
        assertThat(hasher.hash(explicitNull)).isEqualTo(hasher.hash(legacy));
        assertThat(hasher.hash(zero)).isNotEqualTo(hasher.hash(legacy)).isNotEqualTo(hasher.hash(one));
    }

    private static AwardIntent withItem(AwardIntent source, AwardItemIntent item) {
        return new AwardIntent(source.schemaVersion(), source.sourceSystem(), source.sourceRequestId(),
                source.sourceBusinessNo(), source.recipientRef(), source.decision(), source.partialPolicy(),
                List.of(item), source.trace());
    }

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
