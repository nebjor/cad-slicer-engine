package com.slicercore.admin;

import com.slicercore.account.AccountService;
import com.slicercore.order.LocalOrderFileStorage;
import com.slicercore.order.Order;
import com.slicercore.order.OrderFileStorage;
import com.slicercore.order.OrderItem;
import com.slicercore.order.OrderRepository;
import com.slicercore.order.OrderStatus;
import com.slicercore.support.MutableClock;
import com.slicercore.support.TestOrders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.slicercore.support.TestOrders.ANNA;
import static com.slicercore.support.TestOrders.BLACK;
import static com.slicercore.support.TestOrders.RED;
import static com.slicercore.support.TestOrders.SETTINGS;
import static com.slicercore.support.TestOrders.quote;
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
    private final AccountService accounts = mock(AccountService.class);

    @Test
    void shippingStartsTheRetentionClock() {
        Order order = TestOrders.benchyOrder();
        order.transitionTo(OrderStatus.PAID, clock.instant());
        order.transitionTo(OrderStatus.IN_REVIEW, clock.instant());
        order.transitionTo(OrderStatus.APPROVED, clock.instant());
        order.transitionTo(OrderStatus.PRINTING, clock.instant());
        when(repository.findByOrderNumber("POD-1000")).thenReturn(Optional.of(order));
        AdminOrderService service = new AdminOrderService(repository, new LocalOrderFileStorage(dir), SETTINGS, clock, events, accounts);

        AdminOrderResponse shipped = service.transition("pod-1000", OrderStatus.SHIPPED);

        assertThat(shipped.status()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(shipped.shippedAt()).isEqualTo(clock.instant());
        assertThat(shipped.deleteAfter()).isEqualTo(clock.instant().plus(Duration.ofDays(90)));
        assertThat(shipped.allowedTransitions()).isEmpty();
    }

    @Test
    void illegalTransitionsAreRejectedUnchanged() {
        Order order = TestOrders.benchyOrder();
        when(repository.findByOrderNumber("POD-1000")).thenReturn(Optional.of(order));
        AdminOrderService service = new AdminOrderService(repository, new LocalOrderFileStorage(dir), SETTINGS, clock, events, accounts);

        assertThatThrownBy(() -> service.transition("POD-1000", OrderStatus.PRINTING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NEW")
                .hasMessageContaining("PRINTING");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.NEW);
    }

    @Test
    void listsNewestFirstWithOptionalStatusFilterAndEveryItem() {
        Order order = TestOrders.twoItemOrder();
        UUID accountId = UUID.randomUUID();
        order.linkAccount(accountId);
        when(repository.findAllByOrderByCreatedAtDesc(any())).thenReturn(List.of(order, TestOrders.benchyOrder()));
        when(repository.findAllByStatusOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
        when(accounts.emailsById(Set.of(accountId))).thenReturn(Map.of(accountId, "anna@example.com"));
        AdminOrderService service = new AdminOrderService(repository, new LocalOrderFileStorage(dir), SETTINGS, clock, events, accounts);

        List<AdminOrderResponse> listed = service.list(Optional.empty(), 50);

        assertThat(listed).extracting(AdminOrderResponse::orderNumber).containsExactly("POD-1000", "POD-1000");
        assertThat(listed).extracting(AdminOrderResponse::accountEmail).containsExactly("anna@example.com", null);
        assertThat(listed.get(0).items()).extracting(AdminOrderItemResponse::fileName).containsExactly("benchy.stl", "halter.3mf");
        assertThat(listed.get(0).items().get(1).color()).isEqualTo(BLACK);
        assertThat(listed.get(0).items().get(1).lineTotalChf()).isEqualByComparingTo("8.00");
        assertThat(service.list(Optional.of(OrderStatus.SHIPPED), 50)).isEmpty();
    }

    @Test
    void flagsOrdersWithSlicerWarningsOrANonManifoldModelForInspection() {
        Order clean = TestOrders.twoItemOrder();
        Order warned = TestOrders.order("POD-1001", SETTINGS, ANNA,
                quote("benchy.stl", RED, 1, 3.0),
                TestOrders.flagged(quote("vase.stl", RED, 1, 3.0), List.of("Object vase.stl has thin walls"), true));
        Order leaky = TestOrders.order("POD-1002", SETTINGS, ANNA,
                TestOrders.flagged(quote("ring.stl", RED, 1, 3.0), List.of(), false));
        when(repository.findAllByOrderByCreatedAtDesc(any())).thenReturn(List.of(leaky, warned, clean));
        AdminOrderService service = new AdminOrderService(repository, new LocalOrderFileStorage(dir), SETTINGS, clock, events, accounts);

        List<AdminOrderResponse> listed = service.list(Optional.empty(), 50);

        assertThat(listed).extracting(AdminOrderResponse::needsInspection).containsExactly(true, true, false);
        AdminOrderItemResponse vase = listed.get(1).items().get(1);
        assertThat(vase.warnings()).containsExactly("Object vase.stl has thin walls");
        assertThat(vase.manifold()).isTrue();
        assertThat(listed.get(1).items().get(0).warnings()).isEmpty();
        assertThat(listed.get(0).items().get(0).manifold()).isFalse();
        assertThat(listed.get(2).items()).allSatisfy(item -> {
            assertThat(item.warnings()).isEmpty();
            assertThat(item.manifold()).isTrue();
        });
    }

    @Test
    void modelDownloadAddressesOneItemAndUsesASafeName() throws IOException {
        LocalOrderFileStorage files = new LocalOrderFileStorage(dir);
        OrderFileStorage.StoredFile benchy = files.store(new ByteArrayInputStream("solid benchy".getBytes()), "stl");
        OrderFileStorage.StoredFile bracket = files.store(new ByteArrayInputStream("solid x".getBytes()), "stl");
        Order order = TestOrders.order("POD-1000", SETTINGS, ANNA, List.of(
                OrderItem.create(1, "benchy.stl", quote("benchy.stl", RED, 1, 3.0), benchy),
                OrderItem.create(2, "Mein Modell (final).stl", quote("Mein Modell (final).stl", RED, 1, 3.0), bracket)));
        when(repository.findByOrderNumber("POD-1000")).thenReturn(Optional.of(order));
        AdminOrderService service = new AdminOrderService(repository, files, SETTINGS, clock, events, accounts);

        AdminOrderService.ModelDownload download = service.model("POD-1000", 2);

        assertThat(download.fileName()).isEqualTo("POD-1000_2_Mein_Modell__final_.stl");
        assertThat(download.sizeBytes()).isEqualTo(bracket.sizeBytes());
        try (var in = download.content()) {
            assertThat(in.readAllBytes()).isEqualTo("solid x".getBytes());
        }
        assertThatThrownBy(() -> service.model("POD-1000", 3))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("no item 3");
    }

    @Test
    void unknownOrdersAreReportedAsMissing() {
        when(repository.findByOrderNumber(any())).thenReturn(Optional.empty());
        AdminOrderService service = new AdminOrderService(repository, new LocalOrderFileStorage(dir), SETTINGS, clock, events, accounts);

        assertThatThrownBy(() -> service.get("POD-9")).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.model("POD-9", 1)).isInstanceOf(NoSuchElementException.class);
    }
}
