package com.slicercore.notification;

import com.slicercore.order.OrderPaidEvent;
import com.slicercore.order.OrderShippedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns committed order events into notifications. After commit on purpose: a payment that
 * is recorded must stay recorded even when the mail server is down, so a failed mail is
 * logged and never rolls the order back.
 */
@Component
public class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    private final OrderNotifier notifier;

    public OrderEventListener(OrderNotifier notifier) {
        this.notifier = notifier;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaid(OrderPaidEvent event) {
        try {
            notifier.orderPaid(event.order());
        } catch (RuntimeException e) {
            log.error("Confirmation for order {} could not be sent: {}", event.order().getOrderNumber(), e.getMessage(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onShipped(OrderShippedEvent event) {
        try {
            notifier.orderShipped(event.order());
        } catch (RuntimeException e) {
            log.error("Shipping notice for order {} could not be sent: {}", event.order().getOrderNumber(), e.getMessage(), e);
        }
    }
}
