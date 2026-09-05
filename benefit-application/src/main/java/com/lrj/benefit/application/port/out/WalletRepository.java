package com.lrj.benefit.application.port.out;

import com.lrj.benefit.domain.model.WalletEntry;
import com.lrj.benefit.domain.model.WalletEntryStatus;

import java.time.Instant;
import java.util.Optional;

/** WalletEntry 聚合持久化端口。 */
public interface WalletRepository {
    /** 同 item 重放返回首次入账记录，禁止创建第二份用户资产。 */
    WalletEntry createIfAbsent(WalletEntry entry, String operationNo);

    /** 冲正资产并为现金追加反向分录；同 operationNo 重放保持幂等。 */
    Optional<WalletEntry> reverseByItem(String tenantId, String itemNo, String operationNo);

    Optional<WalletEntry> findByItem(String tenantId, String itemNo);

    /** 按租户和资产 ID 点查命令目标。 */
    Optional<WalletEntry> findById(String tenantId, String entryId);

    /** 使用当前状态与版本双条件执行状态 CAS，成功后返回 version+1 的条目。 */
    Optional<WalletEntry> compareAndSetStatus(String tenantId, String entryId,
                                              WalletEntryStatus currentStatus, long currentVersion,
                                              WalletEntryStatus targetStatus, Instant updatedAt);

    /** 仅供钱包 redeem/refund 追加现金分录；不得委托履约冲正 reverseByItem。 */
    void appendCommandBalance(WalletEntry entry, String operationNo, String entryType, long deltaMinor);
}
