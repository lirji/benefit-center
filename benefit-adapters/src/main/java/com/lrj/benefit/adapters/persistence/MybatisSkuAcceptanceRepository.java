package com.lrj.benefit.adapters.persistence;

import com.lrj.benefit.application.port.out.SkuAcceptanceRepository;
import com.lrj.benefit.contract.BenefitType;
import com.lrj.benefit.domain.model.BenefitSku;
import com.lrj.benefit.domain.model.SkuTemplateStatus;
import com.lrj.benefit.domain.model.ValidityType;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** 版本门禁读取主库且必须在事务中；不能将短暂自动提交查询误称持锁。 */
public final class MybatisSkuAcceptanceRepository implements SkuAcceptanceRepository {
    private final SkuAcceptanceMapper mapper;
    /** Mapper与原JDBC使用同一DataSource及事务管理器。 */
    public MybatisSkuAcceptanceRepository(SkuAcceptanceMapper mapper) { this.mapper = mapper; }

    /** 兼容历史ENABLED/DISABLED状态，不放宽指定版本的精确相等要求。 */
    @Override public Optional<BenefitSku> lockCurrent(String tenantId, String skuId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("SKU acceptance lock requires a transaction");
        }
        var r = mapper.lockCurrent(tenantId, skuId);
        if (r == null) return Optional.empty();
        SkuTemplateStatus status = switch (r.status()) {
            case "ENABLED" -> SkuTemplateStatus.ACTIVE;
            case "DISABLED" -> SkuTemplateStatus.DRAFT;
            default -> SkuTemplateStatus.valueOf(r.status());
        };
        List<Integer> days = r.usableWeekdays() == null || r.usableWeekdays().isBlank() ? List.of()
                : Arrays.stream(r.usableWeekdays().split(",")).map(Integer::valueOf).toList();
        return Optional.of(new BenefitSku(r.tenantId(), r.skuId(), BenefitType.valueOf(r.benefitType()),
                r.faceValueMinor(), r.currency(), status, ValidityType.valueOf(r.validityType()),
                r.validFrom(), r.validTo(), r.relativeDays(), days, r.dailyQuota(), r.userLimitPerDay(),
                r.userLimitTotal(), r.equivalentSkuId(), r.version()));
    }
}
