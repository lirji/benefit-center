package com.lrj.benefit.application.port.out;

import com.lrj.benefit.domain.model.BenefitSku;
import com.lrj.benefit.domain.model.ChannelRoute;

import java.util.List;
import java.util.Optional;

public interface BenefitCatalogRepository {
    Optional<BenefitSku> findSku(String tenantId, String skuId);
    /** 按不可变世代读取历史模板；已发资产禁止回看当前版本。 */
    Optional<BenefitSku> findSkuVersion(String tenantId, String skuId, long version);
    List<ChannelRoute> routes(String tenantId, String skuId);
}
