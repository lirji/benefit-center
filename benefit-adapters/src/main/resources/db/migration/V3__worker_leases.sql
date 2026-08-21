ALTER TABLE bc_outbox_event ADD COLUMN lease_owner VARCHAR(128);
ALTER TABLE bc_outbox_event ADD COLUMN lease_until TIMESTAMP(3);
ALTER TABLE bc_outbox_event ADD COLUMN last_error VARCHAR(512);

CREATE INDEX idx_bc_outbox_lease ON bc_outbox_event (status, lease_until, event_id);
CREATE UNIQUE INDEX uk_bc_code_item ON bc_code_asset (tenant_id, reserved_item_no);
