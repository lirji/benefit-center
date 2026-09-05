package com.lrj.benefit.application.port.out;

import java.time.LocalDate;
import java.util.List;

/** 用户级限额账本端口，所有写入必须在发放订单本地事务内完成。 */
public interface UserLimitRepository {
    enum PeriodType { DAY, TOTAL }

    /**
     * 用数据库条件更新原子占额；任一维度超限时返回 false，由上层回滚整个受理事务。
     */
    boolean reserve(String tenantId, String subjectRef, String skuId, String itemNo,
                    long quantity, Long dailyLimit, Long totalLimit, LocalDate businessDate);

    /** 履约成功后把占额从 reserved 转为 issued。 */
    void markIssued(String tenantId, String itemNo);

    /** 明确未发出时释放 reserved；UNKNOWN 不调用，继续保留占额。 */
    void release(String tenantId, String itemNo);

    /** 冲正成功后释放 issued，并保留不可变的 reservation 历史状态。 */
    void reverse(String tenantId, String itemNo);

    long currentUsage(String tenantId, String subjectRef, String skuId,
                      PeriodType periodType, String periodKey);

    /** 查询某订单项关联的计数键，用于释放/冲正后的 L2 精确失效。 */
    List<CounterKey> counterKeysForItem(String tenantId, String itemNo);

    record CounterKey(String subjectRef, String skuId, PeriodType periodType, String periodKey) {}
}
