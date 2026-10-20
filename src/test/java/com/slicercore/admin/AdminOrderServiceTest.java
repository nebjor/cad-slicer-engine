package com.slicercore.admin;

import com.slicercore.model.BoundingBox;
import com.slicercore.model.ColorOption;
import com.slicercore.model.QuoteResponse;
import com.slicercore.order.LocalOrderFileStorage;
import com.slicercore.order.Order;
import com.slicercore.order.OrderFileStorage;
import com.slicercore.order.OrderProperties;
import com.slicercore.order.OrderRepository;
import com.slicercore.order.OrderStatus;
import com.slicercore.order.OrderTotals;
import com.slicercore.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminOrderServiceTest {

    @TempDir
    Path dir;

    private final OrderRepository repository = mock(OrderRepository.class);
    private final MutableClock clock = MutableClock.at("2026-10-19T12:00:00Z");
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final OrderProperties settings = new OrderProperties(new BigDecimal("9.00"), false,
            new BigDecimal("0.081"), 14, 90, "unused", Set.of("CH"));

    @Test
    void shippingStartsTheRetentionClock() {
        Order order = order();
        order.transitionTo(OrderStatus.PAID, clock.instant());
        order.transitionTo(OrderStatus.IN_REVIEW, clock.instant());
        order.transitionTo(OrderStatus.APPROVED, clock.instant());
        order.transitionTo(OrderStatus.PRINTING, clock.instant());
        when(repository.findByOrderNumber("POD-1000")).thenReturn(Optional.of(order));
        AdminOrderService service = new AdminOrderService(repository, new LocalOrderFileStorage(dir), settings, clock, events);

        AdminOrderResponse shipped = service.transition("pod-1000", OrderStatus.SHIPPED);

        assertThat(shipped.status()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(shipped.shippedAt()).isEqualTo(clock.instant());
        assertThat(shipped.deleteAfter()).isEqualTo(clock.instant().plus(Duration.ofDays(90)));
        assertThat(shipped.allowedTransitions()).isEmpty();
    }

    @Test
    void illegalTransitionsAreRejectedUnchanged() {
        Order order = order();
        when(repository.findByOrderNumber("POD-1000")).thenReturn(Optional.of(order));
        AdminOrderService service = new AdminOrderService(repository, new LocalOrderFileStorage(dir), settings, clock, events);

        assertThatThrownBy(() -> service.transition("POD-1000", OrderStatus.PRINTING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NEW")
                .hasMessageContaining("PRINTING");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.NEW);
    }

    @Test
    void listsNewestFirstWithOptionalStatusFilter() {
        Order order = order();
        when(repository.findAllByOrderByCreatedAtDesc(any())).thenReturn(List.of(order));
        when(repository.findAllByStatusOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
        AdminOrderService service = new AdminOrderService(repository, new LocalOrderFileStorage(dir), settings, clock, events);

        assertThat(service.list(Optional.empty(), 50)).extracting(AdminOrderResponse::orderNumber).containsExactly("POD-1000");
        assertThat(service.list(Optional.of(OrderStatus.SHIPPED), 50)).isEmpty();
    }

    @Test
    void modelDownloadUsesTheStoredFileAndASafeName() throws IOException {
        LocalOrderFileStorage files = new LocalOrderFileStorage(dir);
        OrderFileStorage.StoredFile stored = files.store(new ByteArrayInputStream("solid x".getBytes()), "stl");
        Order order = order(stored, "Mein Modell (final).stl");
        when(repository.findByOrderNumber("POD-1000")).thenReturn(Optional.of(order));
        AdminOrderService service = new AdminOrderService(repository, files, settings, clock, events);

        AdminOrderService.ModelDownload download = service.model("POD-1000");

        assertThat(download.fileName()).isEqualTo("POD-1000_Mein_Modell__final_.stl");
        assertThat(download.sizeBytes()).isEqualTo(stored.sizeBytes());
        try (var in = download.content()) {
            assertThat(in.readAllBytes()).isEqualTo("solid x".getBytes());
        }
    }

    @Test
    void unknownOrdersAreReportedAsMissing() {
        when(repository.findByOrderNumber(any())).thenReturn(Optional.empty());
        AdminOrderService service = new AdminOrderService(repository, new LocalOrderFileStorage(dir), settings, clock, events);

        assertThatThrownBy(() -> service.get("POD-9")).isInstanceOf(NoSuchElementException.class);
    }

    private Order order() {
        return order(new OrderFileStorage.StoredFile("123e4567-e89b-12d3-a456-426614174000.stl", "a".repeat(64), 7),
                "benchy.stl");
    }

    private Order order(OrderFileStorage.StoredFile file, String fileName) {
        QuoteResponse quote = new QuoteResponse(fileName, "PLA", new ColorOption("red", "Red", "#d62828"),
                "medium", 2, "A1", 0.2, 1, 2239, 4478, 7761.2, 11.6, 23.2, new BoundingBox(60, 31, 48),
                225154, 5.0, 0.8, 5.0, 3.0, 11.0, "CHF", 3);
        return Order.create("POD-1000", Instant.parse("2026-10-19T11:00:00Z"), 14,
                new Order.Customer("Anna", "anna@example.com", "S 1", "8000", "Z", "CH"), quote, file,
                OrderTotals.from(quote, settings));
    }
}
