ALTER TABLE bc_inventory_ledger ADD COLUMN operator_ref VARCHAR(128);
ALTER TABLE bc_inventory_ledger ADD COLUMN admin_request_id VARCHAR(128);
CREATE UNIQUE INDEX uk_bc_inventory_admin_request
  ON bc_inventory_ledger (tenant_id, admin_request_id);
