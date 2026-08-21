CREATE TABLE bc_tenant_config (
    tenant_id VARCHAR(64) PRIMARY KEY,
    home_cell VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE bc_benefit_sku (
    tenant_id VARCHAR(64) NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    benefit_type VARCHAR(32) NOT NULL,
    currency CHAR(3),
    face_value_minor BIGINT,
    status VARCHAR(24) NOT NULL,
    metadata_json TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, sku_id)
);

CREATE TABLE bc_channel_route (
    tenant_id VARCHAR(64) NOT NULL,
    route_id VARCHAR(128) NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    priority_no INT NOT NULL,
    channel_code VARCHAR(64) NOT NULL,
    owner_type VARCHAR(32) NOT NULL,
    fallback_route_id VARCHAR(128),
    reserve_mode VARCHAR(24) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    config_ref VARCHAR(256),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, route_id),
    UNIQUE (tenant_id, sku_id, priority_no)
);

CREATE TABLE bc_inventory_account (
    tenant_id VARCHAR(64) NOT NULL,
    account_id VARCHAR(128) NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    owner_type VARCHAR(32) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    available BIGINT NOT NULL DEFAULT 0,
    reserved BIGINT NOT NULL DEFAULT 0,
    issued BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    snapshot_at TIMESTAMP(3),
    PRIMARY KEY (tenant_id, account_id),
    UNIQUE (tenant_id, sku_id, owner_type, owner_id),
    CHECK (available >= 0 AND reserved >= 0 AND issued >= 0)
);

CREATE TABLE bc_award_order (
    tenant_id VARCHAR(64) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    source_system VARCHAR(64) NOT NULL,
    source_request_id VARCHAR(128) NOT NULL,
    source_business_no VARCHAR(128),
    recipient_ref VARCHAR(256) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    partial_policy VARCHAR(24) NOT NULL,
    status VARCHAR(32) NOT NULL,
    home_cell VARCHAR(32) NOT NULL,
    trace_id VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (tenant_id, order_no),
    UNIQUE (tenant_id, source_system, source_request_id)
);

CREATE TABLE bc_award_item (
    tenant_id VARCHAR(64) NOT NULL,
    item_no VARCHAR(64) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    client_item_id VARCHAR(128) NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    benefit_type VARCHAR(32) NOT NULL,
    amount_minor BIGINT,
    currency CHAR(3),
    quantity BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    route_id VARCHAR(128),
    failure_code VARCHAR(64),
    retry_at TIMESTAMP(3),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (tenant_id, item_no),
    UNIQUE (tenant_id, order_no, client_item_id)
);

CREATE TABLE bc_fulfillment_operation (
    tenant_id VARCHAR(64) NOT NULL,
    operation_no VARCHAR(64) NOT NULL,
    item_no VARCHAR(64) NOT NULL,
    operation_type VARCHAR(24) NOT NULL,
    remediation_no VARCHAR(64),
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(192) NOT NULL,
    lease_owner VARCHAR(128),
    lease_until TIMESTAMP(3),
    attempt_count INT NOT NULL DEFAULT 0,
    unknown_since TIMESTAMP(3),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (tenant_id, operation_no),
    UNIQUE (tenant_id, idempotency_key)
);

CREATE TABLE bc_fulfillment_attempt (
    tenant_id VARCHAR(64) NOT NULL,
    attempt_no VARCHAR(64) NOT NULL,
    operation_no VARCHAR(64) NOT NULL,
    sequence_no INT NOT NULL,
    channel_code VARCHAR(64) NOT NULL,
    route_id VARCHAR(128) NOT NULL,
    channel_request_no VARCHAR(128) NOT NULL,
    provider_reference VARCHAR(256),
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(64),
    request_redacted TEXT,
    response_redacted TEXT,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (tenant_id, attempt_no),
    UNIQUE (tenant_id, operation_no, sequence_no),
    UNIQUE (tenant_id, channel_code, channel_request_no)
);

CREATE TABLE bc_inventory_ledger (
    tenant_id VARCHAR(64) NOT NULL,
    ledger_no VARCHAR(64) NOT NULL,
    account_id VARCHAR(128) NOT NULL,
    item_no VARCHAR(64),
    operation_no VARCHAR(64) NOT NULL,
    entry_type VARCHAR(32) NOT NULL,
    delta_available BIGINT NOT NULL,
    delta_reserved BIGINT NOT NULL,
    delta_issued BIGINT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (tenant_id, ledger_no),
    UNIQUE (tenant_id, account_id, operation_no, entry_type)
);

CREATE TABLE bc_award_ledger_entry (
    tenant_id VARCHAR(64) NOT NULL,
    ledger_no VARCHAR(64) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    item_no VARCHAR(64) NOT NULL,
    operation_no VARCHAR(64) NOT NULL,
    entry_type VARCHAR(32) NOT NULL,
    amount_minor BIGINT,
    quantity_signed BIGINT NOT NULL,
    currency CHAR(3),
    owner_type VARCHAR(32) NOT NULL,
    channel_code VARCHAR(64),
    provider_reference VARCHAR(256),
    biz_time TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (tenant_id, ledger_no),
    UNIQUE (tenant_id, operation_no, entry_type)
);

CREATE TABLE bc_code_asset (
    tenant_id VARCHAR(64) NOT NULL,
    code_asset_id VARCHAR(64) NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    code_hash CHAR(64) NOT NULL,
    cipher_text TEXT NOT NULL,
    key_version VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    reserved_item_no VARCHAR(64),
    expires_at TIMESTAMP(3),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, code_asset_id),
    UNIQUE (tenant_id, sku_id, code_hash)
);

CREATE TABLE bc_physical_fulfillment (
    tenant_id VARCHAR(64) NOT NULL,
    item_no VARCHAR(64) NOT NULL,
    address_ref VARCHAR(256) NOT NULL,
    fulfillment_order_ref VARCHAR(256),
    shipment_ref VARCHAR(256),
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, item_no)
);

CREATE TABLE bc_remediation_order (
    tenant_id VARCHAR(64) NOT NULL,
    remediation_no VARCHAR(64) NOT NULL,
    source_system VARCHAR(64) NOT NULL,
    external_command_id VARCHAR(128) NOT NULL,
    item_no VARCHAR(64) NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    original_operation_no VARCHAR(64),
    reason VARCHAR(512),
    approval_ref VARCHAR(256),
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (tenant_id, remediation_no),
    UNIQUE (tenant_id, source_system, external_command_id)
);

CREATE TABLE bc_channel_callback (
    tenant_id VARCHAR(64) NOT NULL,
    channel_code VARCHAR(64) NOT NULL,
    callback_event_id VARCHAR(128) NOT NULL,
    channel_request_no VARCHAR(128) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    received_at TIMESTAMP(3) NOT NULL,
    processed_at TIMESTAMP(3),
    PRIMARY KEY (tenant_id, channel_code, callback_event_id)
);

CREATE TABLE bc_outbox_event (
    tenant_id VARCHAR(64) NOT NULL,
    event_id VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    schema_version VARCHAR(16) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(24) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(3),
    published_at TIMESTAMP(3),
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (tenant_id, event_id)
);

CREATE TABLE bc_inbox_message (
    tenant_id VARCHAR(64) NOT NULL,
    consumer_group VARCHAR(128) NOT NULL,
    message_id VARCHAR(128) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    received_at TIMESTAMP(3) NOT NULL,
    processed_at TIMESTAMP(3),
    PRIMARY KEY (tenant_id, consumer_group, message_id)
);
