package com.slicercore.order;

import com.slicercore.model.BoundingBox;
import com.slicercore.model.ColorOption;
import com.slicercore.model.QuoteResponse;
import com.slicercore.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Retention is a promise made on the privacy page: 90 days after shipping the model and the
 * personal data are gone. These tests move a controllable clock past that point.
 */
class OrderRetentionServiceTest {

    @TempDir
    Path dir;

    private final OrderRepository repository = mock(OrderRepository.class);
    private final MutableClock clock = MutableClock.at("2026-10-19T12:00:00Z");

    @Test
    void sweepErasesFileAndPersonalDataOnceRetentionHasEnded() throws IOException {
        LocalOrderFileStorage files = new LocalOrderFileStorage(dir);
        Order order = shippedOrder(files);
        OrderRetentionService service = new OrderRetentionService(repository, files, clock);

        clock.advance(Duration.ofDays(89));
        when(repository.findAllByDeleteAfterBeforeAndErasedAtIsNull(clock.instant())).thenReturn(List.of());
        assertThat(service.eraseExpired()).isZero();
        assertThat(order.isErased()).isFalse();

        clock.advance(Duration.ofDays(2));
        when(repository.findAllByDeleteAfterBeforeAndErasedAtIsNull(clock.instant())).thenReturn(List.of(order));
        assertThat(service.eraseExpired()).isEqualTo(1);

        assertThat(Files.exists(dir.resolve(order.getFileKey()))).isFalse();
        assertThat(order.isErased()).isTrue();
        assertThat(order.getErasedAt()).isEqualTo(clock.instant());
        assertThat(order.getCustomerName()).doesNotContain("Anna");
        assertThat(order.getCustomerEmail()).doesNotContain("example.com");
        assertThat(order.getStreet()).doesNotContain("Bahnhof");
        assertThat(order.getCity()).doesNotContain("Zürich");
        // The business record survives.
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(order.getTotals().total()).isEqualByComparingTo("20.00");
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
    void personalDataIsKeptWhenTheFileCannotBeDeleted() throws IOException {
        OrderFileStorage failing = mock(OrderFileStorage.class);
        when(failing.delete(any())).thenThrow(new IOException("volume offline"));
        Order order = shippedOrder(new LocalOrderFileStorage(dir));
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
        assertThat(Files.exists(dir.resolve(order.getFileKey()))).isFalse();
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
        assertThat(Files.exists(dir.resolve(order.getFileKey()))).isFalse();
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

    private Order unpaidOrder(LocalOrderFileStorage files) throws IOException {
        OrderFileStorage.StoredFile stored = files.store(new ByteArrayInputStream("solid benchy".getBytes()), "stl");
        QuoteResponse quote = new QuoteResponse("benchy.stl", "PLA", new ColorOption("red", "Red", "#d62828", null),
                "medium", 2, "A1", 0.2, 1, 2250, 4500, 7761.2, 11.6, 23.2, new BoundingBox(60, 31, 48),
                225154, 5.0, 0.8, 5.0, 3.0, 11.0, "CHF", 3);
        OrderProperties settings = new OrderProperties(new BigDecimal("9.00"), false, new BigDecimal("0.081"),
                14, 90, dir.toString(), Set.of("CH"));
        Order order = Order.create("POD-1000", clock.instant(), 14,
                new Order.Customer("Anna Muster", "anna@example.com", "Bahnhofstrasse 1", "8001", "Zürich", "CH"),
                quote, stored, OrderTotals.from(quote, settings));
        return order;
    }
}
