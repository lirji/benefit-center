package com.lrj.benefit.contract;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 券包状态命令；expectedVersion 可选，未传时服务端仍以读取版本执行 CAS。 */
public record WalletEntryCommand(
        @Size(max = 512) String reason,
        @Size(max = 256) String merchantRef,
        @PositiveOrZero Long expectedVersion) {
}
