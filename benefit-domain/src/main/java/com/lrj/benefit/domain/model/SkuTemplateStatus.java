package com.lrj.benefit.domain.model;

/** SKU 模板生命周期。 */
public enum SkuTemplateStatus {
    DRAFT,
    PENDING_APPROVAL,
    ACTIVE,
    PAUSED,
    RETIRED;

    /**
     * 校验显式状态迁移，禁止跳过审批或复活已退役模板。
     */
    public boolean canTransitionTo(SkuTemplateStatus target) {
        if (this == target) return true;
        return switch (this) {
            case DRAFT -> target == PENDING_APPROVAL;
            case PENDING_APPROVAL -> target == ACTIVE || target == DRAFT;
            case ACTIVE -> target == PAUSED;
            case PAUSED -> target == ACTIVE || target == RETIRED;
            case RETIRED -> false;
        };
    }
}
