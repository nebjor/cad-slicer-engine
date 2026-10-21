package com.slicercore.order;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A placed order: the customer, one or more {@link OrderItem items} and the order-level money.
 * Everything that determines the price is copied from the quotes at creation and never
 * updated, so changing rates later cannot change what a customer was told.
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

    @Column(name = "erased_at")
    private Instant erasedAt;

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

    /**
     * Every use of an order needs its items (response, mails, payment, erasure), so they load
     * with it; one extra query per result set rather than one per order.
     */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @OrderBy("position ASC")
    private List<OrderItem> items = new ArrayList<>();

    @Column(nullable = false, length = 3)
    private String currency;

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

    @Column(name = "payment_provider", length = 20)
    private String paymentProvider;

    @Column(name = "payment_session_id")
    private String paymentSessionId;

    @Column(name = "payment_url", length = 2000)
    private String paymentUrl;

    protected Order() {
        // JPA
    }

    /**
     * @param items  in position order, starting at 1; the totals must have been computed from them
     */
    public static Order create(String orderNumber, Instant now, int quoteValidityDays, Customer customer,
                               List<OrderItem> items, OrderTotals totals) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("An order needs at least one item.");
        }
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

        for (int i = 0; i < items.size(); i++) {
            OrderItem item = items.get(i);
            if (item.getPosition() != i + 1) {
                throw new IllegalArgumentException("Item positions must run 1.." + items.size() + " without gaps.");
            }
            item.attachTo(order);
            order.items.add(item);
        }

        order.currency = totals.currency();
        order.itemsTotal = totals.itemsTotal();
        order.shipping = totals.shipping();
        order.vatRate = totals.vatRate();
        order.vatAmount = totals.vatAmount();
        order.total = totals.total();
        return order;
    }

    /** Records the payment session created for this order; the customer is sent to the URL. */
    public void attachPayment(String provider, String sessionId, String url) {
        this.paymentProvider = provider;
        this.paymentSessionId = sessionId;
        this.paymentUrl = url;
    }

    /**
     * Confirms payment. Idempotent: a webhook delivered twice marks one payment. Returns true
     * when the state changed.
     */
    public boolean markPaid(Instant now) {
        if (status == OrderStatus.PAID) {
            return false;
        }
        transitionTo(OrderStatus.PAID, now);
        return true;
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

    /**
     * Removes everything that identifies the customer; the model files are the storage's job.
     * The numbers stay so the order remains a valid business record.
     */
    public void eraseCustomerData(Instant now) {
        customerName = "erased";
        customerEmail = "erased@invalid";
        street = "erased";
        postalCode = "";
        city = "erased";
        erasedAt = now;
    }

    public boolean isErased() {
        return erasedAt != null;
    }

    public boolean isQuoteExpired(Instant now) {
        return now.isAfter(quoteExpiresAt);
    }

    /** The item at a 1-based position, if the order has one. */
    public Optional<OrderItem> item(int position) {
        return items.stream().filter(item -> item.getPosition() == position).findFirst();
    }

    /** True when at least one item carries slicer warnings or failed the manifold check. */
    public boolean needsInspection() {
        return items.stream().anyMatch(OrderItem::needsInspection);
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public OrderTotals getTotals() {
        return new OrderTotals(itemsTotal, shipping, vatRate, vatAmount, total, currency);
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

    public Instant getErasedAt() {
        return erasedAt;
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

    public String getPaymentProvider() {
        return paymentProvider;
    }

    public String getPaymentSessionId() {
        return paymentSessionId;
    }

    public String getPaymentUrl() {
        return paymentUrl;
    }

    /** Checkout details of the person ordering. */
    public record Customer(String name, String email, String street, String postalCode, String city, String country) {
    }
}
