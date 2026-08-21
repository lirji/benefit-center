package com.lrj.benefit.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryAccountTest {
    @Test
    void reserveCommitReleasePreserveBalances() {
        var account = new InventoryAccount("t", "a", "sku", InventoryOwnerType.CENTER_STOCK, 10, 0, 0, 0);
        account.reserve(4);
        account.commit(3);
        account.release(1);
        assertThat(account.available()).isEqualTo(7);
        assertThat(account.reserved()).isZero();
        assertThat(account.issued()).isEqualTo(3);
    }

    @Test
    void businessFlowCannotMutateChannelShadow() {
        var account = new InventoryAccount("t", "a", "sku", InventoryOwnerType.CHANNEL_SHADOW, 10, 0, 0, 0);
        assertThatThrownBy(() -> account.reserve(1)).hasMessageContaining("shadow");
    }
}
