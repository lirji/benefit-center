package com.lrj.benefit.application.port.in;

import com.lrj.benefit.domain.model.AwardOrder;

import java.util.Optional;

public interface QueryAwardOrderUseCase {
    Optional<AwardOrder> get(String tenantId, String awardOrderNo);
    Optional<AwardOrder> findBySource(String tenantId, String sourceSystem, String sourceRequestId);
}
