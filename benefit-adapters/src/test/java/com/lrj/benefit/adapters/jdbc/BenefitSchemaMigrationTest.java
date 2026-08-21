package com.lrj.benefit.adapters.jdbc;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class BenefitSchemaMigrationTest {

    @Test
    void migratesCompleteSchemaOnFreshDatabase() throws Exception {
        String url = "jdbc:h2:mem:benefit_schema;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        Flyway flyway = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load();

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(8);
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();

        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.prepareStatement(
                     "select count(*) from information_schema.tables where table_schema = 'public' and table_name like 'bc_%'");
             var rows = statement.executeQuery()) {
            rows.next();
            assertThat(rows.getInt(1)).isEqualTo(16);
        }
    }
}
