package com.slicercore.admin;

import com.slicercore.order.Order;
import com.slicercore.order.OrderStatus;
import com.slicercore.order.OrderTotals;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** Everything the owner needs to handle an order, including what the customer never sees. */
public record AdminOrderResponse(
        String orderNumber,
        OrderStatus status,
        List<OrderStatus> allowedTransitions,
        Instant createdAt,
        Instant quoteExpiresAt,
        @Schema(nullable = true) Instant paidAt,
        @Schema(nullable = true) Instant shippedAt,
        @Schema(nullable = true) Instant deleteAfter,
        @Schema(nullable = true) Instant erasedAt,
        String customerName,
        String customerEmail,
        String street,
        String postalCode,
        String city,
        String country,
        List<AdminOrderItemResponse> items,
        OrderTotals totals,
        @Schema(nullable = true) String paymentProvider,
        /** The e-mail of the customer account the order was placed with; null for guest orders. */
        @Schema(nullable = true) String accountEmail) {

    public static AdminOrderResponse from(Order order, String accountEmail) {
        return new AdminOrderResponse(
                order.getOrderNumber(),
                order.getStatus(),
                order.getStatus().allowedTransitions().stream().sorted().toList(),
                order.getCreatedAt(),
                order.getQuoteExpiresAt(),
                order.getPaidAt(),
                order.getShippedAt(),
                order.getDeleteAfter(),
                order.getErasedAt(),
                order.getCustomerName(),
                order.getCustomerEmail(),
                order.getStreet(),
                order.getPostalCode(),
                order.getCity(),
                order.getCountry(),
                order.getItems().stream().map(AdminOrderItemResponse::from).toList(),
                order.getTotals(),
                order.getPaymentProvider(),
                accountEmail);
    }
}
