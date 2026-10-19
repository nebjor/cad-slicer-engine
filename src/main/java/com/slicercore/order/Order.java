package com.slicercore.order;

import com.slicercore.model.QuoteResponse;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A placed order. Everything that determines the price is copied from the quote at creation
 * and never updated, so changing rates later cannot change what a customer was told.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    private UUID id;

    @Column(name = "order_number", nullable = false, unique = true, length = 20)
    private String orderNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "quote_expires_at", nullable = false)
    private Instant quoteExpiresAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Column(name = "delete_after")
    private Instant deleteAfter;

    @Column(name = "customer_name", nullable = false, length = 120)
    private String customerName;

    @Column(name = "customer_email", nullable = false, length = 254)
    private String customerEmail;

    @Column(nullable = false, length = 160)
    private String street;

    @Column(name = "postal_code", nullable = false, length = 16)
    private String postalCode;

    @Column(nullable = false, length = 120)
    private String city;

    @Column(nullable = false, length = 2)
    private String country;

    @Column(name = "original_file_name", nullable = false)
    private String originalFileName;

    @Column(name = "file_key", nullable = false, length = 64)
    private String fileKey;

    @Column(name = "file_sha256", nullable = false, length = 64)
    private String fileSha256;

    @Column(name = "file_size_bytes", nullable = false)
    private long fileSizeBytes;

    @Column(nullable = false, length = 16)
    private String filament;

    @Column(name = "color_code", nullable = false, length = 32)
    private String colorCode;

    @Column(name = "color_label", nullable = false, length = 64)
    private String colorLabel;

    @Column(nullable = false, length = 16)
    private String quality;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "machine_profile", nullable = false, length = 120)
    private String machineProfile;

    @Column(name = "layer_height_mm", nullable = false, precision = 4, scale = 2)
    private BigDecimal layerHeightMm;

    @Column(name = "plate_count", nullable = false)
    private int plateCount;

    @Column(name = "total_print_time_seconds", nullable = false)
    private int totalPrintTimeSeconds;

    @Column(name = "total_filament_grams", nullable = false, precision = 10, scale = 1)
    private BigDecimal totalFilamentGrams;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "setup_fee", nullable = false, precision = 10, scale = 2)
    private BigDecimal setupFee;

    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "items_total", nullable = false, precision = 10, scale = 2)
    private BigDecimal itemsTotal;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal shipping;

    @Column(name = "vat_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal vatRate;

    @Column(name = "vat_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal vatAmount;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal total;

    protected Order() {
        // JPA
    }

    public static Order create(String orderNumber, Instant now, int quoteValidityDays, Customer customer,
                               QuoteResponse quote, OrderFileStorage.StoredFile file, OrderTotals totals) {
        Order order = new Order();
        order.id = UUID.randomUUID();
        order.orderNumber = orderNumber;
        order.status = OrderStatus.NEW;
        order.createdAt = now;
        order.quoteExpiresAt = now.plus(Duration.ofDays(quoteValidityDays));

        order.customerName = customer.name();
        order.customerEmail = customer.email();
        order.street = customer.street();
        order.postalCode = customer.postalCode();
        order.city = customer.city();
        order.country = customer.country();

        order.originalFileName = quote.fileName();
        order.fileKey = file.key();
        order.fileSha256 = file.sha256();
        order.fileSizeBytes = file.sizeBytes();

        order.filament = quote.filament();
        order.colorCode = quote.color().code();
        order.colorLabel = quote.color().label();
        order.quality = quote.quality();
        order.quantity = quote.quantity();
        order.machineProfile = quote.machineProfile();
        order.layerHeightMm = BigDecimal.valueOf(quote.layerHeightMm()).setScale(2, RoundingMode.HALF_UP);
        order.plateCount = quote.plateCount();
        order.totalPrintTimeSeconds = (int) Math.round(quote.totalPrintTimeSeconds());
        order.totalFilamentGrams = BigDecimal.valueOf(quote.totalFilamentGrams()).setScale(1, RoundingMode.HALF_UP);

        order.currency = totals.currency();
        order.setupFee = totals.setupFee();
        order.unitPrice = totals.unitPrice();
        order.itemsTotal = totals.itemsTotal();
        order.shipping = totals.shipping();
        order.vatRate = totals.vatRate();
        order.vatAmount = totals.vatAmount();
        order.total = totals.total();
        return order;
    }

    /** Moves to the next state or throws; the status enum defines what is legal. */
    public void transitionTo(OrderStatus next, Instant now) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("Order " + orderNumber + " cannot go from " + status + " to " + next + ".");
        }
        status = next;
        if (next == OrderStatus.PAID) {
            paidAt = now;
        }
        if (next == OrderStatus.SHIPPED) {
            shippedAt = now;
        }
    }

    /** Sets the retention deadline once the order is shipped. */
    public void scheduleDeletion(int retentionDays) {
        if (shippedAt == null) {
            throw new IllegalStateException("Retention starts at shipping; order " + orderNumber + " is not shipped.");
        }
        deleteAfter = shippedAt.plus(Duration.ofDays(retentionDays));
    }

    public boolean isQuoteExpired(Instant now) {
        return now.isAfter(quoteExpiresAt);
    }

    public UUID getId() {
        return id;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getQuoteExpiresAt() {
        return quoteExpiresAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public Instant getShippedAt() {
        return shippedAt;
    }

    public Instant getDeleteAfter() {
        return deleteAfter;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public String getStreet() {
        return street;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public String getCity() {
        return city;
    }

    public String getCountry() {
        return country;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public String getFileKey() {
        return fileKey;
    }

    public String getFileSha256() {
        return fileSha256;
    }

    public long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public String getFilament() {
        return filament;
    }

    public String getColorCode() {
        return colorCode;
    }

    public String getColorLabel() {
        return colorLabel;
    }

    public String getQuality() {
        return quality;
    }

    public int getQuantity() {
        return quantity;
    }

    public String getMachineProfile() {
        return machineProfile;
    }

    public BigDecimal getLayerHeightMm() {
        return layerHeightMm;
    }

    public int getPlateCount() {
        return plateCount;
    }

    public int getTotalPrintTimeSeconds() {
        return totalPrintTimeSeconds;
    }

    public BigDecimal getTotalFilamentGrams() {
        return totalFilamentGrams;
    }

    public OrderTotals getTotals() {
        return new OrderTotals(setupFee, unitPrice, itemsTotal, shipping, vatRate, vatAmount, total, currency);
    }

    /** Checkout details of the person ordering. */
    public record Customer(String name, String email, String street, String postalCode, String city, String country) {
    }
}
