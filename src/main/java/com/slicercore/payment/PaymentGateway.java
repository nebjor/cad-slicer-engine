package com.slicercore.payment;

import com.slicercore.order.Order;

/**
 * Starts a hosted payment for an order. The shop never handles card data: the customer is
 * redirected to the gateway's page, and the gateway confirms the payment back to the shop
 * (Stripe through a signed webhook, the mock through its own endpoint).
 */
public interface PaymentGateway {

    PaymentSession start(Order order);
}
