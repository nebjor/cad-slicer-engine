package com.slicercore.order;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V10 moves the setup fee from the items to the order (ADR 0009). An order placed under the
 * per-item fee, two items with CHF 5.00 each, must come out of the migration with the same
 * total: the order carries the fee its items carried, and its lines no longer contain it.
 */
@Testcontainers(disabledWithoutDocker = true)
class SetupFeeMigrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void anOldOrderKeepsItsTotalWhileTheFeesMoveToTheOrder() throws SQLException {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").target("9").load().migrate();

        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement sql = connection.createStatement()) {
            // 2 x 3.55 + 5.00 = 12.10 and 1 x 3.55 + 5.00 = 8.55: items 20.65, shipping 9.00, total 29.65.
            sql.execute("""
                    INSERT INTO orders (id, order_number, status, created_at, quote_expires_at, customer_name,
                        customer_email, street, postal_code, city, country, currency, items_total, shipping,
                        vat_rate, vat_amount, total)
                    VALUES ('00000000-0000-0000-0000-000000000001', 'POD-1', 'PAID', now(), now(), 'Anna',
                        'anna@example.com', 'Bahnhofstrasse 1', '8001', 'Zuerich', 'CH', 'CHF', 20.65, 9.00,
                        0, 0, 29.65)""");
            insertItem(sql, 1, 2, "12.10");
            insertItem(sql, 2, 1, "8.55");

            Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").load().migrate();

            try (ResultSet order = sql.executeQuery("SELECT setup_fee, items_total, shipping, total FROM orders")) {
                assertThat(order.next()).isTrue();
                assertThat(order.getBigDecimal("setup_fee")).isEqualByComparingTo("10.00");
                assertThat(order.getBigDecimal("items_total")).isEqualByComparingTo("10.65");
                assertThat(order.getBigDecimal("shipping")).isEqualByComparingTo("9.00");
                assertThat(order.getBigDecimal("total")).isEqualByComparingTo("29.65");
            }
            try (ResultSet lines = sql.executeQuery("SELECT line_total_chf FROM order_items ORDER BY position")) {
                assertThat(lines.next()).isTrue();
                assertThat(lines.getBigDecimal(1)).isEqualByComparingTo("7.10");
                assertThat(lines.next()).isTrue();
                assertThat(lines.getBigDecimal(1)).isEqualByComparingTo("3.55");
            }
        }
    }

    private static void insertItem(Statement sql, int position, int quantity, String lineTotal) throws SQLException {
        sql.execute("""
                INSERT INTO order_items (id, order_id, position, original_file_name, file_key, file_sha256,
                    file_size_bytes, filament, color_code, color_label, quality, quantity, machine_profile,
                    layer_height_mm, plate_count, print_time_seconds, filament_grams, setup_fee_chf,
                    unit_price_chf, line_total_chf)
                VALUES (gen_random_uuid(), '00000000-0000-0000-0000-000000000001', %d, 'a.stl', 'k%d', 'h', 1,
                    'PLA', 'red', 'Red', 'medium', %d, 'A1', 0.20, 1, 100, 10.0, 5.00, 3.55, %s)"""
                .formatted(position, position, quantity, lineTotal));
    }
}
