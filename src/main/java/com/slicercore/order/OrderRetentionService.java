package com.slicercore.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

/**
 * Deletes what the shop no longer needs: the model file and the customer's personal data,
 * either when the retention period after shipping has passed or on an explicit request.
 * The order row with its numbers stays, because the business record must be kept
 * (Swiss CO art. 958f: ten years) and it no longer identifies anyone.
 */
@Service
public class OrderRetentionService {

    private static final Logger log = LoggerFactory.getLogger(OrderRetentionService.class);

    private final OrderRepository orders;
    private final OrderFileStorage files;
    private final Clock clock;

    public OrderRetentionService(OrderRepository orders, OrderFileStorage files, Clock clock) {
        this.orders = orders;
        this.files = files;
        this.clock = clock;
    }

    /** Erases every shipped order whose retention period has ended. Returns how many. */
    @Transactional
    public int eraseExpired() {
        List<Order> due = orders.findAllByDeleteAfterBeforeAndErasedAtIsNull(clock.instant());
        due.forEach(this::erase);
        return due.size();
    }

    /** A customer's deletion request: one call, file and personal data gone, idempotent. */
    @Transactional
    public Order erase(String orderNumber) {
        Order order = orders.findByOrderNumber(orderNumber.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new NoSuchElementException("Order " + orderNumber + " not found."));
        if (!order.isErased()) {
            erase(order);
        }
        return order;
    }

    private void erase(Order order) {
        try {
            files.delete(order.getFileKey());
        } catch (IOException e) {
            // The file is retried on the next run; the personal data must not wait for it.
            log.error("Model file {} of order {} could not be deleted: {}", order.getFileKey(),
                    order.getOrderNumber(), e.getMessage());
            return;
        }
        order.eraseCustomerData(clock.instant());
        log.info("Order {} erased: model file and personal data removed", order.getOrderNumber());
    }
}
