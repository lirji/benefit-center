package com.lrj.benefit.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 运营台只读查询：一律按当前租户过滤，禁止跨租户聚合。 */
public interface ConsoleQueryUseCase {
    Identity me(String tenantId, String subject, List<String> scopes);

    Overview overview(String tenantId);

    Optional<TenantView> currentTenant(String tenantId);

    List<SkuView> listSkus(String tenantId, String status, String afterSkuId, int limit);

    List<RouteView> listRoutes(String tenantId, String skuId, int limit);

    List<InventoryView> listInventory(String tenantId, int limit);

    List<AttentionOrderView> attentionOrders(String tenantId, int limit);

    List<RemediationView> listRemediations(String tenantId, String status, int limit);

    List<CodeAssetView> listCodeAssets(String tenantId, String skuId, int limit);

    record Identity(String tenantId, String subject, String displayName, List<String> scopes) {}

    record Overview(long unknownOps, long partialOrders, long pendingRemediations, long skuCount,
                    long enabledRoutes, long activeTemplateCount, long walletIssued24h) {}

    record TenantView(String tenantId, String homeCell, boolean enabled, long version) {}

    record SkuView(String skuId, String benefitType, Long faceValueMinor, String currency,
                   String status, boolean enabled, String validityType, Instant validFrom, Instant validTo,
                   Integer relativeDays, List<Integer> usableWeekdays, Long dailyQuota,
                   Long userLimitPerDay, Long userLimitTotal, String equivalentSkuId,
                   String approvalProcessDefinitionKey, String approvalBusinessKey, long version) {}

    record RouteView(String routeId, String skuId, int priority, String channelCode, String ownerType,
                     String fallbackRouteId, String reserveMode, boolean enabled, String configRef, long version) {}

    record InventoryView(String accountId, String skuId, String ownerType, String ownerId,
                         long available, long reserved, long issued, long version) {}

    record AttentionOrderView(String orderNo, String sourceSystem, String sourceRequestId, String status,
                              String reason, Instant updatedAt) {}

    record RemediationView(String remediationNo, String action, String itemNo, String status,
                           String reason, Instant updatedAt) {}

    record CodeAssetView(String codeAssetId, String skuId, String status, String codeHashPrefix, Instant expiresAt) {}
}
