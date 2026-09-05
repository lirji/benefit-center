ALTER TABLE bc_wallet_entry
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '资产状态并发控制版本' AFTER status;

ALTER TABLE bc_command_idempotency
    ADD COLUMN result_payload TEXT NULL COMMENT '首次同步命令结果JSON，用于精确重放';

ALTER TABLE bc_wallet_balance_ledger
    MODIFY entry_type VARCHAR(24) NOT NULL COMMENT '余额流水类型：ISSUE、REVERSAL、REDEEM或REFUND';
