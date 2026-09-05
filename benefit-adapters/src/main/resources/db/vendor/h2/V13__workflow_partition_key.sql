ALTER TABLE bc_outbox_event ALTER COLUMN aggregate_id VARCHAR(256);
COMMENT ON COLUMN bc_outbox_event.aggregate_id IS '聚合标识或 Kafka 分区键；workflow 使用 tenant|definition|businessKey';
