package com.slicercore.payment;

import com.slicercore.order.Order;
import com.slicercore.order.OrderPaidEvent;
import com.slicercore.order.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Applies a gateway's confirmation to the order it belongs to. Starting a payment is the
 * order module's business (it owns the {@code PaymentGateway} port); confirming is the
 * provider's, and that is what lands here from the webhook or the mock page.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final OrderRepository orders;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public PaymentService(OrderRepository orders, Clock clock, ApplicationEventPublisher events) {
        this.orders = orders;
        this.clock = clock;
        this.events = events;
    }

    /**
     * Marks the order of a session as paid. Safe to call repeatedly; unknown sessions are
     * rejected so a forged confirmation cannot create state.
     */
    @Transactional
    public Order confirm(String sessionId) {
        Order order = orders.findByPaymentSessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown payment session."));
        if (order.markPaid(clock.instant())) {
            log.info("Order {} paid via session {}", order.getOrderNumber(), sessionId);
            events.publishEvent(new OrderPaidEvent(order));
        } else {
            log.info("Payment confirmation for order {} repeated; already paid", order.getOrderNumber());
        }
        return order;
    }
}
