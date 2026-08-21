package com.lrj.benefit.application.port.out;

import com.lrj.benefit.domain.model.AwardOrder;

import java.util.Optional;

public interface AwardRepository {
    boolean insert(AwardOrder order);
    Optional<AwardOrder> findByOrderNo(String tenantId, String orderNo);
    Optional<AwardOrder> findBySource(String tenantId, String sourceSystem, String sourceRequestId);
    Optional<AwardOrder> findByItemNo(String tenantId, String itemNo);
    boolean updateExpectedVersion(AwardOrder order, long expectedVersion);
}
