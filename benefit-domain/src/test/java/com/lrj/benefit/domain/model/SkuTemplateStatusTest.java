package com.lrj.benefit.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SkuTemplateStatusTest {
    @Test
    void allowsOnlyApprovedLifecycleTransitions() {
        assertThat(SkuTemplateStatus.DRAFT.canTransitionTo(SkuTemplateStatus.PENDING_APPROVAL)).isTrue();
        assertThat(SkuTemplateStatus.DRAFT.canTransitionTo(SkuTemplateStatus.ACTIVE)).isFalse();
        assertThat(SkuTemplateStatus.PENDING_APPROVAL.canTransitionTo(SkuTemplateStatus.ACTIVE)).isTrue();
        assertThat(SkuTemplateStatus.PENDING_APPROVAL.canTransitionTo(SkuTemplateStatus.DRAFT)).isTrue();
        assertThat(SkuTemplateStatus.ACTIVE.canTransitionTo(SkuTemplateStatus.PAUSED)).isTrue();
        assertThat(SkuTemplateStatus.ACTIVE.canTransitionTo(SkuTemplateStatus.RETIRED)).isFalse();
        assertThat(SkuTemplateStatus.PAUSED.canTransitionTo(SkuTemplateStatus.ACTIVE)).isTrue();
        assertThat(SkuTemplateStatus.RETIRED.canTransitionTo(SkuTemplateStatus.ACTIVE)).isFalse();
    }
}
