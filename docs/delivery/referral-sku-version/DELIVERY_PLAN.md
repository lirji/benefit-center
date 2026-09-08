# 裂变冻结SKU版本受理前置

沿用已批准裂变10-FINAL_PLAN §6.3/§9及用户持续实施授权，补权益中心已有受理路径的可选版本前提。现有AwardItem持久skuVersion、按来源查询和补偿均复用。

## 行为
每项新增可选expectedSkuVersion（非负，允许旧库version=0）。缺失时旧请求构造、缓存路径与请求hash完全兼容。指定时在原受理事务按SKU排序锁主库当前模板，要求版本精确一致及ACTIVE/有效期；冲突拒绝并回滚，不自动选择新版/历史版。原成功相同请求在SKU切版后仍按原账本重放；去掉或改变版本视为不同完整内容。

## 实现
新增最小MyBatis core/spring手动配置及唯一SkuAcceptanceMapper XML，共用现有DataSource/Spring事务，不迁移旧JDBC。应用新增Port，SQL不进Java。无需DDL，现有skuVersion列承载事实。新版本域仅当任一item指定expectedVersion才追加到旧canonical hash，包含排序itemId和nullable version以区分混合请求。

## 验收
AC01旧Java构造及JSON无字段/null和旧hash golden兼容。AC02指定版本在持锁主库读上校验，缓存陈旧无影响；错误版本不占用库存/用户额度/Outbox。AC03同键改变/删除版本冲突，原成功切版后重放。AC04多SKU固定锁序且最后时间复检；实际MySQL锁等待切版后拒绝。AC05映射XML同Spring事务、version=0及负数校验，原请求可继续。

## 验证与门禁
先纯合同/应用测试和独立审查，再一次隔离MySQL锁/回滚与HTTP专项；不重复权益全量、不触共享库或发真实渠道。后端接口变更，无前端工作。同步OpenAPI/README/交付证据，既有CI覆盖；无生产部署、删数据或自动迁移。
