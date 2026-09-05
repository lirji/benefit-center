package com.lrj.benefit.application.port.out;

import com.lrj.benefit.domain.model.BenefitSku;

import java.util.Optional;

/** L1 模板缓存端口；缓存仅加速读取，数据库与不可变快照仍是真相源。 */
public interface SkuTemplateCache {
    Optional<BenefitSku> findCurrent(String tenantId, String skuId);

    /** 模板切版后删除当前世代指针；历史版本缓存保持不可变。 */
    void invalidateCurrent(String tenantId, String skuId);
}
