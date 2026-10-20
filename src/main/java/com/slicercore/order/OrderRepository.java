package com.slicercore.order;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByOrderNumber(String orderNumber);

    Optional<Order> findByPaymentSessionId(String paymentSessionId);

    List<Order> findAllByOrderByCreatedAtDesc(Pageable page);

    List<Order> findAllByStatusOrderByCreatedAtDesc(OrderStatus status, Pageable page);

    /** Shipped orders whose retention period has ended and that still hold personal data. */
    List<Order> findAllByDeleteAfterBeforeAndErasedAtIsNull(Instant now);

    /** Human-readable, gap-free enough, never derived from a count. */
    @Query(value = "SELECT nextval('order_number_seq')", nativeQuery = true)
    long nextOrderNumber();
}
