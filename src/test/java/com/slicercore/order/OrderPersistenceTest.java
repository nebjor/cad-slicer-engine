package com.slicercore.order;

import com.slicercore.support.TestOrders;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static com.slicercore.support.TestOrders.ANNA;
import static com.slicercore.support.TestOrders.BLACK;
import static com.slicercore.support.TestOrders.RED;
import static com.slicercore.support.TestOrders.SETTINGS;
import static com.slicercore.support.TestOrders.quote;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots the whole application against a throwaway PostgreSQL: Flyway applies every migration
 * up to the order items, Hibernate validates the entities against the migrated schema, and an
 * order with two items round-trips with exact money. Skipped automatically where Docker is not
 * available (the local backend container); it runs in CI.
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
    void migratesAndRoundTripsAnOrderWithItsItemsAndExactMoney() {
        String number = "POD-" + orders.nextOrderNumber();
        Order order = TestOrders.order(number, SETTINGS, ANNA,
                quote("benchy.stl", RED, 2, 3.55),
                quote("halter.3mf", "PETG", BLACK, "high", 1, 3.0));
        order.attachPayment("mock", "mock_" + number, "http://localhost:4200/pay/mock?session=mock_" + number);
        orders.saveAndFlush(order);

        Order reloaded = orders.findByOrderNumber(number).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.NEW);
        assertThat(orders.findByPaymentSessionId("mock_" + number)).isPresent();
        assertThat(reloaded.getPaymentUrl()).contains("/pay/mock");
        assertThat(reloaded.getCreatedAt()).isEqualTo(TestOrders.PLACED_AT);
        assertThat(reloaded.getTotals().itemsTotal()).isEqualByComparingTo("20.10");
        assertThat(reloaded.getTotals().total()).isEqualByComparingTo("29.10");
        assertThat(reloaded.getTotals().total().scale()).isEqualTo(2);
        assertThat(reloaded.getTotals().vatRate()).isEqualByComparingTo("0");

        assertThat(reloaded.getItems()).extracting(OrderItem::getPosition).containsExactly(1, 2);
        OrderItem first = reloaded.getItems().get(0);
        assertThat(first.getOriginalFileName()).isEqualTo("benchy.stl");
        assertThat(first.getFileKey()).doesNotContain("benchy");
        assertThat(first.getColor()).isEqualTo(RED);
        assertThat(first.getLineTotal()).isEqualByComparingTo("12.10");
        assertThat(first.getFilamentGrams()).isEqualByComparingTo("23.2");
        OrderItem second = reloaded.getItems().get(1);
        assertThat(second.getFilament()).isEqualTo("PETG");
        assertThat(second.getQuality()).isEqualTo("high");
        assertThat(second.getLineTotal()).isEqualByComparingTo("8.00");

        assertThat(orders.nextOrderNumber()).isGreaterThan(Long.parseLong(number.substring(4)));
    }

    @Test
    void illegalTransitionsAreRejectedBeforeTheyReachTheDatabase() {
        Order order = TestOrders.benchyOrder();

        assertThatThrownBy(() -> order.transitionTo(OrderStatus.SHIPPED, Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NEW")
                .hasMessageContaining("SHIPPED");
        assertThatThrownBy(() -> order.scheduleDeletion(90)).isInstanceOf(IllegalStateException.class);

        order.transitionTo(OrderStatus.PAID, Instant.now());
        order.transitionTo(OrderStatus.IN_REVIEW, Instant.now());
        order.transitionTo(OrderStatus.APPROVED, Instant.now());
        order.transitionTo(OrderStatus.PRINTING, Instant.now());
        order.transitionTo(OrderStatus.SHIPPED, Instant.parse("2026-11-01T10:00:00Z"));
        order.scheduleDeletion(90);
        assertThat(order.getDeleteAfter()).isEqualTo(Instant.parse("2027-01-30T10:00:00Z"));
    }
}
