package com.slicercore.order;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** What the customer sees after placing an order and when looking it up. */
public record OrderResponse(
        String orderNumber,
        OrderStatus status,
        Instant createdAt,
        Instant quoteExpiresAt,
        @Schema(nullable = true) Instant paidAt,
        String customerName,
        String customerEmail,
        List<OrderItemResponse> items,
        OrderTotals totals,
        /** Null when VAT is charged; otherwise the legally required note that no VAT applies. */
        @Schema(nullable = true) String vatNote,
        /** Where to pay; null once paid or when no payment session exists. */
        @Schema(nullable = true) String paymentUrl) {

    public static OrderResponse from(Order order, boolean vatEnabled) {
        boolean awaitingPayment = order.getStatus() == OrderStatus.NEW;
        return new OrderResponse(
                order.getOrderNumber(),
                order.getStatus(),
                order.getCreatedAt(),
                order.getQuoteExpiresAt(),
                order.getPaidAt(),
                order.getCustomerName(),
                order.getCustomerEmail(),
                order.getItems().stream().map(OrderItemResponse::from).toList(),
                order.getTotals(),
                vatEnabled ? null : "No VAT charged: the seller is not VAT registered.",
                awaitingPayment ? order.getPaymentUrl() : null);
    }
}
