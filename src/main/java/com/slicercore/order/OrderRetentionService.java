package com.slicercore.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

/**
 * Deletes what the shop no longer needs: the model files and the customer's personal data,
 * when the retention period after shipping has passed, when an order is abandoned before
 * payment, or on an explicit request. The order row with its numbers stays, because the
 * business record must be kept (Swiss CO art. 958f: ten years) and it no longer identifies
 * anyone.
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

    /**
     * Cancels an order whose payment will not come (the provider's session expired or failed)
     * and erases it at once: nothing of it is needed any more. Idempotent.
     */
    @Transactional
    public void abandon(Order order) {
        if (order.getStatus() == OrderStatus.NEW) {
            order.transitionTo(OrderStatus.CANCELLED, clock.instant());
        }
        if (!order.isErased()) {
            erase(order);
        }
    }

    /**
     * Erases every order that is still unpaid, or was cancelled, once its quote validity has
     * passed; unpaid ones are cancelled on the way. A missed webhook or an admin cancellation
     * therefore cannot leave personal data behind. Returns how many.
     */
    @Transactional
    public int eraseAbandoned() {
        List<Order> due = orders.findAllByStatusInAndQuoteExpiresAtBeforeAndErasedAtIsNull(
                EnumSet.of(OrderStatus.NEW, OrderStatus.CANCELLED), clock.instant());
        due.forEach(this::abandon);
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
        for (OrderItem item : order.getItems()) {
            try {
                files.delete(item.getFileKey());
            } catch (IOException e) {
                // Retried on the next run, deleting is idempotent; the personal data waits for it
                // so that an order is never half erased.
                log.error("Model file {} of order {} could not be deleted: {}", item.getFileKey(),
                        order.getOrderNumber(), e.getMessage());
                return;
            }
        }
        order.eraseCustomerData(clock.instant());
        log.info("Order {} erased: {} model file(s) and personal data removed", order.getOrderNumber(),
                order.getItems().size());
    }
}
