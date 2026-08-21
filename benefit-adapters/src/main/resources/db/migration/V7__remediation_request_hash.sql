ALTER TABLE bc_remediation_order ADD COLUMN request_hash CHAR(64);
CREATE INDEX idx_bc_remediation_command_hash
  ON bc_remediation_order (tenant_id, source_system, external_command_id, request_hash);
