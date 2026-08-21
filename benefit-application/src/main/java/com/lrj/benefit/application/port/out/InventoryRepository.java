package com.lrj.benefit.application.port.out;

import com.lrj.benefit.domain.model.InventoryOwnerType;

import java.util.Set;

public interface InventoryRepository {
    boolean reserve(String tenantId, String accountId, long quantity, long expectedVersion);
    boolean commit(String tenantId, String accountId, long quantity, long expectedVersion);
    boolean release(String tenantId, String accountId, long quantity, long expectedVersion);
    boolean returnIssued(String tenantId, String accountId, long quantity, long expectedVersion);

    boolean reserveAvailable(String tenantId, String skuId, InventoryOwnerType ownerType, long quantity,
                             String itemNo, String operationNo);
    void commitReservations(String tenantId, String operationNo, Set<InventoryOwnerType> ownerTypes);
    void releaseReservations(String tenantId, String operationNo, Set<InventoryOwnerType> ownerTypes);
    void returnIssued(String tenantId, String skuId, long quantity, Set<InventoryOwnerType> ownerTypes,
                      String itemNo, String operationNo);
}
