ALTER TABLE bc_award_order ADD COLUMN routing_key VARCHAR(128) NOT NULL DEFAULT '__legacy__';
CREATE INDEX idx_bc_order_routing ON bc_award_order (tenant_id, routing_key, order_no);
