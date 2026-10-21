package com.slicercore.order;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByOrderNumber(String orderNumber);

    Optional<Order> findByPaymentSessionId(String paymentSessionId);

    List<Order> findAllByOrderByCreatedAtDesc(Pageable page);

    List<Order> findAllByStatusOrderByCreatedAtDesc(OrderStatus status, Pageable page);

    /**
     * A customer's orders, newest first: placed under the account or under its e-mail address
     * before the account existed. Erased orders no longer belong to anyone.
     */
    @Query("""
            select o from Order o
            where o.erasedAt is null and (lower(o.customerEmail) = lower(:email) or o.accountId = :accountId)
            order by o.createdAt desc
            """)
    List<Order> findCustomerOrders(String email, UUID accountId);

    /** The newest order placed under an e-mail address that still holds its customer data. */
    Optional<Order> findFirstByCustomerEmailIgnoreCaseAndErasedAtIsNullOrderByCreatedAtDesc(String email);

    /** Shipped orders whose retention period has ended and that still hold personal data. */
    List<Order> findAllByDeleteAfterBeforeAndErasedAtIsNull(Instant now);
    /** Unpaid or cancelled orders past their quote validity that still hold personal data. */
    List<Order> findAllByStatusInAndQuoteExpiresAtBeforeAndErasedAtIsNull(Collection<OrderStatus> statuses, Instant now);

    /** Human-readable, gap-free enough, never derived from a count. */
    @Query(value = "SELECT nextval('order_number_seq')", nativeQuery = true)
    long nextOrderNumber();
}
