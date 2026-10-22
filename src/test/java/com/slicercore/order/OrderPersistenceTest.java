package com.slicercore.order;

import com.slicercore.account.Account;
import com.slicercore.account.AccountRepository;
import com.slicercore.account.AccountSession;
import com.slicercore.account.AccountSessionRepository;
import com.slicercore.support.TestOrders;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.slicercore.support.TestOrders.ANNA;
import static com.slicercore.support.TestOrders.BLACK;
import static com.slicercore.support.TestOrders.RED;
import static com.slicercore.support.TestOrders.SETTINGS;
import static com.slicercore.support.TestOrders.quote;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots the whole application against a throwaway PostgreSQL: Flyway applies every migration
 * up to the customer accounts, Hibernate validates the entities against the migrated schema,
 * an order with two items round-trips with exact money, and the account's order query does
 * what the account page promises. Skipped automatically where Docker is not
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

    @Autowired
    AccountRepository accounts;

    @Autowired
    AccountSessionRepository sessions;

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
        assertThat(reloaded.getTotals().itemsTotal()).isEqualByComparingTo("10.10");
        assertThat(reloaded.getTotals().setupFee()).isEqualByComparingTo("5.00");
        assertThat(reloaded.getTotals().total()).isEqualByComparingTo("24.10");
        assertThat(reloaded.getTotals().total().scale()).isEqualTo(2);
        assertThat(reloaded.getTotals().vatRate()).isEqualByComparingTo("0");

        assertThat(reloaded.getItems()).extracting(OrderItem::getPosition).containsExactly(1, 2);
        OrderItem first = reloaded.getItems().get(0);
        assertThat(first.getOriginalFileName()).isEqualTo("benchy.stl");
        assertThat(first.getFileKey()).doesNotContain("benchy");
        assertThat(first.getColor()).isEqualTo(RED);
        assertThat(first.getLineTotal()).isEqualByComparingTo("7.10");
        assertThat(first.getFilamentGrams()).isEqualByComparingTo("23.2");
        OrderItem second = reloaded.getItems().get(1);
        assertThat(second.getFilament()).isEqualTo("PETG");
        assertThat(second.getQuality()).isEqualTo("high");
        assertThat(second.getLineTotal()).isEqualByComparingTo("3.00");

        assertThat(orders.nextOrderNumber()).isGreaterThan(Long.parseLong(number.substring(4)));
    }

    @Test
    void accountOrdersMatchTheEmailCaseInsensitivelySkipErasedOnesAndSurviveTheAccount() {
        Instant now = Instant.parse("2026-10-21T10:00:00Z");
        Account account = accounts.saveAndFlush(Account.create("konto@example.com", now));
        Order.Customer mixedCase = new Order.Customer("Kim Konto", "Konto@Example.com", "Weg 2", "3.00", "Winterthur", "CH");
        Order.Customer friend = new Order.Customer("Kim Konto", "friend@example.com", "Weg 2", "3.00", "Winterthur", "CH");
        Order byEmail = TestOrders.order("POD-" + orders.nextOrderNumber(), SETTINGS, mixedCase, quote("a.stl", RED, 1, 3.0));
        Order byAccount = TestOrders.order("POD-" + orders.nextOrderNumber(), SETTINGS, friend, quote("b.stl", RED, 1, 3.0));
        byAccount.linkAccount(account.getId());
        Order erased = TestOrders.order("POD-" + orders.nextOrderNumber(), SETTINGS, mixedCase, quote("c.stl", RED, 1, 3.0));
        erased.linkAccount(account.getId());
        erased.eraseCustomerData(now);
        orders.saveAllAndFlush(List.of(byEmail, byAccount, erased));
        sessions.saveAndFlush(AccountSession.open(account, "f".repeat(64), now, Duration.ofDays(30)));

        assertThat(orders.findCustomerOrders("konto@example.com", account.getId()))
                .extracting(Order::getOrderNumber)
                .containsExactlyInAnyOrder(byEmail.getOrderNumber(), byAccount.getOrderNumber());
        assertThat(orders.findFirstByCustomerEmailIgnoreCaseAndErasedAtIsNullOrderByCreatedAtDesc("KONTO@EXAMPLE.COM"))
                .map(Order::getOrderNumber).contains(byEmail.getOrderNumber());
        assertThat(sessions.findByTokenHash("f".repeat(64))).map(s -> s.getAccount().getEmail()).contains("konto@example.com");

        // Deleting the account cascades to its sessions and only unlinks the orders.
        accounts.delete(account);
        accounts.flush();
        assertThat(sessions.findByTokenHash("f".repeat(64))).isEmpty();
        Order kept = orders.findByOrderNumber(byAccount.getOrderNumber()).orElseThrow();
        assertThat(kept.getAccountId()).isNull();
        assertThat(kept.getCustomerEmail()).isEqualTo("friend@example.com");
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
