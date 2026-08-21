package com.lrj.benefit.application.port.out;

import java.time.Instant;

public interface LedgerRepository {
    boolean appendIfAbsent(LedgerEntry entry);

    record LedgerEntry(String tenantId, String ledgerNo, String orderNo, String itemNo, String operationNo,
                       String entryType, Long amountMinor, long quantitySigned, String currency,
                       String ownerType, String channelCode, String providerReference, Instant bizTime) {
    }
}
