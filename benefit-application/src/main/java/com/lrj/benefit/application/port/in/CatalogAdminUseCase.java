package com.lrj.benefit.application.port.in;

import com.lrj.benefit.contract.BenefitType;
import com.lrj.benefit.domain.model.InventoryOwnerType;
import com.lrj.benefit.domain.model.SkuTemplateStatus;
import com.lrj.benefit.domain.model.ValidityType;

import java.time.Instant;
import java.util.List;

public interface CatalogAdminUseCase {
    void saveTenant(TenantCommand command);
    void saveSku(String tenantId, SkuCommand command);
    SkuSubmitAcceptance submitSkuForApproval(String tenantId, String skuId, long expectedVersion,
                                             String initiator);
    void saveRoute(String tenantId, RouteCommand command);
    void adjustInventory(String tenantId, InventoryCommand command, String operator);
    void importCode(String tenantId, CodeAssetCommand command, String operator);

    record TenantCommand(String tenantId, String homeCell, boolean enabled, Long expectedVersion) {}
    /**
     * 模板写命令。status/validityType 为空时按旧版 enabled 语义兼容；新客户端应显式传状态与有效期类型。
     */
    record SkuCommand(String skuId, BenefitType benefitType, Long faceValueMinor, String currency,
                      Boolean enabled, SkuTemplateStatus status, ValidityType validityType,
                      Instant validFrom, Instant validTo, Integer relativeDays,
                      List<Integer> usableWeekdays, Long dailyQuota, Long userLimitPerDay,
                      Long userLimitTotal, String equivalentSkuId, Long expectedVersion) {}
    /** 提交只表示审批请求已受理，不能据此宣称模板已经投放。 */
    record SkuSubmitCommand(Long expectedVersion) {}
    record SkuSubmitAcceptance(String skuId, String status, long version) {}
    record RouteCommand(String routeId, String skuId, int priority, String channelCode,
                        InventoryOwnerType ownerType, String fallbackRouteId, String reserveMode,
                        boolean enabled, String configRef, Long expectedVersion) {}
    record InventoryCommand(String accountId, String skuId, InventoryOwnerType ownerType,
                            String ownerId, long deltaAvailable, String requestId) {}
    record CodeAssetCommand(String codeAssetId, String skuId, String codeHash, String cipherText,
                            String keyVersion, String expiresAt) {}
}
