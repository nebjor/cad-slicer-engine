package com.slicercore.cart;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AccountCartItemRepository extends JpaRepository<AccountCartItem, UUID> {

    List<AccountCartItem> findByAccountIdOrderByPosition(UUID accountId);

    /** Lines nobody has touched since the cutoff; the sweep deletes them with their files. */
    List<AccountCartItem> findByUpdatedAtBefore(Instant cutoff);
}
