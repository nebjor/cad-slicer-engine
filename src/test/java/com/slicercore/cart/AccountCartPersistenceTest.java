package com.slicercore.cart;

import com.slicercore.account.Account;
import com.slicercore.account.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the application against a throwaway PostgreSQL: V11 migrates, Hibernate validates the
 * entity against it, the quote survives as jsonb, a reorder may swap positions inside one
 * transaction and deleting the account cascades to the lines. Skipped without Docker.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AccountCartPersistenceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");

    @Autowired
    AccountCartItemRepository items;

    @Autowired
    AccountRepository accounts;

    @Autowired
    TransactionTemplate transactions;

    @Test
    void roundTripsTheQuoteAsJsonSwapsPositionsAndGoesWithTheAccount() {
        Account account = accounts.saveAndFlush(Account.create("cart@example.com", NOW));
        Map<String, Object> quote = Map.of("fileName", "benchy.stl", "totalChf", 12.5,
                "warnings", List.of("non-manifold"), "nested", Map.of("unitPrice", 6.25));
        AccountCartItem.Choice choice = new AccountCartItem.Choice("benchy.stl", "PLA", "red", "standard", 2, quote);
        AccountCartItem first = AccountCartItem.create(account.getId(), 1, "k1.stl", 28, "a".repeat(64), choice, NOW);
        AccountCartItem second = AccountCartItem.create(account.getId(), 2, "k2.stl", 30, "b".repeat(64), choice, NOW);
        items.saveAllAndFlush(List.of(first, second));

        AccountCartItem reloaded = items.findById(first.getId()).orElseThrow();
        assertThat(reloaded.getQuote()).containsEntry("totalChf", 12.5)
                .containsEntry("warnings", List.of("non-manifold"))
                .containsEntry("nested", Map.of("unitPrice", 6.25));
        assertThat(reloaded.getUpdatedAt()).isEqualTo(NOW);

        transactions.executeWithoutResult(status -> {
            AccountCartItem a = items.findById(first.getId()).orElseThrow();
            AccountCartItem b = items.findById(second.getId()).orElseThrow();
            a.update(2, choice, NOW);
            b.update(1, choice, NOW);
        });
        assertThat(items.findByAccountIdOrderByPosition(account.getId())).extracting(AccountCartItem::getId)
                .containsExactly(second.getId(), first.getId());
        assertThat(items.findByUpdatedAtBefore(NOW.plusSeconds(1))).hasSize(2);
        assertThat(items.findByUpdatedAtBefore(NOW)).isEmpty();

        accounts.delete(account);
        accounts.flush();
        assertThat(items.findByAccountIdOrderByPosition(account.getId())).isEmpty();
        assertThat(items.findById(UUID.randomUUID())).isEmpty();
    }
}
