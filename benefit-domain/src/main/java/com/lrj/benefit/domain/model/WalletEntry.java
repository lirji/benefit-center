package com.lrj.benefit.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 用户券包资产聚合。
 *
 * <p>资产绑定 skuVersion，而不是回看当前模板，确保模板切版后旧券有效期保持不变。</p>
 */
public record WalletEntry(
        String tenantId,
        String entryId,
        String subjectRef,
        String skuId,
        long skuVersion,
        String awardOrderNo,
        String itemNo,
        WalletAssetType assetType,
        WalletEntryStatus status,
        long version,
        Instant expiresAt,
        Long faceValueMinor,
        String currency,
        Instant createdAt) {

    public WalletEntry {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(subjectRef, "subjectRef");
        Objects.requireNonNull(skuId, "skuId");
        Objects.requireNonNull(awardOrderNo, "awardOrderNo");
        Objects.requireNonNull(itemNo, "itemNo");
        Objects.requireNonNull(assetType, "assetType");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        if ((faceValueMinor == null) != (currency == null)) {
            throw new IllegalArgumentException("wallet face value and currency must be provided together");
        }
        if (version < 0) throw new IllegalArgumentException("wallet version must not be negative");
    }
}
