package com.slicercore.admin;

import com.slicercore.order.Order;
import com.slicercore.order.OrderStatus;
import com.slicercore.order.OrderTotals;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
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
        String fileName,
        long fileSizeBytes,
        String filament,
        String colorCode,
        String colorLabel,
        String quality,
        int quantity,
        String machineProfile,
        BigDecimal layerHeightMm,
        int plateCount,
        int totalPrintTimeSeconds,
        BigDecimal totalFilamentGrams,
        OrderTotals totals,
        @Schema(nullable = true) String paymentProvider) {

    public static AdminOrderResponse from(Order order) {
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
                order.getOriginalFileName(),
                order.getFileSizeBytes(),
                order.getFilament(),
                order.getColorCode(),
                order.getColorLabel(),
                order.getQuality(),
                order.getQuantity(),
                order.getMachineProfile(),
                order.getLayerHeightMm(),
                order.getPlateCount(),
                order.getTotalPrintTimeSeconds(),
                order.getTotalFilamentGrams(),
                order.getTotals(),
                order.getPaymentProvider());
    }
}
