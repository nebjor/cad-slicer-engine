package com.slicercore.order;

import com.slicercore.model.ColorOption;
import com.slicercore.model.FilamentType;
import com.slicercore.model.PrintQuality;
import com.slicercore.model.QuoteResponse;
import com.slicercore.payment.PaymentService;
import com.slicercore.service.FilamentCatalog;
import com.slicercore.service.QuoteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.Locale;
import java.util.Optional;

/**
 * Turns a quote into an order: recompute the price server-side from the uploaded model (the
 * customer's number is only a check), store the model under an opaque key, and persist the
 * full breakdown so later rate changes cannot touch it.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final String ORDER_NUMBER_PREFIX = "POD-";

    private final QuoteService quoteService;
    private final FilamentCatalog catalog;
    private final OrderFileStorage files;
    private final OrderRepository orders;
    private final OrderProperties settings;
    private final PaymentService payments;
    private final Clock clock;

    public OrderService(QuoteService quoteService, FilamentCatalog catalog, OrderFileStorage files,
                        OrderRepository orders, OrderProperties settings, PaymentService payments, Clock clock) {
        this.quoteService = quoteService;
        this.catalog = catalog;
        this.files = files;
        this.orders = orders;
        this.settings = settings;
        this.payments = payments;
        this.clock = clock;
    }

    public OrderSettingsResponse settings() {
        return OrderSettingsResponse.from(settings);
    }

    @Transactional
    public OrderResponse placeOrder(MultipartFile model, OrderRequest request, String clientKey) {
        Order.Customer customer = request.customer();
        if (!settings.shipToCountries().contains(customer.country())) {
            throw new IllegalArgumentException("Shipping to " + customer.country() + " is not offered. Available: "
                    + String.join(", ", settings.shipToCountries()));
        }

        FilamentType filament = FilamentType.fromInput(request.filament());
        ColorOption color = catalog.resolve(filament, request.color());
        PrintQuality quality = PrintQuality.fromInput(request.quality());

        QuoteResponse quote = quoteService.quoteUpload(model, filament, color, quality, request.quantity(), clientKey);
        if (request.expectedTotalChf() != null
                && Chf.round(request.expectedTotalChf()).compareTo(Chf.of(quote.totalPriceChf())) != 0) {
            throw new PriceChangedException(quote);
        }

        OrderTotals totals = OrderTotals.from(quote, settings);
        OrderFileStorage.StoredFile stored = storeModel(model);
        try {
            String orderNumber = ORDER_NUMBER_PREFIX + orders.nextOrderNumber();
            Order order = Order.create(orderNumber, clock.instant(), settings.quoteValidityDays(), customer,
                    quote, stored, totals);
            payments.start(order);
            orders.save(order);
            log.info("Order {} placed: {} x{} {} {} for {} {}", orderNumber, quote.fileName(), quote.quantity(),
                    quote.filament(), quote.quality(), totals.currency(), totals.total());
            return OrderResponse.from(order, settings.vatEnabled());
        } catch (RuntimeException e) {
            discardQuietly(stored.key());
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public Optional<OrderResponse> find(String orderNumber, String customerEmail) {
        return orders.findByOrderNumber(orderNumber.trim().toUpperCase(Locale.ROOT))
                .filter(order -> order.getCustomerEmail().equalsIgnoreCase(customerEmail.trim()))
                .map(order -> OrderResponse.from(order, settings.vatEnabled()));
    }

    private OrderFileStorage.StoredFile storeModel(MultipartFile model) {
        String name = model.getOriginalFilename() == null ? "" : model.getOriginalFilename().toLowerCase(Locale.ROOT);
        String extension = name.endsWith(".3mf") ? "3mf" : "stl";
        try (InputStream in = model.getInputStream()) {
            return files.store(in, extension);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store the order model.", e);
        }
    }

    private void discardQuietly(String key) {
        try {
            files.delete(key);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not remove order file {} after a failed order: {}", key, e.getMessage());
        }
    }
}
