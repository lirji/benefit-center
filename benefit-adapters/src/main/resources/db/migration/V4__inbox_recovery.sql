ALTER TABLE bc_inbox_message ADD COLUMN lease_until TIMESTAMP(3);
CREATE INDEX idx_bc_inbox_recovery ON bc_inbox_message (status, lease_until, message_id);
