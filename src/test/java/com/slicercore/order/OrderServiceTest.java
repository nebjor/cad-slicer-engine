package com.slicercore.order;

import com.slicercore.config.FilamentCatalogProperties;
import com.slicercore.config.ModelStoreProperties;
import com.slicercore.config.QuotePricingProperties;
import com.slicercore.catalog.ColorOption;
import com.slicercore.catalog.FilamentType;
import com.slicercore.upload.ModelNotFoundException;
import com.slicercore.catalog.PrintQuality;
import com.slicercore.quote.QuoteResponse;
import com.slicercore.payment.MockPaymentGateway;
import com.slicercore.payment.PaymentProperties;
import com.slicercore.quote.FilamentCatalog;
import com.slicercore.quote.QuoteService;
import com.slicercore.upload.UploadedModelStore;
import com.slicercore.support.MutableClock;
import com.slicercore.support.TestOrders;
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
import java.util.UUID;

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
                FilamentType.PLA, List.of(BLACK, RED))), new QuotePricingProperties("CHF", 4, 3, 16, 1.75, 100, 300, new QuotePricingProperties.Plate(256, 256, 10, 15), Map.of(), QuotePricingProperties.Material.UNSCALED));
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
        // 2 x 3.55 = 7.10 and 1 x 3.55 = 3.55: items 10.65 (what the customer confirms), one
        // guest setup fee 5.00 for the whole order, shipping 9.00, total 24.65.
        OrderResponse response = service.placeOrder(request("ch", new BigDecimal("10.65"),
                item(BENCHY_ID, "3DBenchy.stl", "red", 2), item(BRACKET_ID, "Halter.stl", "black", 1)), "203.0.113.7", null);

        assertThat(response.orderNumber()).isEqualTo("POD-1000");
        assertThat(response.status()).isEqualTo(OrderStatus.NEW);
        assertThat(response.quoteExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(14)));
        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).position()).isEqualTo(1);
        assertThat(response.items().get(0).fileName()).isEqualTo("3DBenchy.stl");
        assertThat(response.items().get(0).color().code()).isEqualTo("red");
        assertThat(response.items().get(0).lineTotalChf()).isEqualByComparingTo("7.10");
        assertThat(response.items().get(1).position()).isEqualTo(2);
        assertThat(response.items().get(1).color().code()).isEqualTo("black");
        assertThat(response.items().get(1).lineTotalChf()).isEqualByComparingTo("3.55");
        assertThat(response.totals().itemsTotal()).isEqualByComparingTo("10.65");
        assertThat(response.totals().setupFee()).isEqualByComparingTo("5.00");
        assertThat(response.totals().shipping()).isEqualByComparingTo("9.00");
        assertThat(response.totals().total()).isEqualByComparingTo("24.65");
        assertThat(response.vatNote()).contains("not VAT registered");
        assertThat(response.paymentUrl()).startsWith("http://localhost:4200/pay/mock?session=mock_")
                .contains("order=POD-1000").contains("amount=24.65");

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(repository).save(saved.capture());
        Order order = saved.getValue();
        assertThat(order.getCountry()).isEqualTo("CH");
        assertThat(order.getCustomerEmail()).isEqualTo("anna@example.com");
        assertThat(order.getAccountId()).as("a guest order").isNull();
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
    void aLoggedInCustomersOrderIsLinkedToTheAccountButKeepsTheCheckoutEmail() {
        UUID accountId = UUID.randomUUID();

        service.placeOrder(request("CH", null, item(BENCHY_ID, "3DBenchy.stl", "red", 1)), "c", accountId);

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getAccountId()).isEqualTo(accountId);
        // The account holder may order for someone else: the e-mail is the checkout's, not the account's.
        assertThat(saved.getValue().getCustomerEmail()).isEqualTo("anna@example.com");
    }

    @Test
    void aSignedInCustomerPaysTheLowerSetupFeeOnceForTheWholeOrder() {
        OrderResponse response = service.placeOrder(request("CH", null,
                item(BENCHY_ID, "3DBenchy.stl", "red", 2), item(BRACKET_ID, "Halter.stl", "black", 1)), "c", UUID.randomUUID());

        // 7.10 + 3.55 + one account setup fee 3.00 + shipping 9.00.
        assertThat(response.totals().setupFee()).isEqualByComparingTo("3.00");
        assertThat(response.totals().total()).isEqualByComparingTo("22.65");
    }

    @Test
    void storesTheSlicerWarningsAndTheManifoldCheckOnEachItem() {
        when(quoteService.quoteStoredModel(eq(BRACKET_ID), eq(FilamentType.PLA), any(), eq(PrintQuality.MEDIUM), anyInt(), anyString()))
                .thenAnswer(invocation -> TestOrders.flagged(
                        quote("Halter.stl", invocation.getArgument(2), invocation.getArgument(4), 3.55),
                        List.of("Mesh errors reported by the slicer: open_edges = 4"), false));

        service.placeOrder(request("CH", null,
                item(BENCHY_ID, "3DBenchy.stl", "red", 1), item(BRACKET_ID, "Halter.stl", "black", 1)), "c", null);

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(repository).save(saved.capture());
        List<OrderItem> items = saved.getValue().getItems();
        assertThat(items.get(0).getWarnings()).isEmpty();
        assertThat(items.get(0).getManifold()).isTrue();
        assertThat(items.get(0).needsInspection()).isFalse();
        assertThat(items.get(1).getWarnings()).containsExactly("Mesh errors reported by the slicer: open_edges = 4");
        assertThat(items.get(1).getManifold()).isFalse();
        assertThat(saved.getValue().needsInspection()).isTrue();
    }

    @Test
    void anUnknownModelIsReportedWithItsItemIndexAndNothingIsStored() {
        assertThatThrownBy(() -> service.placeOrder(request("CH", null,
                item(BENCHY_ID, "3DBenchy.stl", "red", 1), item(UNKNOWN_ID, "lost.stl", "red", 1)), "c", null))
                .isInstanceOf(OrderModelExpiredException.class)
                .extracting(e -> ((OrderModelExpiredException) e).itemIndex())
                .isEqualTo(1);

        verify(repository, never()).save(any());
        assertThat(orderFiles).isEmptyDirectory();
    }

    @Test
    void aModelThatExpiredBetweenQuoteAndCheckoutIsReportedTheSameWay() {
        assertThatThrownBy(() -> service.placeOrder(request("CH", null,
                item(BENCHY_ID, "3DBenchy.stl", "red", 1), item(GONE_ID, "gone.stl", "red", 1)), "c", null))
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
        assertThatThrownBy(() -> service.placeOrder(request("CH", new BigDecimal("24.65"),
                item(BENCHY_ID, "3DBenchy.stl", "red", 2), item(BRACKET_ID, "Halter.stl", "black", 1)), "c", null))
                .isInstanceOf(PriceChangedException.class)
                .satisfies(e -> {
                    PriceChangedException changed = (PriceChangedException) e;
                    assertThat(changed.currentQuotes()).extracting(QuoteResponse::totalPriceChf).containsExactly(7.1, 3.55);
                    assertThat(changed.currentTotals().itemsTotal()).isEqualByComparingTo("10.65");
                    assertThat(changed.currentTotals().total()).isEqualByComparingTo("24.65");
                });

        verify(repository, never()).save(any());
        assertThat(orderFiles).isEmptyDirectory();
    }

    @Test
    void ordersWithoutAnExpectedPriceAreAccepted() {
        OrderResponse response = service.placeOrder(request("CH", null, item(BENCHY_ID, "3DBenchy.stl", "red", 2)), "c", null);

        assertThat(response.totals().total()).isEqualByComparingTo("21.10");
    }

    @Test
    void theCustomersFileNameIsKeptWithoutPathsAndFallsBackToTheUploadsName() {
        OrderResponse response = service.placeOrder(request("CH", null,
                item(BENCHY_ID, "C:\\Users\\anna\\Boot (v2).stl", "red", 1), item(BRACKET_ID, null, "red", 1)), "c", null);

        assertThat(response.items()).extracting(OrderItemResponse::fileName).containsExactly("Boot (v2).stl", "Halter.stl");
    }

    @Test
    void refusesCountriesOutsideTheShippingArea() {
        assertThatThrownBy(() -> service.placeOrder(request("DE", null, item(BENCHY_ID, "3DBenchy.stl", "red", 1)), "c", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DE")
                .hasMessageContaining("CH");

        verify(quoteService, never()).quoteStoredModel(anyString(), any(), any(), any(), anyInt(), anyString());
    }

    @Test
    void removesEveryStoredFileWhenSavingFails() {
        doThrow(new IllegalStateException("database down")).when(repository).save(any());

        assertThatThrownBy(() -> service.placeOrder(request("CH", null,
                item(BENCHY_ID, "3DBenchy.stl", "red", 1), item(BRACKET_ID, "Halter.stl", "red", 1)), "c", null))
                .isInstanceOf(IllegalStateException.class);

        assertThat(orderFiles).isEmptyDirectory();
    }

    @Test
    void lookupRequiresTheMatchingEmail() {
        service.placeOrder(request("CH", null, item(BENCHY_ID, "3DBenchy.stl", "red", 1)), "c", null);
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
