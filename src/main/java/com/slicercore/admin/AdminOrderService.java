package com.slicercore.admin;

import com.slicercore.order.Order;
import com.slicercore.order.OrderFileStorage;
import com.slicercore.order.OrderProperties;
import com.slicercore.order.OrderRepository;
import com.slicercore.order.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;

/** The owner's view of orders: list, inspect, move along the state machine, fetch the model. */
@Service
public class AdminOrderService {

    private static final Logger log = LoggerFactory.getLogger(AdminOrderService.class);
    private static final int MAX_LIST_SIZE = 500;

    private final OrderRepository orders;
    private final OrderFileStorage files;
    private final OrderProperties settings;
    private final Clock clock;

    public AdminOrderService(OrderRepository orders, OrderFileStorage files, OrderProperties settings, Clock clock) {
        this.orders = orders;
        this.files = files;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AdminOrderResponse> list(Optional<OrderStatus> status, int limit) {
        PageRequest page = PageRequest.of(0, Math.clamp(limit, 1, MAX_LIST_SIZE));
        List<Order> found = status
                .map(value -> orders.findAllByStatusOrderByCreatedAtDesc(value, page))
                .orElseGet(() -> orders.findAllByOrderByCreatedAtDesc(page));
        return found.stream().map(AdminOrderResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public AdminOrderResponse get(String orderNumber) {
        return AdminOrderResponse.from(require(orderNumber));
    }

    /**
     * Moves the order to the next state. Shipping starts the retention clock so personal data
     * and the model file are deleted after the configured period.
     */
    @Transactional
    public AdminOrderResponse transition(String orderNumber, OrderStatus next) {
        Order order = require(orderNumber);
        order.transitionTo(next, clock.instant());
        if (next == OrderStatus.SHIPPED) {
            order.scheduleDeletion(settings.retentionDays());
        }
        log.info("Order {} moved to {} by the owner", order.getOrderNumber(), next);
        return AdminOrderResponse.from(order);
    }

    /** The customer's model as uploaded, named after the original file for Bambu Studio. */
    @Transactional(readOnly = true)
    public ModelDownload model(String orderNumber) {
        Order order = require(orderNumber);
        try {
            InputStream content = files.open(order.getFileKey());
            return new ModelDownload(downloadName(order), order.getFileSizeBytes(), content);
        } catch (IOException e) {
            throw new UncheckedIOException("Model file of order " + orderNumber + " is not readable.", e);
        }
    }

    private Order require(String orderNumber) {
        return orders.findByOrderNumber(orderNumber.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new NoSuchElementException("Order " + orderNumber + " not found."));
    }

    /** Order number first so downloads sort by order; the original name keeps its extension. */
    static String downloadName(Order order) {
        String original = order.getOriginalFileName().replaceAll("[^A-Za-z0-9._-]", "_");
        return order.getOrderNumber() + "_" + original;
    }

    public record ModelDownload(String fileName, long sizeBytes, InputStream content) {
    }
}
