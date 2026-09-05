ALTER TABLE bc_benefit_sku ADD COLUMN validity_type VARCHAR(24) NOT NULL DEFAULT 'RELATIVE';
ALTER TABLE bc_benefit_sku ADD COLUMN valid_from TIMESTAMP(3);
ALTER TABLE bc_benefit_sku ADD COLUMN valid_to TIMESTAMP(3);
ALTER TABLE bc_benefit_sku ADD COLUMN relative_days INT;
ALTER TABLE bc_benefit_sku ADD COLUMN usable_weekdays VARCHAR(32);
ALTER TABLE bc_benefit_sku ADD COLUMN daily_quota BIGINT;
ALTER TABLE bc_benefit_sku ADD COLUMN user_limit_per_day BIGINT;
ALTER TABLE bc_benefit_sku ADD COLUMN user_limit_total BIGINT;
ALTER TABLE bc_benefit_sku ADD COLUMN equivalent_sku_id VARCHAR(128);

UPDATE bc_benefit_sku SET status='ACTIVE' WHERE status='ENABLED';
UPDATE bc_benefit_sku SET status='DRAFT' WHERE status='DISABLED';

CREATE TABLE bc_sku_template_version (
    tenant_id VARCHAR(64) NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    version BIGINT NOT NULL,
    benefit_type VARCHAR(32) NOT NULL,
    currency CHAR(3),
    face_value_minor BIGINT,
    status VARCHAR(24) NOT NULL,
    validity_type VARCHAR(24) NOT NULL,
    valid_from TIMESTAMP(3),
    valid_to TIMESTAMP(3),
    relative_days INT,
    usable_weekdays VARCHAR(32),
    daily_quota BIGINT,
    user_limit_per_day BIGINT,
    user_limit_total BIGINT,
    equivalent_sku_id VARCHAR(128),
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (tenant_id, sku_id, version)
);

INSERT INTO bc_sku_template_version
(tenant_id,sku_id,version,benefit_type,currency,face_value_minor,status,validity_type,
 valid_from,valid_to,relative_days,usable_weekdays,daily_quota,user_limit_per_day,
 user_limit_total,equivalent_sku_id,created_at)
SELECT tenant_id,sku_id,version,benefit_type,currency,face_value_minor,status,validity_type,
       valid_from,valid_to,relative_days,usable_weekdays,daily_quota,user_limit_per_day,
       user_limit_total,equivalent_sku_id,CURRENT_TIMESTAMP
FROM bc_benefit_sku;

ALTER TABLE bc_award_item ADD COLUMN sku_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE bc_award_item ADD COLUMN wallet_entry_id VARCHAR(64);

CREATE TABLE bc_wallet_entry (
    tenant_id VARCHAR(64) NOT NULL,
    entry_id VARCHAR(64) NOT NULL,
    subject_ref VARCHAR(256) NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    sku_version BIGINT NOT NULL,
    award_order_no VARCHAR(64) NOT NULL,
    item_no VARCHAR(64) NOT NULL,
    asset_type VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL,
    expires_at TIMESTAMP(3),
    face_value_minor BIGINT,
    currency CHAR(3),
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (tenant_id, entry_id),
    UNIQUE (tenant_id, item_no)
);

CREATE TABLE bc_wallet_balance_ledger (
    tenant_id VARCHAR(64) NOT NULL,
    ledger_no VARCHAR(64) NOT NULL,
    subject_ref VARCHAR(256) NOT NULL,
    wallet_entry_id VARCHAR(64) NOT NULL,
    operation_no VARCHAR(64) NOT NULL,
    entry_type VARCHAR(24) NOT NULL,
    delta_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (tenant_id, ledger_no),
    UNIQUE (tenant_id, operation_no, entry_type)
);

CREATE TABLE bc_user_limit_counter (
    tenant_id VARCHAR(64) NOT NULL,
    subject_ref VARCHAR(256) NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    period_type VARCHAR(16) NOT NULL,
    period_key VARCHAR(32) NOT NULL,
    reserved_count BIGINT NOT NULL DEFAULT 0,
    issued_count BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (tenant_id, subject_ref, sku_id, period_type, period_key)
);

CREATE TABLE bc_user_limit_reservation (
    tenant_id VARCHAR(64) NOT NULL,
    item_no VARCHAR(64) NOT NULL,
    period_type VARCHAR(16) NOT NULL,
    period_key VARCHAR(32) NOT NULL,
    subject_ref VARCHAR(256) NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    quantity BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (tenant_id, item_no, period_type)
);

CREATE TABLE bc_command_idempotency (
    tenant_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    operation_name VARCHAR(128) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (tenant_id, idempotency_key)
);

CREATE INDEX idx_bc_template_status ON bc_benefit_sku (tenant_id, status, sku_id);
CREATE INDEX idx_bc_wallet_subject ON bc_wallet_entry (tenant_id, subject_ref, entry_id);
CREATE INDEX idx_bc_wallet_subject_status ON bc_wallet_entry (tenant_id, subject_ref, status, entry_id);
CREATE INDEX idx_bc_wallet_subject_sku ON bc_wallet_entry (tenant_id, subject_ref, sku_id, entry_id);
CREATE INDEX idx_bc_wallet_created ON bc_wallet_entry (tenant_id, created_at, entry_id);
CREATE INDEX idx_bc_wallet_balance_subject
    ON bc_wallet_balance_ledger (tenant_id, subject_ref, currency, ledger_no);
CREATE INDEX idx_bc_limit_reservation_subject
    ON bc_user_limit_reservation (tenant_id, subject_ref, sku_id, period_type, period_key);
