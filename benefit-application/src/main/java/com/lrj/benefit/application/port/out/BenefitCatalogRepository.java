package com.lrj.benefit.application.port.out;

import com.lrj.benefit.domain.model.BenefitSku;
import com.lrj.benefit.domain.model.ChannelRoute;

import java.util.List;
import java.util.Optional;

public interface BenefitCatalogRepository {
    Optional<BenefitSku> findSku(String tenantId, String skuId);
    List<ChannelRoute> routes(String tenantId, String skuId);
}
