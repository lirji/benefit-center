package com.lrj.benefit.application.port.in;

import com.lrj.benefit.contract.BenefitType;
import com.lrj.benefit.domain.model.InventoryOwnerType;

public interface CatalogAdminUseCase {
    void saveTenant(TenantCommand command);
    void saveSku(String tenantId, SkuCommand command);
    void saveRoute(String tenantId, RouteCommand command);
    void adjustInventory(String tenantId, InventoryCommand command, String operator);
    void importCode(String tenantId, CodeAssetCommand command, String operator);

    record TenantCommand(String tenantId, String homeCell, boolean enabled, Long expectedVersion) {}
    record SkuCommand(String skuId, BenefitType benefitType, Long faceValueMinor, String currency,
                      boolean enabled, Long expectedVersion) {}
    record RouteCommand(String routeId, String skuId, int priority, String channelCode,
                        InventoryOwnerType ownerType, String fallbackRouteId, String reserveMode,
                        boolean enabled, String configRef, Long expectedVersion) {}
    record InventoryCommand(String accountId, String skuId, InventoryOwnerType ownerType,
                            String ownerId, long deltaAvailable, String requestId) {}
    record CodeAssetCommand(String codeAssetId, String skuId, String codeHash, String cipherText,
                            String keyVersion, String expiresAt) {}
}
