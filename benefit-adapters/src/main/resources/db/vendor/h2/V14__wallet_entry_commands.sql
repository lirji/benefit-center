ALTER TABLE bc_wallet_entry
    ADD COLUMN version BIGINT DEFAULT 0 NOT NULL;
COMMENT ON COLUMN bc_wallet_entry.version IS '资产状态并发控制版本';

ALTER TABLE bc_command_idempotency
    ADD COLUMN result_payload CLOB;
COMMENT ON COLUMN bc_command_idempotency.result_payload IS '首次同步命令结果JSON，用于精确重放';

COMMENT ON COLUMN bc_wallet_balance_ledger.entry_type IS '余额流水类型：ISSUE、REVERSAL、REDEEM或REFUND';
