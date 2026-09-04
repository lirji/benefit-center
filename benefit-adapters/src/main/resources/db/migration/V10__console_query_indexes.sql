CREATE INDEX idx_bc_order_attention ON bc_award_order (tenant_id, status, updated_at);
CREATE INDEX idx_bc_remediation_status ON bc_remediation_order (tenant_id, status, updated_at);
