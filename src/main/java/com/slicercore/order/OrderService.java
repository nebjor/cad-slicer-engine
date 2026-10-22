package com.slicercore.order;

import com.slicercore.model.ColorOption;
import com.slicercore.model.FilamentType;
import com.slicercore.model.ModelNotFoundException;
import com.slicercore.model.PrintQuality;
import com.slicercore.model.QuoteResponse;
import com.slicercore.service.FilamentCatalog;
import com.slicercore.service.QuoteService;
import com.slicercore.service.UploadedModelStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns quotes into an order: re-quote every item server-side from the models the customer
 * uploaded (their number is only a check), copy each model from the short-lived upload store
 * into the order-files storage under an opaque key, persist the full breakdown so later rate
 * changes cannot touch it, and start the payment through the {@link PaymentGateway} port that
 * the payment module implements.
 *
 * Nothing is stored before every item has been quoted and the price confirmed, so a 404 for
 * an expired model or a 409 for a changed price leaves no trace.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final String ORDER_NUMBER_PREFIX = "POD-";

    private final QuoteService quoteService;
    private final UploadedModelStore modelStore;
    private final FilamentCatalog catalog;
    private final OrderFileStorage files;
    private final OrderRepository orders;
    private final OrderProperties settings;
    private final PaymentGateway paymentGateway;
    private final Clock clock;

    public OrderService(QuoteService quoteService, UploadedModelStore modelStore, FilamentCatalog catalog,
                        OrderFileStorage files, OrderRepository orders, OrderProperties settings,
                        PaymentGateway paymentGateway, Clock clock) {
        this.quoteService = quoteService;
        this.modelStore = modelStore;
        this.catalog = catalog;
        this.files = files;
        this.orders = orders;
        this.settings = settings;
        this.paymentGateway = paymentGateway;
        this.clock = clock;
    }

    public OrderSettingsResponse settings() {
        return OrderSettingsResponse.from(settings);
    }

    /**
     * @param accountId the logged-in customer's account, or null for a guest; the order is
     *                  linked to it even when the checkout e-mail differs
     */
    @Transactional
    public OrderResponse placeOrder(OrderRequest request, String clientKey, UUID accountId) {
        Order.Customer customer = request.customer().normalized();
        if (!settings.shipToCountries().contains(customer.country())) {
            throw new IllegalArgumentException("Shipping to " + customer.country() + " is not offered. Available: "
                    + String.join(", ", settings.shipToCountries()));
        }

        List<QuoteResponse> quotes = quoteItems(request.items(), clientKey);
        List<ItemTotals> lines = quotes.stream().map(ItemTotals::from).toList();
        OrderTotals totals = OrderTotals.from(lines, quotes.getFirst().currency(), settings, accountId != null);
        // The customer's number is the sum of the quotes they saw; the setup fee and shipping are
        // fixed settings the cart page shows from the order settings.
        if (request.expectedTotalChf() != null
                && Chf.round(request.expectedTotalChf()).compareTo(totals.itemsTotal()) != 0) {
            throw new PriceChangedException(quotes, totals);
        }

        List<OrderItem> items = copyModels(request.items(), quotes);
        try {
            String orderNumber = ORDER_NUMBER_PREFIX + orders.nextOrderNumber();
            Order order = Order.create(orderNumber, clock.instant(), settings.quoteValidityDays(), customer, items, totals);
            if (accountId != null) {
                order.linkAccount(accountId);
            }
            PaymentSession session = paymentGateway.start(order);
            order.attachPayment(session.provider(), session.sessionId(), session.url());
            orders.save(order);
            log.info("Order {} placed: {} item(s) for {} {}; payment session {} ({}); {}", orderNumber, items.size(),
                    totals.currency(), totals.total(), session.sessionId(), session.provider(),
                    accountId == null ? "guest" : "account " + accountId);
            return OrderResponse.from(order, settings.vatEnabled());
        } catch (RuntimeException e) {
            discardQuietly(items);
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public Optional<OrderResponse> find(String orderNumber, String customerEmail) {
        return orders.findByOrderNumber(orderNumber.trim().toUpperCase(Locale.ROOT))
                .filter(order -> order.getCustomerEmail().equalsIgnoreCase(customerEmail.trim()))
                .map(order -> OrderResponse.from(order, settings.vatEnabled()));
    }

    /** One quote per item, in request order; a cache hit when the customer already saw that quote. */
    private List<QuoteResponse> quoteItems(List<OrderItemRequest> requested, String clientKey) {
        List<QuoteResponse> quotes = new ArrayList<>(requested.size());
        for (int index = 0; index < requested.size(); index++) {
            OrderItemRequest item = requested.get(index);
            FilamentType filament = FilamentType.fromInput(item.filament());
            ColorOption color = catalog.resolve(filament, item.color());
            PrintQuality quality = PrintQuality.fromInput(item.quality());
            try {
                quotes.add(quoteService.quoteStoredModel(item.modelId().trim(), filament, color, quality,
                        item.quantity(), clientKey));
            } catch (ModelNotFoundException e) {
                throw new OrderModelExpiredException(index);
            }
        }
        return quotes;
    }

    /**
     * Copies every model from the upload store into the order-files storage. A model that
     * expired between quote and copy fails the whole order, and what was copied so far is
     * removed again.
     */
    private List<OrderItem> copyModels(List<OrderItemRequest> requested, List<QuoteResponse> quotes) {
        List<OrderItem> items = new ArrayList<>(requested.size());
        try {
            for (int index = 0; index < requested.size(); index++) {
                OrderItemRequest item = requested.get(index);
                QuoteResponse quote = quotes.get(index);
                UploadedModelStore.StoredModel model = modelStore.find(item.modelId().trim())
                        .orElseThrow(() -> new OrderModelExpiredException(items.size()));
                OrderFileStorage.StoredFile stored = copy(model);
                items.add(OrderItem.create(index + 1, fileName(item, quote), quote, stored));
            }
        } catch (RuntimeException e) {
            discardQuietly(items);
            throw e;
        }
        return items;
    }

    private OrderFileStorage.StoredFile copy(UploadedModelStore.StoredModel model) {
        String name = model.path().getFileName().toString().toLowerCase(Locale.ROOT);
        String extension = name.endsWith(".3mf") ? "3mf" : "stl";
        try (InputStream in = Files.newInputStream(model.path())) {
            return files.store(in, extension);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store the order model.", e);
        }
    }

    /** The customer's name for the model, without any path, or the upload's name when blank. */
    private static String fileName(OrderItemRequest item, QuoteResponse quote) {
        if (item.fileName() == null || item.fileName().isBlank()) {
            return quote.fileName();
        }
        String base = item.fileName().trim().replace('\\', '/');
        int slash = base.lastIndexOf('/');
        return slash >= 0 ? base.substring(slash + 1) : base;
    }

    private void discardQuietly(List<OrderItem> items) {
        for (OrderItem item : items) {
            try {
                files.delete(item.getFileKey());
            } catch (IOException | RuntimeException e) {
                log.warn("Could not remove order file {} after a failed order: {}", item.getFileKey(), e.getMessage());
            }
        }
    }
}
