package com.lrj.benefit.contract;

import java.time.Instant;

/**
 * 权益履约事实契约；clientItemId/sourceRequestId 是与营销应发事实稳定勾稽所需的跨系统键。
 */
public record FulfillmentEvent(
        String awardOrderNo,
        String awardItemNo,
        String clientItemId,
        String sourceSystem,
        String sourceRequestId,
        String operationNo,
        String status,
        String channelCode,
        String providerReference,
        String errorCode,
        Instant occurredAt,
        String factType,
        String skuId,
        BenefitType benefitType,
        Long quantity,
        Long amountMinor,
        String currency,
        String entryType) {

    public FulfillmentEvent(String awardOrderNo, String awardItemNo, String operationNo, String status,
                            String channelCode, String providerReference, String errorCode, Instant occurredAt) {
        this(awardOrderNo, awardItemNo, null, null, null, operationNo, status, channelCode, providerReference, errorCode,
                occurredAt, null, null, null, null, null, null, null);
    }
}
