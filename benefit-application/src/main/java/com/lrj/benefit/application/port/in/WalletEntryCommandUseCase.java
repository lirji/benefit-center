package com.lrj.benefit.application.port.in;

import com.lrj.benefit.contract.WalletEntryCommand;
import com.lrj.benefit.contract.WalletEntryCommandAcceptance;

/** 同步执行券包 freeze/redeem/refund，并返回事务已提交的目标状态。 */
public interface WalletEntryCommandUseCase {
    WalletEntryCommandAcceptance execute(String tenantId, String entryId, String idempotencyKey,
                                         Action action, WalletEntryCommand command);

    enum Action {
        FREEZE,
        REDEEM,
        REFUND
    }
}
