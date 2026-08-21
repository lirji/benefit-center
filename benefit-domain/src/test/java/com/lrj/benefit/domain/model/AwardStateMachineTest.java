package com.lrj.benefit.domain.model;

import com.lrj.benefit.contract.BenefitType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AwardStateMachineTest {
    @Test
    void combinationCanEndPartiallySucceeded() {
        var ok = new AwardItem("i1", "c1", "s1", BenefitType.COUPON, 1, AwardItemStatus.PENDING, 0);
        var failed = new AwardItem("i2", "c2", "s2", BenefitType.PHYSICAL, 1, AwardItemStatus.PENDING, 0);
        var order = new AwardOrder("t", "o", "src", "req", "hash", "cell-0", List.of(ok, failed), AwardOrderStatus.ACCEPTED, 0);
        order.startProcessing();
        ok.reserve(); ok.dispatch(); ok.succeed();
        failed.reserve(); failed.dispatch(); failed.failFinal();
        order.recompute();
        assertThat(order.status()).isEqualTo(AwardOrderStatus.PARTIAL_SUCCEEDED);
    }

    @Test
    void unknownItemCannotBeReissued() {
        var item = new AwardItem("i", "c", "s", BenefitType.COUPON, 1, AwardItemStatus.UNKNOWN, 0);
        assertThatThrownBy(item::beginReissue).hasMessageContaining("UNKNOWN");
    }

    @Test
    void replacementWorkerQueriesSameIssueAfterDispatchLeaseExpires() {
        var item = new AwardItem("i", "c", "s", BenefitType.COUPON, 1,
                AwardItemStatus.DISPATCHING, 0);
        item.beginQuery();
        assertThat(item.status()).isEqualTo(AwardItemStatus.QUERYING);
    }

    @Test
    void replacementWorkerQueriesSameReverseAfterDispatchLeaseExpires() {
        var item = new AwardItem("i", "c", "s", BenefitType.COUPON, 1,
                AwardItemStatus.REVERSING, 0);
        item.beginReversalQuery();
        assertThat(item.status()).isEqualTo(AwardItemStatus.REVERSING);
    }
}
