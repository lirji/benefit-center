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
                .locations("classpath:db/migration", "classpath:db/vendor/h2")
                .load();

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(14);
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();

        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.prepareStatement(
                     "select count(*) from information_schema.tables where table_schema = 'public' and table_name like 'bc_%'");
             var rows = statement.executeQuery()) {
            rows.next();
            assertThat(rows.getInt(1)).isEqualTo(22);
        }

        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.prepareStatement(
                     "select count(*) from information_schema.tables "
                             + "where table_schema = 'public' and table_name like 'bc_%' "
                             + "and remarks is not null and remarks <> ''");
             var rows = statement.executeQuery()) {
            rows.next();
            assertThat(rows.getInt(1)).isEqualTo(22);
        }

        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.prepareStatement(
                     "select count(*) from information_schema.columns "
                             + "where table_schema = 'public' and table_name in "
                             + "('bc_sku_template_version','bc_wallet_entry','bc_wallet_balance_ledger',"
                             + "'bc_user_limit_counter','bc_user_limit_reservation','bc_command_idempotency') "
                             + "and (remarks is null or remarks = '')");
             var rows = statement.executeQuery()) {
            rows.next();
            assertThat(rows.getInt(1)).as("Slice 1 新表每个字段必须有注释").isZero();
        }
    }

    @Test
    void walletCommandMigrationBackfillsExistingEntriesWithVersionZero() throws Exception {
        String url = "jdbc:h2:mem:benefit_wallet_v14;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        Flyway beforeWalletCommands = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration", "classpath:db/vendor/h2")
                .target("13")
                .load();
        beforeWalletCommands.migrate();
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.prepareStatement("""
                     INSERT INTO bc_wallet_entry
                     (tenant_id,entry_id,subject_ref,sku_id,sku_version,award_order_no,item_no,asset_type,
                      status,expires_at,face_value_minor,currency,created_at,updated_at)
                     VALUES ('T1','WE1','USER1','SKU1',7,'ORD1','ITEM1','COUPON','UNUSED',NULL,NULL,NULL,
                             CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                     """)) {
            statement.executeUpdate();
        }

        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration", "classpath:db/vendor/h2")
                .load()
                .migrate();
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.prepareStatement(
                     "SELECT version FROM bc_wallet_entry WHERE tenant_id='T1' AND entry_id='WE1'");
             var rows = statement.executeQuery()) {
            rows.next();
            assertThat(rows.getLong(1)).isZero();
        }
    }
}
