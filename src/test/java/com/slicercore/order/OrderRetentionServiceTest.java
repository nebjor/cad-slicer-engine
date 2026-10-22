package com.slicercore.order;

import com.slicercore.support.MutableClock;
import com.slicercore.support.TestOrders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.slicercore.support.TestOrders.ANNA;
import static com.slicercore.support.TestOrders.BLACK;
import static com.slicercore.support.TestOrders.RED;
import static com.slicercore.support.TestOrders.SETTINGS;
import static com.slicercore.support.TestOrders.quote;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Retention is a promise made on the privacy page: 90 days after shipping the models and the
 * personal data are gone. These tests move a controllable clock past that point.
 */
class OrderRetentionServiceTest {

    @TempDir
    Path dir;

    private final OrderRepository repository = mock(OrderRepository.class);
    private final MutableClock clock = MutableClock.at("2026-10-19T12:00:00Z");

    @Test
    void sweepErasesEveryFileAndThePersonalDataOnceRetentionHasEnded() throws IOException {
        LocalOrderFileStorage files = new LocalOrderFileStorage(dir);
        Order order = shippedOrder(files);
        order.linkAccount(UUID.randomUUID());
        OrderRetentionService service = new OrderRetentionService(repository, files, clock);

        clock.advance(Duration.ofDays(89));
        when(repository.findAllByDeleteAfterBeforeAndErasedAtIsNull(clock.instant())).thenReturn(List.of());
        assertThat(service.eraseExpired()).isZero();
        assertThat(order.isErased()).isFalse();

        clock.advance(Duration.ofDays(2));
        when(repository.findAllByDeleteAfterBeforeAndErasedAtIsNull(clock.instant())).thenReturn(List.of(order));
        assertThat(service.eraseExpired()).isEqualTo(1);

        assertThat(order.getItems()).hasSize(2);
        assertThat(dir).isEmptyDirectory();
        assertThat(order.isErased()).isTrue();
        assertThat(order.getErasedAt()).isEqualTo(clock.instant());
        assertThat(order.getCustomerName()).doesNotContain("Anna");
        assertThat(order.getCustomerEmail()).doesNotContain("example.com");
        assertThat(order.getStreet()).doesNotContain("Bahnhof");
        assertThat(order.getCity()).doesNotContain("Zürich");
        assertThat(order.getAccountId()).as("an erased order belongs to nobody").isNull();
        // The business record survives, items included.
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(order.getTotals().total()).isEqualByComparingTo("23.00");
        assertThat(order.getItems().get(1).getLineTotal()).isEqualByComparingTo("3.00");
        assertThat(order.getOrderNumber()).isEqualTo("POD-1000");
    }

    @Test
    void deletionRequestErasesOneOrderAndIsIdempotent() throws IOException {
        LocalOrderFileStorage files = new LocalOrderFileStorage(dir);
        Order order = shippedOrder(files);
        when(repository.findByOrderNumber("POD-1000")).thenReturn(Optional.of(order));
        OrderRetentionService service = new OrderRetentionService(repository, files, clock);

        assertThat(service.erase("pod-1000").isErased()).isTrue();
        Instant firstErasure = order.getErasedAt();

        clock.advance(Duration.ofHours(1));
        assertThat(service.erase("POD-1000").getErasedAt()).isEqualTo(firstErasure);
    }

    @Test
    void personalDataIsKeptWhileAnyFileCannotBeDeleted() throws IOException {
        Order order = shippedOrder(new LocalOrderFileStorage(dir));
        String secondKey = order.getItems().get(1).getFileKey();
        OrderFileStorage failing = mock(OrderFileStorage.class);
        when(failing.delete(any())).thenReturn(true);
        when(failing.delete(secondKey)).thenThrow(new IOException("volume offline"));
        when(repository.findAllByDeleteAfterBeforeAndErasedAtIsNull(any())).thenReturn(List.of(order));

        new OrderRetentionService(repository, failing, clock).eraseExpired();

        // Retried on the next sweep: nothing is half erased.
        assertThat(order.isErased()).isFalse();
        assertThat(order.getCustomerName()).isEqualTo("Anna Muster");
    }

    @Test
    void abandonCancelsAnUnpaidOrderAndErasesItAtOnce() throws IOException {
        LocalOrderFileStorage files = new LocalOrderFileStorage(dir);
        Order order = unpaidOrder(files);

        new OrderRetentionService(repository, files, clock).abandon(order);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.isErased()).isTrue();
        assertThat(dir).isEmptyDirectory();
        assertThat(order.getCustomerEmail()).doesNotContain("example.com");
    }

    @Test
    void sweepCancelsAndErasesOrdersStillUnpaidAfterTheQuoteValidity() throws IOException {
        LocalOrderFileStorage files = new LocalOrderFileStorage(dir);
        Order order = unpaidOrder(files);
        OrderRetentionService service = new OrderRetentionService(repository, files, clock);

        clock.advance(Duration.ofDays(15));
        when(repository.findAllByStatusInAndQuoteExpiresAtBeforeAndErasedAtIsNull(
                EnumSet.of(OrderStatus.NEW, OrderStatus.CANCELLED), clock.instant())).thenReturn(List.of(order));

        assertThat(service.eraseAbandoned()).isEqualTo(1);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.isErased()).isTrue();
        assertThat(dir).isEmptyDirectory();
        // The business record survives here as well.
        assertThat(order.getOrderNumber()).isEqualTo("POD-1000");
    }

    private Order shippedOrder(LocalOrderFileStorage files) throws IOException {
        Order order = unpaidOrder(files);
        for (OrderStatus next : List.of(OrderStatus.PAID, OrderStatus.IN_REVIEW, OrderStatus.APPROVED,
                OrderStatus.PRINTING, OrderStatus.SHIPPED)) {
            order.transitionTo(next, clock.instant());
        }
        order.scheduleDeletion(90);
        return order;
    }

    /** Two items with two real files in the temporary directory: 6.00 + 3.00 + 5.00 setup fee + 9.00 shipping = 23.00. */
    private Order unpaidOrder(LocalOrderFileStorage files) throws IOException {
        OrderFileStorage.StoredFile benchy = files.store(new ByteArrayInputStream("solid benchy".getBytes()), "stl");
        OrderFileStorage.StoredFile bracket = files.store(new ByteArrayInputStream("solid bracket".getBytes()), "3mf");
        return TestOrders.order("POD-1000", SETTINGS, ANNA, List.of(
                OrderItem.create(1, "benchy.stl", quote("benchy.stl", RED, 2, 3.0), benchy),
                OrderItem.create(2, "halter.3mf", quote("halter.3mf", "PETG", BLACK, "high", 1, 3.0), bracket)));
    }
}
