package com.slicercore.order;

import com.slicercore.config.FilamentCatalogProperties;
import com.slicercore.model.BoundingBox;
import com.slicercore.model.ColorOption;
import com.slicercore.model.FilamentType;
import com.slicercore.model.PrintQuality;
import com.slicercore.model.QuoteResponse;
import com.slicercore.service.FilamentCatalog;
import com.slicercore.service.QuoteService;
import com.slicercore.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

class OrderServiceTest {

    private static final ColorOption BLACK = new ColorOption("black", "Black", "#1f1f1f");
    private static final ColorOption RED = new ColorOption("red", "Red", "#d62828");

    @TempDir
    Path dir;

    private final QuoteService quoteService = mock(QuoteService.class);
    private final OrderRepository repository = mock(OrderRepository.class);
    private final MutableClock clock = MutableClock.at("2026-10-19T09:00:00Z");
    private final OrderProperties settings = new OrderProperties(
            new BigDecimal("9.00"), false, new BigDecimal("0.081"), 14, 90, "unused", Set.of("CH"));

    private LocalOrderFileStorage files;
    private OrderService service;

    @BeforeEach
    void wire() {
        files = new LocalOrderFileStorage(dir);
        FilamentCatalog catalog = new FilamentCatalog(new FilamentCatalogProperties(Map.of(
                FilamentType.PLA, List.of(BLACK, RED))));
        service = new OrderService(quoteService, catalog, files, repository, settings, clock);

        when(quoteService.quoteUpload(any(), eq(FilamentType.PLA), any(), eq(PrintQuality.MEDIUM), anyInt(), anyString()))
                .thenAnswer(invocation -> benchyQuote(invocation.getArgument(2), invocation.getArgument(4)));
        when(repository.nextOrderNumber()).thenReturn(1000L);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void placesAnOrderWithTheServerSidePriceAndStoresTheModel() throws IOException {
        OrderResponse response = service.placeOrder(benchy(), request(2, new BigDecimal("12.10"), "ch"), "203.0.113.7");

        assertThat(response.orderNumber()).isEqualTo("POD-1000");
        assertThat(response.status()).isEqualTo(OrderStatus.NEW);
        assertThat(response.quoteExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(14)));
        assertThat(response.totals().itemsTotal()).isEqualByComparingTo("12.10");
        assertThat(response.totals().shipping()).isEqualByComparingTo("9.00");
        assertThat(response.totals().total()).isEqualByComparingTo("21.10");
        assertThat(response.vatNote()).contains("not VAT registered");
        assertThat(response.colorCode()).isEqualTo("red");

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(repository).save(saved.capture());
        Order order = saved.getValue();
        assertThat(order.getCountry()).isEqualTo("CH");
        assertThat(order.getCustomerEmail()).isEqualTo("anna@example.com");
        assertThat(order.getFileKey()).matches("[0-9a-f-]{36}\\.stl");
        assertThat(order.getFileSizeBytes()).isEqualTo(benchy().getSize());
        assertThat(order.getFileSha256()).hasSize(64);
        try (var in = files.open(order.getFileKey())) {
            assertThat(in.readAllBytes()).isEqualTo(benchy().getBytes());
        }
    }

    @Test
    void refusesWhenThePriceTheCustomerSawNoLongerHolds() {
        assertThatThrownBy(() -> service.placeOrder(benchy(), request(2, new BigDecimal("11.00"), "CH"), "c"))
                .isInstanceOf(PriceChangedException.class)
                .extracting(e -> ((PriceChangedException) e).currentQuote().totalPriceChf())
                .isEqualTo(12.1);

        verify(repository, never()).save(any());
        assertThat(dir).isEmptyDirectory();
    }

    @Test
    void ordersWithoutAnExpectedPriceAreAccepted() {
        OrderResponse response = service.placeOrder(benchy(), request(2, null, "CH"), "c");

        assertThat(response.totals().total()).isEqualByComparingTo("21.10");
    }

    @Test
    void refusesCountriesOutsideTheShippingArea() {
        assertThatThrownBy(() -> service.placeOrder(benchy(), request(1, null, "DE"), "c"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DE")
                .hasMessageContaining("CH");

        verify(quoteService, never()).quoteUpload(any(), any(), any(), any(), anyInt(), anyString());
    }

    @Test
    void removesTheStoredFileWhenSavingFails() {
        doThrow(new IllegalStateException("database down")).when(repository).save(any());

        assertThatThrownBy(() -> service.placeOrder(benchy(), request(1, null, "CH"), "c"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(dir).isEmptyDirectory();
    }

    @Test
    void lookupRequiresTheMatchingEmail() {
        service.placeOrder(benchy(), request(1, null, "CH"), "c");
        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(repository).save(saved.capture());
        when(repository.findByOrderNumber("POD-1000")).thenReturn(Optional.of(saved.getValue()));

        assertThat(service.find("pod-1000", "Anna@Example.com")).isPresent();
        assertThat(service.find("POD-1000", "someone@else.com")).isEmpty();
        assertThat(service.find("POD-9999", "anna@example.com")).isEmpty();
    }

    private static OrderRequest request(int quantity, BigDecimal expectedTotal, String country) {
        return new OrderRequest("PLA", "red", "medium", quantity, expectedTotal,
                " Anna Muster ", "Anna@Example.com", "Bahnhofstrasse 1", "8001", "Zürich", country);
    }

    private static MockMultipartFile benchy() {
        return new MockMultipartFile("file", "3DBenchy.stl", "application/octet-stream",
                "solid benchy endsolid benchy".getBytes(StandardCharsets.UTF_8));
    }

    /** Mirrors the real single-Benchy quote: CHF 3.00 per unit above the floor plus CHF 5.00 setup. */
    private static QuoteResponse benchyQuote(ColorOption color, int quantity) {
        double unit = 3.55;
        return new QuoteResponse("3DBenchy.stl", "PLA", color, "medium", quantity, "Bambu Lab A1 0.4 nozzle",
                0.2, 1, 2239, 2239.0 * quantity, 3880.6 * quantity, 11.6, 11.6 * quantity,
                new BoundingBox(60, 31, 48), 225154, 2.5 * quantity, 0.4 * quantity, 5.0, unit,
                5.0 + unit * quantity, "CHF", 3);
    }
}
