package com.lrj.benefit.application.port.in;

import java.time.Instant;
import java.util.List;

/** 客服券包查询；所有查询均由适配器强制带 tenant_id 谓词。 */
public interface WalletQueryUseCase {
    WalletView wallet(String tenantId, String subjectRef);

    List<WalletEntryView> entries(String tenantId, String subjectRef, String status,
                                  String skuId, String afterEntryId, int limit);

    record WalletView(String subjectRef, long totalEntries, long unusedEntries,
                      List<CashBalanceView> cashBalances) {}

    record CashBalanceView(String currency, long balanceMinor) {}

    record WalletEntryView(String entryId, String subjectRef, String skuId, long skuVersion,
                           String awardOrderNo, String itemNo, String assetType, String status,
                           long version, Instant expiresAt, Long faceValueMinor, String currency,
                           Instant createdAt) {}
}
