package com.lrj.benefit.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/** 冻结、核销、退款的资产事实；金额始终取 WalletEntry 入账快照。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WalletFulfillmentEvent(
        String walletEntryId,
        String skuId,
        long skuVersion,
        Long amountMinor,
        String currency,
        String status,
        String entryType,
        String operationNo,
        String reason,
        String merchantRef,
        Instant occurredAt) {
}
