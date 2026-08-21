ALTER TABLE bc_fulfillment_operation ADD COLUMN next_attempt_at TIMESTAMP(3);
CREATE INDEX idx_bc_operation_backoff ON bc_fulfillment_operation (tenant_id, status, next_attempt_at, operation_no);
