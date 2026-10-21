package com.slicercore.order;

import com.slicercore.config.FilamentCatalogProperties;
import com.slicercore.config.ModelStoreProperties;
import com.slicercore.config.QuotePricingProperties;
import com.slicercore.model.ColorOption;
import com.slicercore.model.FilamentType;
import com.slicercore.model.ModelNotFoundException;
import com.slicercore.model.PrintQuality;
import com.slicercore.model.QuoteResponse;
import com.slicercore.payment.MockPaymentGateway;
import com.slicercore.payment.PaymentProperties;
import com.slicercore.service.FilamentCatalog;
import com.slicercore.service.QuoteService;
import com.slicercore.service.UploadedModelStore;
import com.slicercore.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.slicercore.support.TestOrders.SETTINGS;
import static com.slicercore.support.TestOrders.quote;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Placing an order from models kept by {@code POST /api/models}: the quote service is a mock
 * that answers like the real one would, the model store and the order-files storage are real
 * and live in a temporary directory.
 */
class OrderServiceTest {

    private static final ColorOption BLACK = new ColorOption("black", "Black", "#1f1f1f", null);
    private static final ColorOption RED = new ColorOption("red", "Red", "#d62828", null);
    private static final String BENCHY_ID = "a".repeat(64);
    private static final String BRACKET_ID = "b".repeat(64);
    /** Quoted (cached) but no longer in the store: the race between quote and checkout. */
    private static final String GONE_ID = "c".repeat(64);
    private static final String UNKNOWN_ID = "d".repeat(64);
    private static final byte[] BENCHY_BYTES = "solid benchy endsolid benchy".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BRACKET_BYTES = "solid bracket endsolid bracket".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    private final QuoteService quoteService = mock(QuoteService.class);
    private final OrderRepository repository = mock(OrderRepository.class);
    private final MutableClock clock = MutableClock.at("2026-10-19T09:00:00Z");

    private Path orderFiles;
    private LocalOrderFileStorage files;
    private UploadedModelStore modelStore;
    private OrderService service;

    @BeforeEach
    void wire() throws IOException {
        orderFiles = Files.createDirectories(dir.resolve("order-files"));
        files = new LocalOrderFileStorage(orderFiles);
        modelStore = new UploadedModelStore(new ModelStoreProperties(dir.resolve("store").toString(), 60, 64), clock);
        keep(BENCHY_ID, "3DBenchy.stl", BENCHY_BYTES);
        keep(BRACKET_ID, "Halter.stl", BRACKET_BYTES);

        FilamentCatalog catalog = new FilamentCatalog(new FilamentCatalogProperties(Map.of(
                FilamentType.PLA, List.of(BLACK, RED))), new QuotePricingProperties("CHF", 5, 4, 3, 16, 1.75, 20, Map.of()));
        PaymentGateway gateway = new MockPaymentGateway(new PaymentProperties("mock", "http://localhost:4200"));
        service = new OrderService(quoteService, modelStore, catalog, files, repository, SETTINGS, gateway, clock);

        for (String knownId : List.of(BENCHY_ID, BRACKET_ID, GONE_ID)) {
            when(quoteService.quoteStoredModel(eq(knownId), eq(FilamentType.PLA), any(), eq(PrintQuality.MEDIUM), anyInt(), anyString()))
                    .thenAnswer(invocation -> quote(knownId.equals(BENCHY_ID) ? "3DBenchy.stl" : "Halter.stl",
                            invocation.getArgument(2), invocation.getArgument(4), 3.55));
        }
        when(quoteService.quoteStoredModel(eq(UNKNOWN_ID), any(), any(), any(), anyInt(), anyString()))
                .thenThrow(new ModelNotFoundException(UNKNOWN_ID));
        when(repository.nextOrderNumber()).thenReturn(1000L);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void placesATwoItemOrderFromStoredModelsWithTheServerSidePrice() throws IOException {
        // 2 x 3.55 + 5.00 = 12.10 and 1 x 3.55 + 5.00 = 8.55: items 20.65 (what the customer
        // confirms), shipping 9.00, total 29.65.
        OrderResponse response = service.placeOrder(request("ch", new BigDecimal("20.65"),
                item(BENCHY_ID, "3DBenchy.stl", "red", 2), item(BRACKET_ID, "Halter.stl", "black", 1)), "203.0.113.7");

        assertThat(response.orderNumber()).isEqualTo("POD-1000");
        assertThat(response.status()).isEqualTo(OrderStatus.NEW);
        assertThat(response.quoteExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(14)));
        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).position()).isEqualTo(1);
        assertThat(response.items().get(0).fileName()).isEqualTo("3DBenchy.stl");
        assertThat(response.items().get(0).color().code()).isEqualTo("red");
        assertThat(response.items().get(0).lineTotalChf()).isEqualByComparingTo("12.10");
        assertThat(response.items().get(1).position()).isEqualTo(2);
        assertThat(response.items().get(1).color().code()).isEqualTo("black");
        assertThat(response.items().get(1).setupFeeChf()).isEqualByComparingTo("5.00");
        assertThat(response.items().get(1).lineTotalChf()).isEqualByComparingTo("8.55");
        assertThat(response.totals().itemsTotal()).isEqualByComparingTo("20.65");
        assertThat(response.totals().shipping()).isEqualByComparingTo("9.00");
        assertThat(response.totals().total()).isEqualByComparingTo("29.65");
        assertThat(response.vatNote()).contains("not VAT registered");
        assertThat(response.paymentUrl()).startsWith("http://localhost:4200/pay/mock?session=mock_")
                .contains("order=POD-1000").contains("amount=29.65");

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(repository).save(saved.capture());
        Order order = saved.getValue();
        assertThat(order.getCountry()).isEqualTo("CH");
        assertThat(order.getCustomerEmail()).isEqualTo("anna@example.com");
        List<OrderItem> items = order.getItems();
        assertThat(items).extracting(OrderItem::getFileKey).allMatch(key -> key.matches("[0-9a-f-]{36}\\.stl"))
                .doesNotHaveDuplicates();
        assertThat(items.get(0).getFileSizeBytes()).isEqualTo(BENCHY_BYTES.length);
        assertThat(items.get(0).getFileSha256()).hasSize(64);
        try (var in = files.open(items.get(0).getFileKey())) {
            assertThat(in.readAllBytes()).isEqualTo(BENCHY_BYTES);
        }
        try (var in = files.open(items.get(1).getFileKey())) {
            assertThat(in.readAllBytes()).isEqualTo(BRACKET_BYTES);
        }
        // The upload store keeps its copy for further quotes.
        assertThat(modelStore.find(BENCHY_ID)).isPresent();
    }

    @Test
    void anUnknownModelIsReportedWithItsItemIndexAndNothingIsStored() {
        assertThatThrownBy(() -> service.placeOrder(request("CH", null,
                item(BENCHY_ID, "3DBenchy.stl", "red", 1), item(UNKNOWN_ID, "lost.stl", "red", 1)), "c"))
                .isInstanceOf(OrderModelExpiredException.class)
                .extracting(e -> ((OrderModelExpiredException) e).itemIndex())
                .isEqualTo(1);

        verify(repository, never()).save(any());
        assertThat(orderFiles).isEmptyDirectory();
    }

    @Test
    void aModelThatExpiredBetweenQuoteAndCheckoutIsReportedTheSameWay() {
        assertThatThrownBy(() -> service.placeOrder(request("CH", null,
                item(BENCHY_ID, "3DBenchy.stl", "red", 1), item(GONE_ID, "gone.stl", "red", 1)), "c"))
                .isInstanceOf(OrderModelExpiredException.class)
                .extracting(e -> ((OrderModelExpiredException) e).itemIndex())
                .isEqualTo(1);

        verify(repository, never()).save(any());
        // The first item's copy was rolled back as well.
        assertThat(orderFiles).isEmptyDirectory();
    }

    @Test
    void refusesWhenThePriceTheCustomerSawNoLongerHoldsAndAnswersWithFreshQuotes() {
        // The grand total is not what the customer confirms; sending it is a mismatch too.
        assertThatThrownBy(() -> service.placeOrder(request("CH", new BigDecimal("29.65"),
                item(BENCHY_ID, "3DBenchy.stl", "red", 2), item(BRACKET_ID, "Halter.stl", "black", 1)), "c"))
                .isInstanceOf(PriceChangedException.class)
                .satisfies(e -> {
                    PriceChangedException changed = (PriceChangedException) e;
                    assertThat(changed.currentQuotes()).extracting(QuoteResponse::totalPriceChf).containsExactly(12.1, 8.55);
                    assertThat(changed.currentTotals().itemsTotal()).isEqualByComparingTo("20.65");
                    assertThat(changed.currentTotals().total()).isEqualByComparingTo("29.65");
                });

        verify(repository, never()).save(any());
        assertThat(orderFiles).isEmptyDirectory();
    }

    @Test
    void ordersWithoutAnExpectedPriceAreAccepted() {
        OrderResponse response = service.placeOrder(request("CH", null, item(BENCHY_ID, "3DBenchy.stl", "red", 2)), "c");

        assertThat(response.totals().total()).isEqualByComparingTo("21.10");
    }

    @Test
    void theCustomersFileNameIsKeptWithoutPathsAndFallsBackToTheUploadsName() {
        OrderResponse response = service.placeOrder(request("CH", null,
                item(BENCHY_ID, "C:\\Users\\anna\\Boot (v2).stl", "red", 1), item(BRACKET_ID, null, "red", 1)), "c");

        assertThat(response.items()).extracting(OrderItemResponse::fileName).containsExactly("Boot (v2).stl", "Halter.stl");
    }

    @Test
    void refusesCountriesOutsideTheShippingArea() {
        assertThatThrownBy(() -> service.placeOrder(request("DE", null, item(BENCHY_ID, "3DBenchy.stl", "red", 1)), "c"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DE")
                .hasMessageContaining("CH");

        verify(quoteService, never()).quoteStoredModel(anyString(), any(), any(), any(), anyInt(), anyString());
    }

    @Test
    void removesEveryStoredFileWhenSavingFails() {
        doThrow(new IllegalStateException("database down")).when(repository).save(any());

        assertThatThrownBy(() -> service.placeOrder(request("CH", null,
                item(BENCHY_ID, "3DBenchy.stl", "red", 1), item(BRACKET_ID, "Halter.stl", "red", 1)), "c"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(orderFiles).isEmptyDirectory();
    }

    @Test
    void lookupRequiresTheMatchingEmail() {
        service.placeOrder(request("CH", null, item(BENCHY_ID, "3DBenchy.stl", "red", 1)), "c");
        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(repository).save(saved.capture());
        when(repository.findByOrderNumber("POD-1000")).thenReturn(Optional.of(saved.getValue()));

        assertThat(service.find("pod-1000", "Anna@Example.com")).isPresent();
        assertThat(service.find("POD-1000", "someone@else.com")).isEmpty();
        assertThat(service.find("POD-9999", "anna@example.com")).isEmpty();
    }

    private void keep(String id, String displayName, byte[] bytes) throws IOException {
        Path staged = Files.write(dir.resolve("staged-" + id.charAt(0) + ".stl"), bytes);
        modelStore.put(id, staged, ".stl", displayName);
    }

    private static OrderRequest request(String country, BigDecimal expectedTotal, OrderItemRequest... items) {
        return new OrderRequest(new CustomerDetails(" Anna Muster ", "Anna@Example.com", "Bahnhofstrasse 1", "8001",
                "Zürich", country), List.of(items), expectedTotal);
    }

    private static OrderItemRequest item(String modelId, String fileName, String color, int quantity) {
        return new OrderItemRequest(modelId, fileName, "PLA", color, "medium", quantity);
    }
}
