package com.lrj.benefit.contract;

import java.time.Instant;

public record FulfillmentEvent(
        String awardOrderNo,
        String awardItemNo,
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
        this(awardOrderNo, awardItemNo, operationNo, status, channelCode, providerReference, errorCode,
                occurredAt, null, null, null, null, null, null, null);
    }
}
