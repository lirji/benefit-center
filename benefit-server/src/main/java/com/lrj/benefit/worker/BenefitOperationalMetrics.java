package com.lrj.benefit.worker;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Low-cardinality operational gauges. Queries are bounded count/age lookups over indexed state columns. */
@Component
public class BenefitOperationalMetrics {
    private final JdbcTemplate jdbc;

    public BenefitOperationalMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        gauge(registry, "benefit.outbox.pending", "Pending or retryable outbox events",
                "SELECT COUNT(*) FROM bc_outbox_event WHERE status IN ('PENDING','FAILED','SENDING')");
        gauge(registry, "benefit.outbox.dead", "Dead outbox events",
                "SELECT COUNT(*) FROM bc_outbox_event WHERE status='DEAD'");
        gauge(registry, "benefit.operation.unknown", "Operations awaiting authoritative query",
                "SELECT COUNT(*) FROM bc_fulfillment_operation WHERE status IN ('UNKNOWN','QUERYING')");
        gauge(registry, "benefit.order.partial", "Orders currently partially successful",
                "SELECT COUNT(*) FROM bc_award_order WHERE status='PARTIAL_SUCCEEDED'");
        gauge(registry, "benefit.remediation.pending", "Remediations awaiting or undergoing execution",
                "SELECT COUNT(*) FROM bc_remediation_order WHERE status IN ('PROPOSED','APPROVED','DISPATCHING','UNKNOWN')");
        gauge(registry, "benefit.inventory.invalid", "Inventory rows violating non-negative balances",
                "SELECT COUNT(*) FROM bc_inventory_account WHERE available<0 OR reserved<0 OR issued<0");
    }

    private void gauge(MeterRegistry registry, String name, String description, String sql) {
        Gauge.builder(name, this, ignored -> count(sql)).description(description).register(registry);
    }

    private double count(String sql) {
        try {
            Long value = jdbc.queryForObject(sql, Long.class);
            return value == null ? Double.NaN : value.doubleValue();
        } catch (RuntimeException unavailable) {
            return Double.NaN;
        }
    }
}
