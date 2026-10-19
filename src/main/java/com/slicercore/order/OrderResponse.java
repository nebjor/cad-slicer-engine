package com.slicercore.order;

import java.math.BigDecimal;
import java.time.Instant;

/** What the customer sees after placing an order and when looking it up. */
public record OrderResponse(
        String orderNumber,
        OrderStatus status,
        Instant createdAt,
        Instant quoteExpiresAt,
        Instant paidAt,
        String customerName,
        String customerEmail,
        String fileName,
        String filament,
        String colorCode,
        String colorLabel,
        String quality,
        int quantity,
        int plateCount,
        int totalPrintTimeSeconds,
        BigDecimal totalFilamentGrams,
        OrderTotals totals,
        /** Null when VAT is charged; otherwise the legally required note that no VAT applies. */
        String vatNote,
        /** Where to pay; null once paid or when no payment session exists. */
        String paymentUrl) {

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
                order.getOriginalFileName(),
                order.getFilament(),
                order.getColorCode(),
                order.getColorLabel(),
                order.getQuality(),
                order.getQuantity(),
                order.getPlateCount(),
                order.getTotalPrintTimeSeconds(),
                order.getTotalFilamentGrams(),
                order.getTotals(),
                vatEnabled ? null : "No VAT charged: the seller is not VAT registered.",
                awaitingPayment ? order.getPaymentUrl() : null);
    }
}
