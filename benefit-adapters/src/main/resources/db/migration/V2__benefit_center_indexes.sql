CREATE INDEX idx_bc_item_due ON bc_award_item (tenant_id, status, retry_at, item_no);
CREATE INDEX idx_bc_operation_due ON bc_fulfillment_operation (tenant_id, status, lease_until, operation_no);
CREATE INDEX idx_bc_order_business ON bc_award_order (tenant_id, source_business_no, created_at);
CREATE INDEX idx_bc_outbox_due ON bc_outbox_event (tenant_id, status, next_attempt_at, event_id);
CREATE INDEX idx_bc_code_available ON bc_code_asset (tenant_id, sku_id, status, code_asset_id);
