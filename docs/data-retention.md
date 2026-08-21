# 数据保留与隐私

以下为实施基线，正式期限需由法务、财务和安全审批后替换：

- award order/item/operation/attempt：在线 180 天，其后按 tenant 和月份归档；未终结、UNKNOWN、争议单不归档。
- award/inventory ledger：按财务与审计要求长期保留，默认不少于 7 年；只追加，不物理改写。
- inbox/outbox：成功记录在线 90 天后归档；FAILED/DEAD 与关联业务未闭环前不得清理。
- callback 原文：只保留必要的脱敏响应和哈希，默认 90 天。
- 兑换码：库内只存 hash + KMS 密文 + keyVersion；日志、指标、普通查询均不返回明文。
- 实物：只保存 `addressRef`，地址明文由地址域持有。

归档任务必须按 tenant 与主键水位分页，先校验 ledger/operation/outbox 引用完整性，再复制、核对行数和校验和，最后由审批任务删除在线副本。任何删除均需可恢复备份与演练记录。
