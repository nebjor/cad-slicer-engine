package com.slicercore.order;

import com.slicercore.model.BoundingBox;
import com.slicercore.model.ColorOption;
import com.slicercore.model.QuoteResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots the whole application against a throwaway PostgreSQL: Flyway applies V1 and V2,
 * Hibernate validates the entity against the migrated schema, and an order round-trips with
 * exact money. Skipped automatically where Docker is not available (the local backend
 * container); it runs in CI.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class OrderPersistenceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    OrderRepository orders;

    @Test
    void migratesAndRoundTripsAnOrderWithExactMoney() {
        OrderProperties settings = new OrderProperties(new BigDecimal("9.00"), false, new BigDecimal("0.081"),
                14, 90, "unused", Set.of("CH"));
        QuoteResponse quote = new QuoteResponse("benchy.stl", "PLA", new ColorOption("red", "Red", "#d62828"),
                "medium", 2, "Bambu Lab A1 0.4 nozzle", 0.2, 1, 2239, 4478, 7761.2, 11.6, 23.2,
                new BoundingBox(60, 31, 48), 225154, 5.0, 0.8, 5.0, 3.55, 12.1, "CHF", 3);
        Instant placedAt = Instant.parse("2026-10-19T09:00:00Z");
        String number = "POD-" + orders.nextOrderNumber();

        Order order = Order.create(number, placedAt, settings.quoteValidityDays(),
                new Order.Customer("Anna Muster", "anna@example.com", "Bahnhofstrasse 1", "8001", "Zürich", "CH"),
                quote, new OrderFileStorage.StoredFile("123e4567-e89b-12d3-a456-426614174000.stl", "a".repeat(64), 11_257_784),
                OrderTotals.from(quote, settings));
        orders.saveAndFlush(order);

        Order reloaded = orders.findByOrderNumber(number).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.NEW);
        assertThat(reloaded.getCreatedAt()).isEqualTo(placedAt);
        assertThat(reloaded.getTotals().total()).isEqualByComparingTo("21.10");
        assertThat(reloaded.getTotals().total().scale()).isEqualTo(2);
        assertThat(reloaded.getTotals().vatRate()).isEqualByComparingTo("0");
        assertThat(reloaded.getTotalFilamentGrams()).isEqualByComparingTo("23.2");
        assertThat(reloaded.getFileKey()).doesNotContain("benchy");

        assertThat(orders.nextOrderNumber()).isGreaterThan(Long.parseLong(number.substring(4)));
    }

    @Test
    void illegalTransitionsAreRejectedBeforeTheyReachTheDatabase() {
        QuoteResponse quote = new QuoteResponse("x.stl", "PLA", new ColorOption("black", "Black", "#1f1f1f"),
                "medium", 1, "A1", 0.2, 1, 100, 100, 10, 1, 1, BoundingBox.EMPTY, 4, 1, 1, 5, 3, 8, "CHF", 3);
        OrderProperties settings = new OrderProperties(BigDecimal.ONE, false, BigDecimal.ZERO, 14, 90, "x", Set.of("CH"));
        Order order = Order.create("POD-TEST", Instant.now(), 14,
                new Order.Customer("A", "a@example.com", "S 1", "8000", "Z", "CH"), quote,
                new OrderFileStorage.StoredFile("123e4567-e89b-12d3-a456-426614174001.stl", "b".repeat(64), 1),
                OrderTotals.from(quote, settings));

        assertThatThrownBy(() -> order.transitionTo(OrderStatus.SHIPPED, Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NEW")
                .hasMessageContaining("SHIPPED");
        assertThatThrownBy(() -> order.scheduleDeletion(90)).isInstanceOf(IllegalStateException.class);

        order.transitionTo(OrderStatus.PAID, Instant.now());
        order.transitionTo(OrderStatus.IN_REVIEW, Instant.now());
        order.transitionTo(OrderStatus.PRINTING, Instant.now());
        order.transitionTo(OrderStatus.SHIPPED, Instant.parse("2026-11-01T10:00:00Z"));
        order.scheduleDeletion(90);
        assertThat(order.getDeleteAfter()).isEqualTo(Instant.parse("2027-01-30T10:00:00Z"));
    }
}
