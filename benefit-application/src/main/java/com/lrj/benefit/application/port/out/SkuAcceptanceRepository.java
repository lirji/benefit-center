package com.lrj.benefit.application.port.out;

import com.lrj.benefit.domain.model.BenefitSku;
import java.util.Optional;

/** 首次受理的主库模板锁端口；不能以缓存代替指定版本的受理前提。 */
public interface SkuAcceptanceRepository {
    /** 持有当前SKU共享锁至原受理事务提交，与管理员切版互斥。 */
    Optional<BenefitSku> lockCurrent(String tenantId, String skuId);
}
