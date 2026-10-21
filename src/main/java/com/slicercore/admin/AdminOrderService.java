package com.slicercore.admin;

import com.slicercore.account.AccountService;
import com.slicercore.order.Order;
import com.slicercore.order.OrderFileStorage;
import com.slicercore.order.OrderItem;
import com.slicercore.order.OrderProperties;
import com.slicercore.order.OrderRepository;
import com.slicercore.order.OrderShippedEvent;
import com.slicercore.order.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** The owner's view of orders: list, inspect, move along the state machine, fetch the models. */
@Service
public class AdminOrderService {

    private static final Logger log = LoggerFactory.getLogger(AdminOrderService.class);
    private static final int MAX_LIST_SIZE = 500;

    private final OrderRepository orders;
    private final OrderFileStorage files;
    private final OrderProperties settings;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final AccountService accounts;

    public AdminOrderService(OrderRepository orders, OrderFileStorage files, OrderProperties settings, Clock clock,
                             ApplicationEventPublisher events, AccountService accounts) {
        this.orders = orders;
        this.files = files;
        this.settings = settings;
        this.clock = clock;
        this.events = events;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public List<AdminOrderResponse> list(Optional<OrderStatus> status, int limit) {
        PageRequest page = PageRequest.of(0, Math.clamp(limit, 1, MAX_LIST_SIZE));
        List<Order> found = status
                .map(value -> orders.findAllByStatusOrderByCreatedAtDesc(value, page))
                .orElseGet(() -> orders.findAllByOrderByCreatedAtDesc(page));
        return withAccounts(found);
    }

    @Transactional(readOnly = true)
    public AdminOrderResponse get(String orderNumber) {
        return withAccounts(List.of(require(orderNumber))).getFirst();
    }

    /**
     * Moves the order to the next state. Shipping starts the retention clock so personal data
     * and the model files are deleted after the configured period.
     */
    @Transactional
    public AdminOrderResponse transition(String orderNumber, OrderStatus next) {
        Order order = require(orderNumber);
        order.transitionTo(next, clock.instant());
        if (next == OrderStatus.SHIPPED) {
            order.scheduleDeletion(settings.retentionDays());
            events.publishEvent(new OrderShippedEvent(order));
        }
        log.info("Order {} moved to {} by the owner", order.getOrderNumber(), next);
        return withAccounts(List.of(order)).getFirst();
    }

    /** One lookup of the account e-mails for a page of orders, so the owner sees who has an account. */
    private List<AdminOrderResponse> withAccounts(List<Order> found) {
        Set<UUID> accountIds = found.stream().map(Order::getAccountId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, String> emails = accounts.emailsById(accountIds);
        return found.stream()
                .map(order -> AdminOrderResponse.from(order,
                        order.getAccountId() == null ? null : emails.get(order.getAccountId())))
                .toList();
    }

    /** One item's model as uploaded, named after the original file for Bambu Studio. */
    @Transactional(readOnly = true)
    public ModelDownload model(String orderNumber, int position) {
        Order order = require(orderNumber);
        OrderItem item = order.item(position)
                .orElseThrow(() -> new NoSuchElementException("Order " + order.getOrderNumber()
                        + " has no item " + position + "."));
        try {
            InputStream content = files.open(item.getFileKey());
            return new ModelDownload(downloadName(order, item), item.getFileSizeBytes(), content);
        } catch (IOException e) {
            throw new UncheckedIOException("Model file of order " + orderNumber + " item " + position
                    + " is not readable.", e);
        }
    }

    private Order require(String orderNumber) {
        return orders.findByOrderNumber(orderNumber.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new NoSuchElementException("Order " + orderNumber + " not found."));
    }

    /** Order number and position first so downloads sort by order; the original name keeps its extension. */
    static String downloadName(Order order, OrderItem item) {
        String original = item.getOriginalFileName().replaceAll("[^A-Za-z0-9._-]", "_");
        return order.getOrderNumber() + "_" + item.getPosition() + "_" + original;
    }

    public record ModelDownload(String fileName, long sizeBytes, InputStream content) {
    }
}
