ALTER TABLE bc_outbox_event
    MODIFY aggregate_id VARCHAR(256) NOT NULL COMMENT '聚合标识或 Kafka 分区键；workflow 使用 tenant|definition|businessKey';
