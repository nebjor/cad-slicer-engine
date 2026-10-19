package com.slicercore.payment;

import com.slicercore.order.Order;
import com.slicercore.order.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** Attaches payment sessions to orders and applies the gateway's confirmations. */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentGateway gateway;
    private final OrderRepository orders;
    private final Clock clock;

    public PaymentService(PaymentGateway gateway, OrderRepository orders, Clock clock) {
        this.gateway = gateway;
        this.orders = orders;
        this.clock = clock;
    }

    /** Starts a payment for a freshly placed order and records it on the order. */
    public PaymentSession start(Order order) {
        PaymentSession session = gateway.start(order);
        order.attachPayment(session.provider(), session.sessionId(), session.url());
        log.info("Payment session {} ({}) started for order {}", session.sessionId(), session.provider(),
                order.getOrderNumber());
        return session;
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
        } else {
            log.info("Payment confirmation for order {} repeated; already paid", order.getOrderNumber());
        }
        return order;
    }
}
